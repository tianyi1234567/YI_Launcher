/* ==========================================================================
 *  衣启动器 · core 层 ｜ LoaderInstaller.java —— 补加载器
 *  做什么：让整合包真的能启动——缺原版 json 就补，Fabric/Quilt 取官方档案，
 *          Forge/NeoForge 无头跑安装器；返回「该 inheritsFrom 哪个版本 id」。
 *  谁在用：PackInstaller（装完整合包后的最后一步）。
 *  入口：ensure()、installFromMeta()、latestLoader()、runInstaller()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 装完整合包还得能启动：这一步负责把「原版版本信息 + 模组加载器」补齐。
 * <ul>
 *   <li>原版：缺 {@code versions/<mc>/<mc>.json} 就从 Mojang（或 bmclapi 镜像）补；</li>
 *   <li>Fabric / Quilt：从官方 meta 取 loader 档案，补上每个库的下载地址后写成本地版本；</li>
 *   <li>Forge / NeoForge：下载官方安装器，无头跑 {@code --installClient}（它会解处理器、装库）。</li>
 * </ul>
 * <p>返回的是「整合包 json 该 inheritsFrom 哪个版本 id」。</p>
 */
public final class LoaderInstaller {

    /** 进度回调：一句说明 + 0..1 的比例。 */
    public interface Progress {
        void update(String note, double ratio);
    }

    private LoaderInstaller() {
    }

