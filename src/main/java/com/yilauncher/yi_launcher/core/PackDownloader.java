package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包的「模组线程」：清单文件联网下载 + overrides/mods 里的 jar 解出来。
 * <p>几百个文件串行下载会让进度条长时间停在 0%，所以拆成两步：先用 4 条线程把
 * 文件名 / 下载地址 / 体积解析出来（cfwidget 不健康就直接走 MCIM），再用 4 条线程并行下载，
 * 进度按<b>字节</b>算。第一轮失败的文件会在最后统一再补一轮，真的补不回来才记失败。</p>
 */
final class PackDownloader implements Runnable {

    /** 体积未知的文件按这个数估，避免进度条提前冲到 100%。 */
    private static final long UNKNOWN_SIZE = 1_500_000L;

    private final Path zipFile;
    private final Path folder;
    private final List<PackInstaller.PackFile> files;
    private final List<ZipEntry> entries;
    private final int total;

    private volatile int done;
    private volatile int downloaded;
    private volatile int failed;
    private volatile String note = "正在解析模组地址";
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicLong bytesTotal = new AtomicLong();
    /** 失败的文件名（给安装失败提示用）。 */
    private final java.util.List<String> failedFiles =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 失败清单（已去重，最多 30 个）。 */
    java.util.List<String> failedFiles() {
        java.util.List<String> copy = new java.util.ArrayList<>(failedFiles);
        return copy.size() > 30 ? copy.subList(0, 30) : copy;
    }
    /** 实测更快的源：第一个文件下载成功后记下来，后面的文件优先走它。 */
    private static volatile String preferred = "";

    PackDownloader(Path zipFile, Path folder, List<PackInstaller.PackFile> files, List<ZipEntry> entries) {
        this.zipFile = zipFile;
        this.folder = folder;
        this.files = files == null ? List.of() : files;
        this.entries = entries == null ? List.of() : entries;
        this.total = this.files.size() + this.entries.size();
    }

    int total() {
        return Math.max(1, total);
    }

    int done() {
        return done;
    }

    double progress() {
        return Math.min(1, done) + 0.0;
    }

    double ratio() {
        long totalBytes = bytesTotal.get();
        if (totalBytes > 0) {
            return Math.min(1, bytesDone.get() / (double) totalBytes);
        }
        return Math.min(1, done / (double) total());
    }

    int downloaded() {
        return downloaded;
    }

    int failed() {
        return failed;
    }

    String note() {
        long totalBytes = bytesTotal.get();
        if (totalBytes > 0) {
            return String.format(Locale.ROOT, "正在下载模组 %d/%d · %.1f / %.1f MB",
                    Math.min(done, files.size()), files.size(),
                    bytesDone.get() / 1048576.0, totalBytes / 1048576.0);
        }
        return note;
    }

    boolean finished() {
        return done >= total;
    }

    @Override
    public void run() {
        if (total == 0) {
            return;
        }
        downloadAll();
        extractMods();
    }

    /* ============================ 下载 ============================ */

    private void downloadAll() {
        if (files.isEmpty()) {
            return;
        }
        List<List<Mirrors.Source>> plan = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            plan.add(List.of());
            names.add("");
            sizes.add(UNKNOWN_SIZE);
        }

        // ① 官方批量解析：249 个文件一次请求搞定，不用再等半分钟才开始下
        java.util.Map<Integer, CurseForge.CfFile> batch = java.util.Map.of();
        if (CurseForge.available()) {
            List<Integer> ids = new ArrayList<>();
            for (PackInstaller.PackFile file : files) {
                if (file.fileId() > 0 && !ids.contains(file.fileId())) {
                    ids.add(file.fileId());
                }
            }
            note = "正在解析模组地址";
            batch = CurseForge.files(ids);
            for (int index = 0; index < files.size(); index++) {
                PackInstaller.PackFile file = files.get(index);
                CurseForge.CfFile info = batch.get(file.fileId());
                if (info == null) {
                    continue;
                }
                String url = info.downloadUrl().isBlank()
                        ? CfWidget.cdnUrl(file.fileId(), info.fileName()) : info.downloadUrl();
                if (url.isBlank()) {
                    continue;
                }
                List<Mirrors.Source> officialSources = new ArrayList<>();
                officialSources.add(new Mirrors.Source("CurseForge", url));
                // 官方没给直链（作者关了第三方下载）：mediafilez 可能 403，edge 一般有
                if (info.downloadUrl().isBlank()) {
                    String edge = CfWidget.edgeUrl(file.fileId(), info.fileName());
                    if (!edge.isBlank()) {
                        officialSources.add(new Mirrors.Source("CurseForge·edge", edge));
                    }
                    officialSources.add(new Mirrors.Source("MCIM",
                            Mirrors.cfFileUrl(file.fileId(), info.fileName())));
                }
                long size = info.length() > 0 ? info.length() : UNKNOWN_SIZE;
                names.set(index, info.fileName());
                sizes.set(index, size);
                plan.set(index, officialSources);
                bytesTotal.addAndGet(size);
            }
        }

