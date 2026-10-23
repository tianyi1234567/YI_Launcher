package com.yilauncher.yi_launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 启动器配置：上次打开的分组 / 版本、导入的分组，以及每个版本的
 * 改名 / 内存 / 配色 / Java / 窗口尺寸 / 隔离。改一项就落盘。
 * <p>默认存在 %APPDATA%\YI_Launcher\config.json（可用 -Dyi.config 指定）。</p>
 */
public final class LauncherConfig {

    /** 单个版本的设置，null 表示没有单独设置过。 */
    public static final class VersionSettings {
        public String displayName;
        public Integer memoryMb;
        public String accent;
        public String javaPath;
        public String javaLabel;
        public String windowMode;
        public Integer windowWidth;
        public Integer windowHeight;
        public Boolean isolation;
        /** 上次启动时间（毫秒），0 / null 表示没启动过。 */
        public Long lastLaunched;

        public boolean isEmpty() {
            return displayName == null && memoryMb == null && accent == null && javaPath == null
                    && windowMode == null && windowWidth == null && windowHeight == null
                    && isolation == null && lastLaunched == null;
        }
    }

    /** 一个导入过的分组；默认分组不写进配置，由启动器自己创建。 */
    public static final class GroupSettings {
        public String id;
        public String name;
        public String versionsDir;
        public String root;
        public Boolean isolation;
    }

    /** 界面偏好：只影响观感，跟具体版本无关。 */
    public static final class UiSettings {
        /** 动效速度倍率：1.0 = 标准，越小越慢。 */
        public Double motionSpeed;
        /** 玻璃整体不透明度：0.7 – 1.0。 */
        public Double uiOpacity;
        /** 窗口圆角半径（px）。 */
        public Double cornerRadius;
        /** 微软正版登录用的 Azure 公共客户端 ID（设备码流程），空 = 不可用。 */
        public String microsoftClientId;
        /** CurseForge 官方 API Key：有它就走官方源下载，镜像只当备选。 */
        public String curseForgeKey;

        public double speed() {
            return motionSpeed == null || motionSpeed <= 0 ? 1.0 : motionSpeed;
        }

        public double opacity() {
            return uiOpacity == null ? 1.0 : Math.max(0.5, Math.min(1.0, uiOpacity));
        }

        public double radius() {
            return cornerRadius == null ? 26 : Math.max(0, Math.min(34, cornerRadius));
        }

        public String msClientId() {
            return microsoftClientId == null ? "" : microsoftClientId.trim();
        }

        public String cfKey() {
            return curseForgeKey == null ? "" : curseForgeKey.trim();
        }
    }

    private final Path file;
    private final Map<String, VersionSettings> versions = new LinkedHashMap<>();
    private final List<GroupSettings> groups = new ArrayList<>();
    private final UiSettings ui = new UiSettings();
    private String selectedVersion;
    /** 启动页「选择游戏」里挑定的版本（configKey），没有就用空。 */
    private String chosenVersion;
    private String selectedGroup;


