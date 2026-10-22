/* ==========================================================================
 *  衣启动器 · core 层 ｜ PackDownloader.java —— 整合包文件下载
 *  做什么：先把清单里的文件名/地址/体积并行解析出来，再并行下载（进度按字节算），
 *          第一轮失败的会在最后统一补一轮。
 *  谁在用：PackInstaller.installPack()。
 *  入口：downloadAll()、resolve()、extractMods()（从 overrides/mods 取 jar）。
 * ========================================================================== */
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
    private volatile String note = "解析下载地址";
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicLong bytesTotal = new AtomicLong();
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
            return String.format(Locale.ROOT, "下载模组 %d/%d · %.1f / %.1f MB",
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

        // ① 解析：文件名 / 源列表 / 体积
        AtomicInteger cursor = new AtomicInteger();
        runWorkers(4, () -> {
            while (true) {
                int index = cursor.getAndIncrement();
                if (index >= files.size()) {
                    return;
                }
                Resolved resolved = resolve(files.get(index));
                plan.set(index, resolved.sources());
                names.set(index, resolved.fileName());
                sizes.set(index, resolved.size());
                bytesTotal.addAndGet(resolved.size());
            }
        });

        // ② 下载：4 条线程并行，字节进度实时累加
        note = "下载模组";
        List<Integer> retry = Collections.synchronizedList(new ArrayList<>());
        downloadPass(plan, names, sizes, retry);

        // ③ 失败的文件再补一轮（重新解析一次，换个源试试）
        if (!retry.isEmpty()) {
            note = "重试失败的模组";
            List<Integer> pending = new ArrayList<>(retry);
            retry.clear();
            for (int index : pending) {
                Resolved resolved = resolve(files.get(index));
                plan.set(index, resolved.sources());
                names.set(index, resolved.fileName());
            }
            downloadPass(plan, names, sizes, retry);
        }
        failed += retry.size();
    }

    /** 跑一轮下载；失败的下标进 retry。 */
    private void downloadPass(List<List<Mirrors.Source>> plan, List<String> names,
                              List<Long> sizes, List<Integer> retry) {
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        List<Integer> queue = new ArrayList<>();
        if (finished.get() == 0) {
            for (int index = 0; index < files.size(); index++) {
                queue.add(index);
            }
        } else {
            // 重试轮：只跑待补的那些
            synchronized (retry) {
                queue.addAll(retry);
            }
        }
        runWorkers(4, () -> {
            while (true) {
                int position = cursor.getAndIncrement();
                if (position >= queue.size()) {
                    return;
                }
                int index = queue.get(position);
                PackInstaller.PackFile file = files.get(index);
                try {
                    if (plan.get(index).isEmpty() || names.get(index).isBlank()) {
                        if (file.required()) {
                            synchronized (retry) {
                                retry.add(index);
                            }
                        }
                    } else if (fetch(file, plan.get(index), names.get(index), sizes.get(index))) {
                        downloaded++;
                    } else if (file.required()) {
                        synchronized (retry) {
                            retry.add(index);
                        }
                    }
                } catch (RuntimeException error) {
                    if (file.required()) {
                        synchronized (retry) {
                            retry.add(index);
                        }
                    }
                }
                done = Math.min(files.size(), finished.incrementAndGet());
            }
        });
        if (queue.isEmpty()) {
            done = files.size();
        }
    }

    /** 解析一个清单文件的下载源：cfwidget 优先，失败/未知就走 MCIM。 */
    private Resolved resolve(PackInstaller.PackFile file) {
        String fileName = file.fileName();
        List<Mirrors.Source> sources = new ArrayList<>();
        long size = file.size();
        if (file.projectId() > 0 && file.fileId() > 0) {
            if (CfWidget.healthy()) {
                String name = CfWidget.fileName(file.projectId(), file.fileId());
                if (!name.isBlank()) {
                    fileName = name;
                    sources.add(new Mirrors.Source("CurseForge", CfWidget.cdnUrl(file.fileId(), name)));
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
            }
        } else if (!file.url().isBlank()) {
            sources.add(new Mirrors.Source("Modrinth", file.url()));
            String mirror = Mirrors.modrinthFileUrl(file.url());
            if (!mirror.isBlank()) {
                sources.add(new Mirrors.Source("MCIM", mirror));
            }
        }
        // 上次实测快的源提到最前（第一个文件仍按 cfwidget → MCIM 的顺序）
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
            // 读不到就当没下过
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
        note = "解压整合包内的模组";
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
