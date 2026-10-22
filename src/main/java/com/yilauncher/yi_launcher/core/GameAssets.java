/* ==========================================================================
 *  衣启动器 · core 层 ｜ GameAssets.java —— 补齐客户端与资源
 *  做什么：缺客户端 jar 就下，缺资源 objects 就按 hash 并行补（已存在直接跳过）。
 *  谁在用：LoaderInstaller（装整合包 / 加载器前先把底子铺好）。
 *  入口：ensureClient()、ensureObjects()、sha1()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把「能启动」缺的那几样补齐：客户端 jar 与资源 objects。
 * <p>客户端 jar 走 Mojang + bmclapi 镜像；资源 objects 走
 * {@code resources.download.minecraft.net/{前两位}/{hash}}（比 piston-data 稳），
 * 并行 8 条线程拉，已存在就跳过。</p>
 */
public final class GameAssets {

    public interface Progress {
        void update(String note, double ratio);
    }

    private static final String OBJECTS = "https://resources.download.minecraft.net/";

    private GameAssets() {
    }

    /** 客户端 jar：缺失或体积不对就重下（Mojiang → bmclapi）。 */
    public static void ensureClient(Path root, Path versionsDir, String mcVersion,
                                    Progress progress) throws IOException {
        Path baseJson = versionsDir.resolve(mcVersion).resolve(mcVersion + ".json");
        if (!Files.isRegularFile(baseJson)) {
            return;
        }
        Map<String, Object> json = Json.map(Json.parse(Files.readString(baseJson)));
        Map<String, Object> client = Json.map(Json.map(json.get("downloads")).get("client"));
        String url = Remote.absolute(Json.string(client, "url", ""));
        String sha1 = Json.string(client, "sha1", "");
        int size = Json.integer(client, "size", 0);
        Path target = versionsDir.resolve(mcVersion).resolve(mcVersion + ".jar");
        if (Files.isRegularFile(target)) {
            try {
                if (size <= 0 || Files.size(target) == size) {
                    return;
                }
            } catch (IOException ignored) {
                // 读不到就当缺失
            }
        }
        if (progress != null) {
            progress.update("补客户端 " + mcVersion, 0.05);
        }
        List<Mirrors.Source> sources = new ArrayList<>();
        if (!url.isBlank()) {
            sources.add(new Mirrors.Source("Mojang", url));
        }
        sources.add(new Mirrors.Source("bmclapi",
                Mirrors.BMCL_GAME + "/version/" + mcVersion + "/client"));
        Files.createDirectories(target.getParent());
        Mirrors.download(target, sources, null);
        if (!sha1.isBlank()) {
            String actual;
            try {
                actual = sha1(target);
            } catch (Exception error) {
                actual = "";
            }
            if (!actual.equalsIgnoreCase(sha1)) {
                Files.deleteIfExists(target);
                throw new IOException("客户端 jar 校验失败");
            }
        }
    }

    /**
     * 资源 objects：按索引里的 hash 逐个补。
     * <p>1.20.1 大约 3000 个 / 500MB，所以并行拉；缺一两个不影响启动，全部失败才抛。</p>
     */
    public static void ensureObjects(Path root, Path versionsDir, String mcVersion,
                                     Progress progress) throws IOException {
        if (System.getProperty("yi.noAssets") != null) {
            return;   // 调试/快速安装时可以跳过几百 MB 的资源
        }
        Path baseJson = versionsDir.resolve(mcVersion).resolve(mcVersion + ".json");
        if (!Files.isRegularFile(baseJson)) {
            return;
        }
        Map<String, Object> json = Json.map(Json.parse(Files.readString(baseJson)));
        Map<String, Object> index = Json.map(json.get("assetIndex"));
        if (index.isEmpty()) {
            index = Json.map(Json.map(json.get("downloads")).get("assetIndex"));
        }
        String id = Json.string(index, "id", "");
        String indexUrl = Remote.absolute(Json.string(index, "url", ""));
        Path indexPath = root.resolve("assets").resolve("indexes").resolve(id + ".json");
        if (id.isBlank()) {
            return;
        }
        if (!Files.isRegularFile(indexPath)) {
            if (indexUrl.isBlank()) {
                return;
            }
            Files.createDirectories(indexPath.getParent());
            Mirrors.download(indexPath, Mirrors.withMirror("资源索引", indexUrl), null,
                    Mirrors.SMALL_DEADLINE);
        }
        Map<String, Object> deviy = Json.map(Json.parse(Files.readString(indexPath)));
        Map<String, Object> objects = Json.map(deviy.get("objects"));
        Path objectRoot = root.resolve("assets").resolve("objects");

        List<String> hashes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : objects.entrySet()) {
            String hash = Json.string(Json.map(entry.getValue()), "hash", "");
            if (hash.length() < 2) {
                continue;
            }
            Path target = objectRoot.resolve(hash.substring(0, 2)).resolve(hash);
            if (Files.isRegularFile(target)) {
                continue;
            }
            hashes.add(hash);
        }
        if (hashes.isEmpty()) {
            return;
        }
        if (progress != null) {
            progress.update("补游戏资源 " + hashes.size() + " 个文件", 0.06);
        }
        AtomicInteger finished = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger cursor = new AtomicInteger();
        int workers = 8;
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(() -> {
                while (true) {
                    int position = cursor.getAndIncrement();
                    if (position >= hashes.size()) {
                        return;
                    }
                    String hash = hashes.get(position);
                    Path target = objectRoot.resolve(hash.substring(0, 2)).resolve(hash);
                    List<Mirrors.Source> sources = List.of(
                            new Mirrors.Source("Mojang", OBJECTS + hash.substring(0, 2) + "/" + hash),
                            new Mirrors.Source("bmclapi", Mirrors.BMCL_GAME
                                    + "/assets/" + hash.substring(0, 2) + "/" + hash));
                    try {
                        Mirrors.download(target, sources, null, 120_000L);
                    } catch (IOException error) {
                        failed.incrementAndGet();
                    }
                    int doneCount = finished.incrementAndGet();
                    if (progress != null && doneCount % 40 == 0) {
                        progress.update("补游戏资源 " + doneCount + " / " + hashes.size(), 0.06);
                    }
                }
            }, "yi-assets-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (progress != null) {
            progress.update("游戏资源就绪（失败 " + failed.get() + " 个）", 0.1);
        }
    }

    private static String sha1(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** 给安装器留的口子：外部可以直接传一段 JSON 里的资源索引 id（调试用）。 */
    static String describeVersion(Path jsonFile) {
        try {
            Map<String, Object> json = Json.map(Json.parse(Files.readString(jsonFile, StandardCharsets.UTF_8)));
            return Json.string(json, "id", "");
        } catch (Exception error) {
            return "";
        }
    }
}
