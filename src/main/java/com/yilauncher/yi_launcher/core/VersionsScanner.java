package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 扫描 .minecraft/versions，读取每个版本的 json 并识别加载器。 */
public final class VersionsScanner {

    private VersionsScanner() {
    }

    public static List<VersionEntry> scan(Path root) {
        return scan(MinecraftRoot.versions(root), root);
    }

    /** 扫描任意 versions 目录（导入的分组用），root 用来找 libraries / mods / assets。 */
    public static List<VersionEntry> scan(Path versionsDir, Path root) {
        List<VersionEntry> versions = new ArrayList<>();
        if (!Files.isDirectory(versionsDir)) {
            return versions;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versionsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String folder = dir.getFileName().toString();
                Path json = dir.resolve(folder + ".json");
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                try {
                    versions.add(read(root, dir, folder, json));
                } catch (Exception ex) {
                    VersionEntry broken = new VersionEntry(folder, dir, json);
                    broken.accent = Accents.random();
                    broken.warning = "版本 json 解析失败：" + ex.getMessage();
                    versions.add(broken);
                }
            }
        } catch (IOException ex) {
            return versions;
        }
        versions.sort(Comparator.comparingLong((VersionEntry entry) -> entry.releaseTime).reversed());
        return versions;
    }

    private static VersionEntry read(Path root, Path dir, String folder, Path jsonFile) throws IOException {
        Map<String, Object> json = Json.map(Json.parse(Files.readString(jsonFile)));

        VersionEntry entry = new VersionEntry(folder, dir, jsonFile);
        entry.displayName = folder;
        entry.type = Json.string(json, "type", "release");
        entry.releaseTime = parseTime(Json.string(json, "releaseTime"))
                .orElseGet(() -> lastModified(jsonFile));
        entry.javaMajor = readJavaMajor(json);
        entry.gameVersion = resolveBaseVersion(root, Json.string(json, "id", folder), 0);
        if (entry.javaMajor <= 0) {
            entry.javaMajor = javaMajorOf(root, entry.gameVersion);
        }
        if (entry.javaMajor <= 0) {
            entry.javaMajor = 8;
        }
        detectLoader(entry, json);
        entry.modsCount = countMods(root, dir);
        entry.sizeBytes = directorySize(dir);
        entry.accent = Accents.random();
        entry.memoryOverrideMb = null;
        entry.refreshMemory();
        return entry;
    }

    /* ============================ 细节 ============================ */

    private static int readJavaMajor(Map<String, Object> json) {
        Map<String, Object> javaVersion = Json.map(json.get("javaVersion"));
        int major = Json.integer(javaVersion, "majorVersion", 0);
        return major > 0 ? major : 0;
    }

    /** 继承版本自己没有 javaVersion 时，从底层原版的 json 里读。 */
    private static int javaMajorOf(Path root, String id) {
        Path json = MinecraftRoot.versions(root).resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            return 0;
        }
        try {
            return readJavaMajor(Json.map(Json.parse(Files.readString(json))));
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * 识别加载器。
     * <p>整合包的 json 往往只有 {@code inheritsFrom}（mainClass、库、加载器版本全在父版本里），
     * 所以要把整条继承链都看一遍，不能只看自己这一层。</p>
     */
    private static void detectLoader(VersionEntry entry, Map<String, Object> json) {
        List<Map<String, Object>> chain = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        chain.add(json);
        ids.add(entry.id);
        String parent = Json.string(json, "inheritsFrom");
        int depth = 0;
        while (parent != null && !parent.isBlank() && depth++ < 10) {
            ids.add(parent);
            Path parentJson = entry.dir.getParent().resolve(parent).resolve(parent + ".json");
            if (!Files.isRegularFile(parentJson)) {
                break;
            }
            Map<String, Object> map;
            try {
                map = Json.map(Json.parse(Files.readString(parentJson)));
            } catch (Exception error) {
                break;
            }
            chain.add(map);
            parent = Json.string(map, "inheritsFrom");
        }

        StringBuilder blob = new StringBuilder();
        for (int index = 0; index < chain.size(); index++) {
            blob.append(ids.get(index)).append('\n')
                    .append(Json.string(chain.get(index), "mainClass", "")).append('\n');
            for (Object raw : Json.list(chain.get(index).get("libraries"))) {
                blob.append(Json.string(Json.map(raw), "name", "")).append('\n');
            }
        }
        String lower = blob.toString().toLowerCase(Locale.ROOT);

        if (lower.contains("neoforge")) {
            entry.loader = "NeoForge";
            entry.loaderVersion = firstVersion(chain, ids, "neoforge", "neoforge-");
        } else if (lower.contains("fabric-loader") || lower.contains("net.fabricmc.loader")) {
            entry.loader = "Fabric";
            entry.loaderVersion = firstVersion(chain, ids, "fabric-loader", "fabric-loader-");
        } else if (lower.contains("quilt-loader")) {
            entry.loader = "Quilt";
            entry.loaderVersion = firstVersion(chain, ids, "quilt-loader", "quilt-loader-");
        } else if (lower.contains("net.minecraftforge") || lower.contains("forge-")) {
            entry.loader = "Forge";
            entry.loaderVersion = firstVersion(chain, ids, "forge", "forge-");
        } else if (lower.contains("optifine")) {
            entry.loader = "OptiFine";
            entry.loaderVersion = firstVersion(chain, ids, "optifine", "OptiFine_");
        } else {
            entry.loader = "原版";
            entry.loaderVersion = "";
        }
    }

    /** 链上任意一层里找到的库版本。 */
    private static String chainLibrary(List<Map<String, Object>> chain, String keyword) {
        for (Map<String, Object> layer : chain) {
            String found = libraryVersion(Json.list(layer.get("libraries")), keyword);
            if (!found.isBlank()) {
                return found;
            }
        }
        return "";
    }

    /** 库里没有再退一步：从版本 id 里取（1.20.1-forge-47.4.0 → 47.4.0）。 */
    private static String firstVersion(List<Map<String, Object>> chain, List<String> ids,
                                       String keyword, String idPrefix) {
        String found = chainLibrary(chain, keyword);
        if (!found.isBlank()) {
            return found;
        }
        for (String id : ids) {
            String lower = id.toLowerCase(Locale.ROOT);
            int at = lower.lastIndexOf(idPrefix.toLowerCase(Locale.ROOT));
            if (at >= 0 && id.length() > at + idPrefix.length()) {
                return id.substring(at + idPrefix.length());
            }
        }
        return "";
    }

    /** 从 maven 坐标里取加载器版本号，例如 net.fabricmc:fabric-loader:0.16.9。 */
    private static String libraryVersion(List<Object> libraries, String keyword) {
        for (Object raw : libraries) {
            String name = Json.string(Json.map(raw), "name", "");
            String[] parts = name.split(":");
            // 只认 artifactId 正好是加载器的库，别被 accesstransformers:8.0.4 这类带 forge 的坐标骗到
            if (parts.length < 3 || !parts[1].equalsIgnoreCase(keyword)) {
                continue;
            }
            String version = parts[parts.length - 1];
            if (version.startsWith("natives-")) {
                version = parts.length >= 4 ? parts[parts.length - 2] : version;
            }
            if (keyword.equalsIgnoreCase("forge") && version.contains("-")) {
                version = version.substring(version.lastIndexOf('-') + 1);
            }
            if (!version.isBlank()) {
                return version;
            }
        }
        return "";
    }

    /** 顺着 inheritsFrom 找到最底层的原版版本号。 */
    private static String resolveBaseVersion(Path root, String id, int depth) {
        if (depth > 10) {
            return id;
        }
        Path json = MinecraftRoot.versions(root).resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            return id;
        }
        try {
            Map<String, Object> map = Json.map(Json.parse(Files.readString(json)));
            String parent = Json.string(map, "inheritsFrom");
            return parent == null ? id : resolveBaseVersion(root, parent, depth + 1);
        } catch (Exception ex) {
            return id;
        }
    }

    private static int countMods(Path root, Path versionDir) {
        Path mods = versionDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            mods = root.resolve("mods");
        }
        if (!Files.isDirectory(mods)) {
            return 0;
        }
        // .jar.disabled 也算在内，不然禁用几个后数量会莫名变少
        try (DirectoryStream<Path> files = Files.newDirectoryStream(mods, "*.jar*")) {
            int count = 0;
            for (Path ignored : files) {
                count++;
            }
            return count;
        } catch (IOException ex) {
            return 0;
        }
    }

    private static long directorySize(Path dir) {
        long[] total = {0};
        int[] count = {0};
        try (var stream = Files.walk(dir, 4)) {
            stream.forEach(path -> {
                if (count[0] > 8000) {
                    return;
                }
                count[0]++;
                try {
                    if (Files.isRegularFile(path)) {
                        total[0] += Files.size(path);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ex) {
            return 0;
        }
        return total[0];
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }

    private static java.util.OptionalLong parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return java.util.OptionalLong.empty();
        }
        try {
            return java.util.OptionalLong.of(Instant.parse(iso).toEpochMilli());
        } catch (Exception ex) {
            return java.util.OptionalLong.empty();
        }
    }
}
