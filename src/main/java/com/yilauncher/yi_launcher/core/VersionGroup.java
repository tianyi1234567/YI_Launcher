/* ==========================================================================
 *  衣启动器 · core 层 ｜ VersionGroup.java —— 版本分组
 *  做什么：导入的每个文件夹算一个分组，启动器自己的 .minecraft 是内置「默认分组」；
 *          开启版本隔离的文件夹单独成组。
 *  谁在用：LauncherEngine、VersionsPage、MainView、GamePicker、DropInstall、VersionEntry。
 *  入口：builtin()、fromFolder()、countVersions()、isolationText()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 版本分组：玩家导入过的每个文件夹算一个分组，启动器自己的 .minecraft 是内置的「默认分组」。
 * <p>开启版本隔离的文件夹单独成组，列表里就不会和别的来源的版本混在一起。</p>
 */
public final class VersionGroup {

    public static final String DEFAULT_ID = "default";
    public static final String DEFAULT_NAME = "默认分组";

    /** 稳定 id，写进配置；默认分组固定为 default。 */
    public final String id;
    /** 玩家起的名字；没起就是「未命名分组 / 未命名分组2」。 */
    public String name;
    /** 扫描用的 versions 目录。 */
    public final Path versionsDir;
    /** 启动该分组版本时的根目录（libraries / assets / logs / 游戏目录）。 */
    public final Path root;
    /** 该分组是否开启版本隔离。 */
    public boolean isolation;
    /** 启动器启动时自动创建的默认分组，不能删除。 */
    public final boolean builtin;

    public VersionGroup(String id, String name, Path versionsDir, Path root, boolean isolation, boolean builtin) {
        this.id = id;
        this.name = name;
        this.versionsDir = versionsDir;
        this.root = root;
        this.isolation = isolation;
        this.builtin = builtin;
    }

    public static VersionGroup builtin(Path minecraftRoot) {
        return new VersionGroup(DEFAULT_ID, DEFAULT_NAME, MinecraftRoot.versions(minecraftRoot),
                minecraftRoot, true, true);
    }

    /**
     * 从玩家选的文件夹推断分组：选了 .minecraft 根目录就用里面的 versions，
     * 选了 versions 目录或某个版本目录就把上一层当根目录。
     */
    public static VersionGroup fromFolder(String id, String name, Path chosen, boolean isolation) {
        Path normalized = chosen.toAbsolutePath().normalize();
        Path nested = normalized.resolve("versions");
        Path versionsDir;
        Path root;
        if (Files.isDirectory(nested)) {
            // 选的是 .minecraft 这类根目录
            versionsDir = nested;
            root = normalized;
        } else if (Files.isRegularFile(normalized.resolve(normalized.getFileName() + ".json"))) {
            // 选的是单个版本目录：用它的上一层当 versions 目录
            versionsDir = normalized.getParent() == null ? normalized : normalized.getParent();
            root = versionsDir.getParent() == null ? versionsDir : versionsDir.getParent();
        } else {
            // 选的是 versions 目录本身
            versionsDir = normalized;
            root = normalized.getParent() == null ? normalized : normalized.getParent();
        }
        return new VersionGroup(id, name, versionsDir, root, isolation, false);
    }

    public String isolationText() {
        return isolation ? "独立版本隔离" : "共用 mods 目录";
    }

    /** 分组里能扫到几个版本，界面上用来给玩家提示。 */
    public int countVersions() {
        if (!Files.isDirectory(versionsDir)) {
            return 0;
        }
        int count = 0;
        try (var dirs = Files.newDirectoryStream(versionsDir)) {
            for (Path dir : dirs) {
                String folder = dir.getFileName().toString();
                if (Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(folder + ".json"))) {
                    count++;
                }
            }
        } catch (Exception ignored) {
            return count;
        }
        return count;
    }

    @Override
    public String toString() {
        return name;
    }
}
