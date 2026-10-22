/* ==========================================================================
 *  衣启动器 · core 层 ｜ LauncherEngine.java —— 启动器内核（单例）
 *  做什么：持有 .minecraft 根、分组列表、扫描出的版本、当前选中项，是界面访问数据的唯一入口。
 *  谁在用：MainView、LaunchPage、VersionsPage、SettingsPage、ModsPage、GamePicker、DropInstall。
 *  入口：get()、groups()/group()、scanAsync()、select()、launchAsync()、recentPlayed()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 启动器内核：持有 .minecraft 路径、分组、版本列表、当前选中版本与配置，
 * 并提供扫描 / 启动入口。<p>方法里的回调都发生在后台线程，界面层自己切回 FX 线程。</p>
 */
public final class LauncherEngine {

    private static final LauncherEngine INSTANCE = new LauncherEngine();

    private final Path root = MinecraftRoot.resolve();
    private final LauncherConfig config = LauncherConfig.load();
    private final List<VersionGroup> groups = new ArrayList<>();
    private final List<VersionEntry> versions = new ArrayList<>();
    private final List<Consumer<List<VersionEntry>>> listeners = new CopyOnWriteArrayList<>();

    private VersionEntry selected;
    private VersionGroup selectedGroup;
    private volatile boolean scanning;
    private volatile String scanError;

    private LauncherEngine() {
        loadGroups();
    }

    public static LauncherEngine get() {
        return INSTANCE;
    }

    public Path root() {
        return root;
    }

    public LauncherConfig config() {
        return config;
    }

    /* ============================ 分组 ============================ */

    /** 启动时先建默认分组，再把配置里导入过的分组加回来。 */
    private void loadGroups() {
        groups.clear();
        groups.add(VersionGroup.builtin(root));
        for (LauncherConfig.GroupSettings settings : config.groups()) {
            if (settings.id == null || VersionGroup.DEFAULT_ID.equals(settings.id)) {
                continue;
            }
            try {
                Path versionsDir = Path.of(settings.versionsDir);
                Path groupRoot = settings.root == null || settings.root.isBlank()
                        ? versionsDir.getParent()
                        : Path.of(settings.root);
                if (groupRoot == null) {
                    continue;
                }
                String name = settings.name == null || settings.name.isBlank()
                        ? nextGroupName() : settings.name;
                boolean isolation = settings.isolation == null || settings.isolation;
                groups.add(new VersionGroup(settings.id, name, versionsDir, groupRoot, isolation, false));
            } catch (Exception ex) {
                System.err.println("[分组] 跳过无效分组：" + settings.versionsDir + "（" + ex.getMessage() + "）");
            }
        }
    }

    public synchronized List<VersionGroup> groups() {
        return Collections.unmodifiableList(new ArrayList<>(groups));
    }

    public synchronized VersionGroup group(String id) {
        for (VersionGroup group : groups) {
            if (group.id.equals(id)) {
                return group;
            }
        }
        return null;
    }

