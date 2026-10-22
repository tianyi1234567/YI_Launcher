package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 启动页搜索：在版本列表与 .minecraft 的常见目录里按名字找东西。
 * <p>纯文件系统操作，调用方负责放到后台线程里跑。</p>
 */
public final class SearchIndex {

    /** 一条搜索结果：version 非空表示版本，path 非空表示文件或目录。 */
    public record Hit(String kind, String title, String subtitle, VersionEntry version, Path path) {
    }

    private static final int PER_KIND_LIMIT = 40;
    private static final int TOTAL_LIMIT = 120;

    private SearchIndex() {
    }

    public static List<Hit> search(Path root, VersionEntry selected, List<VersionEntry> versions,
                                   String query, String filter) {
        List<Hit> hits = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || root == null) {
            return hits;
        }
        boolean all = filter == null || "全部".equals(filter);

        if (all || "版本".equals(filter)) {
            if (versions != null) {
                for (VersionEntry version : versions) {
                    String name = version.displayName == null ? version.id : version.displayName;
                    if (contains(name, needle) || contains(version.loaderText(), needle)) {
                        hits.add(new Hit("版本", name,
                                version.loaderText() + " · Java " + version.javaMajor
                                        + (version.modsCount > 0 ? " · " + version.modsCount + " 模组" : " · 无模组"),
                                version, version.dir));
                    }
                }
            }
        }
        if (all || "模组".equals(filter)) {
            for (Path dir : modsDirs(root, selected)) {
                collectFiles(dir, needle, "模组", hits, ".jar");
            }
        }
        if (all || "光影".equals(filter)) {
            collectFiles(root.resolve("shaderpacks"), needle, "光影", hits, ".zip");
        }
        if (all || "资源包".equals(filter)) {
            collectFiles(root.resolve("resourcepacks"), needle, "资源包", hits, ".zip");
        }
        if (all || "存档".equals(filter)) {
            collectDirs(root.resolve("saves"), needle, hits);
        }
        return hits.size() > TOTAL_LIMIT ? new ArrayList<>(hits.subList(0, TOTAL_LIMIT)) : hits;
    }

    /** 隔离版本先看自己的 mods，再看共享 mods；两边都不存在就都不看。 */
    private static List<Path> modsDirs(Path root, VersionEntry selected) {
        List<Path> dirs = new ArrayList<>();
        if (selected != null && selected.isolation) {
            dirs.add(selected.dir.resolve("mods"));
        }
        Path shared = root.resolve("mods");
        if (dirs.stream().noneMatch(shared::equals)) {
            dirs.add(shared);
        }
        return dirs;
    }

    private static void collectFiles(Path dir, String needle, String kind, List<Hit> hits, String suffix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .filter(path -> contains(path.getFileName().toString(), needle))
                    .limit(PER_KIND_LIMIT)
                    .forEach(path -> hits.add(new Hit(kind, path.getFileName().toString(),
                            dir.getFileName() + " · " + sizeText(path), null, path)));
        } catch (IOException ignored) {
        }
    }

    private static void collectDirs(Path dir, String needle, List<Hit> hits) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> contains(path.getFileName().toString(), needle))
                    .limit(PER_KIND_LIMIT)
                    .forEach(path -> hits.add(new Hit("存档", path.getFileName().toString(),
                            "saves · 存档目录", null, path)));
        } catch (IOException ignored) {
        }
    }

    private static String sizeText(Path file) {
        try {
            long bytes = Files.size(file);
            if (bytes >= 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
            }
            return Math.max(1, bytes / 1024) + " KB";
        } catch (IOException ex) {
            return "大小未知";
        }
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }
}
