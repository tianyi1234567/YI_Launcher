package com.yilauncher.yi_launcher.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个可启动的版本（扫描结果 + 用户可改的显示/运行设置）。
 * <p>版本可能来自不同的分组（不同的 .minecraft 或 versions 目录），启动时用所属分组的根目录。</p>
 */
public final class VersionEntry {

    /** 版本文件夹名，同时用作 --version 参数。 */
    public final String id;
    public final Path dir;
    public final Path jsonFile;

    /** 界面上显示的名字，可通过重命名修改。 */
    public String displayName;

    public String loader = "原版";
    public String loaderVersion = "";
    /** 继承链最底层的原版版本号。 */
    public String gameVersion;
    public String type = "release";
    public long releaseTime;
    public int javaMajor = 8;
    public int modsCount;
    public long sizeBytes;

    /** 所属分组（默认分组 id 为 default）。 */
    public String groupId = VersionGroup.DEFAULT_ID;
    /** 分组显示名，列表与详情里给玩家看。 */
    public String groupName = VersionGroup.DEFAULT_NAME;
    /** 该分组的根目录（libraries / assets / logs / 游戏目录）；null 表示用启动器自己的 .minecraft。 */
    public Path groupRoot;

    /** 详情模块配色（导入时随机）。 */
    public String accent;
    /** 用户手动指定的内存（MB）；null 表示按模组数量自动推荐。 */
    public Integer memoryOverrideMb;
    /** 当前生效的内存（MB），由 memoryOverrideMb 或推荐值决定。 */
    public int memoryMb = MemoryRecommendation.SMALL;
    /** 窗口模式：default / fullscreen / custom。 */
    public String windowMode = "default";
    /** 自定义窗口尺寸。 */
    public int windowWidth = 1280;
    public int windowHeight = 720;
    /** 是否独立版本隔离。 */
    public boolean isolation = true;
    /** 用户指定的 java 可执行文件；空表示启动时自动挑选。 */
    public Path javaPath;
    /** java 的显示名（选中后写入）。 */
    public String javaLabel;

    /** 扫描时提示的异常（缺 json、解析失败等）。 */
    public String warning;

    /** 上次启动时间（毫秒），0 表示没启动过。 */
    public long lastLaunched;

    private List<String> launchArgs;

    public VersionEntry(String id, Path dir, Path jsonFile) {
        this.id = id;
        this.dir = dir;
        this.jsonFile = jsonFile;
        this.displayName = id;
        this.gameVersion = id;
    }

    public String loaderText() {
        return loaderVersion.isBlank() ? loader : loader + " " + loaderVersion;
    }

    /** 配置里区分同名版本用的键：默认分组就是版本 id，其他分组带前缀。 */
    public String configKey() {
        return VersionGroup.DEFAULT_ID.equals(groupId) ? id : groupId + "/" + id;
    }

    /** 启动这个版本时使用的根目录。 */
    public Path root(Path fallback) {
        return groupRoot == null ? fallback : groupRoot;
    }

    /** 按模组数量算出来的推荐内存。 */
    public int recommendedMemory() {
        return MemoryRecommendation.forMods(modsCount);
    }

    /** 实际生效的内存：手动值优先，否则用推荐值。 */
    public int effectiveMemory() {
        return memoryOverrideMb != null ? memoryOverrideMb : recommendedMemory();
    }

    /** 手动值低于推荐值时提示玩家。 */
    public boolean memoryTooLow() {
        return effectiveMemory() < recommendedMemory();
    }

    public boolean memoryIsAuto() {
        return memoryOverrideMb == null;
    }

    /** 改了设置或扫描完成后刷新生效值。 */
    public void refreshMemory() {
        this.memoryMb = effectiveMemory();
    }

    public boolean isFullscreen() {
        return "fullscreen".equals(windowMode);
    }

    public String windowText() {
        if (isFullscreen()) {
            return "全屏";
        }
        return windowWidth + " × " + windowHeight;
    }

    public void setLaunchArgs(List<String> args) {
        this.launchArgs = args;
    }

    public List<String> launchArgs() {
        return launchArgs == null ? List.of() : launchArgs;
    }

    /** 列表里显示的副标题：加载器 + 上次改动时间。 */
    public String subtitle(String updatedText) {
        return (loaderText().isBlank() ? "原版" : loaderText()) + " · " + updatedText;
    }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