    /** 玩家没起名字时的默认名字：未命名分组 / 未命名分组2 / … */
    public synchronized String nextGroupName() {
        String base = "未命名分组";
        if (!nameTaken(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + i;
            if (!nameTaken(candidate)) {
                return candidate;
            }
        }
        return base + System.currentTimeMillis();
    }

    private boolean nameTaken(String name) {
        for (VersionGroup group : groups) {
            if (group.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 导入一个文件夹作为新分组。同一个 versions 目录只登记一次，重复导入时更新名字与隔离设置。
     */
    public synchronized VersionGroup addGroup(Path folder, String name, boolean isolation) {
        VersionGroup created = VersionGroup.fromFolder(newGroupId(), name, folder, isolation);
        for (VersionGroup group : groups) {
            if (group.versionsDir.toAbsolutePath().normalize()
                    .equals(created.versionsDir.toAbsolutePath().normalize())) {
                if (name != null && !name.isBlank()) {
                    group.name = name;
                }
                group.isolation = isolation;
                persistGroups();
                return group;
            }
        }
        groups.add(created);
        persistGroups();
        return created;
    }

    public synchronized void removeGroup(String id) {
        if (VersionGroup.DEFAULT_ID.equals(id)) {
            return;
        }
        groups.removeIf(group -> group.id.equals(id));
        persistGroups();
    }

    public synchronized void setGroupIsolation(VersionGroup group, boolean isolation) {
        group.isolation = isolation;
        persistGroups();
    }

    private String newGroupId() {
        int index = 1;
        while (group("g" + index) != null) {
            index++;
        }
        return "g" + index;
    }

    private void persistGroups() {
        List<LauncherConfig.GroupSettings> settings = new ArrayList<>();
        for (VersionGroup group : groups) {
            if (group.builtin) {
                continue;
            }
            LauncherConfig.GroupSettings item = new LauncherConfig.GroupSettings();
            item.id = group.id;
            item.name = group.name;
            item.versionsDir = group.versionsDir.toString();
            item.root = group.root.toString();
            item.isolation = group.isolation;
            settings.add(item);
        }
        config.setGroups(settings);
        config.save();
    }

    /* ============================ 版本 ============================ */

    public synchronized List<VersionEntry> versions() {
        return Collections.unmodifiableList(new ArrayList<>(versions));
    }

    /** 上次打开的分组；分组没了（例如文件夹被删）就回落到默认分组。 */
    public synchronized VersionGroup selectedGroup() {
        if (selectedGroup == null) {
            selectedGroup = resolveGroup(config.selectedGroup());
        }
        return selectedGroup;
    }

    /** 切成当前分组，并记进配置。 */
    public synchronized void selectGroup(VersionGroup group) {
        if (group == null) {
            return;
        }
        this.selectedGroup = group;
        config.setSelectedGroup(group.id);
        config.save();
    }

    private VersionGroup resolveGroup(String id) {
        if (id != null) {
            for (VersionGroup group : groups) {
                if (group.id.equals(id)) {
                    return group;
                }
            }
        }
        return groups.isEmpty() ? VersionGroup.builtin(root) : groups.get(0);
    }

    /** 当前分组里的版本（界面列表用）。 */
    public synchronized List<VersionEntry> groupVersions() {
        List<VersionEntry> result = new ArrayList<>();
        VersionGroup group = selectedGroup();
        if (group == null) {
            return result;
        }
        for (VersionEntry entry : versions) {
            if (entry.groupId.equals(group.id)) {
                result.add(entry);
            }
        }
        return result;
    }

    public synchronized VersionEntry selected() {
        return selected;
    }

    /** 选中版本并写进配置，下次启动还是这个版本。 */
    public synchronized void select(VersionEntry entry) {
        this.selected = entry;
        if (entry != null) {
            config.setSelectedVersion(entry.configKey());
            config.save();
        }
    }

    /** 启动页「选择游戏」挑定的版本；被删掉或换了分组就返回 null，界面回到"请选择游戏"。 */
    public synchronized VersionEntry chosen() {
        String key = config.chosenVersion();
        if (key == null || key.isBlank()) {
            return null;
        }
        for (VersionEntry entry : versions()) {
            if (key.equals(entry.configKey())) {
                return entry;
            }
        }
        return null;
    }

    /** 记下"选中的游戏"，下次打开还是它。 */
    public synchronized void choose(VersionEntry entry) {
        config.setChosenVersion(entry == null ? "" : entry.configKey());
        if (entry != null) {
            this.selected = entry;
            config.setSelectedVersion(entry.configKey());
        }
        config.save();
    }

    /** 把界面上改过的版本设置写回 config.json。 */
    public synchronized void persist(VersionEntry entry) {
        LauncherConfig.VersionSettings settings = config.version(entry.configKey());
        settings.displayName = entry.displayName;
        settings.memoryMb = entry.memoryOverrideMb;
        settings.accent = entry.accent;
        settings.javaPath = entry.javaPath == null ? null : entry.javaPath.toString();
        settings.javaLabel = entry.javaLabel;
        settings.windowMode = entry.windowMode;
        settings.windowWidth = entry.windowWidth;
        settings.windowHeight = entry.windowHeight;
        settings.isolation = entry.isolation;
        settings.lastLaunched = entry.lastLaunched;
        config.save();
    }

    public String scanError() {
        return scanError;
    }

    /** 扫描所有分组的版本目录；正在扫描时只登记回调，结束后统一通知。 */
    public void scanAsync(Consumer<List<VersionEntry>> onDone) {
        if (onDone != null) {
            listeners.add(onDone);
        }
        if (scanning) {
            return;
        }
        scanning = true;
        Thread thread = new Thread(() -> {
            List<VersionEntry> found = new ArrayList<>();
            String error = null;
            for (VersionGroup group : groups()) {
                try {
                    List<VersionEntry> groupVersions = VersionsScanner.scan(group.versionsDir, group.root);
                    for (VersionEntry entry : groupVersions) {
                        entry.groupId = group.id;
                        entry.groupName = group.name;
                        entry.groupRoot = group.builtin ? null : group.root;
                        entry.isolation = group.isolation;
                        found.add(entry);
                    }
                } catch (Exception ex) {
                    error = group.name + "：" + ex.getMessage();
                }
            }
            synchronized (this) {
                versions.clear();
                versions.addAll(found);
                scanError = error;
                applyConfig();
                selectedGroup = resolveGroup(config.selectedGroup());
                selected = findByKey(config.selectedVersion());
                if (selected == null || !selected.groupId.equals(selectedGroup.id)) {
                    VersionEntry firstInGroup = null;
                    for (VersionEntry entry : versions) {
                        if (entry.groupId.equals(selectedGroup.id)) {
                            firstInGroup = entry;
                            break;
                        }
                    }
                    if (firstInGroup != null) {
                        selected = firstInGroup;
                    } else if (selected == null && !found.isEmpty()) {
                        selected = found.get(0);
                    }
                }
                if (config.selectedVersion() == null && selected != null) {
                    config.setSelectedVersion(selected.configKey());
                    config.save();
                }
                if (config.selectedGroup() == null) {
                    config.setSelectedGroup(selectedGroup.id);
                    config.save();
                }
                scanning = false;
            }
            List<VersionEntry> snapshot = List.copyOf(found);
            for (Consumer<List<VersionEntry>> listener : listeners) {
                listener.accept(snapshot);
            }
            listeners.clear();
            // Java 列表要逐个跑 --version，比较慢（实测 ~6s）；放到通知之后在后台热缓存，
            // 免得详情页的下拉列表拖慢版本列表的首次显示。
            try {
                JavaRuntimes.all(root);
            } catch (Exception ignored) {
                // 忽略
            }
        }, "yi-version-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyConfig() {
        for (VersionEntry entry : versions) {
            LauncherConfig.VersionSettings settings = config.peek(entry.configKey());
            if (settings != null) {
                if (settings.displayName != null && !settings.displayName.isBlank()) {
                    entry.displayName = settings.displayName;
                }
                if (settings.accent != null && !settings.accent.isBlank()) {
                    entry.accent = settings.accent;
                }
                if (settings.memoryMb != null && settings.memoryMb > 0) {
                    entry.memoryOverrideMb = settings.memoryMb;
                }
                if (settings.javaPath != null && !settings.javaPath.isBlank()) {
                    try {
                        entry.javaPath = Path.of(settings.javaPath);
                        entry.javaLabel = settings.javaLabel;
                    } catch (Exception ignored) {
                        // 路径失效就当没设置
                    }
                }
                if (settings.windowMode != null) {
                    entry.windowMode = settings.windowMode;
                }
                if (settings.windowWidth != null && settings.windowWidth > 0) {
                    entry.windowWidth = settings.windowWidth;
                }
                if (settings.windowHeight != null && settings.windowHeight > 0) {
                    entry.windowHeight = settings.windowHeight;
                }
                if (settings.isolation != null) {
                    entry.isolation = settings.isolation;
                }
                if (settings.lastLaunched != null && settings.lastLaunched > 0) {
                    entry.lastLaunched = settings.lastLaunched;
                }
            }
            entry.refreshMemory();
        }
    }

    private VersionEntry findByKey(String key) {
        if (key == null) {
            return null;
        }
        for (VersionEntry entry : versions) {
            if (entry.configKey().equals(key)) {
                return entry;
            }
        }
        // 兼容旧配置：只存了版本 id
        for (VersionEntry entry : versions) {
            if (entry.id.equals(key)) {
                return entry;
            }
        }
        return null;
    }

    /** 在后台拼参数并启动游戏；status 里是给用户看的进度文本。 */
    public void launchAsync(VersionEntry entry, Consumer<String> status, Consumer<Exception> onError) {
        entry.refreshMemory();
        Path launchRoot = entry.root(root);
        Thread thread = new Thread(() -> {
            try {
                status.accept("正在检查依赖与 Java…");
                GameLaunch.Result result = GameLaunch.launch(launchRoot, entry, entry.effectiveMemory(), status);
                // 记下这次启动，重启后启动页才能显示"上次启动的版本"
                persist(entry);
                if (!result.missing().isEmpty()) {
                    status.accept("缺少 " + result.missing().size() + " 个库文件，游戏可能启动失败");
                }
                if (result.process() != null) {
                    Process process = result.process();
                    Thread watcher = new Thread(() -> {
                        try {
                            int code = process.waitFor();
                            status.accept("游戏已退出（退出码 " + code + "）");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }, "yi-game-watch");
                    watcher.setDaemon(true);
                    watcher.start();
                }
            } catch (Exception ex) {
                onError.accept(ex);
            }
        }, "yi-game-launch");
        thread.setDaemon(true);
        thread.start();
    }

    /** 最近启动过的版本（按启动时间倒序，最多 limit 个）。 */
    public synchronized List<VersionEntry> recentPlayed(int limit) {
        List<VersionEntry> played = new ArrayList<>();
        for (VersionEntry entry : versions) {
            if (entry.lastLaunched > 0) {
                played.add(entry);
            }
        }
        played.sort(Comparator.comparingLong((VersionEntry entry) -> entry.lastLaunched).reversed());
        if (played.size() > limit) {
            return new ArrayList<>(played.subList(0, limit));
        }
        return played;
    }

    /** 上次启动的那个版本；没有启动记录时返回 null。 */
    public synchronized VersionEntry lastPlayed() {
        List<VersionEntry> recent = recentPlayed(1);
        return recent.isEmpty() ? null : recent.get(0);
    }

    /** 按发布时间排序的副本，给列表用。 */
    public List<VersionEntry> sortedForDisplay() {
        List<VersionEntry> copy = versions();
        copy.sort(Comparator.comparingLong((VersionEntry entry) -> entry.releaseTime).reversed());
        return copy;
    }

    /** 分组名（找不到时给个兜底文本）。 */
    public String groupName(String id) {
        VersionGroup group = group(id);
        return group == null ? "未知分组" : group.name;
    }

    /** 调试用：当前分组概览。 */
    public String describeGroups() {
        StringBuilder sb = new StringBuilder();
        for (VersionGroup group : groups()) {
            sb.append(String.format(Locale.ROOT, "%s(%s) -> %s%n", group.name, group.id, group.versionsDir));
        }
        return sb.toString();
    }
}