        // ② 并行下载：批量里没有的（没 Key / 官方没给）就在下载线程里现解析，边解析边下
        note = "正在下载模组";
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        List<Integer> retry = Collections.synchronizedList(new ArrayList<>());
        runWorkers(4, () -> {
            while (true) {
                int index = cursor.getAndIncrement();
                if (index >= files.size()) {
                    return;
                }
                PackInstaller.PackFile file = files.get(index);
                List<Mirrors.Source> sources = plan.get(index);
                String fileName = names.get(index);
                long size = sizes.get(index);
                if (sources.isEmpty() || fileName.isBlank()) {
                    Resolved resolved = resolve(file);
                    sources = resolved.sources();
                    fileName = resolved.fileName();
                    size = resolved.size();
                    plan.set(index, sources);
                    names.set(index, fileName);
                    sizes.set(index, size);
                    bytesTotal.addAndGet(size);
                }
                boolean ok;
                try {
                    ok = !sources.isEmpty() && !fileName.isBlank()
                            && fetch(file, sources, fileName, size);
                } catch (RuntimeException error) {
                    ok = false;
                }
                if (ok) {
                    downloaded++;
                } else if (file.required()) {
                    retry.add(index);
                }
                done = Math.min(files.size(), finished.incrementAndGet());
            }
        });

