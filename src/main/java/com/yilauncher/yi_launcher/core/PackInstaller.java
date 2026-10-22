/* ==========================================================================
 *  衣启动器 · core 层 ｜ PackInstaller.java —— 整合包 / 模组安装
 *  做什么：认出 .zip 是 CF 还是 Modrinth 整合包，解到「分组目录/包名/」（overrides 原样铺开、
 *          写一份继承版本 json），认出 .jar 是模组并装进当前版本。
 *  谁在用：DropInstall（拖文件）、ModsPage（刷新模组列表）；工具方法也给 PackDownloader 用。
 *  入口：inspect()、installPack()、installMod()、installedMods()、versionJson()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;

import java.util.Optional;
import java.util.zip.ZipFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * 整合包 / 模组的识别与安装。
 * <ul>
 *   <li>整合包：CurseForge（manifest.json）与 Modrinth（modrinth.index.json）；</li>
 *   <li>模组：.jar 里带 fabric.mod.json / mods.toml / mcmod.info 这类元数据。</li>
 * </ul>
 * 整合包会被解开到「分组目录/包名/」：overrides 原样铺开，并写一份
 * 继承原版版本号的 json，让版本列表能认出它。
 */
public final class PackInstaller {

    public static final String CURSEFORGE = "CurseForge";
    public static final String MODRINTH = "Modrinth";

    /** 解包进度：总进度 / 模组线程 / 加载器线程，取值 0..1。 */
    public interface Progress {
        void update(double overall, double mods, double loader, String note);
    }

    /**
     * 整合包里要联网下载的一个文件。
     * <p>CurseForge 只给 projectID + fileID，文件名要装的时候再向 cfwidget 换；
     * Modrinth 直接给 url / path / sha1 和体积。</p>
     */
    public record PackFile(int projectId, int fileId, String path, String url,
                           String sha1, long size, boolean required) {

        /** 落盘文件名：CF 要等解析出文件名才知道，这里只处理 Modrinth 那种带 path 的。 */
        public String fileName() {
            String value = path == null ? "" : path.replace('\\', '/').trim();
            if (value.isEmpty()) {
                return "";
            }
            int slash = value.lastIndexOf('/');
            return slash >= 0 ? value.substring(slash + 1) : value;
        }
    }

