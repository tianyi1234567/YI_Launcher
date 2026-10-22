package com.yilauncher.yi_launcher.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组识别：先读 jar 里的本地元数据，再联网认「这是哪个项目」。
 * <p>顺序按需求来：<b>先 CurseForge</b>（用 modid / 名字猜 slug 查 cfwidget），
 * 认不出来才走 Modrinth（按 jar 的 sha1 精确查，一次请求批量搞定）。
 * 两边都不认识的，就只显示本地信息。</p>
 */
public final class ModIdentity {

    /** 本地读出来的模组信息。 */
    public record LocalMod(Path jar, String fileName, String modId, String name,
                           String version, String loader) {
    }

    /** 联网识别后的补充信息。 */
    public record Remote(String source, String title, String iconUrl, String pageUrl, String version) {
    }

    private static final Pattern TOML_FIELD =
            Pattern.compile("(?m)^\\s*(modId|displayName|version)\\s*=\\s*\"([^\"]*)\"");

    private ModIdentity() {
    }

    /** 扫一个 mods 目录，读出每个 jar 的本地元数据。 */
    public static List<LocalMod> scan(Path modsDir) {
        List<LocalMod> result = new ArrayList<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return result;
        }
        try (var stream = Files.list(modsDir)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jar -> result.add(local(jar)));
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 读单个 jar 的元数据；读不出就退回文件名。 */
    public static LocalMod local(Path jar) {
        String fileName = jar.getFileName().toString();
        String name = stripExt(fileName);
        String version = "";
        String modId = "";
        String loader = "未知";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            ZipEntry quilt = zip.getEntry("quilt.mod.json");
            ZipEntry neo = zip.getEntry("META-INF/neoforge.mods.toml");
            ZipEntry forge = zip.getEntry("META-INF/mods.toml");
            ZipEntry legacy = zip.getEntry("mcmod.info");
            if (fabric != null) {
                loader = "Fabric";
                Map<String, Object> root = Json.map(Json.parse(read(zip, fabric)));
                modId = Json.string(root, "id", "");
                name = firstNonBlank(Json.string(root, "name", ""), name);
                version = Json.string(root, "version", "");
            } else if (quilt != null) {
                loader = "Quilt";
                Map<String, Object> root = Json.map(Json.parse(read(zip, quilt)));
                Map<String, Object> quiltLoader = Json.map(root.get("quilt_loader"));
                Map<String, Object> metadata = Json.map(root.get("metadata"));
                modId = Json.string(quiltLoader, "id", "");
                name = firstNonBlank(Json.string(metadata, "name", ""), name);
                version = firstNonBlank(Json.string(quiltLoader, "version", ""),
                        Json.string(metadata, "version", ""));
            } else if (neo != null || forge != null) {
                String text = read(zip, neo != null ? neo : forge);
                loader = neo != null ? "NeoForge" : "Forge";
                Matcher matcher = TOML_FIELD.matcher(text);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    String value = matcher.group(2);
                    if ("modId".equals(key) && modId.isBlank()) {
                        modId = value;
                    } else if ("displayName".equals(key) && name.equals(stripExt(fileName))) {
                        name = firstNonBlank(value, name);
                    } else if ("version".equals(key) && version.isBlank()) {
                        version = value;
                    }
                }
                version = version.replace("${file.jarVersion}", "").trim();
            } else if (legacy != null) {
                loader = "Forge";
                Object parsed = Json.parse(read(zip, legacy));
                List<Object> items = parsed instanceof List<?> ? Json.list(parsed)
                        : Json.list(Json.map(parsed).get("modList"));
                if (!items.isEmpty()) {
                    Map<String, Object> item = Json.map(items.get(0));
                    modId = Json.string(item, "modid", "");
                    name = firstNonBlank(Json.string(item, "name", ""), name);
                    version = Json.string(item, "version", "");
                }
            }
        } catch (Exception ignored) {
        }
        return new LocalMod(jar, fileName, modId, name, version, loader);
    }

    /**
     * 联网识别（阻塞，调用方自己放后台线程）。
     * <p>CF 逐个查（要按 modid 猜 slug，没法批量），认不出来的再统一走 Modrinth 哈希查询。</p>
     *
     * @param onOne 每识别出一个就回调一次，参数是该模组在列表里的下标
     */
    public static void enrich(List<LocalMod> mods, String gameVersion,
                              BiConsumer<Integer, Remote> onOne) {
        if (mods == null || mods.isEmpty()) {
            return;
        }
        List<Integer> pending = new ArrayList<>();
        for (int index = 0; index < mods.size(); index++) {
            LocalMod mod = mods.get(index);
            Remote remote = byCurseForge(mod);
            if (remote != null) {
                onOne.accept(index, remote);
            } else {
                pending.add(index);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        byModrinth(mods, pending, onOne);
    }

    /**
     * CurseForge：先用 modid 猜 slug（可信），再用名字猜。
     * <p>名字猜出来的必须跟项目标题对得上——否则「Random Junk」这种名字会撞到同名的无关项目。</p>
     */
    private static Remote byCurseForge(LocalMod mod) {
        String byId = mod.modId().isBlank() ? "" : slugify(mod.modId());
        if (!byId.isBlank()) {
            Optional<CfWidget.Project> project = CfWidget.bySlug(byId);
            if (project.isPresent()) {
                return remote(project.get());
            }
        }
        String byName = mod.name().isBlank() ? "" : slugify(mod.name());
        if (!byName.isBlank() && !byName.equals(byId)) {
            Optional<CfWidget.Project> project = CfWidget.bySlug(byName);
            if (project.isPresent() && sameTitle(project.get().title(), mod.name())) {
                return remote(project.get());
            }
        }
        return null;
    }

    /** 把 cfwidget 项目转成 Remote（版本取它最新文件对应的游戏版本）。 */
    private static Remote remote(CfWidget.Project project) {
        String version = project.files().isEmpty() ? "" : project.files().get(0).gameVersion();
        if (version.isBlank() && !project.files().isEmpty()) {
            version = versionFromName(project.files().get(0).name());
        }
        return new Remote(PackInstaller.CURSEFORGE, project.title(),
                project.thumbnail(), project.pageUrl(), version);
    }

    /** 标题是否对得上（去掉空格标点、忽略大小写后互相包含即可）。 */
    private static boolean sameTitle(String left, String right) {
        String a = normalizeTitle(left);
        String b = normalizeTitle(right);
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private static String normalizeTitle(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Modrinth：一个请求批量按 sha1 换版本，再批量换项目信息（图标 / 标题）。 */
    private static void byModrinth(List<LocalMod> mods, List<Integer> indexes,
                                   BiConsumer<Integer, Remote> onOne) {
        Map<String, Integer> byHash = new LinkedHashMap<>();
        for (int index : indexes) {
            String hash = sha1(mods.get(index).jar());
            if (!hash.isBlank()) {
                byHash.put(hash, index);
            }
        }
        if (byHash.isEmpty()) {
            return;
        }
        Map<String, ModrinthApi.Version> versions =
                ModrinthApi.versionsBySha1(new ArrayList<>(byHash.keySet()));
        if (versions.isEmpty()) {
            return;
        }
        List<String> projectIds = new ArrayList<>();
        Map<String, Integer> indexByProject = new LinkedHashMap<>();
        for (Map.Entry<String, ModrinthApi.Version> entry : versions.entrySet()) {
            Integer index = byHash.get(entry.getKey());
            String projectId = entry.getValue().projectId();
            if (index == null || projectId.isBlank()) {
                continue;
            }
            indexByProject.put(projectId, index);
            projectIds.add(projectId);
        }
        Map<String, ModrinthApi.Project> projects = ModrinthApi.projects(projectIds);
        for (Map.Entry<String, Integer> entry : indexByProject.entrySet()) {
            ModrinthApi.Project project = projects.get(entry.getKey());
            ModrinthApi.Version version = null;
            for (ModrinthApi.Version item : versions.values()) {
                if (entry.getKey().equals(item.projectId())) {
                    version = item;
                    break;
                }
            }
            String title = project == null ? "" : project.title();
            String icon = project == null ? "" : project.iconUrl();
            if (icon.toLowerCase(Locale.ROOT).contains(".webp")) {
                icon = cfIcon(title);
            }
            String page = project == null ? "" : "https://modrinth.com/mod/" + project.slug();
            String versionNumber = version == null ? "" : version.versionNumber();
            onOne.accept(entry.getValue(),
                    new Remote(PackInstaller.MODRINTH, title, icon, page, versionNumber));
        }
    }

    /** 用项目名去 CF 换一张 JavaFX 能解的图标（找不到就空着，界面回退到首字母）。 */
    private static String cfIcon(String title) {
        String slug = slugify(title);
        if (slug.isBlank()) {
            return "";
        }
        return CfWidget.bySlug(slug)
                .filter(project -> sameTitle(project.title(), title))
                .map(CfWidget.Project::thumbnail)
                .orElse("");
    }

    /** slug 规则：小写、空格和下划线换成连字符，去掉奇怪字符。 */
    static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String lowered = text.toLowerCase(Locale.ROOT).trim();
        String cleaned = lowered.replaceAll("[^a-z0-9_\\- ]", "");
        return cleaned.replaceAll("[_ ]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }

    private static String versionFromName(String fileName) {
        Matcher matcher = Pattern.compile("(\\d+\\.\\d+(\\.\\d+)?)").matcher(fileName);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String sha1(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            return "";
        }
    }

    private static String read(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
