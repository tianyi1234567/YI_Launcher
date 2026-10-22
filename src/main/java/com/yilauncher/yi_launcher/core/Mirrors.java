package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * 下载镜像链：CurseForge 走 cfwidget，拿不到 / 下不动就换 MCIM（mcimirror.top）。
 * <p>规则：<b>每个源先试两次</b>（第二次算重连），两次都失败才换下一个源；某个源不响应也不断开时，
 * 靠 {@link Remote#withDeadline} 的硬超时把控制权拿回来。所有源都挂了才算这个文件失败。</p>
 */
public final class Mirrors {

    /** MCIM 主站：CF 与 Modrinth 的元数据 + 文件代理。 */
    public static final String MCIM = "https://mod.mcimirror.top";
    /** 国内常用的 maven 镜像（Forge / NeoForge 安装器与依赖）。 */
    public static final String BMCL_MAVEN = "https://bmclapi2.bangbang93.com/maven";
    /** Mojang 元数据镜像（版本 json）。 */
    public static final String BMCL_GAME = "https://bmclapi2.bangbang93.com";

    /** 单个源单次尝试的默认最长等待（10 分钟，够下大模组）。 */
    private static final long DOWNLOAD_DEADLINE = 600_000L;
    /** 小文件（资源索引之类）用的短超时。 */
    public static final long SMALL_DEADLINE = 60_000L;
    /** 查询元数据的硬超时。 */
    private static final long QUERY_DEADLINE = 12_000L;

    /** 一个下载源。 */
    public record Source(String label, String url) {
    }

    /** MCIM 返回的 CF 文件信息。 */
    public record CfFile(String fileName, String downloadUrl, long length) {
    }

    private Mirrors() {
    }

    /** 依次尝试每个源：每个源最多两次，成功就返回命中的源名。 */
    public static String download(Path target, List<Source> sources, LongConsumer onBytes) throws IOException {
        return download(target, sources, onBytes, DOWNLOAD_DEADLINE);
    }

    /** 指定单次尝试的硬超时（小文件用短的，别为了一个索引等十分钟）。 */
    public static String download(Path target, List<Source> sources, LongConsumer onBytes,
                                  long deadlineMs) throws IOException {
        IOException last = null;
        StringBuilder tried = new StringBuilder();
        for (Source source : sources) {
            if (source == null || source.url() == null || source.url().isBlank()) {
                continue;
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Remote.withDeadline(source.label(), deadlineMs, () -> {
                        Remote.download(source.url(), target, onBytes);
                        return Boolean.TRUE;
                    });
                    return source.label();
                } catch (IOException error) {
                    last = error;
                    tried.append(source.label()).append(attempt == 1 ? "(1)" : "(2)").append(' ');
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        throw new IOException("所有下载源都失败：" + tried.toString().trim()
                + (last == null ? "" : "（最后错误：" + last.getMessage() + "）"));
    }

    /** MCIM 的 CF 文件代理地址（fileId 拆成 {id/1000}/{id%1000}，和 forgecdn 一样）。 */
    public static String cfFileUrl(int fileId, String fileName) {
        if (fileId <= 0 || fileName == null || fileName.isBlank()) {
            return "";
        }
        return MCIM + "/files/" + (fileId / 1000) + "/"
                + String.format(Locale.ROOT, "%03d", fileId % 1000) + "/" + encodeSegment(fileName);
    }

    /** Modrinth 的 CDN 地址换成 MCIM 代理：保留 /data/ 之后的路径。 */
    public static String modrinthFileUrl(String cdnUrl) {
        if (cdnUrl == null || cdnUrl.isBlank()) {
            return "";
        }
        int index = cdnUrl.indexOf("/data/");
        return index < 0 ? "" : MCIM + cdnUrl.substring(index);
    }

    /** 用 MCIM 的 CurseForge 接口补文件名（cfwidget 没缓存到新文件、或者压根连不上时用）。 */
    public static Optional<CfFile> cfFile(int projectId, int fileId) {
        if (projectId <= 0 || fileId <= 0) {
            return Optional.empty();
        }
        try {
            String url = MCIM + "/curseforge/v1/mods/" + projectId + "/files/" + fileId;
            String text = Remote.withDeadline("MCIM", QUERY_DEADLINE, () -> Remote.getText(url));
            Map<String, Object> root = Json.map(Json.parse(text));
            Map<String, Object> data = Json.map(root.get("data"));
            String name = Json.string(data, "fileName", "");
            if (name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new CfFile(name,
                    Remote.absolute(Json.string(data, "downloadUrl", "")),
                    Json.integer(data, "fileLength", 0)));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    /** maven 地址换国内镜像（Forge / NeoForge 官方 maven 在国内经常龟速）。 */
    public static String mavenMirror(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int forge = url.indexOf("maven.minecraftforge.net/maven2/");
        if (forge >= 0) {
            return BMCL_MAVEN + "/" + url.substring(forge + "maven.minecraftforge.net/maven2/".length());
        }
        forge = url.indexOf("maven.minecraftforge.net/");
        if (forge >= 0) {
            return BMCL_MAVEN + "/" + url.substring(forge + "maven.minecraftforge.net/".length());
        }
        int neo = url.indexOf("maven.neoforged.net/releases/");
        if (neo >= 0) {
            return BMCL_MAVEN + "/" + url.substring(neo + "maven.neoforged.net/releases/".length());
        }
        int fabric = url.indexOf("maven.fabricmc.net/");
        if (fabric >= 0) {
            return BMCL_MAVEN + "/" + url.substring(fabric + "maven.fabricmc.net/".length());
        }
        return "";
    }

    /** 原始地址 + 镜像地址，组成一个源列表。 */
    public static List<Source> withMirror(String label, String url) {
        List<Source> sources = new ArrayList<>();
        if (url != null && !url.isBlank()) {
            sources.add(new Source(label, url));
        }
        String mirror = mavenMirror(url);
        if (!mirror.isBlank()) {
            sources.add(new Source(label + "·镜像", mirror));
        }
        return sources;
    }

    /** 带硬超时的 JSON 查询：卡住的源不会拖死调用方。 */
    public static String queryText(String what, String url) throws IOException {
        return Remote.withDeadline(what, QUERY_DEADLINE, () -> Remote.getText(url));
    }

    /** 路径分段百分号编码：'+' 必须变 %2B，空格要 %20（不是 '+'）。 */
    public static String encodeSegment(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20");
    }
}
