package com.yilauncher.yi_launcher.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模组 / 资源包 / 光影包 搜索：CurseForge 与 Modrinth 一起查，合并成一页。
 * <p>两个源都有同一个项目时只保留 CurseForge 那条（按 slug / 标题去重），
 * 这样列表里不会出现两个一模一样的东西。</p>
 */
public final class ModSearch {

    /** 搜的是哪一类东西。 */
    public enum Kind {
        MOD("模组", 6, "mod"),
        RESOURCE_PACK("材质包", 12, "resourcepack"),
        SHADER("光影包", 6552, "shader");

        public final String label;
        /** CurseForge classId。 */
        final int classId;
        /** Modrinth project_type。 */
        final String projectType;

        Kind(String label, int classId, String projectType) {
            this.label = label;
            this.classId = classId;
            this.projectType = projectType;
        }
    }

    /** 一条搜索结果。 */
    public record Hit(String kind, String source, String id, String slug, String title,
                      String author, String summary, String iconUrl, String pageUrl, long downloads) {
    }

    /** 一页结果 + 是否还有下一页。 */
    public record Page(List<Hit> hits, boolean hasNext, int page) {
    }

    private static final String CF_BASE = "https://api.curseforge.com/v1";
    private static final String MR_BASE = "https://api.modrinth.com/v2";
    /** 搜过的页缓存：键 = 类型|每页条数|页码|关键词。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Page> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ModSearch() {
    }

    /**
     * 搜一页。两个源各取一页再合并去重。
     *
     * @param query  关键词，空串就是「最热门」
     * @param kind   模组 / 材质包 / 光影
     * @param page   从 0 开始
     * @param size   每页条数
     */
    public static Page search(String query, Kind kind, int page, int size) throws Exception {
        String key = kind.name() + "|" + size + "|" + page + "|"
                + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT));
        Page cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int index = Math.max(0, page) * size;
        List<Hit> cf = curseForge(query, kind, index, size);
        List<Hit> mr = modrinth(query, kind, index, size);

        Set<String> seen = new LinkedHashSet<>();
        List<Hit> merged = new ArrayList<>();
        for (Hit hit : cf) {
            if (seen.add(keyOf(hit))) {
                merged.add(hit);
            }
        }
        for (Hit hit : mr) {
            // 两个源都有 → 只留 CurseForge 那条（seen 里已有同 slug 的就跳过）
            if (seen.add(keyOf(hit))) {
                merged.add(hit);
            }
        }
        boolean hasNext = cf.size() >= size || mr.size() >= size;
        Page result = new Page(merged, hasNext, page);
        // 搜过的页存起来：来回翻页不再发请求
        CACHE.put(key, result);
        return result;
    }

    /** 去重键：slug 归一化；没有 slug 就用标题。 */
    static String keyOf(Hit hit) {
        String slug = normalize(hit.slug());
        if (!slug.isBlank()) {
            return slug;
        }
        return normalize(hit.title());
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /* ============================ CurseForge ============================ */

    private static List<Hit> curseForge(String query, Kind kind, int index, int size) {
        List<Hit> hits = new ArrayList<>();
        if (!CurseForge.available()) {
            return hits;
        }
        try {
            StringBuilder url = new StringBuilder(CF_BASE + "/mods/search")
                    .append("?gameId=432")
                    .append("&classId=").append(kind.classId)
                    .append("&index=").append(index)
                    .append("&pageSize=").append(size)
                    .append("&sortField=6")     // 总下载量
                    .append("&sortOrder=desc");
            if (query != null && !query.isBlank()) {
                url.append("&searchFilter=").append(encode(query.trim()));
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-api-key", CurseForge.key());
            Map<String, Object> root = Json.map(Json.parse(Remote.getTextWithHeaders(url.toString(), headers)));
            for (Object raw : Json.list(root.get("data"))) {
                Map<String, Object> item = Json.map(raw);
                String name = Json.string(item, "name", "");
                String slug = Json.string(item, "slug", "");
                String summary = Json.string(item, "summary", "");
                String logo = Json.string(Json.map(item.get("logo")), "thumbnailUrl", "");
                if (logo.isBlank()) {
                    logo = Json.string(Json.map(item.get("logo")), "url", "");
                }
                long downloads = (long) Json.integer(item, "downloadCount", 0);
                String author = Json.string(Json.list(item.get("authors")).isEmpty() ? Map.of()
                        : Json.map(Json.list(item.get("authors")).get(0)), "name", "");
                String links = Json.string(Json.map(item.get("links")), "websiteUrl", "");
                int id = Json.integer(item, "id", 0);
                if (id == 0 || name.isBlank()) {
                    continue;
                }
                hits.add(new Hit(kind.name(), PackInstaller.CURSEFORGE, String.valueOf(id), slug,
                        name, author, summary, logo,
                        links.isBlank() ? "https://www.curseforge.com/minecraft/" + "mc-mods/" + slug : links,
                        downloads));
            }
        } catch (Exception error) {
            if (System.getProperty("yi.debugSearch") != null) {
                System.out.println("[search] CF 失败：" + error);
            }
            // 网络不好就当这个源没结果
        }
        return hits;
    }

    /* ============================ Modrinth ============================ */

    private static List<Hit> modrinth(String query, Kind kind, int index, int size) {
        List<Hit> hits = new ArrayList<>();
        try {
            String facets = "[[\"project_type:" + kind.projectType + "\"]]";
            String url = MR_BASE + "/search?limit=" + size + "&offset=" + index
                    + "&index=downloads&query=" + encode(query == null ? "" : query.trim())
                    + "&facets=" + encode(facets);
            Map<String, Object> root = Json.map(Json.parse(Remote.getText(url)));
            for (Object raw : Json.list(root.get("hits"))) {
                Map<String, Object> item = Json.map(raw);
                String title = Json.string(item, "title", "");
                String slug = Json.string(item, "slug", "");
                String summary = Json.string(item, "description", "");
                String icon = Json.string(item, "icon_url", "");
                long downloads = (long) Json.integer(item, "downloads", 0);
                String author = Json.string(item, "author", "");
                if (title.isBlank()) {
                    continue;
                }
                hits.add(new Hit(kind.name(), PackInstaller.MODRINTH, slug, slug, title, author,
                        summary, icon, "https://modrinth.com/project/" + slug, downloads));
            }
        } catch (Exception error) {
            if (System.getProperty("yi.debugSearch") != null) {
                System.out.println("[search] Modrinth 失败：" + error);
            }
            // 同上
        }
        return hits;
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
