/* ==========================================================================
 *  衣启动器 · ui 层 ｜ SystemMonitor.java —— 系统占用
 *  做什么：读 CPU 占用率与总/已用/空闲内存；优先 com.sun.management，取不到退回 JVM 数据。
 *  谁在用：LaunchPage 的系统监控卡、VersionsPage（内存推荐与进度条上限）。
 *  入口：cpuLoad()、totalMemory()、usedMemory()、freeMemory()、toGb()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.ui;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/**
 * 读取系统 CPU / 内存占用。
 * <p>优先用 com.sun.management 的扩展接口（Windows / macOS / Linux 的 HotSpot 都支持），
 * 取不到时退回 JVM 自己的内存数据，保证界面不会出现空值。</p>
 */
public final class SystemMonitor {

    private static final OperatingSystemMXBean OS =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    private SystemMonitor() {
    }

    /** 系统 CPU 占用，0.0 ~ 1.0；不可用时返回 0。 */
    public static double cpuLoad() {
        if (OS == null) {
            return 0;
        }
        double load = OS.getCpuLoad();
        if (Double.isNaN(load) || load < 0) {
            return 0;
        }
        return Math.min(1.0, load);
    }

    public static long totalMemory() {
        if (OS != null) {
            long total = OS.getTotalMemorySize();
            if (total > 0) {
                return total;
            }
        }
        return Runtime.getRuntime().maxMemory();
    }

    public static long usedMemory() {
        if (OS != null) {
            long total = OS.getTotalMemorySize();
            if (total > 0) {
                long free = OS.getFreeMemorySize();
                return Math.max(0, total - free);
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** 转成 GB，保留一位小数。 */
    public static double toGb(long bytes) {
        return bytes / 1024.0 / 1024.0 / 1024.0;
    }

    /** 系统当前可用（空闲）物理内存，字节。 */
    public static long freeMemory() {
        if (OS != null) {
            long total = OS.getTotalMemorySize();
            if (total > 0) {
                return Math.max(0, OS.getFreeMemorySize());
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }
}
