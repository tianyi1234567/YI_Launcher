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

    private static void detectLoader(VersionEntry entry, Map<String, Object> json) {
        String mainClass = Json.string(json, "mainClass", "");
        StringBuilder libraryNames = new StringBuilder();
        for (Object raw : Json.list(json.get("libraries"))) {
            libraryNames.append(Json.string(Json.map(raw), "name", "")).append('\n');
        }
        String blob = (entry.id + "\n" + mainClass + "\n" + libraryNames).toLowerCase(Locale.ROOT);

        if (blob.contains("neoforge")) {
            entry.loader = "NeoForge";
            entry.loaderVersion = idVersion(entry.id, "neoforge-",
                    libraryVersion(Json.list(json.get("libraries")), "neoforge"));
        } else if (blob.contains("fabric-loader") || blob.contains("net.fabricmc.loader")) {
            entry.loader = "Fabric";
            entry.loaderVersion = libraryVersion(Json.list(json.get("libraries")), "fabric-loader");
        } else if (blob.contains("quilt-loader")) {
            entry.loader = "Quilt";
            entry.loaderVersion = libraryVersion(Json.list(json.get("libraries")), "quilt-loader");
        } else if (blob.contains("net.minecraftforge") || entry.id.toLowerCase(Locale.ROOT).contains("forge")) {
            entry.loader = "Forge";
            entry.loaderVersion = libraryVersion(Json.list(json.get("libraries")), "forge");
        } else if (blob.contains("optifine")) {
            entry.loader = "OptiFine";
            entry.loaderVersion = libraryVersion(Json.list(json.get("libraries")), "optifine");
        } else {
            entry.loader = "原版";
            entry.loaderVersion = "";
        }
    }

    /** 从 maven 坐标里取加载器版本号，例如 net.fabricmc:fabric-loader:0.16.9。 */
    private static String libraryVersion(List<Object> libraries, String keyword) {
        String firstMatch = null;
        for (Object raw : libraries) {
            String name = Json.string(Json.map(raw), "name", "");
            String[] parts = name.split(":");
            if (parts.length < 3 || !name.toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            String version = parts[parts.length - 1];
            if (version.startsWith("natives-")) {
                version = parts.length >= 4 ? parts[parts.length - 2] : version;
            }
            if (keyword.equals("forge") && version.contains("-")) {
                version = version.substring(version.lastIndexOf('-') + 1);
            }
            if (parts[1].equals(keyword)) {
                return version;
            }
            if (firstMatch == null) {
                firstMatch = version;
            }
        }
        return firstMatch == null ? "" : firstMatch;
    }

    /** 版本文件夹名里通常带着加载器版本，例如 neoforge-21.1.235。 */
    private static String idVersion(String id, String prefix, String fallback) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith(prefix) && id.length() > prefix.length()) {
            return id.substring(prefix.length());
        }
        return fallback;
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
        try (DirectoryStream<Path> files = Files.newDirectoryStream(mods, "*.jar")) {
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
