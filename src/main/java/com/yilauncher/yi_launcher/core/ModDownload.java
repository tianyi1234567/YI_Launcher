package com.yilauncher.yi_launcher.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * 从 CurseForge / Modrinth 找某个项目「适合这个游戏版本」的文件并下载到 mods 目录。
 * <p>CurseForge 先按 gameVersion 过滤文件，优先正式版；downloadUrl 为空时退回
 * edge.forgecdn.net（官方 CDN 直链，跟整合包下载用的是同一套兜底）。</p>
 */
public final class ModDownload {

    /** 找到的可下载文件。 */
    public record File(String url, String fileName, long size) {
    }

    private ModDownload() {
    }

    /** 解析下载地址。 */
    public static File resolve(ModSearch.Hit hit, String gameVersion) throws Exception {
        if (PackInstaller.MODRINTH.equals(hit.source())) {
            return modrinth(hit.slug().isBlank() ? hit.id() : hit.slug(), gameVersion);
        }
        return curseForge(hit.id(), gameVersion);
    }

    /* ============================ CurseForge ============================ */

    private static File curseForge(String modId, String gameVersion) throws Exception {
        String url = "https://api.curseforge.com/v1/mods/" + modId + "/files?pageSize=50"
                + (gameVersion == null || gameVersion.isBlank() ? "" : "&gameVersion=" + gameVersion);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", CurseForge.key());
        Map<String, Object> root = Json.map(Json.parse(Remote.getTextWithHeaders(url, headers)));
        Map<String, Object> best = null;
        for (Object raw : Json.list(root.get("data"))) {
            Map<String, Object> file = Json.map(raw);
            if (best == null) {
                best = file;
            }
            if (Json.integer(file, "releaseType", 0) == 1) {
                best = file;
                break;
            }
        }
        if (best == null) {
            throw new java.io.IOException("CurseForge 上没有找到适合这个版本的文件");
        }
        String fileName = Json.string(best, "fileName", "");
        String download = Json.string(best, "downloadUrl", "");
        long size = (long) Json.integer(best, "fileLength", 0);
        int fileId = Json.integer(best, "id", 0);
        if (download == null || download.isBlank()) {
            String edge = CfWidget.edgeUrl(fileId, fileName);
            if (!edge.isBlank()) {
                download = edge;
            } else {
                download = CfWidget.cdnUrl(fileId, fileName);
            }
        }
        if (download == null || download.isBlank()) {
            throw new java.io.IOException("这个文件没有可用的下载地址（可能作者关闭了第三方下载）");
        }
        return new File(download, fileName.isBlank() ? ("mod-" + modId + ".jar") : fileName, size);
    }

    /* ============================ Modrinth ============================ */

    private static File modrinth(String slug, String gameVersion) throws Exception {
        String versions = gameVersion == null || gameVersion.isBlank()
                ? "[]" : "[\"" + gameVersion + "\"]";
        String url = "https://api.modrinth.com/v2/project/" + slug + "/version?game_versions="
                + encode(versions) + "&loaders=" + encode("[\"forge\",\"neoforge\",\"fabric\",\"quilt\"]");
        List<Object> list = Json.list(Json.parse(Remote.getText(url)));
        if (list.isEmpty()) {
            throw new java.io.IOException("Modrinth 上没有找到适合这个版本的文件");
        }
        Map<String, Object> version = Json.map(list.get(0));
        List<Object> files = Json.list(version.get("files"));
        if (files.isEmpty()) {
            throw new java.io.IOException("这个 Modrinth 项目没有可用文件");
        }
        Map<String, Object> file = Json.map(files.get(0));
        String download = Json.string(file, "url", "");
        String name = Json.string(file, "filename", "");
        long size = (long) Json.integer(file, "size", 0);
        if (download == null || download.isBlank()) {
            throw new java.io.IOException("Modrinth 文件没有下载地址");
        }
        return new File(download, name.isBlank() ? (slug + ".jar") : name, size);
    }

    /* ============================ 下载 ============================ */

    /** 下载到指定目录，返回落地的文件。 */
    public static Path download(ModSearch.Hit hit, String gameVersion, Path dir, LongConsumer onBytes)
            throws Exception {
        File file = resolve(hit, gameVersion);
        Files.createDirectories(dir);
        Path target = dir.resolve(safeName(file.fileName()));
        Remote.download(file.url(), target, onBytes);
        return target;
    }

    /** 目录：隔离版本用自己的 mods，否则用根目录 mods。 */
    public static Path targetDir(VersionEntry entry) {
        if (entry == null) {
            return LauncherEngine.get().root().resolve("mods");
        }
        return entry.isolation ? entry.dir.resolve("mods") : entry.root(LauncherEngine.get().root()).resolve("mods");
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? "download.jar" : cleaned;
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