    /**
     * 确保加载器可用。
     *
     * @param root        游戏根目录（libraries / versions 的父目录）
     * @param versionsDir 版本目录（整合包自己也在这下面）
     * @return 应当作为 inheritsFrom 的版本 id
     */
    public static String ensure(Path root, Path versionsDir, String loader, String mcVersion,
                                String loaderVersion, Progress progress) throws IOException {
        String kind = normalize(loader);
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IOException("整合包没有写游戏版本，没法装加载器");
        }
        ensureBaseVersion(root, versionsDir, mcVersion, progress);
        ensureAssetsIndex(root, versionsDir, mcVersion, progress);
        // 客户端 jar 先备好：Forge 安装器的处理器要用它，启动时也省一次下载
        GameAssets.ensureClient(root, versionsDir, mcVersion,
                (note, ratio) -> note(progress, note, 0.05));
        String parent = switch (kind) {
            case "fabric" -> installFromMeta(versionsDir, mcVersion, loaderVersion,
                    "https://meta.fabricmc.net/v2/versions/loader/", "Fabric", progress);
            case "quilt" -> installFromMeta(versionsDir, mcVersion, loaderVersion,
                    "https://meta.quiltmc.org/v3/versions/loader/", "Quilt", progress);
            case "forge", "neoforge" -> installForge(root, versionsDir, kind, mcVersion, loaderVersion, progress);
            default -> mcVersion;
        };
        // 资源 objects 放最后：几百 MB，但缺了就只有缺贴图/音效的游戏
        GameAssets.ensureObjects(root, versionsDir, mcVersion,
                (note, ratio) -> note(progress, note, 0.95));
        return parent;
    }

    private static void note(Progress progress, String text, double ratio) {
        if (progress != null) {
            progress.update(text, ratio);
        }
    }

    /* ============================ 原版版本信息 ============================ */

    /** 原版 json 不在就补一份（启动链要顺着 inheritsFrom 找到它）。 */
    public static void ensureBaseVersion(Path root, Path versionsDir, String mcVersion,
                                         Progress progress) throws IOException {
        Path dir = versionsDir.resolve(mcVersion);
        Path json = dir.resolve(mcVersion + ".json");
        if (Files.isRegularFile(json)) {
            return;
        }
        if (progress != null) {
            progress.update("补原版 " + mcVersion + " 的版本信息", 0.02);
        }
        String manifest = firstOk(List.of(
                "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json",
                Mirrors.BMCL_GAME + "/mc/game/version_manifest_v2.json"));
        Map<String, Object> rootMap = Json.map(Json.parse(manifest));
        String url = "";
        for (Object raw : Json.list(rootMap.get("versions"))) {
            Map<String, Object> item = Json.map(raw);
            if (mcVersion.equals(Json.string(item, "id", ""))) {
                url = Json.string(item, "url", "");
                break;
            }
        }
        if (url.isBlank()) {
            throw new IOException("找不到原版 " + mcVersion + " 的版本信息");
        }
        String text = firstOk(List.of(url, Mirrors.BMCL_GAME + "/version/" + mcVersion + "/json"));
        Files.createDirectories(dir);
        Files.writeString(json, text, StandardCharsets.UTF_8);
    }

    /* ============================ Fabric / Quilt ============================ */


    /**
     * 资源索引：原版客户端启动时必须有 {@code assets/indexes/<id>.json}。
     * <p>只补索引，不拉几百 MB 的 objects——没有 objects 时游戏能进，只是缺贴图/音效。</p>
     */
    public static void ensureAssetsIndex(Path root, Path versionsDir, String mcVersion,
                                         Progress progress) throws IOException {
        Path baseJson = versionsDir.resolve(mcVersion).resolve(mcVersion + ".json");
        if (!Files.isRegularFile(baseJson)) {
            return;
        }
        try {
            Map<String, Object> json = Json.map(Json.parse(Files.readString(baseJson)));
            // 原版 json 里 assetIndex 在根上（downloads 里只有 client / server / mappings）
            Map<String, Object> index = Json.map(json.get("assetIndex"));
            if (index.isEmpty()) {
                index = Json.map(Json.map(json.get("downloads")).get("assetIndex"));
            }
            String id = Json.string(index, "id", "");
            String url = Json.string(index, "url", "");
            if (id.isBlank() || url.isBlank()) {
                return;
            }
            Path target = root.resolve("assets").resolve("indexes").resolve(id + ".json");
            if (Files.isRegularFile(target)) {
                return;
            }
            if (progress != null) {
                progress.update("补资源索引 " + id, 0.04);
            }
            Files.createDirectories(target.getParent());
            Mirrors.download(target, Mirrors.withMirror("资源索引", url), null, Mirrors.SMALL_DEADLINE);
        } catch (Exception ignored) {
            // 索引补不了不算致命：游戏那边会自己报缺资源
        }
    }

    /**
     * meta 档案本身没有下载地址，只有 maven 基址；这里把每个库补成
     * {@code downloads.artifact.url}，启动器就能自己把库补齐。
     */
    private static String installFromMeta(Path versionsDir, String mcVersion, String loaderVersion,
                                          String metaBase, String label, Progress progress) throws IOException {
        String version = loaderVersion;
        if (version == null || version.isBlank()) {
            version = latestLoader(metaBase, mcVersion);
        }
        if (progress != null) {
            progress.update("安装 " + label + " 加载器 " + version, 0.05);
        }
        String text = Remote.getText(metaBase + mcVersion + "/" + version + "/profile/json");
        Map<String, Object> profile = Json.map(Json.parse(text));
        String id = Json.string(profile, "id", "");
        if (id.isBlank()) {
            throw new IOException(label + " 档案里没有版本 id");
        }
        Path dir = versionsDir.resolve(id);
        Path json = dir.resolve(id + ".json");
        if (Files.isRegularFile(json)) {
            return id;
        }
        String mainClass = Json.string(profile, "mainClass", "");
        Map<String, Object> arguments = Json.map(profile.get("arguments"));

        StringBuilder out = new StringBuilder();
        out.append("{\n  \"id\": \"").append(id).append("\",\n");
        out.append("  \"inheritsFrom\": \"").append(mcVersion).append("\",\n");
        out.append("  \"type\": \"release\",\n");
        out.append("  \"time\": \"").append(java.time.Instant.now()).append("\",\n");
        out.append("  \"releaseTime\": \"").append(java.time.Instant.now()).append("\",\n");
        out.append("  \"mainClass\": \"").append(mainClass).append("\",\n");
        out.append("  \"yilauncherLoader\": \"").append(label).append(' ').append(version).append("\",\n");
        out.append("  \"libraries\": [\n");
        List<Object> libraries = Json.list(profile.get("libraries"));
        for (int index = 0; index < libraries.size(); index++) {
            Map<String, Object> library = Json.map(libraries.get(index));
            String name = Json.string(library, "name", "");
            String base = Json.string(library, "url", "");
            String artifact = base.isBlank() || name.isBlank() ? "" : joinUrl(base, mavenPath(name));
            out.append("    {\"name\": \"").append(name).append('"');
            if (!base.isBlank()) {
                out.append(", \"url\": \"").append(base).append('"');
            }
            if (!artifact.isBlank()) {
                out.append(", \"downloads\": {\"artifact\": {\"url\": \"").append(artifact).append("\"}}");
            }
            out.append('}').append(index + 1 < libraries.size() ? "," : "").append('\n');
        }
        out.append("  ]");
        List<Object> game = Json.list(arguments.get("game"));
        if (!game.isEmpty()) {
            out.append(",\n  \"arguments\": {\"game\": [");
            for (int index = 0; index < game.size(); index++) {
                out.append('"').append(String.valueOf(game.get(index)).replace("\"", "\\\"")).append('"');
                out.append(index + 1 < game.size() ? ", " : "");
            }
            out.append("]}");
        }
        out.append("\n}\n");
        Files.createDirectories(dir);
        Files.writeString(json, out.toString(), StandardCharsets.UTF_8);
        return id;
    }

    /** 没写加载器版本时取 meta 里最新的一个。 */
    private static String latestLoader(String metaBase, String mcVersion) throws IOException {
        List<Object> items = Json.list(Json.parse(Remote.getText(metaBase + mcVersion)));
        for (Object raw : items) {
            Map<String, Object> item = Json.map(raw);
            Map<String, Object> loader = Json.map(item.get("loader"));
            String version = Json.string(loader, "version", "");
            if (!version.isBlank()) {
                return version;
            }
        }
        throw new IOException("meta 里没有这个游戏的加载器版本");
    }

    /* ============================ Forge / NeoForge ============================ */

    /** 跑官方安装器：它会下载库、跑处理器，产出能直接启动的版本 json。 */
    private static String installForge(Path root, Path versionsDir, String kind, String mcVersion,
                                       String loaderVersion, Progress progress) throws IOException {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IOException("整合包没写 " + kind + " 版本，没法装加载器");
        }
        boolean neo = "neoforge".equals(kind);
        boolean neoLegacy = neo && mcVersion.startsWith("1.20.1");
        String installerUrl;
        String expectedId;
        if (neo && !neoLegacy) {
            installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                    + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
            expectedId = "neoforge-" + loaderVersion;
        } else {
            String owner = neo ? "https://maven.neoforged.net/releases/net/neoforged/forge"
                    : "https://maven.minecraftforge.net/net/minecraftforge/forge";
            String prefix = mcVersion + "-" + loaderVersion;
            installerUrl = owner + "/" + prefix + "/forge-" + prefix + "-installer.jar";
            expectedId = prefix;
        }

        // 已经装过就直接用
        if (Files.isRegularFile(versionsDir.resolve(expectedId).resolve(expectedId + ".json"))) {
            return expectedId;
        }

        if (progress != null) {
            progress.update("下载 " + (neo ? "NeoForge" : "Forge") + " 安装器 " + loaderVersion, 0.1);
        }
        Path work = root.resolve(".yi-launcher").resolve("installer");
        Files.createDirectories(work);
        Path jar = work.resolve((neo ? "neoforge-" : "forge-") + loaderVersion + "-installer.jar");
        List<Mirrors.Source> sources = new ArrayList<>();
        sources.add(new Mirrors.Source(neo ? "NeoForge" : "Forge", installerUrl));
        String mirror = Mirrors.mavenMirror(installerUrl);
        if (!mirror.isBlank()) {
            sources.add(new Mirrors.Source((neo ? "NeoForge" : "Forge") + "·镜像", mirror));
        }
        Mirrors.download(jar, sources, null);

        // 官方安装器要求根目录里有 launcher_profiles.json
        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.isRegularFile(profiles)) {
            Files.writeString(profiles, "{\"profiles\":{},\"settings\":{},\"version\":3}",
                    StandardCharsets.UTF_8);
        }

        if (progress != null) {
            progress.update("运行 " + (neo ? "NeoForge" : "Forge") + " 安装器（要几分钟）", 0.6);
        }
        int code = runInstaller(root, jar);
        String found = Files.isRegularFile(versionsDir.resolve(expectedId).resolve(expectedId + ".json"))
                ? expectedId : locateLoaderVersion(versionsDir, loaderVersion);
        if (found.isBlank()) {
            throw new IOException("加载器安装器结束（返回 " + code + "），但没找到它生成的版本");
        }
        if (progress != null) {
            progress.update("加载器就绪：" + found, 0.9);
        }
        return found;
    }

    /**
     * 跑安装器前先把它内部的下载地址换成国内镜像。
     * <p>Forge/NeoForge 安装器把库地址写死在自己包里（install_profile.json、version.json），
     * 直接跑就是龟速；这里复制一份、把 maven 主机名换掉再跑，速度差一个数量级。</p>
     */
    private static Path mirrorPatched(Path jar) {
        try {
            Path patched = jar.resolveSibling(jar.getFileName().toString().replace(".jar", "-mirror.jar"));
            if (Files.isRegularFile(patched) && Files.size(patched) == Files.size(jar)) {
                return patched;
            }
            try (var zip = new java.util.zip.ZipFile(jar.toFile());
                 var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(patched))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    byte[] data = zip.getInputStream(entry).readAllBytes();
                    if (!entry.isDirectory() && data.length < 4_000_000) {
                        String text = new String(data, StandardCharsets.UTF_8);
                        if (text.contains("maven.minecraftforge.net") || text.contains("maven.neoforged.net")) {
                            String replaced = text
                                    .replace("https://maven.minecraftforge.net/maven2/",
                                            "https://bmclapi2.bangbang93.com/maven/")
                                    .replace("https://maven.minecraftforge.net/",
                                            "https://bmclapi2.bangbang93.com/maven/")
                                    .replace("https://maven.neoforged.net/releases/",
                                            "https://bmclapi2.bangbang93.com/maven/")
                                    .replace("http://maven.minecraftforge.net/",
                                            "https://bmclapi2.bangbang93.com/maven/")
                                    .replace("http://files.minecraftforge.net/maven/",
                                            "https://bmclapi2.bangbang93.com/maven/");
                            if (!replaced.equals(text)) {
                                data = replaced.getBytes(StandardCharsets.UTF_8);
                            }
                        }
                    }
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    out.write(data);
                    out.closeEntry();
                }
            }
            return patched;
        } catch (Exception error) {
            // 改不了就用原包，慢总比装不上好
            return jar;
        }
    }

    private static int runInstaller(Path root, Path jar) throws IOException {
        // bmclapi 只镜像安装器本身、不提供 forge maven 构件（实测 404），整包改写地址会让安装器直接失败，
        // 所以这里保持官方地址；国内镜像留给启动时的库下载兜底（GameLaunch）。
        Path runnable = jar;
        Path java = installerJava(root);
        List<String> command = List.of(java.toString(), "-jar", runnable.toString(),
                "--installClient", root.toAbsolutePath().toString());
        Path log = root.resolve(".yi-launcher").resolve("installer")
                .resolve(runnable.getFileName().toString().replaceAll("\\.jar$", ".log"));
        try {
            Files.createDirectories(log.getParent());
        } catch (IOException ignored) {
            // 日志目录建不出来也无所谓
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        Process process = builder.start();
        // 输出必须读掉，否则安装器会卡在写满的管道上
        Thread drain = new Thread(() -> {
            try (var in = process.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (Exception ignored) {
                // 安装器日志不重要
            }
        }, "yi-loader-installer-log");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(20, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("加载器安装超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("加载器安装被打断", error);
        }
        return process.exitValue();
    }

    /**
     * 安装器要用哪个 Java：Forge/NeoForge 的安装器在 Java 25 上会直接退出，
     * 所以优先挑 17 / 21（启动器里已经探测到的运行时），实在没有才用当前 JVM。
     */
    private static Path installerJava(Path root) {
        try {
            Path best = null;
            int bestScore = -1;
            for (JavaRuntimes.JavaRuntime runtime : JavaRuntimes.all(root)) {
                int major = runtime.major();
                if (major < 8 || major > 21) {
                    continue;
                }
                int score = major == 17 ? 100 : major == 21 ? 90 : major == 8 ? 60 : major;
                if (score > bestScore) {
                    bestScore = score;
                    best = runtime.exe();
                }
            }
            if (best != null) {
                return best;
            }
        } catch (Exception ignored) {
            // 探测失败就用当前运行时
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return Files.isRegularFile(java) ? java : Path.of("java");
    }


    /** 安装器生成的目录名可能和猜的不一样，扫一遍找带加载器版本的。 */
    private static String locateLoaderVersion(Path versionsDir, String loaderVersion) {
        if (!Files.isDirectory(versionsDir)) {
            return "";
        }
        try (var stream = Files.list(versionsDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(dir -> {
                        String name = dir.getFileName().toString();
                        return name.contains(loaderVersion)
                                && Files.isRegularFile(dir.resolve(name + ".json"));
                    })
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .reduce((first, second) -> second)
                    .orElse("");
        } catch (Exception error) {
            return "";
        }
    }

    /* ============================ 小工具 ============================ */

    private static String normalize(String loader) {
        String lower = loader == null ? "" : loader.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) {
            return "neoforge";
        }
        if (lower.contains("forge")) {
            return "forge";
        }
        if (lower.startsWith("fabric")) {
            return "fabric";
        }
        if (lower.startsWith("quilt")) {
            return "quilt";
        }
        return "vanilla";
    }

    /** maven 坐标转路径：group:artifact:version[:classifier][@ext]。 */
    static String mavenPath(String name) {
        String extension = "jar";
        String coordinate = name;
        int at = coordinate.indexOf('@');
        if (at > 0) {
            extension = coordinate.substring(at + 1);
            coordinate = coordinate.substring(0, at);
        }
        String[] parts = coordinate.split(":");
        if (parts.length < 3) {
            return "";
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + extension;
    }

    private static String joinUrl(String base, String path) {
        if (path.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base + path : base + "/" + path;
    }

    private static String firstOk(List<String> urls) throws IOException {
        IOException last = null;
        for (String url : urls) {
            try {
                // 每个地址都套硬超时：镜像卡住要能立刻换下一个
                return Remote.withDeadline("元数据", 20_000, () -> Remote.getText(url));
            } catch (IOException error) {
                last = error;
            }
        }
        throw last == null ? new IOException("没有可用的地址") : last;
    }
}