    /** 识别出来的整合包信息。 */
    public record Pack(String kind, String name, String version, String mcVersion,
                       String loader, String loaderVersion, int manifestMods, List<PackFile> files) {
        public Pack {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** 安装结果。 */
    public record Result(Path folder, Path jsonFile, int files, int mods,
                         int downloaded, int failed) {
    }

    private PackInstaller() {
    }

    /* ============================ 识别 ============================ */
    /** 是整合包就返回信息，不是返回 null。 */
    public static Pack inspect(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".zip")) {
            return null;
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry cf = zip.getEntry("manifest.json");
            if (cf != null) {
                Map<String, Object> root = Json.map(Json.parse(read(zip, cf)));
                Map<String, Object> minecraft = Json.map(root.get("minecraft"));
                Map<String, Object> modLoaders = Json.map(minecraft.get("modLoaders"));
                String loader = "";
                String loaderVersion = "";
                for (Object raw : Json.list(minecraft.get("modLoaders"))) {
                    Map<String, Object> item = Json.map(raw);
                    String id = Json.string(item, "id", "");
                    if (!id.isBlank()) {
                        int dash = id.indexOf('-');
                        loader = dash > 0 ? id.substring(0, dash) : id;
                        loaderVersion = dash > 0 ? id.substring(dash + 1) : "";
                        break;
                    }
                }
                List<PackFile> files = new ArrayList<>();
                for (Object raw : Json.list(root.get("files"))) {
                    Map<String, Object> item = Json.map(raw);
                    int projectId = Json.integer(item, "projectID", 0);
                    int fileId = Json.integer(item, "fileID", 0);
                    if (projectId <= 0 || fileId <= 0) {
                        continue;
                    }
                    // CF 清单只有 id：文件名等真正安装时再向 cfwidget 换
                    files.add(new PackFile(projectId, fileId, "",
                            "", "", 0, Json.bool(item, "required", true)));
                }
                return new Pack(CURSEFORGE,
                        Json.string(root, "name", stripExt(file)),
                        Json.string(root, "version", ""),
                        Json.string(minecraft, "version", ""),
                        prettyLoader(loader), loaderVersion,
                        files.size(), files);
            }
            ZipEntry mr = zip.getEntry("modrinth.index.json");
            if (mr != null) {
                Map<String, Object> root = Json.map(Json.parse(read(zip, mr)));
                Map<String, Object> deps = Json.map(root.get("dependencies"));
                String loader = "";
                String loaderVersion = "";
                for (String key : new String[]{"fabric-loader", "forge", "neoforge", "quilt-loader"}) {
                    String value = Json.string(deps, key, "");
                    if (!value.isBlank()) {
                        loader = key;
                        loaderVersion = value;
                        break;
                    }
                }
                List<PackFile> files = new ArrayList<>();
                for (Object raw : Json.list(root.get("files"))) {
                    Map<String, Object> item = Json.map(raw);
                    List<Object> urls = Json.list(item.get("downloads"));
                    String url = urls.isEmpty() ? "" : Remote.absolute(String.valueOf(urls.get(0)));
                    if (url.isBlank()) {
                        continue;
                    }
                    Map<String, Object> hashes = Json.map(item.get("hashes"));
                    files.add(new PackFile(0, 0,
                            Json.string(item, "path", ""),
                            url,
                            Json.string(hashes, "sha1", ""),
                            Json.integer(item, "fileSize", 0),
                            Json.bool(item, "required", true)));
                }
                return new Pack(MODRINTH,
                        Json.string(root, "name", stripExt(file)),
                        Json.string(root, "versionId", ""),
                        Json.string(deps, "minecraft", ""),
                        prettyLoader(loader), loaderVersion,
                        files.size(), files);
            }
            return null;
        } catch (Exception error) {
            return null;
        }
    }

