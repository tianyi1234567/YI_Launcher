package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.ModIdentity;
import com.yilauncher.yi_launcher.core.PackInstaller;
import com.yilauncher.yi_launcher.core.Remote;
import com.yilauncher.yi_launcher.core.VersionEntry;
import com.yilauncher.yi_launcher.core.VersionFromName;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模组管理：看选中版本装了哪些模组，能搜索、按「所有 / 已禁用 / 可更新」过滤，
 * 选中模组后在底部禁用 / 更新 / 查看详情。
 * <p>禁用就是把 {@code xxx.jar} 改名成 {@code xxx.jar.disabled}（游戏不会加载），
 * 解除禁用再改回来；列表一次性建好行，滚轮不会重建，淡入只在打开页面时播一次。</p>
 */
public final class ModsPage extends BorderPane {

    /** 顶部过滤。 */
    private enum Filter {
        ALL("所有"), DISABLED("已禁用"), UPDATABLE("可更新");

        final String text;

        Filter(String text) {
            this.text = text;
        }
    }

    /** 查模组资料时可选的三家网站。 */
    private enum Site {
        MC_MOD("MC 百科", "https://search.mcmod.cn/s?key="),
        CURSEFORGE("CurseForge", "https://www.curseforge.com/minecraft/search?search="),
        MODRINTH("Modrinth", "https://modrinth.com/mods?q=");

        final String text;
        final String search;

        Site(String text, String search) {
            this.text = text;
            this.search = search;
        }
    }

    private final MainView host;
    private final VBox list = new VBox(8);
    private final Label subtitle = Ui.label("", "section-sub");
    private final Label status = Ui.label("", "settings-hint");
    private final Label countLabel = Ui.label("", "settings-hint");
    // 图标缓存在 IconCache 里，全启动器共用
    private final List<Row> rows = new ArrayList<>();
    /** 识别是否已经跑完（跑完之前不给未识别的行去找兜底图标）。 */
    private boolean identifyDone;
    /** 列表容器：图标按「是否滚进视口」按需加载。 */
    private ScrollPane scroller;
    private PauseTransition iconFlush;

    private final List<Label> filterChips = new ArrayList<>();
    private final TextField search = new TextField();
    private final Label emptyHint = Ui.label("", "version-meta");
    private final HBox actions = new HBox(8);
    private final Label selectedLabel = Ui.label("", "mod-actions-label");
    private final Button disableButton = Ui.ghostButton("禁用", Icons.CLOSE, null);
    private final Button updateButton = Ui.ghostButton("更新", Icons.DOWNLOAD, null);
    private final Button detailsButton = Ui.ghostButton("查看详情", Icons.GRID, null);

    private Filter filter = Filter.ALL;
    private String query = "";

    /**
     * 淡入只在每次「打开页面」播一次；滚轮/刷新都不重播。
     */
    private boolean introPlayed;
    /** 换版本 / 重新扫描时作废上一次的联网识别结果。 */
    private int flowToken;
    /** 当前版本的游戏版本号（从文件名解析模组版本时要排除它）。 */
    private String gameVersion = "";
    /** 当前版本的 mods 目录（禁用 / 解除禁用要用）。 */
    private Path modsDir;

    public ModsPage(MainView host) {
        this.host = host;
        setCenter(build());
    }

    /* ============================ 骨架 ============================ */

    private Node build() {
        VBox main = new VBox(10);
        main.setPadding(new Insets(4, 16, 16, 6));

        VBox titles = new VBox(0,
                Ui.label("模组管理", "section-title"),
                subtitle);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.ALWAYS);

