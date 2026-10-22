package com.yilauncher.yi_launcher.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 拼装启动参数并拉起游戏进程。
 * <p>覆盖 PCL / 官方启动器常见的 JSON 结构：inheritsFrom 继承、libraries 的 rules 过滤、
 * 新旧两种 arguments 写法、natives 解压、离线账户参数。</p>
 */
public final class GameLaunch {

    public record Result(Process process, List<String> command, JavaRuntimes.JavaRuntime java,
                         List<String> missing, Path logFile) {
    }

    /** 缺失、但 json 里给了下载地址的库。 */
    private record PendingLibrary(Path target, String url, String sha1, String name, boolean nativeLib) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private GameLaunch() {
    }

    public static Result launch(Path root, VersionEntry entry, int memoryMb, Consumer<String> status) throws Exception {
        Map<String, Object> resolved = resolveChain(root, entry.id);
        Path nativesDir = root.resolve("natives").resolve(entry.id);
        Files.createDirectories(nativesDir);

        List<String> classpath = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<PendingLibrary> pending = new ArrayList<>();
        collectLibraries(root, resolved, nativesDir, classpath, missing, pending);
        addGameJars(root, entry.id, classpath, missing, pending);

        if (System.getProperty("yi.dryrun") == null && !pending.isEmpty()) {
            downloadMissing(pending, classpath, nativesDir, missing, status);
        }

        String classpathText = String.join(File.pathSeparator, classpath);
        String assetIndex = assetIndexId(resolved);
        Map<String, String> vars = variables(root, entry, classpathText, nativesDir, assetIndex);

        String mainClass = Json.string(resolved, "mainClass", "net.minecraft.client.main.Main");

        List<String> jvm = new ArrayList<>();
        jvm.add("-Xmx" + memoryMb + "M");
        jvm.add("-Xms" + Math.min(1024, memoryMb) + "M");
        List<String> vanillaJvm = substituteArguments(argumentList(resolved, "jvm"), vars);
        if (vanillaJvm.isEmpty()) {
            jvm.add("-Djava.library.path=" + nativesDir);
            jvm.add("-cp");
            jvm.add(classpathText);
        } else {
            jvm.addAll(vanillaJvm);
        }

        List<String> gameArgs = substituteArguments(argumentList(resolved, "game"), vars);
        if (gameArgs.isEmpty()) {
            String legacy = Json.string(resolved, "minecraftArguments", "");
            for (String token : legacy.trim().split("\\s+")) {
                if (!token.isBlank()) {
                    gameArgs.add(substitute(token, vars));
                }
            }
        }
        applyWindowArgs(entry, gameArgs);

        JavaRuntimes.JavaRuntime java = entry.javaPath == null ? null : JavaRuntimes.probe(entry.javaPath);
        if (java == null) {
            java = JavaRuntimes.pick(root, entry.javaMajor);
        }
        if (java == null) {
            throw new IllegalStateException("没有找到可用的 Java（该版本需要 Java " + entry.javaMajor + "）");
        }
        if (java.major() < entry.javaMajor) {
            status.accept("警告：Java " + java.major() + " 低于该版本要求的 " + entry.javaMajor + "，可能无法启动");
        }

        List<String> command = new ArrayList<>();
        command.add(java.exe().toString());
        command.addAll(jvm);
        command.add(mainClass);
        command.addAll(gameArgs);

        Path logs = root.resolve("logs");
        Files.createDirectories(logs);
        Path logFile = logs.resolve("yi-launcher-" + entry.id + "-" + LocalDateTime.now().format(LOG_STAMP) + ".log");

        entry.setLaunchArgs(List.copyOf(command));
        entry.javaPath = java.exe();
        entry.lastLaunched = System.currentTimeMillis();

        if (System.getProperty("yi.dryrun") != null) {
            System.out.println("[干跑] " + String.join(" ", command));
            return new Result(null, command, java, missing, logFile);
        }

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        status.accept("已启动：PID " + process.pid() + "，日志 " + logFile);
        return new Result(process, command, java, missing, logFile);
    }

