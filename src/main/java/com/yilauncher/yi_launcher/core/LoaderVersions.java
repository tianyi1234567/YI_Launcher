package com.yilauncher.yi_launcher.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 各类模组加载器：某个游戏版本下能装哪些加载器版本。
 * <p>全部镜像优先：Fabric 走 meta（bmclapi 有镜像），Quilt 走官方 meta，
 * Forge / NeoForge 走 bmclapi 的版本接口和 maven 索引；
 * 拿不到就返回空列表，界面上允许手填版本号。</p>
 */
public final class LoaderVersions {

    /** 可选的加载器。id 与 {@link LoaderInstaller} 的 loader 字段一致。 */
    public record Loader(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static final Loader NONE = new Loader("", "不安装加载器");
    public static final Loader FORGE = new Loader("forge", "Forge");
    public static final Loader NEOFORGE = new Loader("neoforge", "NeoForge");
    public static final Loader FABRIC = new Loader("fabric", "Fabric");
    public static final Loader QUILT = new Loader("quilt", "Quilt");

    public static List<Loader> loaders() {
        return List.of(NONE, FORGE, NEOFORGE, FABRIC, QUILT);
    }

    private LoaderVersions() {
    }

    /** 某个加载器在某个游戏版本下的可选版本号（新的排前面）。 */
    public static List<String> list(Loader loader, String mcVersion) {
        if (loader == null || loader.id().isBlank() || mcVersion == null || mcVersion.isBlank()) {
            return List.of();
        }
        try {
            List<String> versions = switch (loader.id()) {
                case "fabric" -> fabric(mcVersion);
                case "quilt" -> quilt(mcVersion);
                case "forge" -> forge(mcVersion);
                case "neoforge" -> neoforge(mcVersion);
                default -> List.of();
            };
            // 接口给的顺序不一定是新的在前（bmclapi 的 Forge 列表就是乱的），统一按数字段排
            List<String> sorted = new ArrayList<>(versions);
            sorted.sort((left, right) -> compareVersion(right, left));
            return sorted;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 只比数字段：66.0.3 > 66.0.2 > 66.0.0。 */
    private static int compareVersion(String left, String right) {
        String[] a = left.split("[^0-9]+");
        String[] b = right.split("[^0-9]+");
        int length = Math.min(a.length, b.length);
        for (int index = 0; index < length; index++) {
            int x = segment(a[index]);
            int y = segment(b[index]);
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int segment(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /* ============================ Fabric / Quilt ============================ */

    private static List<String> fabric(String mcVersion) {
        for (String base : List.of("https://meta.fabricmc.net/v2/versions/loader/",
                Mirrors.BMCL_GAME + "/fabric-meta/v2/versions/loader/")) {
            try {
                String text = Mirrors.queryText("Fabric 版本", base + mcVersion);
                List<String> versions = loaderField(text);
                if (!versions.isEmpty()) {
                    return versions;
                }
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    private static List<String> quilt(String mcVersion) {
        try {
            String text = Mirrors.queryText("Quilt 版本",
                    "https://meta.quiltmc.org/v3/versions/loader/" + mcVersion);
            return loaderField(text);
        } catch (Exception error) {
            return List.of();
        }
    }

    /** {"loader":{"version":"0.16.9",…}} 这样的数组，取每个 loader.version。 */
    private static List<String> loaderField(String text) {
        List<String> versions = new ArrayList<>();
        for (Object raw : Json.list(Json.parse(text))) {
            String version = Json.string(Json.map(Json.map(raw).get("loader")), "version", "");
            if (!version.isBlank() && !versions.contains(version)) {
                versions.add(version);
            }
        }
        return versions;
    }

    /* ============================ Forge / NeoForge ============================ */

    private static List<String> forge(String mcVersion) {
        try {
            String text = Mirrors.queryText("Forge 版本", Mirrors.BMCL_GAME + "/forge/minecraft/" + mcVersion);
            List<String> versions = new ArrayList<>();
            for (Object raw : Json.list(Json.parse(text))) {
                String version = Json.string(Json.map(raw), "version", "");
                if (!version.isBlank() && !versions.contains(version)) {
                    versions.add(version);
                }
            }
            if (!versions.isEmpty()) {
                return versions;
            }
        } catch (Exception ignored) {
        }
        // 镜像接口挂了就直接读 maven 索引（"1.20.1-47.4.0" → "47.4.0"）
        return fromMaven(Mirrors.BMCL_MAVEN + "/net/minecraftforge/forge/maven-metadata.xml", mcVersion + "-");
    }

    private static List<String> neoforge(String mcVersion) {
        List<String> versions = fromMaven(
                Mirrors.BMCL_MAVEN + "/net/neoforged/neoforge/maven-metadata.xml", neoPrefix(mcVersion));
        if (!versions.isEmpty()) {
            return versions;
        }
        // 1.20.1 的 NeoForge 还在 forge 坐标下（1.20.1-47.1.x）
        return fromMaven(Mirrors.BMCL_MAVEN + "/net/neoforged/forge/maven-metadata.xml", mcVersion + "-");
    }

    /** 1.21.1 → 21.1.；1.20.4 → 20.4.；1.20.1 特殊（NeoForge 47.1.）。 */
    private static String neoPrefix(String mcVersion) {
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 3) {
            return "";
        }
        String minor = parts[1];
        String patch = parts[2];
        if ("1".equals(minor) && "20".equals(parts[0])) {
            return "47.1.";
        }
        return minor + "." + patch + ".";
    }

    /** 解析 maven-metadata.xml 里的 {@code <version>}，去掉 mc 前缀，反转成「新 → 旧」。 */
    private static List<String> fromMaven(String url, String prefix) {
        try {
            String text = Mirrors.queryText("加载器版本", url);
            Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(text);
            List<String> versions = new ArrayList<>();
            while (matcher.find()) {
                String version = matcher.group(1).trim();
                if (version.isEmpty() || !version.startsWith(prefix)) {
                    continue;
                }
                String stripped = version.substring(prefix.length());
                if (!stripped.isEmpty() && !versions.contains(stripped)) {
                    versions.add(stripped);
                }
            }
            Collections.reverse(versions);
            return versions;
        } catch (Exception error) {
            return List.of();
        }
    }
}
