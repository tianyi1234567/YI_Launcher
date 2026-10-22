/* ==========================================================================
 *  衣启动器 · core 层 ｜ MemoryRecommendation.java —— 内存推荐
 *  做什么：按模组数量分档（≤150 → 6 GB，150~300 → 8 GB，≥300 → 12 GB）并给一句理由。
 *  谁在用：VersionEntry.refreshMemory()、LaunchPage 状态栏、VersionsPage 内存弹窗。
 *  入口：forMods()、reason()、asText()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

/**
 * 按模组数量推荐内存分配：
 * <ul>
 *   <li>0 – 150 个模组：6 GB</li>
 *   <li>150 – 300 个模组：8 GB</li>
 *   <li>300 个及以上：12 GB</li>
 * </ul>
 */
public final class MemoryRecommendation {

    public static final int SMALL = 6144;
    public static final int MEDIUM = 8192;
    public static final int LARGE = 12288;

    private MemoryRecommendation() {
    }

    public static int forMods(int mods) {
        if (mods < 150) {
            return SMALL;
        }
        if (mods < 300) {
            return MEDIUM;
        }
        return LARGE;
    }

    /** 给提示文本用：为什么推荐这个值。 */
    public static String reason(int mods) {
        if (mods < 150) {
            return mods + " 个模组 → 推荐 6 GB";
        }
        if (mods < 300) {
            return mods + " 个模组 → 推荐 8 GB";
        }
        return mods + " 个模组 → 推荐 12 GB";
    }

    public static String asText(int memoryMb) {
        if (memoryMb % 1024 == 0) {
            return (memoryMb / 1024) + " GB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB", memoryMb / 1024.0);
    }
}