    /* ============================ 继承合并 ============================ */

    private static Map<String, Object> resolveChain(Path root, String id) throws IOException {
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = id;
        while (cursor != null && seen.add(cursor)) {
            Path json = MinecraftRoot.versions(root).resolve(cursor).resolve(cursor + ".json");
            if (!Files.isRegularFile(json)) {
                break;
            }
            Map<String, Object> map = Json.map(Json.parse(Files.readString(json)));
            chain.add(map);
            cursor = Json.string(map, "inheritsFrom");
        }
        if (chain.isEmpty()) {
            throw new IOException("找不到版本 json：" + id);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            mergeInto(merged, chain.get(i));
        }
        return merged;
    }

    private static void mergeInto(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("libraries".equals(key)) {
                List<Object> merged = new ArrayList<>(Json.list(entry.getValue()));
                Set<String> names = new LinkedHashSet<>();
                for (Object lib : merged) {
                    names.add(Json.string(Json.map(lib), "name", ""));
                }
                for (Object base : Json.list(target.get("libraries"))) {
                    String name = Json.string(Json.map(base), "name", "");
                    if (names.add(name)) {
                        merged.add(base);
                    }
                }
                target.put(key, merged);
            } else if ("arguments".equals(key)) {
                Map<String, Object> mergedArgs = new LinkedHashMap<>(Json.map(target.get("arguments")));
                for (Map.Entry<String, Object> arg : Json.map(entry.getValue()).entrySet()) {
                    List<Object> all = new ArrayList<>(Json.list(mergedArgs.get(arg.getKey())));
                    all.addAll(Json.list(arg.getValue()));
                    mergedArgs.put(arg.getKey(), all);
                }
                target.put(key, mergedArgs);
            } else {
                target.put(key, entry.getValue());
            }
        }
    }

    /* ============================ 依赖 / 资源 ============================ */

    private static void collectLibraries(Path root, Map<String, Object> resolved, Path nativesDir,
                                         List<String> classpath, List<String> missing,
                                         List<PendingLibrary> pending) {
        Path libraryRoot = MinecraftRoot.libraries(root);
        for (Object raw : Json.list(resolved.get("libraries"))) {
            Map<String, Object> library = Json.map(raw);
            if (!rulesAllow(library.get("rules"))) {
                continue;
            }
            String name = Json.string(library, "name", "");
            if (name.isBlank()) {
                continue;
            }
            String classifier = classifierOf(name);
            String legacyNative = legacyNativeClassifier(library);
            boolean nativeOnly = legacyNative != null
                    || (classifier != null && classifier.startsWith("natives-"));
            if (nativeOnly && archMismatch(classifier != null ? classifier : legacyNative)) {
                continue; // arm64 / x86 的原生库在别的架构上没必要下载
            }

            Path artifact = legacyNative != null
                    ? classifierArtifact(libraryRoot, library, legacyNative)
                    : artifact(libraryRoot, library, name);
            if (artifact == null) {
                continue;
            }
            if (!Files.isRegularFile(artifact)) {
                String url = Json.string(Json.map(Json.map(library.get("downloads")).get("artifact")), "url");
                String sha1 = Json.string(Json.map(Json.map(library.get("downloads")).get("artifact")), "sha1");
                if (url != null && !url.isBlank()) {
                    pending.add(new PendingLibrary(artifact, url, sha1, name, nativeOnly));
                } else {
                    missing.add(name);
                }
                continue;
            }
            if (nativeOnly) {
                extractNatives(artifact, nativesDir);
                // LWJGL 3 也能直接从 classpath 里的 natives jar 自行解压，两路都保留更稳
                classpath.add(artifact.toString());
            } else {
                classpath.add(artifact.toString());
            }
        }
    }

    private static void addGameJars(Path root, String id, List<String> classpath, List<String> missing,
                                    List<PendingLibrary> pending) {
        List<String> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = id;
        while (cursor != null && seen.add(cursor)) {
            Path base = MinecraftRoot.versions(root).resolve(cursor);
            Path jar = base.resolve(cursor + ".jar");
            if (Files.isRegularFile(jar)) {
                jars.add(jar.toString());
            }
            Path json = base.resolve(cursor + ".json");
            if (!Files.isRegularFile(json)) {
                break;
            }
            try {
                cursor = Json.string(Json.map(Json.parse(Files.readString(json))), "inheritsFrom");
            } catch (Exception ex) {
                break;
            }
        }
        if (jars.isEmpty()) {
            // 客户端 jar 不在本地时，顺着继承链找第一个带 downloads.client 的版本（就是原版那层），
            // jar 必须放回它自己的版本目录：整合包/加载器自己的 json 里没有 client 下载信息
            String cursorJson = id;
            Set<String> visited = new HashSet<>();
            while (cursorJson != null && visited.add(cursorJson)) {
                Path jsonFile = MinecraftRoot.versions(root).resolve(cursorJson)
                        .resolve(cursorJson + ".json");
                if (!Files.isRegularFile(jsonFile)) {
                    break;
                }
                Map<String, Object> json;
                try {
                    json = Json.map(Json.parse(Files.readString(jsonFile)));
                } catch (Exception error) {
                    break;
                }
                Map<String, Object> client = Json.map(Json.map(json.get("downloads")).get("client"));
                String url = Json.string(client, "url");
                if (url != null && !url.isBlank()) {
                    Path target = MinecraftRoot.versions(root).resolve(cursorJson)
                            .resolve(cursorJson + ".jar");
                    pending.add(new PendingLibrary(target, url, Json.string(client, "sha1"),
                            cursorJson + ".jar", false));
                    classpath.add(0, target.toString());
                    return;
                }
                cursorJson = Json.string(json, "inheritsFrom");
            }
            missing.add(id + ".jar");
            return;
        }
        classpath.addAll(0, jars);
    }

    /** 下载缺失的库 / 客户端 jar，并在下载成功后补进 classpath 或解压 natives。 */
    private static void downloadMissing(List<PendingLibrary> pending, List<String> classpath,
                                        Path nativesDir, List<String> missing, Consumer<String> status) {
        int index = 0;
        for (PendingLibrary library : pending) {
            index++;
            status.accept("下载缺失文件 " + index + "/" + pending.size() + "：" + library.name());
            try {
                download(library);
                if (library.nativeLib()) {
                    extractNatives(library.target(), nativesDir);
                } else {
                    classpath.add(library.target().toString());
                }
            } catch (Exception ex) {
                missing.add(library.name());
            }
        }
    }

    private static void download(PendingLibrary library) throws Exception {
        Files.createDirectories(library.target().getParent());
        Path temp = library.target().resolveSibling(library.target().getFileName() + ".part");
        java.net.URLConnection connection = java.net.URI.create(library.url()).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "YI_Launcher/0.1.0");
        try (InputStream in = connection.getInputStream();
             java.io.OutputStream out = Files.newOutputStream(temp,
                     java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
        if (library.sha1() != null && !library.sha1().isBlank()) {
            String actual = sha1(temp);
            if (!actual.equalsIgnoreCase(library.sha1())) {
                Files.deleteIfExists(temp);
                throw new IOException("sha1 校验失败：" + library.name());
            }
        }
        Files.move(temp, library.target(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha1(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String classifierOf(String mavenName) {
        String[] parts = mavenName.split(":");
        return parts.length >= 4 ? parts[3] : null;
    }

    /** 判断 natives 分类器是否和当前 CPU 架构不符。 */
    private static boolean archMismatch(String classifier) {
        if (classifier == null) {
            return false;
        }
        String lower = classifier.toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        boolean x86 = !arch.contains("64") && !arm;
        if (lower.endsWith("-arm64")) {
            return !arm;
        }
        if (lower.endsWith("-x86")) {
            return !x86;
        }
        return false;
    }

    private static String legacyNativeClassifier(Map<String, Object> library) {
        Map<String, Object> natives = Json.map(library.get("natives"));
        if (natives.isEmpty()) {
            return null;
        }
        String key = osName().equals("windows") ? "windows" : osName().equals("osx") ? "osx" : "linux";
        String classifier = Json.string(natives, key);
        if (classifier == null) {
            return null;
        }
        return classifier.replace("${arch}", System.getProperty("os.arch").contains("64") ? "64" : "32");
    }

    private static Path artifact(Path libraryRoot, Map<String, Object> library, String name) {
        Map<String, Object> artifact = Json.map(Json.map(library.get("downloads")).get("artifact"));
        String path = Json.string(artifact, "path");
        if (path != null && !path.isBlank()) {
            return libraryRoot.resolve(path);
        }
        String[] parts = name.split(":");
        if (parts.length < 3) {
            return null;
        }
        String group = parts[0].replace('.', '/');
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? parts[3] : null;
        String file = artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        return libraryRoot.resolve(group).resolve(artifactId).resolve(version).resolve(file);
    }

    private static Path classifierArtifact(Path libraryRoot, Map<String, Object> library, String classifier) {
        Map<String, Object> classifiers = Json.map(Json.map(library.get("downloads")).get("classifiers"));
        Map<String, Object> entry = Json.map(classifiers.get(classifier));
        String path = Json.string(entry, "path");
        return path == null ? null : libraryRoot.resolve(path);
    }

    private static void extractNatives(Path jar, Path nativesDir) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".jnilib"))) {
                    continue;
                }
                Path target = nativesDir.resolve(Path.of(name).getFileName().toString());
                if (Files.exists(target)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
        } catch (IOException ignored) {
            // 解压失败不阻断启动，游戏自己会报错
        }
    }

    private static String assetIndexId(Map<String, Object> resolved) {
        Object raw = resolved.get("assetIndex");
        if (raw instanceof String text) {
            return text;
        }
        String id = Json.string(Json.map(raw), "id");
        return id == null || id.isBlank() ? Json.string(resolved, "assets", "legacy") : id;
    }

    /* ============================ 规则 / 参数 ============================ */

    private static boolean rulesAllow(Object rawRules) {
        List<Object> rules = Json.list(rawRules);
        if (rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Object raw : rules) {
            Map<String, Object> rule = Json.map(raw);
            if (!ruleMatches(rule)) {
                continue;
            }
            allowed = "allow".equals(Json.string(rule, "action", "allow"));
        }
        return allowed;
    }

    private static boolean ruleMatches(Map<String, Object> rule) {
        Map<String, Object> os = Json.map(rule.get("os"));
        if (!os.isEmpty()) {
            String name = Json.string(os, "name", "");
            if (!name.isEmpty() && !name.equals(osName())) {
                return false;
            }
            String arch = Json.string(os, "arch", "");
            if (!arch.isEmpty()) {
                String current = System.getProperty("os.arch", "");
                boolean is64 = current.contains("64") || current.contains("aarch64");
                if (arch.equals("x86") && is64) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, Object> feature : Json.map(rule.get("features")).entrySet()) {
            if (Boolean.TRUE.equals(feature.getValue())) {
                return false; // 启动器没有开启 demo / 自定义分辨率等特性
            }
        }
        return true;
    }

    private static List<Object> argumentList(Map<String, Object> resolved, String key) {
        return Json.list(Json.map(resolved.get("arguments")).get(key));
    }

    private static List<String> substituteArguments(List<Object> arguments, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Object raw : arguments) {
            if (raw instanceof String text) {
                out.add(substitute(text, vars));
                continue;
            }
            Map<String, Object> entry = Json.map(raw);
            if (!rulesAllow(entry.get("rules"))) {
                continue;
            }
            Object value = entry.get("value");
            if (value instanceof String text) {
                out.add(substitute(text, vars));
            } else {
                for (Object item : Json.list(value)) {
                    out.add(substitute(String.valueOf(item), vars));
                }
            }
        }
        return out;
    }

    private static String substitute(String text, Map<String, String> vars) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = vars.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 用版本设置里的窗口尺寸覆盖 json 里可能自带的 --width / --height，
     * 需要全屏时再补一个 --fullscreen（1.13 起支持）。
     */
    private static void applyWindowArgs(VersionEntry entry, List<String> gameArgs) {
        for (int i = 0; i < gameArgs.size(); ) {
            String token = gameArgs.get(i);
            if ("--width".equals(token) || "--height".equals(token)) {
                gameArgs.subList(i, Math.min(gameArgs.size(), i + 2)).clear();
                continue;
            }
            if ("--fullscreen".equals(token)) {
                gameArgs.remove(i);
                continue;
            }
            i++;
        }
        int width = entry.windowWidth > 0 ? entry.windowWidth : 1280;
        int height = entry.windowHeight > 0 ? entry.windowHeight : 720;
        gameArgs.add("--width");
        gameArgs.add(String.valueOf(width));
        gameArgs.add("--height");
        gameArgs.add(String.valueOf(height));
        if (entry.isFullscreen() && supportsFullscreenArg(entry)) {
            gameArgs.add("--fullscreen");
        }
    }

    /** --fullscreen 是 1.13 之后才有的参数；快照版本按新版处理。 */
    private static boolean supportsFullscreenArg(VersionEntry entry) {
        String version = entry.gameVersion == null ? "" : entry.gameVersion.trim();
        if (version.isEmpty() || !Character.isDigit(version.charAt(0)) || !version.contains(".")) {
            return true;
        }
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1].replaceAll("[^0-9].*$", ""));
            return major > 1 || (major == 1 && minor >= 13);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static Map<String, String> variables(Path root, VersionEntry entry, String classpath,
                                                 Path nativesDir, String assetIndex) {
        Map<String, String> vars = new HashMap<>();
        // 用当前登录的账户进服：正版 / LS 站带令牌，离线就是本机按名字算的 UUID
        Account account = AccountStore.get().selectedOrGuest();
        String player = account.name == null || account.name.isBlank() ? "Steve" : account.name;
        String uuid = account.uuid == null || account.uuid.isBlank()
                ? AccountStore.offlineUuid(player) : account.uuid.replace("-", "");
        String token = account.accessToken == null || account.accessToken.isBlank()
                ? "0" : account.accessToken;
        vars.put("auth_player_name", player);
        vars.put("version_name", entry.id);
        vars.put("game_directory", root.toString());
        vars.put("assets_root", MinecraftRoot.assets(root).toString());
        vars.put("game_assets", root.resolve("virtual").resolve("legacy").toString());
        vars.put("assets_index_name", assetIndex);
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", token);
        vars.put("auth_session", token);
        vars.put("clientid", "");
        vars.put("auth_xuid", "");
        vars.put("user_type", account.kind == Account.Kind.MICROSOFT ? "msa" : "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_type", entry.type);
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", "YI_Launcher");
        vars.put("launcher_version", "0.1.0");
        vars.put("classpath", classpath);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", MinecraftRoot.libraries(root).toString());
        int windowWidth = entry.windowWidth > 0 ? entry.windowWidth : 1280;
        int windowHeight = entry.windowHeight > 0 ? entry.windowHeight : 720;
        vars.put("resolution_width", String.valueOf(windowWidth));
        vars.put("resolution_height", String.valueOf(windowHeight));
        vars.put("quickPlayPath", "");
        vars.put("quickPlaySingleplayer", "");
        vars.put("quickPlayMultiplayer", "");
        vars.put("quickPlayRealms", "");
        return vars;
    }

    private static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        return "linux";
    }
}