        // ③ 失败项再补一轮（重新解析，换个源）
        if (!retry.isEmpty()) {
            note = "正在重试失败的模组";
            List<Integer> pending = new ArrayList<>(retry);
            retry.clear();
            for (int index : pending) {
                PackInstaller.PackFile file = files.get(index);
                Resolved resolved = resolve(file);
                boolean ok;
                try {
                    ok = !resolved.sources().isEmpty() && !resolved.fileName().isBlank()
                            && fetch(file, resolved.sources(), resolved.fileName(), resolved.size());
                } catch (RuntimeException error) {
                    ok = false;
                }
                if (ok) {
                    downloaded++;
                } else {
                    failed++;
                    failedFiles.add(describe(file, resolved.fileName()));
                }
            }
        }
    }

    /** 失败提示里显示的名字：文件名 > 项目 id。 */
    private static String describe(PackInstaller.PackFile file, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        if (file.path() != null && !file.path().isBlank()) {
            return file.path();
        }
        return "项目 " + file.projectId() + " / 文件 " + file.fileId();
    }
    /** 解析一个清单文件的下载源：cfwidget 优先，失败/未知就走 MCIM。 */
    private Resolved resolve(PackInstaller.PackFile file) {
        String fileName = file.fileName();
        List<Mirrors.Source> sources = new ArrayList<>();
        long size = file.size();
        if (file.projectId() > 0 && file.fileId() > 0) {
            // ① CurseForge 官方 API（有 Key 时首选：给文件名 + 直连 CDN）
            Optional<CurseForge.CfFile> official =
                    CurseForge.file(file.projectId(), file.fileId());
            boolean officialOk = false;
            if (official.isPresent()) {
                CurseForge.CfFile info = official.get();
                if (fileName.isBlank()) {
                    fileName = info.fileName();
                }
                if (size <= 0) {
                    size = info.length();
                }
                String direct = info.downloadUrl();
                String url = direct.isBlank() ? CfWidget.cdnUrl(file.fileId(), fileName) : direct;
                if (!url.isBlank()) {
                    sources.add(new Mirrors.Source("CurseForge", url));
                    officialOk = true;
                }
            }
            // ② cfwidget：官方没 Key / 官方失败时的第一个备选
            if (!officialOk && CfWidget.healthy()) {
                String name = CfWidget.fileName(file.projectId(), file.fileId());
                if (!name.isBlank()) {
                    fileName = name;
                    sources.add(new Mirrors.Source("CurseForge·cfwidget",
                            CfWidget.cdnUrl(file.fileId(), name)));
                }
            }
            Optional<Mirrors.CfFile> info = Mirrors.cfFile(file.projectId(), file.fileId());
            if (info.isPresent()) {
                if (fileName.isBlank()) {
                    fileName = info.get().fileName();
                }
                if (size <= 0) {
                    size = info.get().length();
                }
                sources.add(new Mirrors.Source("MCIM", Mirrors.cfFileUrl(file.fileId(), fileName)));
                if (!info.get().downloadUrl().isBlank()) {
                    sources.add(new Mirrors.Source("MCIM·CDN", info.get().downloadUrl()));
                }
            } else if (!fileName.isBlank() && sources.isEmpty()) {
                sources.add(new Mirrors.Source("MCIM", Mirrors.cfFileUrl(file.fileId(), fileName)));
                String edge = CfWidget.edgeUrl(file.fileId(), fileName);
                if (!edge.isBlank()) {
                    sources.add(new Mirrors.Source("CurseForge·edge", edge));
                }
            }
        } else if (!file.url().isBlank()) {
            sources.add(new Mirrors.Source("Modrinth", file.url()));
            String mirror = Mirrors.modrinthFileUrl(file.url());
            if (!mirror.isBlank()) {
                sources.add(new Mirrors.Source("MCIM", mirror));
            }
        }
        String fastest = preferred;
        if (fastest != null && !fastest.isBlank() && sources.size() > 1) {
            sources.sort((left, right) -> Boolean.compare(
                    right.label().equals(fastest), left.label().equals(fastest)));
        }
        return new Resolved(fileName, sources, size > 0 ? size : UNKNOWN_SIZE);
    }

    /** 下载一个已经解析好的文件；成功返回 true。 */
    private boolean fetch(PackInstaller.PackFile file, List<Mirrors.Source> sources,
                          String fileName, long expectedSize) {
        String relative = file.path() == null || file.path().isBlank()
                ? "mods/" + fileName : file.path();
        Path target = safeResolve(folder, relative);
        if (target == null) {
            return false;
        }
        long cap = expectedSize > 0 ? expectedSize : UNKNOWN_SIZE;
        try {
            if (Files.isRegularFile(target) && expectedSize > 0 && Files.size(target) == expectedSize) {
                bytesDone.addAndGet(expectedSize);
                return true;
            }
        } catch (IOException ignored) {
        }
        AtomicLong fileDone = new AtomicLong();
        AtomicLong last = new AtomicLong();
        try {
            String used = Mirrors.download(target, sources, bytes -> {
                long delta = bytes - last.getAndSet(bytes);
                if (delta <= 0) {
                    return;
                }
                long grew = Math.min(delta, Math.max(0, cap - fileDone.get()));
                if (grew > 0) {
                    fileDone.addAndGet(grew);
                    bytesDone.addAndGet(grew);
                }
            });
            if (!used.isBlank()) {
                preferred = used;
            }
        } catch (IOException error) {
            return false;
        }
        if (!file.sha1().isBlank()) {
            try {
                if (!file.sha1().equalsIgnoreCase(sha1(target))) {
                    Files.deleteIfExists(target);
                    return false;
                }
            } catch (Exception error) {
                return false;
            }
        }
        return true;
    }

    /* ============================ 解包 ============================ */

    private void extractMods() {
        if (entries.isEmpty()) {
            return;
        }
        note = "正在解压整合包内的模组";
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            for (ZipEntry entry : entries) {
                String relative = relativeOf(entry.getName());
                Path target = relative == null || relative.isEmpty() ? null : safeResolve(folder, relative);
                if (target != null) {
                    try {
                        Files.createDirectories(target.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception ignored) {
                        failed++;
                    }
                }
                done++;
            }
        } catch (Exception ignored) {
            failed += entries.size();
            done = total;
        }
    }

    /* ============================ 工具 ============================ */

    private record Resolved(String fileName, List<Mirrors.Source> sources, long size) {
    }

    private static void runWorkers(int count, Runnable body) {
        Thread[] threads = new Thread[Math.max(1, count)];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(body, "yi-dl-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

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

    private static Path safeResolve(Path base, String relative) {
        Path target = base.resolve(relative).normalize();
        return target.startsWith(base.normalize()) ? target : null;
    }

    private static String sha1(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
