/* ==========================================================================
 *  衣启动器 · core 层 ｜ MinecraftRoot.java —— 定位 .minecraft
 *  做什么：按系统猜出 .minecraft 路径（可用 -Dyi.minecraftDir 覆盖），并给出各子目录。
 *  谁在用：LauncherEngine、VersionsScanner、VersionGroup、GameLaunch、JavaRuntimes。
 *  入口：resolve()、versions(root)、libraries(root)、assets(root)。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 定位 .minecraft 目录（可用 -Dyi.minecraftDir 覆盖）。 */
public final class MinecraftRoot {

    private MinecraftRoot() {
    }

    public static Path resolve() {
        String override = System.getProperty("yi.minecraftDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, ".minecraft");
            }
        }
        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft");
        }
        Path candidates = Path.of(System.getProperty("user.home"), ".minecraft");
        if (Files.isDirectory(candidates)) {
            return candidates;
        }
        String appData = System.getenv("APPDATA");
        return appData == null || appData.isBlank() ? candidates : Path.of(appData, ".minecraft");
    }

    public static Path versions(Path root) {
        return root.resolve("versions");
    }

    public static Path libraries(Path root) {
        return root.resolve("libraries");
    }

    public static Path assets(Path root) {
        return root.resolve("assets");
    }
}
