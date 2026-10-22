package com.yilauncher.yi_launcher.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Modrinth 公开 API（不需要 key，但要带 UA）。
 * <p>识别思路：本地 jar 先算 sha1，一次 POST /v2/version_files 就能精确换回
 * 「项目 + 版本 + 图标」，比按名字猜可靠得多。</p>
 */
public final class ModrinthApi {

    private static final String BASE = "https://api.modrinth.com/v2";

    private ModrinthApi() {
    }

    /** 项目（用于图标和标题）。 */
    public record Project(String id, String title, String slug, String iconUrl, String description) {
    }

    /** 一个具体文件版本。 */
    public record Version(String id, String projectId, String versionNumber, String fileName,
                          String downloadUrl, long size, String gameVersions, String loaders) {
    }

    /** 按 sha1 批量查版本：hash → Version。查不到的部分不会出现在结果里。 */
    public static Map<String, Version> versionsBySha1(List<String> hashes) {
        Map<String, Version> result = new LinkedHashMap<>();
        if (hashes == null || hashes.isEmpty()) {
            return result;
        }
        try {
            String body = "{\"hashes\":[" + joinQuoted(hashes) + "],\"algorithm\":\"sha1\"}";
            Map<String, Object> root = Json.map(Json.parse(Remote.postJson(BASE + "/version_files", body)));
            for (String hash : hashes) {
                Map<String, Object> item = Json.map(root.get(hash.toLowerCase(Locale.ROOT)));
                if (!item.isEmpty()) {
                    result.put(hash.toLowerCase(Locale.ROOT), version(item));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 批量拿项目信息（图标、标题）。 */
    public static Map<String, Project> projects(List<String> ids) {
        Map<String, Project> result = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        try {
            String url = BASE + "/projects?ids=" + encode("[" + joinQuoted(ids) + "]");
            List<Object> items = Json.list(Json.parse(Remote.getText(url)));
            for (Object raw : items) {
                Map<String, Object> item = Json.map(raw);
                String id = Json.string(item, "id", "");
                if (id.isBlank()) {
                    continue;
                }
                result.put(id, new Project(id,
                        Json.string(item, "title", ""),
                        Json.string(item, "slug", ""),
                        Remote.absolute(Json.string(item, "icon_url", "")),
                        Json.string(item, "description", "")));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 项目的最新版本（可选按游戏版本 / 加载器过滤），用于显示"版本"。 */
    public static Optional<Version> latestVersion(String projectId, String gameVersion, String loader) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try {
            StringBuilder url = new StringBuilder(BASE).append("/project/").append(projectId).append("/version");
            List<String> query = new ArrayList<>();
            if (gameVersion != null && !gameVersion.isBlank()) {
                query.add("game_versions=" + encode("[\"" + gameVersion + "\"]"));
            }
            if (loader != null && !loader.isBlank()) {
                query.add("loaders=" + encode("[\"" + loader.toLowerCase(Locale.ROOT) + "\"]"));
            }
            if (!query.isEmpty()) {
                url.append('?').append(String.join("&", query));
            }
            List<Object> items = Json.list(Json.parse(Remote.getText(url.toString())));
            if (items.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(version(Json.map(items.get(0))));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    /** 用名字搜一个项目（CF 没认出来时的兜底）。 */
    public static Optional<Project> searchFirst(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            String url = BASE + "/search?limit=1&query=" + encode(query.trim());
            Map<String, Object> root = Json.map(Json.parse(Remote.getText(url)));
            List<Object> hits = Json.list(root.get("hits"));
            if (hits.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> hit = Json.map(hits.get(0));
            String id = Json.string(hit, "project_id", "");
            if (id.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Project(id,
                    Json.string(hit, "title", ""),
                    Json.string(hit, "slug", ""),
                    Remote.absolute(Json.string(hit, "icon_url", "")),
                    Json.string(hit, "description", "")));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    private static Version version(Map<String, Object> item) {
        List<Object> files = Json.list(item.get("files"));
        String fileName = "";
        String url = "";
        long size = 0;
        if (!files.isEmpty()) {
            Map<String, Object> file = Json.map(files.get(0));
            fileName = Json.string(file, "filename", "");
            url = Remote.absolute(Json.string(file, "url", ""));
            size = Json.integer(file, "size", 0);
        }
        return new Version(Json.string(item, "id", ""),
                Json.string(item, "project_id", ""),
                Json.string(item, "version_number", ""),
                fileName, url, size,
                String.join("/", strings(item.get("game_versions"))),
                String.join("/", strings(item.get("loaders"))));
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object raw : Json.list(value)) {
            result.add(String.valueOf(raw));
        }
        return result;
    }

    private static String joinQuoted(List<String> values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            parts.add("\"" + value.replace("\"", "") + "\"");
        }
        return String.join(",", parts);
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }
}
