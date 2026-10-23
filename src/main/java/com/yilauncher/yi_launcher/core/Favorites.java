package com.yilauncher.yi_launcher.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收藏的模组 / 材质包 / 光影：存在启动器配置目录的 favorites.json 里。
 * <p>只记展示需要的字段（来源、项目 id、slug、名字、图标、页面地址、收藏时间），
 * 打开收藏模块时直接读文件，不用联网。</p>
 */
public final class Favorites {

    /** 一条收藏。 */
    public record Item(String kind, String source, String id, String slug, String title,
                       String iconUrl, String pageUrl, long time) {
    }

    private Favorites() {
    }

    public static Path file() {
        Path config = LauncherConfig.defaultFile();
        Path dir = config.getParent() == null ? Path.of(".") : config.getParent();
        return dir.resolve("favorites.json");
    }

    /** 全部收藏，按收藏时间从新到旧。 */
    public static synchronized List<Item> all() {
        List<Item> items = new ArrayList<>();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return items;
        }
        try {
            Map<String, Object> root = Json.map(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            for (Object raw : Json.list(root.get("items"))) {
                Map<String, Object> map = Json.map(raw);
                String kind = Json.string(map, "kind", "");
                String id = Json.string(map, "id", "");
                if (kind.isBlank() || id.isBlank()) {
                    continue;
                }
                items.add(new Item(kind, Json.string(map, "source", ""), id,
                        Json.string(map, "slug", ""), Json.string(map, "title", ""),
                        Json.string(map, "icon", ""), Json.string(map, "page", ""),
                        Json.integer(map, "time", 0)));
            }
        } catch (Exception ignored) {
            // 文件坏了就当没有收藏
        }
        items.sort((left, right) -> Long.compare(right.time(), left.time()));
        return items;
    }

    public static synchronized boolean has(String kind, String id) {
        for (Item item : all()) {
            if (item.kind().equals(kind) && item.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 加一条（同一个项目只留一条，重复收藏会刷新时间）。 */
    public static synchronized void add(Item item) {
        List<Item> items = all();
        items.removeIf(old -> old.kind().equals(item.kind()) && old.id().equals(item.id()));
        items.add(item);
        save(items);
    }

    public static synchronized void remove(String kind, String id) {
        List<Item> items = all();
        items.removeIf(old -> old.kind().equals(kind) && old.id().equals(id));
        save(items);
    }

    private static void save(List<Item> items) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (index > 0) {
                sb.append(',');
            }
            sb.append("\n  {\"kind\":\"").append(escape(item.kind()))
                    .append("\",\"source\":\"").append(escape(item.source()))
                    .append("\",\"id\":\"").append(escape(item.id()))
                    .append("\",\"slug\":\"").append(escape(item.slug()))
                    .append("\",\"title\":\"").append(escape(item.title()))
                    .append("\",\"icon\":\"").append(escape(item.iconUrl()))
                    .append("\",\"page\":\"").append(escape(item.pageUrl()))
                    .append("\",\"time\":").append(item.time())
                    .append('}');
        }
        sb.append("\n]}\n");
        try {
            Path file = file();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写不进去就算了，界面上会提示失败
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
