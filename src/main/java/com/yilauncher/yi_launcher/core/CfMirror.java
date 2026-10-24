package com.yilauncher.yi_launcher.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CurseForge 项目查询的「免 Key」通道：走 MCIM 镜像的 curseforge 接口。
 *
 * <p>三条路都试过：官方 API 要 Key；curseforge.com 的网页接口被 Cloudflare 拦（403）；
 * cfwidget 能用但慢（本项目实测 4~12 秒一次）。镜像这条 0.7~3 秒就能拿到项目名、
 * 项目页地址和 PNG 缩略图（media.forgecdn.net），正好用来补图标和标题。</p>
 *
 * <p>查到的结果按 slug / 名字缓存；只有「确实查过且没有」才记空，网络错误不记，
 * 免得一次超时把后面所有查询都判死。</p>
 */
public final class CfMirror {

    /** MCIM 镜像：和 CurseForge 官方 API 同形状。 */
    private static final String BASE = Mirrors.MCIM + "/curseforge/v1/mods/search";

    private record Hit(int id, String name, String slug, String thumbnail, String pageUrl, String summary) {
    }

    private static final Map<String, Hit> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISS = ConcurrentHashMap.newKeySet();

    private CfMirror() {
    }

    /** 查到的项目（对外只需要这几个字段）。 */
    public record Mod(int id, String name, String slug, String thumbnail, String pageUrl, String summary) {
    }

    /** 按 slug 精确查（CurseForge 的 slug 一般等于 modid 或模组英文名）。 */
    public static Optional<Mod> bySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String cleaned = slug.trim().toLowerCase(Locale.ROOT);
        return lookup("slug:" + cleaned, "slug=" + encode(cleaned))
                .map(hit -> new Mod(hit.id(), hit.name(), hit.slug(), hit.thumbnail(),
                        hit.pageUrl(), hit.summary()));
    }

    /** 按名字模糊查，取第一个（镜像已按相关度排序）。 */
    public static Optional<Mod> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String cleaned = name.trim();
        return lookup("name:" + cleaned.toLowerCase(Locale.ROOT), "searchFilter=" + encode(cleaned))
                .map(hit -> new Mod(hit.id(), hit.name(), hit.slug(), hit.thumbnail(),
                        hit.pageUrl(), hit.summary()));
    }

    /* ============================ 内部 ============================ */

    private static Optional<Hit> lookup(String key, String query) {
        Hit cached = CACHE.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (MISS.contains(key)) {
            return Optional.empty();
        }
        try {
            String text = Mirrors.queryText("CurseForge 项目",
                    BASE + "?gameId=432&pageSize=1&" + query);
            Hit hit = parse(text);
            if (hit == null) {
                MISS.add(key);
                return Optional.empty();
            }
            CACHE.put(key, hit);
            return Optional.of(hit);
        } catch (Exception error) {
            // 网络问题：不记空，下次还能再试
            return Optional.empty();
        }
    }

    private static Hit parse(String text) {
        Map<String, Object> root = Json.map(Json.parse(text));
        List<Object> data = Json.list(root.get("data"));
        if (data.isEmpty()) {
            return null;
        }
        Map<String, Object> item = Json.map(data.get(0));
        Map<String, Object> logo = Json.map(item.get("logo"));
        String thumbnail = Json.string(logo, "thumbnailUrl", "");
        if (thumbnail.isBlank()) {
            thumbnail = Json.string(logo, "url", "");
        }
        Map<String, Object> links = Json.map(item.get("links"));
        String page = Json.string(links, "websiteUrl", "");
        String name = Json.string(item, "name", "");
        if (name.isBlank() && thumbnail.isBlank()) {
            return null;
        }
        return new Hit(Json.integer(item, "id", 0), name, Json.string(item, "slug", ""),
                Remote.absolute(thumbnail), page, Json.string(item, "summary", ""));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 给「模组管理」按需补图标时用的轻量缓存清理（测试用）。 */
    static Map<String, Hit> debugCache() {
        return new LinkedHashMap<>(CACHE);
    }
}
