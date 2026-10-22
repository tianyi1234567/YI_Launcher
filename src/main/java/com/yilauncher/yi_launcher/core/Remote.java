/* ==========================================================================
 *  衣启动器 · core 层 ｜ Remote.java —— 联网底座
 *  做什么：统一 UA / 超时 / 重定向；下载先写临时文件再原子改名；withDeadline 提供硬超时。
 *  谁在用：CfWidget、ModrinthApi、Mirrors、GameAssets、LoaderInstaller、PackInstaller、ModsPage。
 *  入口：getText()、postJson()、download()、withDeadline()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * 联网下载的公共底座：统一 UA、超时、重定向，下载走临时文件再原子改名。
 * <p>CurseForge 的 CDN 对「不带 UA / UA 像脚本」的请求会 403，所以这里统一发一个
 * 正常浏览器风格的 UA，别改。</p>
 */
public final class Remote {

    /** 请求头里的身份；Cloudflare 会拦空 UA。 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) YILauncher/1.0 (+https://yilauncher.local)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Remote() {
    }

    /** GET 一段文本（200 才算成功，其余抛异常让调用方兜底）。 */
    public static String getText(String url) throws IOException {
        return send(request(url).GET().build(), Duration.ofSeconds(30));
    }

    /** POST 一个 JSON 文本。 */
    public static String postJson(String url, String body) throws IOException {
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request, Duration.ofSeconds(30));
    }

    /** 打开一个下载流（调用方负责关）。 */
    public static HttpResponse<InputStream> openStream(String url) throws IOException {
        HttpRequest request = request(url).GET().build();
        try {
            HttpResponse<InputStream> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream body = response.body()) {
                    body.readAllBytes();
                }
                throw new IOException("HTTP " + response.statusCode() + "：" + url);
            }
            return response;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断：" + url, error);
        }
    }

    /**
     * 下载到文件：先写 .part，成功后再换名，避免中断留下半个 jar 被当成真模组。
     *
     * @param onBytes 已下载字节数的回调，可以传 null
     */
    public static void download(String url, Path target, LongConsumer onBytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = openStream(url);
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        long done = 0;
        try (InputStream in = response.body()) {
            Files.deleteIfExists(part);
            try (var out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                long lastReport = 0;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    long now = System.nanoTime();
                    if (onBytes != null && now - lastReport > 120_000_000L) {
                        lastReport = now;
                        onBytes.accept(done);
                    }
                }
            }
            if (total > 0 && Files.size(part) != total) {
                throw new IOException("下载不完整：" + target.getFileName()
                        + "（" + Files.size(part) + " / " + total + "）");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        if (onBytes != null) {
            onBytes.accept(done);
        }
    }

    /** 把一段文本里的第一个 https 链接挖出来（Modrinth / cfwidget 的字段偶尔是相对写法）。 */
    public static String absolute(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("http://")) {
            return "https://" + trimmed.substring("http://".length());
        }
        return trimmed.startsWith("https://") ? trimmed : "";
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*")
                .timeout(Duration.ofSeconds(60));
    }

    private static String send(HttpRequest request, Duration timeout) throws IOException {
        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + "：" + request.uri());
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断：" + request.uri(), error);
        }
    }

    /* ============================ 硬超时 ============================ */

    /** 下载 / 查询用的后台线程池：卡住的调用可以随时放弃，不拖死安装线程。 */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "yi-net");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 给一个可能卡死的网络调用套上硬超时。
     * <p>HttpClient 的超时管不到 DNS 解析和响应体停顿；对「源不响应也不断开」的情况，
     * 只能靠外面这条 deadline 把控制权拿回来。</p>
     */
    public static <T> T withDeadline(String what, long timeoutMillis,
                                     java.util.concurrent.Callable<T> task) throws IOException {
        java.util.concurrent.Future<T> future = POOL.submit(task);
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException error) {
            future.cancel(true);
            throw new IOException(what + " 超时（" + timeoutMillis / 1000 + " 秒）", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IOException(what + " 被打断", error);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            throw cause instanceof IOException io ? io
                    : new IOException(what + " 失败：" + cause, cause);
        }
    }
}