    private LauncherConfig(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        String override = System.getProperty("yi.config");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "YI_Launcher", "config.json");
            }
        }
        return Path.of(System.getProperty("user.home"), ".config", "yi-launcher", "config.json");
    }

    public static LauncherConfig load() {
        Path file = defaultFile();
        LauncherConfig config = new LauncherConfig(file);
        if (!Files.isRegularFile(file)) {
            return config;
        }
        try {
            Map<String, Object> root = Json.map(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            config.selectedVersion = Json.string(root, "selectedVersion");
            config.chosenVersion = Json.string(root, "chosenVersion");
            config.selectedGroup = Json.string(root, "selectedGroup");
            Map<String, Object> uiMap = Json.map(root.get("ui"));
            config.ui.motionSpeed = decimal(uiMap, "motionSpeed");
            config.ui.uiOpacity = decimal(uiMap, "uiOpacity");
            config.ui.cornerRadius = decimal(uiMap, "cornerRadius");
            config.ui.microsoftClientId = Json.string(uiMap, "microsoftClientId");
            config.ui.curseForgeKey = Json.string(uiMap, "curseForgeKey");
            for (Object rawGroup : Json.list(root.get("groups"))) {
                Map<String, Object> groupMap = Json.map(rawGroup);
                GroupSettings settings = new GroupSettings();
                settings.id = Json.string(groupMap, "id");
                settings.name = Json.string(groupMap, "name");
                settings.versionsDir = Json.string(groupMap, "versionsDir");
                settings.root = Json.string(groupMap, "root");
                settings.isolation = groupMap.get("isolation") instanceof Boolean flag ? flag : null;
                if (settings.id != null && settings.versionsDir != null) {
                    config.groups.add(settings);
                }
            }
            for (Map.Entry<String, Object> entry : Json.map(root.get("versions")).entrySet()) {
                Map<String, Object> raw = Json.map(entry.getValue());
                VersionSettings settings = new VersionSettings();
                settings.displayName = Json.string(raw, "displayName");
                settings.memoryMb = number(raw, "memoryMb");
                settings.accent = Json.string(raw, "accent");
                settings.javaPath = Json.string(raw, "javaPath");
                settings.javaLabel = Json.string(raw, "javaLabel");
                settings.windowMode = Json.string(raw, "windowMode");
                settings.windowWidth = number(raw, "windowWidth");
                settings.windowHeight = number(raw, "windowHeight");
                settings.isolation = raw.get("isolation") instanceof Boolean flag ? flag : null;
                settings.lastLaunched = raw.get("lastLaunched") instanceof Number stamp
                        ? stamp.longValue() : null;
                config.versions.put(entry.getKey(), settings);
            }
        } catch (Exception ex) {
            System.err.println("[配置] 读取失败，使用默认配置：" + ex.getMessage());
        }
        return config;
    }

    private static Integer number(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number value ? value.intValue() : null;
    }

    private static Double decimal(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number value ? value.doubleValue() : null;
    }

    /** 界面偏好（动画速度 / 不透明度 / 圆角）。 */
    public UiSettings ui() {
        return ui;
    }


    public Path file() {
        return file;
    }

    public String selectedVersion() {
        return selectedVersion;
    }

    public void setSelectedVersion(String id) {
        this.selectedVersion = id;
    }

    /** 启动页选定的游戏版本（configKey）。 */
    public String chosenVersion() {
        return chosenVersion;
    }

    public void setChosenVersion(String key) {
        this.chosenVersion = key;
    }

    /** 上次打开的分组 id；分组没了就回落到默认分组。 */
    public String selectedGroup() {
        return selectedGroup;
    }

    public void setSelectedGroup(String id) {
        this.selectedGroup = id;
    }

    public VersionSettings version(String id) {
        return versions.computeIfAbsent(id, key -> new VersionSettings());
    }

    public VersionSettings peek(String id) {
        return versions.get(id);
    }

    public List<GroupSettings> groups() {
        return new ArrayList<>(groups);
    }

    /** 用当前分组列表覆盖配置里的分组。 */
    public void setGroups(List<GroupSettings> settings) {
        groups.clear();
        groups.addAll(settings);
    }

    /** 把设置写回 json（体积很小，改一次写一次即可）。 */
    public synchronized void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String json = toJson();
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            System.err.println("[配置] 写入失败：" + ex.getMessage());
        }
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"selectedVersion\": ").append(quote(selectedVersion)).append(",\n");
        sb.append("  \"chosenVersion\": ").append(quote(chosenVersion)).append(",\n");
        sb.append("  \"selectedGroup\": ").append(quote(selectedGroup)).append(",\n");

        StringBuilder uiFields = new StringBuilder();
        appendDecimal(uiFields, "motionSpeed", ui.motionSpeed);
        appendDecimal(uiFields, "uiOpacity", ui.uiOpacity);
        appendDecimal(uiFields, "cornerRadius", ui.cornerRadius);
        appendString(uiFields, "microsoftClientId", ui.microsoftClientId);
        appendString(uiFields, "curseForgeKey", ui.curseForgeKey);
        sb.append("  \"ui\": {")
                .append(uiFields.toString().replaceFirst("^,", ""))
                .append(" },\n");

        sb.append("  \"groups\": [");
        boolean firstGroup = true;
        for (GroupSettings group : groups) {
            sb.append(firstGroup ? "\n" : ",\n");
            firstGroup = false;
            sb.append("    {");
            sb.append("\"id\": ").append(quote(group.id));
            sb.append(", \"name\": ").append(quote(group.name));
            sb.append(", \"versionsDir\": ").append(quote(group.versionsDir));
            sb.append(", \"root\": ").append(quote(group.root));
            sb.append(", \"isolation\": ").append(group.isolation == null ? "true" : group.isolation);
            sb.append('}');
        }
        sb.append(firstGroup ? "],\n" : "\n  ],\n");

        sb.append("  \"versions\": {");
        boolean first = true;
        for (Map.Entry<String, VersionSettings> entry : versions.entrySet()) {
            VersionSettings settings = entry.getValue();
            if (settings.isEmpty()) {
                continue;
            }
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("    ").append(quote(entry.getKey())).append(": {");
            StringBuilder fields = new StringBuilder();
            appendString(fields, "displayName", settings.displayName);
            appendNumber(fields, "memoryMb", settings.memoryMb);
            appendString(fields, "accent", settings.accent);
            appendString(fields, "javaPath", settings.javaPath);
            appendString(fields, "javaLabel", settings.javaLabel);
            appendString(fields, "windowMode", settings.windowMode);
            appendNumber(fields, "windowWidth", settings.windowWidth);
            appendNumber(fields, "windowHeight", settings.windowHeight);
            appendBool(fields, "isolation", settings.isolation);
            appendLong(fields, "lastLaunched", settings.lastLaunched);
            sb.append(fields.toString().replaceFirst("^,", "")).append("}");
        }
        sb.append(first ? "}\n" : "\n  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(", ").append(quote(key)).append(": ").append(quote(value));
        }
    }

    private static void appendNumber(StringBuilder sb, String key, Integer value) {
        if (value != null) {
            sb.append(", ").append(quote(key)).append(": ").append(value);
        }
    }

    private static void appendBool(StringBuilder sb, String key, Boolean value) {
        if (value != null) {
            sb.append(", ").append(quote(key)).append(": ").append(value);
        }
    }


    private static void appendDecimal(StringBuilder sb, String key, Double value) {
        if (value != null) {
            sb.append(", ").append(quote(key)).append(": ")
                    .append(String.format(Locale.ROOT, "%.2f", value));
        }
    }
    private static void appendLong(StringBuilder sb, String key, Long value) {
        if (value != null && value > 0) {
            sb.append(", ").append(quote(key)).append(": ").append(value);
        }
    }
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
