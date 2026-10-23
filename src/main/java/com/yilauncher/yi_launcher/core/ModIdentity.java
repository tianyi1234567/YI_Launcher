package com.yilauncher.yi_launcher.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            stream.filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        // 禁用就是把 .jar 改名成 .jar.disabled，扫描时要一起列出来
                        return name.endsWith(".jar") || name.endsWith(".jar.disabled");
                    })
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
        // 先看缓存：切页面回来、刷新列表都不用再联网
        List<Integer> todo = new ArrayList<>();
        Map<Integer, String> keys = new LinkedHashMap<>();
        for (int index = 0; index < mods.size(); index++) {
            String key = cacheKey(mods.get(index), gameVersion);
            keys.put(index, key);
            Remote cached = CACHE.get(key);
            if (cached != null) {
                onOne.accept(index, cached);
            } else {
                todo.add(index);
            }
        }
        if (todo.isEmpty()) {
            return;
        }

        Set<Integer> reported = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        BiConsumer<Integer, Remote> store = (index, remote) -> {
            if (index == null || remote == null) {
                return;
            }
            reported.add(index);
            String key = keys.get(index);
            if (key != null) {
                CACHE.put(key, remote);
            }
            onOne.accept(index, remote);
        };

        // ① 有官方 Key 就先走指纹精确匹配（比按名字猜准，也更快）
        Set<Integer> identified = new LinkedHashSet<>();
        if (CurseForge.available()) {
            Map<Integer, Integer> byIndex = new LinkedHashMap<>();
            Map<Integer, Integer> printToIndex = new java.util.concurrent.ConcurrentHashMap<>();
            parallel(todo, index -> {
                int fingerprint = Murmur2.fingerprint(mods.get(index).jar());
                if (fingerprint != 0) {
                    printToIndex.put(fingerprint, index);
                }
            });
            if (!printToIndex.isEmpty()) {
                Map<Integer, Integer> matches =
                        CurseForge.fingerprints(new ArrayList<>(printToIndex.keySet()));
                for (Map.Entry<Integer, Integer> entry : matches.entrySet()) {
                    Integer index = printToIndex.get(entry.getKey());
                    if (index != null) {
                        byIndex.put(index, entry.getValue());
                    }
                }
            }
            identified.addAll(byIndex.keySet());
            if (!byIndex.isEmpty()) {
                Map<Integer, CurseForge.Mod> infos =
                        CurseForge.mods(new ArrayList<>(new LinkedHashSet<>(byIndex.values())));
                for (Map.Entry<Integer, Integer> entry : byIndex.entrySet()) {
                    CurseForge.Mod info = infos.get(entry.getValue());
                    if (info == null) {
                        continue;
                    }
                    store.accept(entry.getKey(), new Remote(PackInstaller.CURSEFORGE,
                            info.name(), info.logoUrl(), info.links(), ""));
                }
            }
        }

        List<Integer> pending = new ArrayList<>();
        for (int index : todo) {
            if (!identified.contains(index)) {
                pending.add(index);   // 官方指纹已经认出来的就不用再猜了
            }
        }

        // ② cfwidget 只能一个个按名字查，最慢，先把它挂到后台跑
        Map<Integer, Remote> cfResults = new java.util.concurrent.ConcurrentHashMap<>();
        Thread cfWorker = null;
        if (!pending.isEmpty()) {
            cfWorker = new Thread(() -> byCurseForgeParallel(mods, pending, cfResults::put), "yi-mod-cf");
            cfWorker.setDaemon(true);
            cfWorker.start();
        }

        // ③ Modrinth 按 sha1 批量认：两三个请求就能覆盖几百个模组，图标立刻出现
        if (!pending.isEmpty()) {
            byModrinth(mods, pending, store);
        }
        if (cfWorker != null) {
            try {
                cfWorker.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            // ④ 再补 cfwidget 的名字匹配：sha1 匹配更准，已经认出来的不覆盖
            for (Map.Entry<Integer, Remote> entry : cfResults.entrySet()) {
                if (!reported.contains(entry.getKey())) {
                    store.accept(entry.getKey(), entry.getValue());
                }
            }
        }

        // 仍然没认出来的：记一条「空结果」，下次直接跳过联网，界面也不会一直转圈
        for (int index : todo) {
            if (reported.contains(index)) {
                continue;
            }
            String key = keys.get(index);
            if (key != null) {
                CACHE.put(key, MISS);
            }
            onOne.accept(index, MISS);
        }
    }

    /** 认出一个模组的结果缓存；source 为空表示「试过了，没认出来」。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Remote> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Remote MISS = new Remote("", "", "", "", "");

    /** 缓存键带上大小和修改时间：换了同名的新 jar 会自动失效。 */
    private static String cacheKey(LocalMod mod, String gameVersion) {
        Path jar = mod.jar();
        String base = jar.toAbsolutePath().toString();
        try {
            base = base + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
        } catch (Exception ignored) {
            // 读不到就用路径
        }
        return base + "|" + (gameVersion == null ? "" : gameVersion);
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

    /** cfwidget 只能一个个查，但可以并发查：8 条线程，几百个模组也就几秒钟。 */
    private static void byCurseForgeParallel(List<LocalMod> mods, List<Integer> indexes,
                                             BiConsumer<Integer, Remote> store) {
        parallel(indexes, index -> {
            Remote remote = byCurseForge(mods.get(index));
            if (remote != null) {
                store.accept(index, remote);
            }
        });
    }

    /** 简单并发：把一批下标分给几条线程跑完，单个失败不影响其它。 */
    private static void parallel(List<Integer> indexes, java.util.function.IntConsumer body) {
        if (indexes == null || indexes.isEmpty()) {
            return;
        }
        int threads = Math.min(8, indexes.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads,
                task -> {
                    Thread thread = new Thread(task, "yi-mod-scan");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index : indexes) {
                futures.add(pool.submit(() -> body.accept(index)));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                    // 单个失败不影响别的模组
                }
            }
        } finally {
            pool.shutdown();
        }
    }


    /** Modrinth：一个请求批量按 sha1 换版本，再批量换项目信息（图标 / 标题）。 */
    private static void byModrinth(List<LocalMod> mods, List<Integer> indexes,
                                   BiConsumer<Integer, Remote> onOne) {
        // 283 个 jar 串行算 sha1 要十几二十秒，8 条线程一起算快得多
        Map<String, Integer> byHash = new java.util.concurrent.ConcurrentHashMap<>();
        parallel(indexes, index -> {
            String hash = sha1(mods.get(index).jar());
            if (!hash.isBlank()) {
                byHash.put(hash, index);
            }
        });
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
            String icon = project == null ? "" : iconPng(entry.getKey(), project.iconUrl());
            String page = project == null ? "" : "https://modrinth.com/mod/" + project.slug();
            String versionNumber = version == null ? "" : version.versionNumber();
            onOne.accept(entry.getValue(),
                    new Remote(PackInstaller.MODRINTH, title, icon, page, versionNumber));
        }
    }

    /**
     * Modrinth 只给 WebP，JavaFX 的 Image 解不了；同一张图有 PNG 版（约四成项目有），
     * 直接换成 PNG 地址，省掉一次 cfwidget 查询；没有的项目就空着，界面回退首字母。
     */
    private static String iconPng(String projectId, String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (!raw.toLowerCase(Locale.ROOT).contains(".webp")) {
            return raw;
        }
        return projectId == null || projectId.isBlank()
                ? "" : "https://cdn.modrinth.com/data/" + projectId + "/icon.png";
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
        String text = name;
        if (text.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            text = text.substring(0, text.length() - ".disabled".length());
        }
        int dot = text.lastIndexOf('.');
        return dot > 0 ? text.substring(0, dot) : text;
    }
}
