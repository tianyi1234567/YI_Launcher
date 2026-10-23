package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CurseForge 的免密钥通道（cfwidget）。
 * <p>官方 api.curseforge.com 要 X-Api-Key，网站接口又被 Cloudflare 挡；cfwidget 是公开镜像，
 * 能给到项目标题、图标和「文件 id → 文件名」。拿到文件名之后就能直接拼 CDN 地址：
 * {@code https://mediafilez.forgecdn.net/files/{id/1000}/{id%1000}/{文件名}}，不再需要 key。</p>
 */
public final class CfWidget {

    private static final String BASE = "https://api.cfwidget.com";
    private static final Map<Integer, Project> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Project> BY_SLUG = new LinkedHashMap<>();
    /** 连续失败次数；到 2 次就冷却，冷却期内不再发请求。 */
    private static volatile int failures;
    private static volatile long degradeUntil;

    private CfWidget() {
    }

    /** 整合包里的一个文件。 */
    public record FileEntry(int id, String name, String gameVersion) {
    }

    /** 项目信息：标题、图标、文件列表。 */
    public record Project(int id, String title, String thumbnail, String pageUrl, List<FileEntry> files) {
    }

    /** 按项目 id 查（整合包清单里给的就是 id）。查不到 / 网络失败返回空。 */
    public static Optional<Project> byId(int id) {
        Project cached = BY_ID.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Project> project = read(BASE + "/" + id);
        project.ifPresent(value -> BY_ID.put(id, value));
        return project;
    }

    /** 按 slug 查（模组管理里用 modid 猜 slug）。 */
    public static Optional<Project> bySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String key = slug.trim().toLowerCase(Locale.ROOT);
        Project cached = BY_SLUG.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Project> project = read(BASE + "/minecraft/mc-mods/" + key);
        project.ifPresent(value -> {
            BY_SLUG.put(key, value);
            BY_ID.putIfAbsent(value.id(), value);
        });
        return project;
    }

    /** 清单里只有 projectID + fileID，这里换出文件名；换不到就没法拼 CDN 地址。 */
    public static String fileName(int projectId, int fileId) {
        return byId(projectId).flatMap(project -> project.files().stream()
                        .filter(file -> file.id() == fileId)
                        .map(FileEntry::name)
                        .findFirst())
                .orElse("");
    }

    /**
     * CurseForge CDN 地址。fileId 拆成 {id/1000}/{id%1000 三位}，文件名必须完全一致，
     * 否则 CDN 会直接 403。
     */
    public static String cdnUrl(int fileId, String fileName) {
        if (fileId <= 0 || fileName == null || fileName.isBlank()) {
            return "";
        }
        return "https://mediafilez.forgecdn.net/files/"
                + (fileId / 1000) + "/" + String.format(Locale.ROOT, "%03d", fileId % 1000)
                + "/" + encodeSegment(fileName);
    }

    /**
     * 备用 CDN：forgecdn 有两个域名，mediafilez 上不一定有每个文件（会 403），
     * edge 上一般都有（返回 302 到真实 CDN）。作者关掉第三方下载时只能靠这个。
     */
    public static String edgeUrl(int fileId, String fileName) {
        if (fileId <= 0 || fileName == null || fileName.isBlank()) {
            return "";
        }
        return "https://edge.forgecdn.net/files/"
                + (fileId / 1000) + "/" + String.format(Locale.ROOT, "%03d", fileId % 1000)
                + "/" + encodeSegment(fileName);
    }

    /**
     * 文件名要百分号编码：模组名里的 {@code +}（如 sodium-...beta+mc26.3.jar）
     * 原样发出去 CDN 直接 403，必须写成 %2B。
     */
    private static String encodeSegment(String name) {
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        return encoded.replace("+", "%20");
    }

    /**
     * cfwidget 是否还能用。
     * <p>它一旦连续失败/超时（国内经常连上不回应），后面 249 个文件逐个等 12 秒会把安装拖死；
     * 所以连续两次失败就冷却 5 分钟，期间直接走 MCIM。</p>
     */
    public static boolean healthy() {
        return System.currentTimeMillis() >= degradeUntil;
    }

    private static void fail() {
        if (++failures >= 2) {
            degradeUntil = System.currentTimeMillis() + 300_000L;
        }
    }

    private static Optional<Project> read(String url) {
        if (!healthy()) {
            return Optional.empty();
        }
        String body;
        try {
            body = Remote.withDeadline("cfwidget", 6_000, () -> Remote.getText(url));
            failures = 0;
        } catch (IOException error) {
            fail();
            return Optional.empty();
        }
        try {
            Map<String, Object> root = Json.map(Json.parse(body));
            int id = Json.integer(root, "id", 0);
            String title = Json.string(root, "title", "");
            if (id <= 0 || title.isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> urls = Json.map(root.get("urls"));
            String page = Remote.absolute(Json.string(urls, "curseforge", ""));
            List<FileEntry> files = new ArrayList<>();
            for (Object raw : Json.list(root.get("files"))) {
                Map<String, Object> item = Json.map(raw);
                int fileId = Json.integer(item, "id", 0);
                String name = Json.string(item, "name", "");
                if (fileId <= 0 || name.isBlank()) {
                    continue;
                }
                String version = Json.string(item, "version", "");
                if (version.isBlank()) {
                    version = Json.list(item.get("versions")).stream()
                            .map(String::valueOf)
                            .filter(text -> text.matches("\\d+\\.\\d+(\\.\\d+)?"))
                            .findFirst()
                            .orElse("");
                }
                files.add(new FileEntry(fileId, name, version));
            }
            return Optional.of(new Project(id, title,
                    Remote.absolute(Json.string(root, "thumbnail", "")), page, files));
        } catch (Exception error) {
            return Optional.empty();
        }
    }
}
