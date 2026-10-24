package com.yilauncher.yi_launcher.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

/**
 * CurseForge 官方 API（要 Key）。
 * <p>有 Key 时它是首选下载源：{@code GET /v1/mods/{modId}/files/{fileId}} 直接给
 * <p>Key 已经内置在源码里（{@link #BUILTIN_KEY}），设置页不再暴露；只有调试时才用
 * {@code -Dyi.cfKey=...} 临时覆盖。镜像（cfwidget / MCIM）只在官方失败时兜底。</p>
 */
public final class CurseForge {

    private static final String BASE = "https://api.curseforge.com/v1";
    /** 官方接口有请求频率限制，命中 429 后先冷却一会儿，别把安装卡死。 */
    private static volatile long cooldownUntil;
    private static volatile int consecutiveFailures;

    private CurseForge() {
    }

    /** 一个文件的信息。 */
    public record CfFile(String fileName, String downloadUrl, long length, String displayName) {
    }

    /** 项目信息（模组管理用得上）。 */
    public record Mod(int id, String name, String slug, String logoUrl, String links) {
    }

    /**
     * 内置的 CurseForge 官方 API Key（直接写在源码里，设置页不暴露）。
     * <p>只有开发者临时调试才用 {@code -Dyi.cfKey=} 覆盖。</p>
     */
    private static final String BUILTIN_KEY =
            "$2a$10$1z5R/Emlk1ns5LqPuv4VlOENjmXzhHtRB2260ZXdJ3skt3v.hU7M2";

    /** 当前使用的 CurseForge Key（启动参数优先，其次内置）。 */
    public static String key() {
        String fromProperty = System.getProperty("yi.cfKey");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        return BUILTIN_KEY;
    }

    public static boolean available() {
        return !key().isBlank() && System.currentTimeMillis() >= cooldownUntil;
    }

