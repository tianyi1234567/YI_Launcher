package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版下载：版本清单 + 版本本体安装（参考 HMCL 的做法）。
 * <p>全程镜像优先：清单、版本 json、客户端 jar 都先走 bmclapi，失败才回 Mojang 官方；
 * 清单会缓存到启动器目录，12 小时内直接读文件。</p>
 */
public final class VanillaVersions {

    /** 清单里的一条版本。 */
    public record Version(String id, String type, long releaseTime) {
        public String typeLabel() {
            return switch (type) {
                case "release" -> "正式版";
                case "snapshot" -> "快照";
                case "old_beta" -> "远古 Beta";
                case "old_alpha" -> "远古 Alpha";
                default -> type;
            };
        }
    }

    /** 进度回调：一句说明 + 0..1。 */
    public interface Progress {
        void update(String note, double ratio);
    }

    private static final String OFFICIAL_MANIFEST =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final long CACHE_TTL = 12 * 3600_000L;

    private static List<Version> cache;
    private static String cacheManifest;

    private VanillaVersions() {
    }

    /* ============================ 版本清单 ============================ */

    /** 版本清单：先读本地缓存，再镜像优先拉取，缓存 12 小时。 */
    public static synchronized List<Version> manifest() throws IOException {
        if (cache != null) {
            return cache;
        }
        Path cached = cacheFile();
        if (Files.isRegularFile(cached)) {
            try {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(cached).toMillis();
                if (age < CACHE_TTL) {
                    String text = Files.readString(cached, StandardCharsets.UTF_8);
                    List<Version> versions = parseManifest(text);
                    if (!versions.isEmpty()) {
                        cacheManifest = text;
                        cache = versions;
                        return versions;
                    }
                }
            } catch (Exception ignored) {
                // 缓存坏了就重新拉
            }
        }
        IOException last = null;
        for (String url : List.of(Mirrors.BMCL_GAME + "/mc/game/version_manifest_v2.json",
                OFFICIAL_MANIFEST)) {
            try {
                // 12 秒没响应就换源，不能让界面一直转圈
                String text = Mirrors.queryText("版本清单", url);
                List<Version> versions = parseManifest(text);
                if (!versions.isEmpty()) {
                    cacheManifest = text;
                    cache = versions;
                    saveCache(cached, text);
                    return versions;
                }
            } catch (IOException error) {
                last = error;
            }
        }
        throw last == null ? new IOException("拿不到版本清单") : last;
    }

    private static List<Version> parseManifest(String text) {
        List<Version> versions = new ArrayList<>();
        Map<String, Object> root = Json.map(Json.parse(text));
        for (Object raw : Json.list(root.get("versions"))) {
            Map<String, Object> item = Json.map(raw);
            String id = Json.string(item, "id", "");
            if (id.isBlank()) {
                continue;
            }
            versions.add(new Version(id, Json.string(item, "type", "release"),
                    parseTime(Json.string(item, "releaseTime"))));
        }
        return versions;
    }

    private static Path cacheFile() {
        Path config = LauncherConfig.defaultFile();
        Path dir = config.getParent() == null ? Path.of(".") : config.getParent();
        return dir.resolve("version_manifest.json");
    }

    private static void saveCache(Path file, String text) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写不进去下次再拉
        }
    }

    /** 清单里某个版本的 json 地址（官方）。 */
    private static String officialVersionJson(String id) {
        try {
            Map<String, Object> root = Json.map(Json.parse(manifestText()));
            for (Object raw : Json.list(root.get("versions"))) {
                Map<String, Object> item = Json.map(raw);
                if (id.equals(Json.string(item, "id", ""))) {
                    return Json.string(item, "url", "");
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String manifestText() throws IOException {
        manifest();
        return cacheManifest == null ? "" : cacheManifest;
    }

    /* ============================ 下载 ============================ */

    /**
     * 下载一个原版版本：版本 json + 客户端 jar + 资源索引。
     * <p>客户端 jar 放到 {@code versions/<id>/<id>.jar}，启动器启动时能直接找到。</p>
     */
    public static Path install(Path root, Path versionsDir, String id, Progress progress) throws IOException {
        note(progress, "准备下载 " + id, 0.02);
        Path dir = versionsDir.resolve(id);
        Files.createDirectories(dir);
        Path jsonFile = dir.resolve(id + ".json");

        if (!Files.isRegularFile(jsonFile)) {
            String text = null;
            IOException last = null;
            for (String url : List.of(Mirrors.BMCL_GAME + "/version/" + id + "/json",
                    officialVersionJson(id))) {
                if (url == null || url.isBlank()) {
                    continue;
                }
                try {
                    text = Mirrors.queryText("版本 " + id + " 的 json", url);
                    if (text != null && !text.isBlank()) {
                        break;
                    }
                } catch (IOException error) {
                    last = error;
                }
            }
            if (text == null || text.isBlank()) {
                throw last == null ? new IOException("拿不到 " + id + " 的版本信息") : last;
            }
            Files.writeString(jsonFile, text, StandardCharsets.UTF_8);
        }
        note(progress, "版本信息就绪", 0.2);

        Map<String, Object> json = Json.map(Json.parse(Files.readString(jsonFile)));
        Map<String, Object> client = Json.map(Json.map(json.get("downloads")).get("client"));
        String clientUrl = Json.string(client, "url", "");
        Path jar = dir.resolve(id + ".jar");
        if (!Files.isRegularFile(jar)) {
            List<Mirrors.Source> sources = new ArrayList<>();
            sources.add(new Mirrors.Source("bmclapi", Mirrors.BMCL_GAME + "/version/" + id + "/client"));
            if (!clientUrl.isBlank()) {
                sources.add(new Mirrors.Source("Mojang", clientUrl));
            }
            note(progress, "下载客户端 " + id, 0.35);
            Mirrors.download(jar, sources, null, 600_000L);
        }
        note(progress, "客户端就绪", 0.75);

        LoaderInstaller.ensureAssetsIndex(root, versionsDir, id, (text, ratio) ->
                note(progress, text, 0.75 + Math.min(0.2, Math.max(0, ratio) * 0.2)));
        note(progress, id + " 下载完成", 1.0);
        return jar;
    }

    private static void note(Progress progress, String text, double ratio) {
        if (progress != null) {
            progress.update(text, ratio);
        }
    }

    private static long parseTime(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception error) {
            return 0;
        }
    }
}