        Button pick = Ui.ghostButton("选择游戏", null, this::openPicker);
        Button refresh = Ui.ghostButton("刷新", null, this::refresh);
        HBox header = new HBox(8, titles, Ui.spacer(), status, pick, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        // 底部留 74px：浮动动作条不会盖住最后几行，列表也不会因为它出现而上下跳
        list.setPadding(new Insets(2, 6, 74, 0));
        scroller = new ScrollPane(list);
        scroller.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // 滚到哪补到哪：只给进入视口（上下各留 400px）的行下载图标
        scroller.vvalueProperty().addListener((obs, old, now) -> scheduleIconFlush());
        // 布局一变（行加进来 / 窗口拉伸）就补一次：保证第一次进来图标能自己冒出来
        scroller.heightProperty().addListener((obs, old, now) -> scheduleIconFlush());
        list.heightProperty().addListener((obs, old, now) -> scheduleIconFlush());

        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("mod-actions");
        actions.setVisible(false);
        actions.setMouseTransparent(true);
        actions.setMaxHeight(Region.USE_PREF_SIZE);
        disableButton.setOnAction(e -> toggleSelectedDisabled());
        updateButton.setOnAction(e -> openUpdateShell());
        detailsButton.setOnAction(e -> openDetails());
        actions.getChildren().addAll(selectedLabel, Ui.spacer(), disableButton, updateButton, detailsButton);

        // 动作条浮在列表上方（StackPane 叠层）：出现/收起都不占用布局，滚动条和行位置保持不动
        StackPane listStack = new StackPane(scroller, actions);
        StackPane.setAlignment(actions, Pos.BOTTOM_CENTER);
        StackPane.setMargin(actions, new Insets(0, 10, 10, 0));
        VBox.setVgrow(listStack, Priority.ALWAYS);

        main.getChildren().addAll(header, toolbar(), listStack);
        return main;
    }

    /** 顶部：所有 / 已禁用 / 可更新 + 搜索栏。 */
    private Node toolbar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("mods-toolbar");
        for (Filter value : Filter.values()) {
            Label chip = Ui.label(value.text, "filter-chip");
            chip.setUserData(value);
            chip.setOnMouseClicked(e -> {
                filter = value;
                for (Label item : filterChips) {
                    item.getStyleClass().remove("active");
                }
                chip.getStyleClass().add("active");
                applyFilter();
            });
            filterChips.add(chip);
            bar.getChildren().add(chip);
        }
        filterChips.get(0).getStyleClass().add("active");

