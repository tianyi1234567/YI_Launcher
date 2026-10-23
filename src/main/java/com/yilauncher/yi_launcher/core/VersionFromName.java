package com.yilauncher.yi_launcher.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模组 jar 的文件名里取版本号。
 * <p>绝大多数模组都把版本写进文件名，例如
 * {@code jei-1.20.1-forge-15.74.0.228.jar} → 15.74.0.228、
 * {@code entityculling-forge-1.10.0-mc1.20.1.jar} → 1.10.0、
 * {@code create-1.20.1-0.5.1.f.jar} → 0.5.1.f、
 * {@code sodium-fabric-0.9.3-beta.1+mc26.3.jar} → 0.9.3-beta.1。
 * 实在找不到才返回空串，交给上层用别的办法。</p>
 */
public final class VersionFromName {

    /** 一段「纯版本号」：1.2、1.2.3、0.5.1.f、1.2.3.4 这种。 */
    private static final Pattern TOKEN_VERSION =
            Pattern.compile("^\\d+(?:\\.\\d+)+(?:\\.[A-Za-z]+\\d*)?$");
    /** 兜底：在整串里找 1.2 / 1.2.3 这样的片段。 */
    private static final Pattern LOOSE_VERSION =
            Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+)+(?:\\.[A-Za-z]+\\d*)?");
    /** 紧跟版本号的预发布后缀，拼回去更像原版（0.9.3-beta.1）。 */
    private static final Pattern PRERELEASE =
            Pattern.compile("(?i)^(beta|alpha|rc|pre|preview|dev|snapshot)[0-9A-Za-z.\\-]*$");
    /** 加载器名，不算版本。 */
    private static final List<String> LOADERS = List.of(
            "forge", "fabric", "neoforge", "quilt", "optifine", "liteloader", "mc", "minecraft");

    private VersionFromName() {
    }

    /**
     * 从文件名解析版本。
     *
     * @param fileName    jar 文件名
     * @param gameVersion 当前游戏版本（用来排除 {@code 1.20.1} 这种 MC 版本号），可为空
     * @return 版本号；解析不出来返回空串
     */
    public static String parse(String fileName, String gameVersion) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String base = fileName.trim();
        int dot = base.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        List<String> tokens = new ArrayList<>();
        for (String token : base.split("[-_+ ]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        String fromTokens = pickToken(tokens, gameVersion);
        if (!fromTokens.isBlank()) {
            return fromTokens;
        }
        return pickLoose(base, gameVersion);
    }

    /**
     * 按分隔符切开后逐个看：第一个「不是加载器、也不是 MC 版本」的纯版本片段。
     * <p>MC 版本（1.20.1）先记下来当备选：如果整个文件名里只有它像版本号，
     * 那就用它（总比「未知版本」强）。</p>
     */
    private static String pickToken(List<String> tokens, String gameVersion) {
        String minecraftLike = "";
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String lower = token.toLowerCase(Locale.ROOT);
            if (LOADERS.contains(lower)) {
                continue;
            }
            // mc1.20.1 / mc26.3：整段都是 MC 版本标记
            if (lower.startsWith("mc") && lower.length() > 2) {
                if (minecraftLike.isBlank()) {
                    minecraftLike = token.substring(2);
                }
                continue;
            }
            if (!TOKEN_VERSION.matcher(token).matches()) {
                continue;
            }
            if (isGameVersion(token, gameVersion)) {
                if (minecraftLike.isBlank()) {
                    minecraftLike = token;
                }
                continue;
            }
            // 后面紧跟 beta.1 / alpha.2 这类预发布标签，拼回去
            if (index + 1 < tokens.size() && PRERELEASE.matcher(tokens.get(index + 1)).matches()) {
                return token + "-" + tokens.get(index + 1);
            }
            return token;
        }
        return minecraftLike;
    }

    /** 兜底：在整串里找第一个像版本的片段（跳过 MC 版本，去掉前缀的 1.20.1-）。 */
    private static String pickLoose(String base, String gameVersion) {
        Matcher matcher = LOOSE_VERSION.matcher(base);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isGameVersion(candidate, gameVersion)) {
                // 1.20.1-0.5.1.f 这种，MC 版本后面接的才是模组版本
                int end = matcher.end();
                String rest = base.substring(end);
                if (rest.startsWith("-") || rest.startsWith("_")) {
                    String tail = rest.substring(1);
                    int next = tail.indexOf('-');
                    String piece = next > 0 ? tail.substring(0, next) : tail;
                    if (TOKEN_VERSION.matcher(piece).matches() && !isGameVersion(piece, gameVersion)) {
                        return piece;
                    }
                }
                continue;
            }
            return candidate;
        }
        return "";
    }

    private static boolean isGameVersion(String token, String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return false;
        }
        String left = token.toLowerCase(Locale.ROOT);
        String right = gameVersion.toLowerCase(Locale.ROOT);
        return left.equals(right)
                || left.startsWith(right + ".")
                || right.startsWith(left + ".")
                || left.endsWith("mc" + right);
    }
}