    /** .jar 里有没有模组元数据。 */
    public static boolean isModJar(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        String[] markers = {
                "fabric.mod.json", "quilt.mod.json",
                "META-INF/mods.toml", "META-INF/neoforge.mods.toml",
                "mcmod.info", "META-INF/coremods.json",
        };
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                for (String marker : markers) {
                    if (entryName.equalsIgnoreCase(marker)) {
                        return true;
                    }
                }
            }
        } catch (Exception error) {
            return false;
        }
        return false;
    }

    /* ============================ 安装 ============================ */

    /** 把模组装进版本（隔离版进版本的 mods，共用版进 .minecraft/mods）。 */
    public static Path installMod(Path jar, VersionEntry version, Path launcherRoot) throws IOException {
        Path mods = version != null && version.isolation
                ? version.dir.resolve("mods")
                : launcherRoot.resolve("mods");
        Files.createDirectories(mods);
        Path target = mods.resolve(jar.getFileName().toString());
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** 解压整合包到分组目录下，写出版本 json，并统计里面的模组数量。 */
    public static Result installPack(Path zipFile, Pack pack, VersionGroup group, String name) throws IOException {
        return installPack(zipFile, pack, group, name, null);
    }

    /**
     * 安装整合包：两条线程并行。
     * <ul>
     *   <li>模组线程：清单里的文件按 CF / Modrinth 联网下载，再把 overrides/mods 里的 jar 拷出来；</li>
     *   <li>加载器线程：把其余 overrides（配置、资源包、加载器配置）铺进版本目录。</li>
     * </ul>
     * <p>进度通过 {@link Progress} 回传，取值 0..1。</p>
     */
    public static Result installPack(Path zipFile, Pack pack, VersionGroup group, String name,
                                     Progress progress) throws IOException {
        Path base = group.versionsDir;
        Path folder = base.resolve(name);
        Files.createDirectories(folder);

        List<ZipEntry> modEntries = new ArrayList<>();
        List<ZipEntry> otherEntries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String relative = relativeOf(entry.getName());
                if (relative == null || relative.isEmpty() || entry.isDirectory()) {
                    continue;
                }
                boolean isMod = relative.toLowerCase(Locale.ROOT).contains("mods/")
                        && relative.toLowerCase(Locale.ROOT).endsWith(".jar");
                if (isMod) {
                    modEntries.add(entry);
                } else {
                    otherEntries.add(entry);
                }
            }
        }

        PackDownloader mods = new PackDownloader(zipFile, folder, pack.files(), modEntries);
        Job others = new Job(zipFile, folder, otherEntries);
        Thread modThread = new Thread(mods, "yi-pack-mods");
        Thread loaderThread = new Thread(others, "yi-pack-loader");
        modThread.setDaemon(true);
        loaderThread.setDaemon(true);
        if (progress != null) {
            String hint = pack.files().isEmpty()
                    ? "开始解压" : "开始下载 " + pack.files().size() + " 个文件";
            progress.update(0, 0, 0, hint);
        }
        modThread.start();
        loaderThread.start();
        Thread watcher = null;
        if (progress != null) {
            watcher = new Thread(() -> {
                while (!(mods.finished() && others.finished())) {
                    double overall = (mods.progress() + others.progress()) / (mods.total() + others.total());
                    progress.update(Math.min(0.97, overall), mods.ratio(), others.ratio(), mods.note());
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
                progress.update(0.97, 1, 1, "写入版本信息");
            }, "yi-pack-progress");
            watcher.setDaemon(true);
            watcher.start();
        }
        try {
            modThread.join();
            loaderThread.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (watcher != null) {
            watcher.interrupt();
        }

        // 有文件没下来就不算装好：直接清掉这次下载的内容，让调用方弹「下载源网络不佳」
        if (mods.failed() > 0) {
            deleteTree(folder);
            throw new IOException("下载源网络不佳：" + mods.failed() + " 个文件没下下来（已删除本次下载的内容）");
        }

        // 光解包是启动不了的：这一步把原版版本信息 + 模组加载器补齐
        if (progress != null) {
            progress.update(0.98, 1, 1, "准备模组加载器");
        }
        String parent;
        try {
            parent = LoaderInstaller.ensure(group.root, group.versionsDir, pack.loader(),
                    pack.mcVersion(), pack.loaderVersion(), (note, ratio) -> {
                        if (progress != null) {
                            progress.update(0.98 + Math.min(0.99, ratio) * 0.01, 1, 1, note);
                        }
                    });
        } catch (IOException error) {
            // 加载器装不上，这个整合包留着也启动不了，一起清掉
            deleteTree(folder);
            throw new IOException("模组加载器安装失败：" + error.getMessage()
                    + "（已删除本次安装的内容）", error);
        }

        Path jsonFile = folder.resolve(name + ".json");
        Files.writeString(jsonFile, versionJson(name, pack, parent), StandardCharsets.UTF_8);
        if (progress != null) {
            progress.update(1, 1, 1, "完成");
        }
        return new Result(folder, jsonFile, mods.done() + others.done(), mods.done(),
                mods.downloaded(), mods.failed());
    }

    /** 递归删目录（失败不抛，清理用）。 */
    private static void deleteTree(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (var stream = Files.walk(folder)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 删不掉就算了，别把失败提示吞掉
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 模组线程：清单文件联网下载 + overrides/mods 里的 jar 解出来。
     * <p>249 个文件串行下载会让进度条长时间停在 0%，所以这里分两步：
     * 先用 4 条小线程把「文件名 / 下载地址 / 体积」解析出来（cfwidget 不健康就直接走 MCIM），

    /** 单文件 sha1（小写十六进制）。 */
    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 一条"搬运线程"：把自己那份条目解到目标目录。 */
    private static final class Job implements Runnable {
        private final Path zipFile;
        private final Path folder;
        private final List<ZipEntry> entries;
        private final int total;
        private volatile int done;

        Job(Path zipFile, Path folder, List<ZipEntry> entries) {
            this.zipFile = zipFile;
            this.folder = folder;
            this.entries = entries;
            this.total = Math.max(1, entries.size());
        }

        double ratio() {
            return Math.min(1, (double) done / total);
        }

        int done() {
            return done;
        }

        /** 已完成条目数（总进度用它加权）。 */
        double progress() {
            return Math.min(total, done);
        }

        int total() {
            return total;
        }

        boolean finished() {
            return done >= entries.size();
        }

        @Override
        public void run() {
            if (entries.isEmpty()) {
                return;
            }
            try (ZipFile zip = new ZipFile(zipFile.toFile())) {
                for (ZipEntry entry : entries) {
                    String relative = relativeOf(entry.getName());
                    if (relative == null || relative.isEmpty()) {
                        done++;
                        continue;
                    }
                    Path target = safeResolve(folder, relative);
                    if (target == null) {
                        done++;
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    done++;
                }
            } catch (Exception ignored) {
                // 单个条目失败不影响其它文件
            }
        }
    }

    /** 去掉 overrides/ 或 client-overrides/ 前缀；不是要铺开的文件返回 null。 */
    private static String relativeOf(String entryName) {
        String name = entryName == null ? "" : entryName.replace('\\', '/');
        if (name.startsWith("overrides/")) {
            return name.substring("overrides/".length());
        }
        if (name.startsWith("client-overrides/")) {
            return name.substring("client-overrides/".length());
        }
        return null;
    }

    /**
     * 生成整合包的版本 json。
     * <p>{@code parent} 是加载器（或原版）的版本 id——启动链顺着它拿到 mainClass、
     * libraries 和原版客户端 jar，整合包自己只补元数据。</p>
     */
    private static String versionJson(String name, Pack pack, String parent) {
        String inherits = parent == null || parent.isBlank() ? pack.mcVersion() : parent;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\": \"").append(escape(name)).append("\",\n");
        if (inherits != null && !inherits.isBlank()) {
            sb.append("  \"inheritsFrom\": \"").append(escape(inherits)).append("\",\n");
        }
        if (pack.mcVersion() != null && !pack.mcVersion().isBlank()
                && !pack.mcVersion().equals(inherits)) {
            sb.append("  \"yilauncherBase\": \"").append(escape(pack.mcVersion())).append("\",\n");
        }
        sb.append("  \"type\": \"release\",\n");
        sb.append("  \"releaseTime\": \"").append(Instant.now().toString()).append("\",\n");
        sb.append("  \"yilauncherPack\": {\n");
        sb.append("    \"kind\": \"").append(escape(pack.kind())).append("\",\n");
        sb.append("    \"name\": \"").append(escape(pack.name())).append("\",\n");
        sb.append("    \"version\": \"").append(escape(pack.version())).append("\",\n");
        sb.append("    \"loader\": \"").append(escape(pack.loader())).append("\",\n");
        sb.append("    \"loaderVersion\": \"").append(escape(pack.loaderVersion())).append("\"\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /* ============================ 小工具 ============================ */

    /** 当前版本已经装了多少个模组（只数 .jar）。 */
    public static List<Path> installedMods(VersionEntry version, Path launcherRoot) {
        List<Path> result = new ArrayList<>();
        Path mods = version != null && version.isolation
                ? version.dir.resolve("mods")
                : launcherRoot.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return result;
        }
        try (var stream = Files.list(mods)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException ignored) {
            // 读不了就当没有
        }
        return result;
    }

    /** 默认的整合包名：去掉扩展名，并把文件名里不能做文件夹名的字符换掉。 */
    public static String defaultName(Path zip) {
        String base = stripExt(zip);
        String cleaned = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "新整合包" : cleaned;
    }

    private static String stripExt(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String prettyLoader(String raw) {
        if (raw == null || raw.isBlank()) {
            return "原版";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fabric")) {
            return "Fabric";
        }
        if (lower.startsWith("quilt")) {
            return "Quilt";
        }
        if (lower.startsWith("neoforge")) {
            return "NeoForge";
        }
        if (lower.startsWith("forge")) {
            return "Forge";
        }
        return raw;
    }

    private static Path safeResolve(Path base, String relative) {
        Path target = base.resolve(relative).normalize();
        return target.startsWith(base.normalize()) ? target : null;
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
