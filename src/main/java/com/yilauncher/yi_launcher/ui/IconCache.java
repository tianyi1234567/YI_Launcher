package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherConfig;
import com.yilauncher.yi_launcher.core.Remote;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 图标加载：内存缓存 + 磁盘缓存 + 固定线程池。
 *
 * <p>踩过的坑，记下来免得再犯：</p>
 * <ul>
 *   <li>以前每个图标开一条线程，模组管理里 283 个模组就是 283 条线程抢带宽，慢得离谱；
 *       现在固定 12 条（网络延迟为主，多几条更快），同一个地址只拉一次，其余挂起等回调
 *       （以前直接把后来的调用丢掉，那些图标就永远不出现了）。</li>
 *   <li>Modrinth 的图标地址是 {@code _96.webp}，JavaFX 的 Image 根本不支持 WebP，
 *       以前全部静默失败。现在统一换成 {@code /data/{id}/icon.png}（同一张图，PNG 版）。</li>
 *   <li>单个图标给 6 秒硬超时：某个 CDN 卡住也不会把线程池一直占着。</li>
 *   <li>拉到就按 48px 解码（CF 图标常常 256px，按原尺寸解码纯属浪费），并落盘缓存，
 *       下次打开页面直接读磁盘。</li>
 * </ul>
 */
public final class IconCache {

    /** 显示尺寸 36px，多解一点像素留给高分屏。 */
    private static final int DECODE_SIZE = 48;
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<Consumer<Image>>> WAITERS = new ConcurrentHashMap<>();
    /** 拉取失败的地址，本次运行不再重试（免得反复卡同一条坏链接）。 */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final Path DIR = LauncherConfig.defaultFile().getParent().resolve("iconcache");

    private static final ExecutorService POOL = Executors.newFixedThreadPool(12, task -> {
        Thread thread = new Thread(task, "yi-icon");
        thread.setDaemon(true);
        return thread;
    });

    private IconCache() {
    }

    /** 已经在内存里的图标（没有就返回 null）。 */
    public static Image cached(String url) {
        String key = normalize(url);
        return key == null ? null : CACHE.get(key);
    }

    /**
     * 异步取图标；拿到后一定在 FX 线程回调。
     * <p>同一个地址并发只发一次请求，其他调用等这一次的结果。</p>
     */
    public static void load(String url, Consumer<Image> onLoaded) {
        String key = normalize(url);
        if (key == null || onLoaded == null) {
            return;
        }
        Image ready = CACHE.get(key);
        if (ready != null) {
            onLoaded.accept(ready);
            return;
        }
        if (FAILED.contains(key)) {
            return;
        }
        List<Consumer<Image>> waiters = WAITERS.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (waiters) {
            waiters.add(onLoaded);
            if (waiters.size() > 1) {
                return;   // 已经有人在拉了，等它的回调
            }
        }
        POOL.submit(() -> fetch(key, waiters));
    }

    /** 先批量排队（列表还没渲染时预热，滚到哪都已经是图了）。 */
    public static void prefetch(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            load(url, image -> {
                // 预热不需要做事
            });
        }
    }

    /** 清空内存缓存（磁盘缓存还在），测冷热对比 / 设置页清理时用。 */
    public static void clearMemory() {
        CACHE.clear();
        FAILED.clear();
    }

    /* ============================ 地址归一化 ============================ */

    /**
     * Modrinth 只给 WebP（JavaFX 解不了），换成同一张图的 PNG 版本。
     * <p>{@code cdn.modrinth.com/data/<项目id>/<哈希>_96.webp} → {@code cdn.modrinth.com/data/<项目id>/icon.png}</p>
     */
    static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("//")) {
            trimmed = "https:" + trimmed;
        }
        if (!trimmed.endsWith(".webp")) {
            return trimmed;
        }
        int data = trimmed.indexOf("cdn.modrinth.com/data/");
        if (data < 0) {
            return trimmed;
        }
        int start = data + "cdn.modrinth.com/data/".length();
        int end = trimmed.indexOf('/', start);
        if (end <= start) {
            return trimmed;
        }
        return "https://cdn.modrinth.com/data/" + trimmed.substring(start, end) + "/icon.png";
    }

    /* ============================ 抓取 / 解码 ============================ */

    private static void fetch(String url, List<Consumer<Image>> waiters) {
        Image image = null;
        try {
            image = fromDisk(url);
            if (image == null) {
                byte[] bytes;
                try (InputStream in = Remote.openStream(url, TIMEOUT).body()) {
                    bytes = in.readAllBytes();
                }
                image = new Image(new ByteArrayInputStream(bytes), DECODE_SIZE, DECODE_SIZE, true, true);
                if (image.isError() || image.getWidth() < 1) {
                    image = null;
                } else {
                    save(url, bytes);
                }
            }
        } catch (Exception ignored) {
            // 失败就保持首字母
        }
        WAITERS.remove(url);
        if (image == null) {
            FAILED.add(url);
            return;
        }
        CACHE.put(url, image);
        Image ready = image;
        Platform.runLater(() -> {
            List<Consumer<Image>> callbacks;
            synchronized (waiters) {
                callbacks = new ArrayList<>(waiters);
            }
            for (Consumer<Image> callback : callbacks) {
                callback.accept(ready);
            }
        });
    }

    /* ============================ 磁盘缓存 ============================ */

    private static Path fileOf(String url) {
        String name = Integer.toHexString(url.hashCode()) + "-" + url.length() + ".img";
        return DIR.resolve(name);
    }

    private static Image fromDisk(String url) {
        try {
            Path file = fileOf(url);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            Image image = new Image(new ByteArrayInputStream(bytes), DECODE_SIZE, DECODE_SIZE, true, true);
            if (image.isError() || image.getWidth() < 1) {
                Files.deleteIfExists(file);
                return null;
            }
            return image;
        } catch (Exception error) {
            return null;
        }
    }

    private static void save(String url, byte[] bytes) {
        try {
            Files.createDirectories(DIR);
            Files.write(fileOf(url), bytes);
        } catch (Exception ignored) {
            // 存不下就算了，下次重新下
        }
    }
}