        search.getStyleClass().add("search-field");
        search.setPromptText("搜索模组名 / 文件名 / modId");
        search.setPrefWidth(240);
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            query = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            applyFilter();
        });
        bar.getChildren().addAll(Ui.spacer(), search, countLabel);
        return bar;
    }

    /* ============================ 页面生命周期 ============================ */

    /** 每次切到这一页（MainView 只在真的换页时调）。 */
    public void onShown() {
        introPlayed = false;
        VersionEntry entry = LauncherEngine.get().selected();
        if (entry == null) {
            setEmpty("还没有选择版本", "选一个版本之后，这里会列出它的模组");
            openPicker();
            return;
        }
        load(entry);
    }

    /** 切走：下一次进来重新播淡入。 */
    public void onHidden() {
        introPlayed = false;
    }

    private void openPicker() {
        host.show(GamePicker.create(host, () -> {
            VersionEntry picked = LauncherEngine.get().selected();
            if (picked != null) {
                load(picked);
            }
        }));
    }

    private void refresh() {
        VersionEntry entry = LauncherEngine.get().selected();
        if (entry == null) {
            openPicker();
            return;
        }
        load(entry);
    }

    /* ============================ 列表 ============================ */

    private void load(VersionEntry entry) {
        int token = ++flowToken;
        modsDir = modsDir(entry);
        List<ModIdentity.LocalMod> mods = ModIdentity.scan(modsDir);
        gameVersion = entry.gameVersion == null ? "" : entry.gameVersion;
        subtitle.setText(entry.displayName + " · " + entry.loaderText() + " · "
                + mods.size() + " 个模组 · " + modsDir);

        list.getChildren().clear();
        rows.clear();
        clearSelection();
        if (mods.isEmpty()) {
            setEmpty("这个版本还没有模组",
                    "把 .jar 拖进窗口就能装进「" + entry.displayName + "」");
            return;
        }
        setStatus("正在识别模组…");

        for (int index = 0; index < mods.size(); index++) {
            Row row = buildRow(mods.get(index));
            rows.add(row);
            list.getChildren().add(row.node);
        }
        list.getChildren().add(emptyHint);
        emptyHint.setVisible(false);
        emptyHint.setManaged(false);
        applyFilter();
        if (!introPlayed) {
            playIntro();
            introPlayed = true;
        } else {
            // 刷新 / 禁用之后重新载入：整列轻轻淡入，不再重建行
            list.setOpacity(0);
            Motion.opacity(list, 1, 180, Motion.SOFT);
        }

        // 识别没跑完之前不要给「还没认出来」的行去找兜底图标：那会让几百行同时去查镜像，
        // 而且真正的官方图标地址随后就到，白跑一趟还会把行标成「已请求」再也不重试。
        identifyDone = false;
        Thread worker = new Thread(() -> {
            try {
                ModIdentity.enrich(mods, entry.gameVersion,
                        (index, remote) -> Platform.runLater(() -> applyRemote(token, index, remote)));
            } finally {
                Platform.runLater(() -> {
                    if (token == flowToken) {
                        identifyDone = true;
                        scheduleIconFlush();
                    }
                });
            }
        }, "yi-mod-identify");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyRemote(int token, int index, ModIdentity.Remote remote) {
        if (token != flowToken || index < 0 || index >= rows.size()) {
            return;
        }
        Row row = rows.get(index);
        row.remote = remote;
        row.name.setText(remote.title().isBlank() ? row.local.name() : remote.title());
        // 大部分模组把版本写在文件名里，优先从文件名取；实在没有才用接口给的
        String version = VersionFromName.parse(row.local.fileName(), gameVersion);
        if (version.isBlank()) {
            version = remote.version();
        }
        if (!version.isBlank()) {
            row.versionChip.setText(version);
        }
        // 来源为空 = 试过但没认出来（缓存里也记的是这种），只显示本地信息
        if (remote.source().isBlank()) {
            row.sourceChip.setText("");
            row.sourceChip.setVisible(false);
            row.sourceChip.setManaged(false);
        } else {
            row.sourceChip.setText(remote.source());
            row.sourceChip.getStyleClass().removeAll("source-cf", "source-mr");
            row.sourceChip.getStyleClass().add(
                    PackInstaller.CURSEFORGE.equals(remote.source()) ? "source-cf" : "source-mr");
            row.sourceChip.setVisible(true);
            row.sourceChip.setManaged(true);
        }
        row.iconUrl = remote.iconUrl();
        if (!remote.iconUrl().isBlank()) {
            scheduleIconFlush();
        }
        long identified = rows.stream().filter(item -> item.remote != null).count();
        if (identified == rows.size()) {
            setStatus("已识别 " + identified + " / " + rows.size() + " 个模组");
        } else {
            setStatus("正在识别模组… " + identified + "/" + rows.size());
        }
        applyFilter();
    }

    /**
     * 图标只给「看得见的那一段」加载：283 个模组一次性去拉 CDN 纯属浪费带宽。
     * <p>没有图标地址的行（Modrinth 没认出来的）也在这里补：先看 CurseForge 镜像有没有 PNG 缩略图。</p>
     */
    private void flushIcons() {
        if (scroller == null) {
            return;
        }
        javafx.geometry.Bounds viewport = scroller.localToScene(scroller.getBoundsInLocal());
        if (viewport == null) {
            return;   // 还没布局好，等高度变化再刷
        }
        for (Row row : rows) {
            if (row.iconRequested || !row.node.isVisible()) {
                continue;
            }
            // 识别没结束、这行也还没认出任何东西 → 先别去查兜底图（否则几百行一起冲镜像）
            if (!identifyDone && row.remote == null) {
                continue;
            }
            javafx.geometry.Bounds bounds = row.node.localToScene(row.node.getBoundsInLocal());
            boolean near = bounds == null
                    || (bounds.getMaxY() >= viewport.getMinY() - 1200
                    && bounds.getMinY() <= viewport.getMaxY() + 1200);
            if (!near) {
                continue;
            }
            row.iconRequested = true;
            loadIcon(row);
        }
    }

    /** 识别结果是一批一批来的，攒 120ms 再统一刷一次；正在等就不重复排队，免得一直不触发。 */
    private void scheduleIconFlush() {
        if (iconFlush == null) {
            iconFlush = new PauseTransition(Duration.millis(120));
            iconFlush.setOnFinished(event -> flushIcons());
        }
        if (iconFlush.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            iconFlush.playFromStart();
        }
    }

    /**
     * 先按项目自己的图标地址拉；没有地址或者拉不到（Modrinth 只给 WebP 的项目），
     * 就去 CurseForge 镜像找同一张图的 PNG；都没有就保持首字母。
     */
    private void loadIcon(Row row) {
        int token = flowToken;
        String url = row.iconUrl == null ? "" : row.iconUrl;
        if (url.isBlank()) {
            loadFallbackIcon(row, token, "");
            return;
        }
        IconCache.load(url, image -> {
            if (token == flowToken) {
                row.setIcon(image);
            }
        }, () -> loadFallbackIcon(row, token, url));
    }

    /** 去 CurseForge 镜像（免 Key 的 MCIM 通道）找 PNG 缩略图。 */
    private void loadFallbackIcon(Row row, int token, String tried) {
        String page = row.remote == null ? "" : row.remote.pageUrl();
        String title = row.remote != null && !row.remote.title().isBlank()
                ? row.remote.title() : row.local.name();
        String modId = row.local.modId();
        Thread worker = new Thread(() -> {
            String fallback = ModIdentity.iconFallback(page, title, modId);
            if (fallback.isBlank() || fallback.equals(tried)) {
                return;
            }
            Platform.runLater(() -> IconCache.load(fallback, image -> {
                if (token == flowToken) {
                    row.setIcon(image);
                }
            }));
        }, "yi-icon-fallback");
        worker.setDaemon(true);
        worker.start();
    }

    private Row buildRow(ModIdentity.LocalMod mod) {
        Label letter = Ui.label(initial(mod.name()), "mod-icon-letter");
        StackPane icon = new StackPane(letter);
        icon.getStyleClass().add("mod-icon");
        icon.setMinSize(36, 36);
        icon.setPrefSize(36, 36);
        icon.setMaxSize(36, 36);

        Label name = Ui.label(mod.name(), "mod-name");
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        // 版本优先按文件名取：绝大多数模组都把版本写进文件名，jar 元数据常常是空的
        String fileVersion = VersionFromName.parse(mod.fileName(), gameVersion);
        if (fileVersion.isBlank()) {
            fileVersion = mod.version();
        }
        String meta = mod.loader() + " · " + mod.fileName();
        Label metaLabel = Ui.label(meta, "mod-meta");
        metaLabel.setMinWidth(0);
        metaLabel.setMaxWidth(Double.MAX_VALUE);

        VBox texts = new VBox(2, name, metaLabel);
        texts.setMinWidth(0);
        texts.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(texts, Priority.ALWAYS);

        // 建行时就先把版本显示出来，不用等识别完成（识别不出来也不会再是「未知版本」）
        Label versionChip = Ui.label(fileVersion.isBlank() ? "未知版本" : fileVersion, "mod-chip");
        versionChip.setVisible(true);
        versionChip.setManaged(true);
        Label sourceChip = Ui.label("", "mod-chip");
        sourceChip.setVisible(false);
        sourceChip.setManaged(false);

        boolean disabled = mod.fileName().toLowerCase(Locale.ROOT).endsWith(".disabled");
        Label disabledChip = Ui.label("已禁用", "mod-chip");
        disabledChip.getStyleClass().add("mod-chip-disabled");
        disabledChip.setVisible(disabled);
        disabledChip.setManaged(disabled);

        HBox content = new HBox(11, icon, texts, versionChip, sourceChip, disabledChip);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMaxWidth(Double.MAX_VALUE);
        // 内边距放在内层：外层留给选中高亮，铺满整行
        content.setPadding(new Insets(9, 12, 9, 12));

        // 选中高亮是盖在行上的一层圆角块：只改透明度/微缩放做动画，不加边框、不改内边距，行不会变大
        Region highlight = new Region();
        highlight.getStyleClass().add("mod-row-hl");
        highlight.setMouseTransparent(true);
        highlight.setOpacity(0);
        // Region 默认最大尺寸是「首选尺寸」(0)，不铺满的话只会显示中间一条，所以要放开
        highlight.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        StackPane node = new StackPane(highlight, content);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("mod-row");
        node.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(node, new Tooltip(mod.fileName() + (disabled ? "（已禁用）" : "")));

        Row row = new Row(node, highlight, icon, letter, name, metaLabel, versionChip, sourceChip,
                disabledChip, mod, disabled);
        node.setOnMouseClicked(e -> toggleSelection(row));
        if (disabled) {
            node.getStyleClass().add("mod-row-disabled");
            content.setOpacity(0.62);
        }
        return row;
    }

    /* ============================ 过滤 / 选择 ============================ */

    private void applyFilter() {
        int visible = 0;
        for (Row row : rows) {
            boolean show = matches(row);
            boolean wasVisible = row.node.isVisible();
            row.node.setVisible(show);
            row.node.setManaged(show);
            if (show) {
                visible++;
                if (!wasVisible) {
                    fadeInRow(row);
                }
            }
        }
        countLabel.setText(visible + " / " + rows.size());
        boolean nothing = visible == 0 && !rows.isEmpty();
        if (nothing && !emptyHint.isVisible()) {
            emptyHint.setOpacity(0);
            Motion.opacity(emptyHint, 1, 160, Motion.SOFT);
        }
        emptyHint.setVisible(nothing);
        emptyHint.setManaged(nothing);
        if (nothing) {
            emptyHint.setText(filter == Filter.UPDATABLE
                    ? "更新检查还没接上，等「模组资源」页做好后一起来。"
                    : filter == Filter.DISABLED
                    ? "这个版本还没有被禁用的模组。"
                    : "没有匹配的模组，换个关键词试试。");
        }
        // 过滤后可见集合变了：把新出现的行补上图标
        scheduleIconFlush();
    }

    /** 过滤/搜索让某一行重新出现时，淡入 + 轻微上移，而不是突然跳出来。 */
    private void fadeInRow(Row row) {
        double target = row.disabled ? 0.62 : 1;
        if (row.node.getOpacity() >= target - 0.01) {
            return;
        }
        row.node.setOpacity(0);
        row.node.setTranslateY(6);
        Motion.opacity(row.node, target, 140, Motion.SOFT);
        Motion.translate(row.node, 0, 0, 160, Motion.SOFT);
    }

    private boolean matches(Row row) {
        if (filter == Filter.DISABLED && !row.disabled) {
            return false;
        }
        if (filter == Filter.UPDATABLE && !row.updateAvailable) {
            return false;
        }
        if (query.isEmpty()) {
            return true;
        }
        String text = (row.name.getText() + " " + row.local.name() + " "
                + row.local.fileName() + " " + row.local.modId()).toLowerCase(Locale.ROOT);
        return text.contains(query);
    }

    private void toggleSelection(Row row) {
        if (!row.node.isVisible()) {
            return;
        }
        setSelected(row, !row.selected, true);
        refreshActions();
    }

    /** 选中 / 取消选中：高亮层淡入淡出 + 一点点放大，文字不动、行尺寸也不变。 */
    private void setSelected(Row row, boolean selected, boolean animate) {
        row.selected = selected;
        if (!animate) {
            row.highlight.setOpacity(selected ? 1 : 0);
            return;
        }
        if (selected) {
            row.highlight.setScaleX(0.985);
            row.highlight.setScaleY(0.985);
            Motion.scale(row.highlight, 1, 190, Motion.SOFT);
            Motion.opacity(row.highlight, 1, 170, Motion.SOFT);
        } else {
            Motion.scale(row.highlight, 0.99, 140, Motion.EASE_IN);
            Motion.opacity(row.highlight, 0, 140, Motion.EASE_IN);
        }
    }

    private void clearSelection() {
        for (Row row : rows) {
            if (row.selected) {
                setSelected(row, false, true);
            }
        }
        refreshActions();
    }

    private List<Row> selectedRows() {
        List<Row> picked = new ArrayList<>();
        for (Row row : rows) {
            if (row.selected) {
                picked.add(row);
            }
        }
        return picked;
    }

    private void refreshActions() {
        List<Row> picked = selectedRows();
        boolean any = !picked.isEmpty();
        if (any) {
            selectedLabel.setText("已选 " + picked.size() + " 个模组");
            boolean allDisabled = picked.stream().allMatch(row -> row.disabled);
            disableButton.setText(allDisabled ? "解除禁用" : "禁用");
        }
        showActions(any);
    }

    /** 底部动作条：出现时从下方浮上来，收起时往下沉再隐藏。 */
    private void showActions(boolean show) {
        if (show) {
            if (actions.isVisible() && actions.getOpacity() > 0.99 && actions.getTranslateY() == 0) {
                actions.setMouseTransparent(false);
                return;
            }
            actions.setVisible(true);
            actions.setMouseTransparent(false);
            actions.setOpacity(0);
            actions.setTranslateY(10);
            Motion.opacity(actions, 1, 180, Motion.SOFT);
            Motion.translate(actions, 0, 0, 220, Motion.SOFT);
            return;
        }
        if (!actions.isVisible()) {
            return;
        }
        actions.setMouseTransparent(true);
        Motion.opacity(actions, 0, 130, Motion.EASE_IN);
        Motion.translate(actions, 0, 8, 130, Motion.EASE_IN);
        PauseTransition wait = new PauseTransition(
                Duration.millis(140 / Math.max(0.2, Motion.speed())));
        wait.setOnFinished(event -> {
            if (!selectedRows().isEmpty()) {
                return;
            }
            // 只隐藏不取消 managed：布局一直是满的，列表和滚动条不会跟着跳
            actions.setVisible(false);
            actions.setTranslateY(0);
            actions.setOpacity(1);
        });
        wait.play();
    }

    /* ============================ 禁用 / 解除禁用 ============================ */

    private void toggleSelectedDisabled() {
        List<Row> picked = selectedRows();
        if (picked.isEmpty() || modsDir == null) {
            return;
        }
        boolean allDisabled = picked.stream().allMatch(row -> row.disabled);
        int done = 0;
        List<String> failed = new ArrayList<>();
        for (Row row : picked) {
            try {
                Path file = row.local.jar();
                if (allDisabled) {
                    String name = file.getFileName().toString();
                    String trimmed = name.substring(0, name.length() - ".disabled".length());
                    Files.move(file, file.resolveSibling(trimmed), StandardCopyOption.REPLACE_EXISTING);
                } else if (!row.disabled) {
                    Path target = file.resolveSibling(file.getFileName() + ".disabled");
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    continue;
                }
                done++;
            } catch (Exception error) {
                failed.add(row.local.fileName());
            }
        }
        VersionEntry entry = LauncherEngine.get().selected();
        if (entry != null) {
            load(entry);
        }
        String note = (allDisabled ? "已解除禁用 " : "已禁用 ") + done + " 个模组（重启游戏后生效）";
        if (!failed.isEmpty()) {
            note = note + "；" + failed.size() + " 个失败：" + String.join("、", failed);
        }
        setStatus(note);
    }

    /* ============================ 更新（壳子） ============================ */

    private void openUpdateShell() {
        VBox card = new VBox(10);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox titles = new VBox(2,
                Ui.label("更新模组", "dialog-title"),
                Ui.label("已选 " + selectedRows().size() + " 个模组", "dialog-hint"));
        Button close = Ui.ghostButton("知道了", null, host::hide);
        HBox head = new HBox(10, titles, Ui.spacer(), close);
        head.setAlignment(Pos.CENTER_LEFT);

        Label hint = Ui.label("更新功能还在做：等「模组资源」页上线后，这里会显示新版本、能一键替换。", "dialog-hint");
        hint.setWrapText(true);
        card.getChildren().addAll(head, hint);
        host.show(card);
    }

    /* ============================ 查看详情 ============================ */

    private void openDetails() {
        List<Row> picked = selectedRows();
        if (picked.isEmpty()) {
            return;
        }
        Row row = picked.get(0);
        String title = row.remote != null && !row.remote.title().isBlank()
                ? row.remote.title() : row.local.name();
        String keyword = searchKeyword(row);

        VBox card = new VBox(11);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox titles = new VBox(2,
                Ui.label("查看详情 · " + title, "dialog-title"),
                Ui.label(picked.size() > 1 ? "以第一个选中的模组为准" : "选一个网站，会自动搜这个模组",
                        "dialog-hint"));
        Button close = Ui.ghostButton("关闭", null, host::hide);
        HBox head = new HBox(10, titles, Ui.spacer(), close);
        head.setAlignment(Pos.CENTER_LEFT);

        Label keywordLabel = Ui.label("搜索词：" + keyword, "settings-hint");
        keywordLabel.setWrapText(true);

        HBox sites = new HBox(8);
        sites.setAlignment(Pos.CENTER_LEFT);
        for (Site site : Site.values()) {
            Button button = Ui.primaryButton(site.text, null, () -> {
                openUrl(site.search + encode(keyword));
                host.hide();
            });
            button.getStyleClass().add("mod-site-btn");
            sites.getChildren().add(button);
        }

        card.getChildren().addAll(head, keywordLabel, sites);
        host.show(card);
    }

    /** 搜的时候优先用接口给的英文 slug，其次 jar 里的 modId，最后才用文件名。 */
    private String searchKeyword(Row row) {
        if (row.remote != null) {
            String url = row.remote.pageUrl();
            if (url != null && !url.isBlank()) {
                String slug = url.replaceAll("/+$", "");
                int slash = slug.lastIndexOf('/');
                String tail = slash >= 0 ? slug.substring(slash + 1) : slug;
                if (!tail.isBlank()) {
                    return tail;
                }
            }
        }
        if (row.local.modId() != null && !row.local.modId().isBlank()) {
            return row.local.modId();
        }
        return row.local.name();
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 用系统默认浏览器打开；JNA 不行就退到 cmd start。 */
    private static void openUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String target = url.trim();
        try {
            com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecute(null, "open", target, null, null, 1);
            return;
        } catch (Throwable ignored) {
            // 退到 cmd
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", target).start();
        } catch (Exception ignored) {
        }
    }

    /* ============================ 收尾 ============================ */

    /** 逐行从下方淡入：位移 14px、间隔 45ms，一行接一行（就是「逐列」的效果）。 */
    private void playIntro() {
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            row.node.setOpacity(0);
            row.node.setTranslateY(14);
            PauseTransition delay = new PauseTransition(
                    Duration.millis(Math.min(600, index * 45) / Math.max(0.2, Motion.speed())));
            delay.setOnFinished(event -> {
                Motion.translate(row.node, 0, 0, 300, Motion.SOFT);
                Motion.opacity(row.node, 1, 260, Motion.SOFT);
            });
            delay.play();
        }
    }

    private void setEmpty(String title, String hint) {
        list.getChildren().clear();
        rows.clear();
        clearSelection();
        countLabel.setText("");
        VBox empty = new VBox(5, Ui.label(title, "version-name"), Ui.label(hint, "version-meta"));
        empty.setPadding(new Insets(20, 6, 20, 6));
        list.getChildren().add(empty);
        setStatus("");
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private static Path modsDir(VersionEntry entry) {
        LauncherEngine engine = LauncherEngine.get();
        return entry != null && entry.isolation
                ? entry.dir.resolve("mods")
                : engine.root().resolve("mods");
    }

    private static String initial(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase();
    }

    /** 一行模组：节点 + 本地信息 + 识别结果 + 选中状态。 */
    private static final class Row {
        final StackPane node;
        final Region highlight;
        final StackPane iconBox;
        final Label letter;
        final Label name;
        final Label meta;
        final Label versionChip;
        final Label sourceChip;
        final Label disabledChip;
        final ModIdentity.LocalMod local;
        final boolean disabled;
        ModIdentity.Remote remote;
        /** 认出图标地址后先记下来，滚到可见区才真的去下载。 */
        String iconUrl;
        boolean iconRequested;
        boolean selected;
        /** 更新检查还没做，先留个占位，等「模组资源」接上。 */
        boolean updateAvailable;

        Row(StackPane node, Region highlight, StackPane iconBox, Label letter, Label name, Label meta,
            Label versionChip, Label sourceChip, Label disabledChip,
            ModIdentity.LocalMod local, boolean disabled) {
            this.node = node;
            this.highlight = highlight;
            this.iconBox = iconBox;
            this.letter = letter;
            this.name = name;
            this.meta = meta;
            this.versionChip = versionChip;
            this.sourceChip = sourceChip;
            this.disabledChip = disabledChip;
            this.local = local;
            this.disabled = disabled;
        }
        void setIcon(Image image) {
            ImageView view = new ImageView(image);
            view.setFitWidth(36);
            view.setFitHeight(36);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            Rectangle clip = new Rectangle(36, 36);
            clip.setArcWidth(20);
            clip.setArcHeight(20);
            view.setClip(clip);
            iconBox.getChildren().setAll(view);
        }
    }
}