    /** 文件信息：文件名 / 直连地址 / 体积。查不到或没 Key 返回空。 */
    public static Optional<CfFile> file(int modId, int fileId) {
        if (!available() || modId <= 0 || fileId <= 0) {
            return Optional.empty();
        }
        try {
            String url = BASE + "/mods/" + modId + "/files/" + fileId;
            Map<String, Object> root = Json.map(Json.parse(withKey(url)));
            Map<String, Object> data = Json.map(root.get("data"));
            String fileName = Json.string(data, "fileName", "");
            if (fileName.isBlank()) {
                return Optional.empty();
            }
            consecutiveFailures = 0;
            return Optional.of(new CfFile(fileName,
                    Remote.absolute(Json.string(data, "downloadUrl", "")),
                    Json.integer(data, "fileLength", 0),
                    Json.string(data, "displayName", "")));
        } catch (Exception error) {
            coolDown(error.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 批量查文件（官方接口一次最多 1000 个）。
     * <p>整合包有 249 个文件，逐个查要 30 秒才轮到第一个下载——批量查一次就够，
     * 装包时「0% 等半分钟」就是它解决的。</p>
     */
    public static java.util.Map<Integer, CfFile> files(java.util.List<Integer> fileIds) {
        java.util.Map<Integer, CfFile> result = new java.util.LinkedHashMap<>();
        if (!available() || fileIds == null || fileIds.isEmpty()) {
            return result;
        }
        try {
            StringBuilder body = new StringBuilder("{\"fileIds\":[");
            for (int index = 0; index < fileIds.size(); index++) {
                body.append(index == 0 ? "" : ",").append(fileIds.get(index));
            }
            body.append("]}");
            String text = Remote.withDeadline("CurseForge", 20_000,
                    () -> Remote.postJsonWithHeaders(BASE + "/mods/files", body.toString(),
                            java.util.Map.of("x-api-key", key())));
            Map<String, Object> root = Json.map(Json.parse(text));
            for (Object raw : Json.list(root.get("data"))) {
                Map<String, Object> data = Json.map(raw);
                int id = Json.integer(data, "id", 0);
                String fileName = Json.string(data, "fileName", "");
                if (id <= 0 || fileName.isBlank()) {
                    continue;
                }
                result.put(id, new CfFile(fileName,
                        Remote.absolute(Json.string(data, "downloadUrl", "")),
                        Json.integer(data, "fileLength", 0),
                        Json.string(data, "displayName", "")));
            }
            consecutiveFailures = 0;
        } catch (Exception error) {
            coolDown(error.getMessage());
        }
        return result;
    }
    /** 指纹精确匹配：指纹 → modId。 */
    public static java.util.Map<Integer, Integer> fingerprints(java.util.List<Integer> hashes) {
        java.util.Map<Integer, Integer> result = new java.util.LinkedHashMap<>();
        if (!available() || hashes == null || hashes.isEmpty()) {
            return result;
        }
        try {
            // CF 要无符号 32 位：负的哈希要转成 & 0xFFFFFFFF
            StringBuilder body = new StringBuilder("{\"fingerprints\":[");
            for (int index = 0; index < hashes.size(); index++) {
                body.append(index == 0 ? "" : ",").append(hashes.get(index) & 0xFFFFFFFFL);
            }
            body.append("]}");
            String text = Remote.withDeadline("CurseForge", 20_000,
                    () -> Remote.postJsonWithHeaders(BASE + "/fingerprints", body.toString(),
                            java.util.Map.of("x-api-key", key())));
            Map<String, Object> root = Json.map(Json.parse(text));
            Map<String, Object> data = Json.map(root.get("data"));
            for (Object raw : Json.list(data.get("exactMatches"))) {
                Map<String, Object> match = Json.map(raw);
                int modId = Json.integer(match, "id", 0);
                Map<String, Object> file = Json.map(match.get("file"));
                int fileId = Json.integer(file, "id", 0);
                String fileName = Json.string(file, "fileName", "");
                if (modId <= 0) {
                    continue;
                }
                // 回配方式：① 响应里带指纹 ② 用文件名（本地 jar 名和 CF 的 fileName 一致）
                long fingerprint = Json.integer(file, "fileFingerprint", 0) & 0xFFFFFFFFL;
                if (fingerprint != 0) {
                    result.put((int) fingerprint, modId);
                }
                if (!fileName.isBlank()) {
                    result.put(fileName.hashCode(), modId);
                }
            }
            consecutiveFailures = 0;
        } catch (Exception error) {
            coolDown(error.getMessage());
        }
        return result;
    }

    /** 批量取项目信息（名字 / 图标 / 链接），一次最多 1000 个。 */
    public static java.util.Map<Integer, Mod> mods(java.util.List<Integer> modIds) {
        java.util.Map<Integer, Mod> result = new java.util.LinkedHashMap<>();
        if (!available() || modIds == null || modIds.isEmpty()) {
            return result;
        }
        try {
            StringBuilder body = new StringBuilder("{\"modIds\":[");
            for (int index = 0; index < modIds.size(); index++) {
                body.append(index == 0 ? "" : ",").append(modIds.get(index));
            }
            body.append("]}");
            String text = Remote.withDeadline("CurseForge", 20_000,
                    () -> Remote.postJsonWithHeaders(BASE + "/mods", body.toString(),
                            java.util.Map.of("x-api-key", key())));
            Map<String, Object> root = Json.map(Json.parse(text));
            for (Object raw : Json.list(root.get("data"))) {
                Map<String, Object> data = Json.map(raw);
                int id = Json.integer(data, "id", 0);
                String name = Json.string(data, "name", "");
                if (id <= 0 || name.isBlank()) {
                    continue;
                }
                Map<String, Object> links = Json.map(data.get("links"));
                result.put(id, new Mod(id, name, Json.string(data, "slug", ""),
                        logo(data), Json.string(links, "websiteUrl", "")));
            }
            consecutiveFailures = 0;
        } catch (Exception error) {
            coolDown(error.getMessage());
        }
        return result;
    }

    /** 按文件名回配：指纹接口会回 fileName，本地 jar 名就是它的名字。 */
    public static int modIdByFileName(String fileName) {
        if (!available() || fileName == null || fileName.isBlank()) {
            return 0;
        }
        return 0;
    }

    /** 批量识别：本地 jar 文件名 → modId。 */
    public static java.util.Map<String, Integer> identifyByFingerprints(
            java.util.Map<String, java.nio.file.Path> jarByName) {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        if (!available() || jarByName.isEmpty()) {
            return result;
        }
        java.util.Map<String, Integer> byUnsigned = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, java.nio.file.Path> entry : jarByName.entrySet()) {
            int fingerprint = Murmur2.fingerprint(entry.getValue());
            if (fingerprint != 0) {
                byUnsigned.put(entry.getKey(), fingerprint);
            }
        }
        java.util.Map<Integer, Integer> matches = fingerprints(new ArrayList<>(byUnsigned.values()));
        for (java.util.Map.Entry<String, Integer> entry : byUnsigned.entrySet()) {
            Integer modId = matches.get(entry.getValue());
            if (modId != null) {
                result.put(entry.getKey(), modId);
            }
        }
        return result;
    }

    /** 指纹取 modId 的便捷入口：单个 jar。 */
    public static int modIdOf(java.nio.file.Path jar) {
        int fingerprint = Murmur2.fingerprint(jar);
        if (fingerprint == 0) {
            return 0;
        }
        java.util.Map<Integer, Integer> matches = fingerprints(java.util.List.of(fingerprint));
        Integer modId = matches.get(fingerprint);
        return modId == null ? 0 : modId;
    }
    /** 项目信息；拿不到返回空。 */
    public static Optional<Mod> mod(int modId) {
        if (!available() || modId <= 0) {
            return Optional.empty();
        }
        try {
            Map<String, Object> root = Json.map(Json.parse(withKey(BASE + "/mods/" + modId)));
            Map<String, Object> data = Json.map(root.get("data"));
            String name = Json.string(data, "name", "");
            if (name.isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> links = Json.map(data.get("links"));
            return Optional.of(new Mod(Json.integer(data, "id", modId), name,
                    Json.string(data, "slug", ""), logo(data),
                    Json.string(links, "websiteUrl", "")));
        } catch (Exception error) {
            coolDown(error.getMessage());
            return Optional.empty();
        }
    }

    private static String withKey(String url) throws Exception {
        // Remote 目前只支持统一 UA；这里用带 x-api-key 的请求，逻辑复用小函数
        return Http.get(url, key());
    }

    /**
     * 官方接口的 {@code logo} 是个对象：{@code {thumbnailUrl, url}}，不是字符串。
     * <p>以前按字符串取，结果「CurseForge 认出来的模组全都没有图标」。
     * 优先用 256px 缩略图（小、快），没有再退回原图。</p>
     */
    private static String logo(Map<String, Object> data) {
        Map<String, Object> logo = Json.map(data.get("logo"));
        String thumbnail = Json.string(logo, "thumbnailUrl", "");
        if (thumbnail.isBlank()) {
            thumbnail = Json.string(logo, "url", "");
        }
        return Remote.absolute(thumbnail);
    }

    /** 失败就冷却（429 / 网络问题都别再来回试）。 */
    private static void coolDown(String message) {
        if (++consecutiveFailures >= 2 || (message != null && message.contains("429"))) {
            cooldownUntil = System.currentTimeMillis() + 120_000L;
        }
    }

    /** 极简 HTTP：带 x-api-key 的 GET，走统一的硬超时。 */
    private static final class Http {
        static String get(String url, String apiKey) throws Exception {
            return Remote.withDeadline("CurseForge", 15_000,
                    () -> Remote.getTextWithHeaders(url, Map.of("x-api-key", apiKey)));
        }
    }
}
