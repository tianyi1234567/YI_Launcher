package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.Accents;
import com.yilauncher.yi_launcher.core.JavaRuntimes;
import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.MemoryRecommendation;
import com.yilauncher.yi_launcher.core.VersionEntry;
import com.yilauncher.yi_launcher.core.VersionGroup;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import javafx.stage.DirectoryChooser;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 版本管理页：左侧真实扫描出的版本列表（筛选 / 搜索）+ 右侧版本详情与运行设置。
 * <p>版本名可重命名，列表图标与详情卡用同一个随机初始配色，Java 运行时下拉快选。</p>
 */
public final class VersionsPage extends BorderPane {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 列表栏展开 / 收起的宽度：收起时也留够宽，别让滚动条把行挤得太窄。 */
    private static final double LIST_EXPANDED = 268;
    private static final double LIST_COMPACT = 88;

    private final Ui.OverlayHost overlay;
    private final LauncherEngine engine = LauncherEngine.get();

    private final VBox listBox = new VBox(6);
    private final VBox detailBox = new VBox(14);
    private final Label summary = Ui.label("正在扫描 .minecraft/versions…", "section-sub");
    private final Label statusLabel = Ui.label("", "status-line");
    private final List<Label> filterChips = new ArrayList<>();
    private final Label memoryLabel = Ui.label("", "field-value");

    private VBox detailCard;
    private Button launchButton;
    private HBox filterRow;
    private final VBox groupList = new VBox(5);
    private String filter = "全部";
    private String keyword = "";
    private VBox rail;
    private ScrollPane groupScroll;
    private ScrollPane listScroll;
    // 一进来是收起状态：鼠标移上去才展开，把宽度让给详情
    private boolean railExpanded;
    private boolean listExpanded;
    /** 栏位宽度动画令牌：旧的收尾任务不能覆盖新动画。 */
    private int widthToken;

    /** 紧凑行沿用收起前的高度：否则换行后每行变矮，整列会往上缩一截。 */
    private double compactRowHeight;
    private double compactGroupHeight;

    /** 换紧凑行的令牌：只认最后一次收起，重复触发/反向操作都会让旧任务失效。 */
    private int railSwapToken;
    private String lastDetailKey;
    public VersionsPage(Ui.OverlayHost overlay) {
        this.overlay = overlay;
        setTop(buildToolbar());
        setCenter(buildBody());
        rebuildGroupRail();
        rebuildList();
        showDetail(engine.selected());

        engine.scanAsync(list -> Platform.runLater(() -> {
            rebuildGroupRail();
            if (filterRow != null) {
                rebuildFilterChips(filterRow);
            }
            rebuildList();
            showDetail(engine.selected());
            applyCompactDevMode();
            maybeOpenDevDialog();
        }));
    }

    /* ============================ 顶部工具条 ============================ */

    private Node buildToolbar() {
        VBox titles = new VBox(0, Ui.label("版本管理", "section-title"), summary);

        TextField search = new TextField();
        search.setPromptText("搜索版本…");
        search.getStyleClass().add("search-field");
        search.setPrefWidth(176);
        search.setMinHeight(26);
        search.setFocusTraversable(false);
        search.textProperty().addListener((obs, old, value) -> {
            keyword = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            rebuildList();
        });

        HBox searchBox = new HBox(4, Icons.icon(Icons.SEARCH, 14, "ghost-icon"), search);
        searchBox.getStyleClass().add("search-box");
        searchBox.setAlignment(Pos.CENTER_LEFT);

        Button add = Ui.primaryButton("添加版本", Icons.PLUS, this::openAddVersionDialog);
        add.setMinHeight(34);
        add.setPrefHeight(34);

        HBox header = new HBox(8, titles, Ui.spacer(), searchBox, add);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox toolbar = new VBox(10, header, buildFilters());
        toolbar.setPadding(new Insets(4, 16, 12, 6));
        return toolbar;
    }

    private HBox buildFilters() {
        filterRow = new HBox(6);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        rebuildFilterChips(filterRow);
        return filterRow;
    }

    /** 加载器分类只统计当前分组里的版本：先选分组，再看分类。 */
    private void rebuildFilterChips(HBox row) {
        row.getChildren().clear();
        filterChips.clear();

        List<VersionEntry> groupVersions = engine.groupVersions();
        Label all = Ui.label("全部 " + groupVersions.size(), "filter-chip");
        if ("全部".equals(filter)) {
            all.getStyleClass().add("active");
        }
        all.setOnMouseClicked(e -> {
            filter = "全部";
            Ui.selectOne(filterChips, all);
            rebuildList();
        });
        filterChips.add(all);
        row.getChildren().add(all);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (VersionEntry version : groupVersions) {
            counts.merge(version.loader, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Label chip = Ui.label(entry.getKey() + " " + entry.getValue(), "filter-chip");
            if (entry.getKey().equals(filter)) {
                chip.getStyleClass().add("active");
            }
            Motion.pressFade(chip);
            chip.setOnMouseClicked(e -> {
                filter = entry.getKey();
                Ui.selectOne(filterChips, chip);
                rebuildList();
            });
            filterChips.add(chip);
            row.getChildren().add(chip);
        }

        row.getChildren().add(Ui.spacer());
        row.getChildren().add(Ui.label("按版本发布时间 ↓", "filter-chip"));
    }

    /* ============================ 主体 ============================ */

    private Node buildBody() {
        ScrollPane groups = new ScrollPane(groupList);
        groups.getStyleClass().addAll("list-scroll", "group-scroll");
        groups.setFitToWidth(true);
        groups.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        groups.setPrefWidth(150);
        groups.setMinWidth(150);
        groups.setMaxWidth(150);

        VBox rail = new VBox(6, Ui.label("分组", "side-caption"), groups);
        rail.getStyleClass().add("group-rail");
        rail.setPadding(new Insets(12, 10, 12, 10));
        VBox.setVgrow(groups, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.getStyleClass().addAll("list-scroll", "version-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // 竖向滚动条固定常驻：宽度不变，收起 / 展开时行宽估算才准，动画结束也不会再「挪一下」
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);

        this.rail = rail;
        this.groupScroll = groups;
        this.listScroll = scroll;
        applyWidths(false);
        rail.hoverProperty().addListener((obs, was, now) -> setRailExpanded(now));
        scroll.hoverProperty().addListener((obs, was, now) -> setListExpanded(now));
        detailCard = new VBox(detailBox);
        detailCard.getStyleClass().add("detail-card");
        detailCard.setPadding(new Insets(18, 18, 18, 18));
        HBox.setHgrow(detailCard, Priority.ALWAYS);
        VBox.setVgrow(detailBox, Priority.ALWAYS);

        HBox body = new HBox(10, rail, scroll, detailCard);
        body.setPadding(new Insets(0, 16, 16, 6));
        return body;
    }

    /** 开发自检：-Dyi.dialog=memory / isolation 直接打开对应弹窗，方便截图核对。 */
    /** 开发自检：-Dyi.compact=1 直接以收起状态启动，方便截图核对。 */
    private void applyCompactDevMode() {
        if (System.getProperty("yi.compact") == null) {
            return;
        }
        setRailExpanded(false);
        setListExpanded(false);
    }

    private void maybeOpenDevDialog() {
        String which = System.getProperty("yi.dialog");
        if (devDialogShown || which == null) {
            return;
        }
        devDialogShown = true;
        if ("isolation".equals(which)) {
            confirmDisableIsolation(() -> { }, null);
        } else if ("memory".equals(which) && engine.selected() != null) {
            openMemoryDialog(engine.selected());
        }
    }

    /**
     * 分组/版本栏位宽度：收起 62 / 78，展开 172 / 268。
     * <p>列表内容始终保持 fitToWidth，行宽 = 视口宽：卡片和栏位永远是同一个宽度，
     * 中途不会被裁成半张卡。行内文字靠省略号收窄，再淡出。</p>
     */
    private void applyWidths(boolean animate) {
        double railWidth = railExpanded ? 172 : 62;
        double listWidth = listExpanded ? LIST_EXPANDED : LIST_COMPACT;
        if (animate) {
            final int token = ++widthToken;
            if (listScroll != null) {
                listScroll.setFitToWidth(true);
            }
            Motion.width(rail, railWidth, 300, railExpanded ? Motion.SPRING : Motion.SOFT);
            Motion.width(groupScroll, railWidth - 20, 300, railExpanded ? Motion.SPRING : Motion.SOFT);
            Motion.width(listScroll, listWidth, 300, listExpanded ? Motion.SPRING : Motion.SOFT);
            return;
        }

        if (rail != null) {
            rail.setPrefWidth(railWidth);
            rail.setMinWidth(railWidth);
            rail.setMaxWidth(railWidth);
        }
        if (groupScroll != null) {
            double inner = railWidth - 20;
            groupScroll.setPrefWidth(inner);
            groupScroll.setMinWidth(inner);
            groupScroll.setMaxWidth(inner);
        }
        if (listScroll != null) {
            listScroll.setPrefWidth(listWidth);
            listScroll.setMinWidth(listWidth);
            listScroll.setMaxWidth(listWidth);
        }
    }

    /** 行里的文字统一用省略号收窄：栏位变窄时是「名字…」而不是被裁掉半截。 */
    private Label ellipsis(Label label) {
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        return label;
    }

    /**
     * 展开/收起次级栏位。
     * <p>收起：文字先直接淡出（不留省略号），徽标一边淡一边挪到紧凑行的居中位置，
     * 宽度动画结束才换成紧凑行——所以换行那一帧看不出变化。</p>
     */
    private void setRailExpanded(boolean expanded) {
        if (railExpanded == expanded) {
            return;
        }
        railExpanded = expanded;
        final int token = ++railSwapToken;
        if (expanded) {
            rebuildGroupRail();
            fadeTexts(groupList, 1, 150);
            applyWidths(true);
            return;
        }
        compactGroupHeight = firstHeight(groupList);
        fadeTexts(groupList, 0, 120);
        applyWidths(true);
        swapGroupToCompact(token);
    }

    private void setListExpanded(boolean expanded) {
        if (listExpanded == expanded) {
            return;
        }
        listExpanded = expanded;
        if (expanded) {
            Bounds before = badgeSceneBounds();
            rebuildList();
            glideBadges(expandedStartOffset(before), 0);
            fadeTexts(listBox, 1, 150);
        } else {
            compactRowHeight = firstHeight(listBox);
            fadeTexts(listBox, 0, 120);
            glideBadges(badgeTranslate(), badgeGlide(compactRowWidth()));
            settleBadgesLater();
        }
        applyWidths(true);
    }

    /** 文字淡完后把分组换成紧凑行，首字淡入。只在"真的收起"时排一次。 */
    private void swapGroupToCompact(int token) {
        PauseTransition swap = new PauseTransition(
                Duration.millis(150 / Math.max(0.2, Motion.speed())));
        swap.setOnFinished(event -> {
            if (token != railSwapToken || railExpanded) {
                return;
            }
            rebuildGroupRail();
            for (Node node : groupList.lookupAll(".group-initial")) {
                node.setOpacity(0);
                Motion.opacity(node, 1, 160, Motion.SOFT);
            }
        });
        swap.play();
    }

    /** 当前列表里徽标的位移（每行一样，取第一枚）。 */
    private double badgeTranslate() {
        Node badge = listBox == null ? null : listBox.lookup(".loader-badge");
        return badge == null ? 0 : badge.getTranslateX();
    }

    /** 徽标（第一枚）在场景里的位置；列表空时返回 null。 */
    private Bounds badgeSceneBounds() {
        Node badge = listBox == null ? null : listBox.lookup(".loader-badge");
        return badge == null ? null : badge.localToScene(badge.getLayoutBounds());
    }

    /** 重建行之后：新徽标要挪多少才能接上刚才那个屏幕位置。 */
    private double expandedStartOffset(Bounds before) {
        Node badge = listBox == null ? null : listBox.lookup(".loader-badge");
        if (before == null || badge == null) {
            return 0;
        }
        listBox.applyCss();
        listBox.layout();
        return before.getMinX() - badge.localToScene(badge.getLayoutBounds()).getMinX();
    }

    /** 把徽标从 from 滑到 to（0 = 展开时靠左的位置，正值 = 紧凑行的居中位）。 */
    private void glideBadges(double from, double to) {
        if (listBox == null || Math.abs(to - from) < 0.05) {
            return;
        }
        for (Node badge : listBox.lookupAll(".loader-badge")) {
            badge.setTranslateX(from);
            Motion.translate(badge, to, 0, 300, Motion.SOFT);
        }
    }

    /**
     * 收起动画落定后，按「实际行宽」再校准一次徽标位置。
     * <p>滚动条、内边距都会让行宽和估算差一两像素，这里按量出来的行宽补一次短补间
     * （已经居中就完全不动作）。{@code badgeGlide} 给的是「translateX 该等于多少」，
     * 不是增量——所以直接补间到这个值。</p>
     */
    private void settleBadges() {
        if (listBox == null || listBox.getChildren().isEmpty()) {
            return;
        }
        double want = badgeGlide(currentRowWidth());
        for (Node badge : listBox.lookupAll(".loader-badge")) {
            if (Math.abs(want - badge.getTranslateX()) < 0.4) {
                continue;
            }
            Motion.translate(badge, want, 0, 140, Motion.SOFT);
        }
    }

    /** 宽度动画结束后校准一次（只在校正"真的收起了"时）。 */
    private void settleBadgesLater() {
        PauseTransition settle = new PauseTransition(
                Duration.millis(330 / Math.max(0.2, Motion.speed())));
        settle.setOnFinished(event -> {
            if (!listExpanded) {
                settleBadges();
            }
        });
        settle.play();
    }

    /** 收起前的行高：紧凑行要沿用这个高度，换行后整列才不会往上缩。 */
    private double firstHeight(javafx.scene.layout.Pane box) {
        if (box == null || box.getChildren().isEmpty()) {
            return 0;
        }
        return box.getChildren().get(0).getLayoutBounds().getHeight();
    }

    /** 把紧凑行的高度锁成展开时的高度。 */
    private void applyCompactHeight(Region node, double height) {
        if (node == null || height <= 0) {
            return;
        }
        node.setMinHeight(height);
        node.setPrefHeight(height);
        node.setMaxHeight(height);
    }

    /**
     * 徽标要滑多远：目标行宽下「行内居中」与它现在 x 的差。
     * <p>现在的 x 是量出来的（含行内 padding / 边框），不写死数字，改样式也不会偏。</p>
     */
    private double badgeGlide(double rowWidth) {
        Node badge = listBox == null ? null : listBox.lookup(".loader-badge");
        if (badge == null) {
            return 0;
        }
        double badgeWidth = badge.getLayoutBounds().getWidth();
        double xInRow = badge.getBoundsInParent().getMinX() - badge.getTranslateX();
        return (rowWidth - badgeWidth) / 2 - xInRow;
    }

    /** 行当前实际宽度（已经扣掉滚动条与内边距）。 */
    private double currentRowWidth() {
        if (listBox == null || listBox.getChildren().isEmpty()) {
            return listExpanded ? LIST_EXPANDED : LIST_COMPACT;
        }
        return listBox.getChildren().get(0).getLayoutBounds().getWidth();
    }

    /** 视口会窄多少，行宽就跟着窄多少：用当前行宽推算收起后的行宽。 */
    private double compactRowWidth() {
        if (listScroll == null) {
            return LIST_COMPACT;
        }
        double shrink = Math.max(0, listScroll.getWidth() - LIST_COMPACT);
        return Math.max(30, currentRowWidth() - shrink);
    }

    /** 行里所有文字统一淡入/淡出；淡入时先归零，避免"啪"地出现。 */
    private void fadeTexts(javafx.scene.Parent box, double target, int ms) {
        if (box == null) {
            return;
        }
        for (String selector : new String[]{".version-name", ".version-meta", ".version-tag",
                ".group-name", ".group-meta"}) {
            for (Node node : box.lookupAll(selector)) {
                if (target >= 1) {
                    node.setOpacity(0);
                }
                Motion.opacity(node, target, ms, Motion.SOFT);
            }
        }
    }

    /** 外部装完整合包/模组后刷新列表与详情。 */
    public void reload() {
        rebuildGroupRail();
        rebuildList();
        if (engine.selected() != null) {
            showDetail(engine.selected());
        }
    }

    /** 次级栏位：分组列表，点哪个就切到哪个分组；鼠标不在时只显示名字首字。 */
    private void rebuildGroupRail() {
        groupList.getChildren().clear();
        VersionGroup current = engine.selectedGroup();
        for (VersionGroup group : engine.groups()) {
            VBox item;
            if (railExpanded) {
                item = new VBox(2,
                        ellipsis(Ui.label(group.name, "group-name")),
                        ellipsis(Ui.label(countInGroup(group.id) + " 个版本 · "
                                + (group.isolation ? "隔离" : "共用"), "group-meta")));
            } else {
                String initial = group.name.isBlank() ? "?" : group.name.substring(0, 1);
                item = new VBox(Ui.label(initial, "group-initial"));
                item.setAlignment(Pos.CENTER);
                item.getStyleClass().add("compact");
                applyCompactHeight(item, compactGroupHeight);
                javafx.scene.control.Tooltip.install(item,
                        new javafx.scene.control.Tooltip(group.name));
            }
            decorateGroupItem(item, group, current);
            groupList.getChildren().add(item);
        }
        VBox addItem;
        if (railExpanded) {
            addItem = new VBox(2, ellipsis(Ui.label("＋ 添加分组", "group-name")),
                    ellipsis(Ui.label("导入其他文件夹", "group-meta")));
        } else {
            addItem = new VBox(Ui.label("＋", "group-initial"));
            addItem.setAlignment(Pos.CENTER);
            addItem.getStyleClass().add("compact");
            applyCompactHeight(addItem, compactGroupHeight);
        }
        addItem.getStyleClass().addAll("group-item", "group-add");
        addItem.setMaxWidth(Double.MAX_VALUE);
        addItem.setOnMouseClicked(e -> openAddVersionDialog());
        Motion.pressFade(addItem);
        groupList.getChildren().add(addItem);
    }

    private void decorateGroupItem(VBox item, VersionGroup group, VersionGroup current) {
        item.getStyleClass().add("group-item");
        item.setMaxWidth(Double.MAX_VALUE);
        item.setUserData(group.id);
        if (current != null && current.id.equals(group.id)) {
            item.getStyleClass().add("active");
        }
        item.setOnMouseClicked(e -> switchGroup(group));
        Motion.pressFade(item);
    }

    /** 切换分组：清掉加载器筛选，列表换成这个分组里的版本。 */
    private void switchGroup(VersionGroup group) {
        engine.selectGroup(group);
        filter = "全部";
        rebuildGroupRail();
        if (filterRow != null) {
            rebuildFilterChips(filterRow);
        }
        VersionEntry first = firstInGroup(group.id);
        if (first != null) {
            engine.select(first);
            rebuildList();
            showDetail(first);
        } else {
            rebuildList();
            showDetail(null);
        }
    }

    /** 外部（启动页的「版本设置」）跳过来时，刷新到这个版本的详情。 */
    public void focusSelected() {
        VersionEntry entry = engine.selected();
        if (entry != null) {
            VersionGroup group = engine.group(entry.groupId);
            if (group != null) {
                engine.selectGroup(group);
            }
        }
        filter = "全部";
        rebuildGroupRail();
        if (filterRow != null) {
            rebuildFilterChips(filterRow);
        }
        rebuildList();
        showDetail(entry);
    }

    private int countInGroup(String groupId) {
        int count = 0;
        for (VersionEntry version : engine.versions()) {
            if (version.groupId.equals(groupId)) {
                count++;
            }
        }
        return count;
    }

    private VersionEntry firstInGroup(String groupId) {
        for (VersionEntry version : engine.versions()) {
            if (version.groupId.equals(groupId)) {
                return version;
            }
        }
        return null;
    }

    private void rebuildList() {
        listBox.getChildren().clear();
        List<VersionEntry> matched = new ArrayList<>();
        String selectedKey = engine.selected() == null ? "" : engine.selected().configKey();
        VersionGroup group = engine.selectedGroup();
        for (VersionEntry version : engine.groupVersions()) {
            if (!"全部".equals(filter) && !filter.equals(version.loader)) {
                continue;
            }
            String haystack = (version.displayName + " " + version.id + " " + version.loaderText())
                    .toLowerCase(Locale.ROOT);
            if (!keyword.isBlank() && !haystack.contains(keyword)) {
                continue;
            }
            matched.add(version);
        }

        if (matched.isEmpty()) {
            boolean groupEmpty = group == null || countInGroup(group.id) == 0;
            VBox empty = new VBox(4,
                    Ui.label(groupEmpty ? "这个分组里还没有版本" : "没有匹配的版本", "version-name"),
                    Ui.label(groupEmpty
                            ? "点左下角「添加分组」把别的文件夹导进来"
                            : "换个关键词或筛选条件试试", "version-meta"));
            empty.setPadding(new Insets(18, 6, 18, 6));
            listBox.getChildren().add(empty);
        } else {
            for (VersionEntry version : matched) {
                listBox.getChildren().add(versionRow(version, version.configKey().equals(selectedKey)));
            }
        }

        String groupName = group == null ? "未选择分组" : group.name;
        summary.setText(String.format(Locale.ROOT, "%s · %d 个版本 · 共 %d 个分组 · %s",
                groupName, matched.size(), engine.groups().size(), engine.root()));
    }

    private Node versionRow(VersionEntry version, boolean active) {
        Label badge = new Label(shortLoader(version.loader));
        badge.getStyleClass().add("loader-badge");
        badge.setStyle("-fx-background-color: linear-gradient(to bottom right, "
                + version.accent + ", " + Accents.shade(version.accent, 0.78) + ");");

        HBox row;
        if (listExpanded) {
            String meta = version.loaderText() + " · " + dateOf(version)
                    + (VersionGroup.DEFAULT_ID.equals(version.groupId) ? "" : " · " + version.groupName);
            Label name = ellipsis(Ui.label(version.displayName, "version-name"));
            Label metaLabel = ellipsis(Ui.label(meta, "version-meta"));
            VBox texts = new VBox(1, name, metaLabel);
            texts.setMinWidth(0);
            texts.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(texts, Priority.ALWAYS);
            Label tag = Ui.label(version.modsCount > 0 ? version.modsCount + " 模组" : "无模组", "version-tag");
            tag.setMinWidth(0);
            tag.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
            row = new HBox(10, badge, texts, tag);
            row.setAlignment(Pos.CENTER_LEFT);
        } else {
            row = new HBox(badge);
            row.setAlignment(Pos.CENTER);
            row.getStyleClass().add("compact");
            applyCompactHeight(row, compactRowHeight);
            javafx.scene.control.Tooltip.install(row,
                    new javafx.scene.control.Tooltip(version.displayName));
        }
        row.getStyleClass().add("version-row");
        row.setMaxWidth(Double.MAX_VALUE);
        if (active) {
            // 选中反馈：整行换成这个版本自己的配色（每个版本 accent 不同）
            row.getStyleClass().add("active");
            row.setStyle("-fx-background-color: linear-gradient(to right, "
                    + Accents.rgba(version.accent, 0.34) + ", " + Accents.rgba(version.accent, 0.16) + ");"
                    + "-fx-border-color: " + Accents.rgba(version.accent, 0.58) + ";");
        }
        row.setOnMouseClicked(e -> {
            engine.select(version);
            rebuildList();
            showDetail(version);
        });
        return row;
    }

    private static String shortLoader(String loader) {
        return switch (loader) {
            case "Fabric" -> "Fa";
            case "Forge" -> "Fo";
            case "NeoForge" -> "N";
            case "OptiFine" -> "O";
            case "Quilt" -> "Q";
            default -> "原";
        };
    }

    private static String dateOf(VersionEntry version) {
        if (version.releaseTime <= 0) {
            return "未知时间";
        }
        return DATE.format(Instant.ofEpochMilli(version.releaseTime).atZone(ZoneId.systemDefault()));
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        double mb = bytes / 1024.0 / 1024.0;
        if (mb < 1) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024) : String.format(Locale.ROOT, "%.0f MB", mb);
    }

    /* ============================ 详情面板 ============================ */

    private void showDetail(VersionEntry version) {
        statusLabel.setText("");
        if (version == null) {
            detailBox.getChildren().setAll(emptyDetail());
            return;
        }
        memoryLabel.setText(memoryText(version.effectiveMemory()));

        VBox body = new VBox(14,
                pathRow(version),
                divider(),
                Ui.label("运行设置", "card-title"),
                memoryRow(version),
                javaRow(version),
                windowRow(version),
                isolationRow(version),
                quickFoldersRow(version),
                divider(),
                footer(version));
        body.setPadding(new Insets(2, 6, 4, 0));

        ScrollPane scroller = new ScrollPane(body);
        scroller.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        VBox header = new VBox(10, detailHeader(version), statusLabel);
        detailBox.getChildren().setAll(header, scroller);
        applyAccent(version);

        String key = version.configKey();
        if (!key.equals(lastDetailKey)) {
            lastDetailKey = key;
            javafx.application.Platform.runLater(() ->
                    Motion.stagger(new ArrayList<>(body.getChildren()), 24, 8));
        }
    }

    /** 快捷目录：Mod（原版不显示）/ 存档 / 截图 / 光影（有 shaderpacks 才显示）。 */
    private Node quickFoldersRow(VersionEntry version) {
        java.nio.file.Path root = version.root(engine.root());
        boolean isolated = version.isolation;
        javafx.scene.layout.FlowPane row = settingRow("快捷目录");

        // 只要不是纯原版，或者目录里已经有模组，就给这个按钮（整合包的 json 没写加载器名也不能漏）
        java.nio.file.Path modsDir = isolated ? version.dir.resolve("mods") : root.resolve("mods");
        boolean hasMods = version.modsCount > 0
                || java.nio.file.Files.isDirectory(modsDir)
                || !"原版".equals(version.loader);
        if (hasMods) {
            row.getChildren().add(folderButton("Mod 文件夹", modsDir));
        }
        row.getChildren().add(folderButton("存档文件夹",
                isolated ? version.dir.resolve("saves") : root.resolve("saves")));
        row.getChildren().add(folderButton("截图文件夹", root.resolve("screenshots")));

        java.nio.file.Path versionShaders = version.dir.resolve("shaderpacks");
        java.nio.file.Path rootShaders = root.resolve("shaderpacks");
        java.nio.file.Path shaders = java.nio.file.Files.isDirectory(versionShaders) ? versionShaders : rootShaders;
        if (java.nio.file.Files.isDirectory(shaders)) {
            row.getChildren().add(folderButton("光影文件夹", shaders));
        }
        return row;
    }

    private Button folderButton(String text, java.nio.file.Path dir) {
        return Ui.ghostButton(text, Icons.FOLDER, () -> openPath(dir));
    }

    private void openPath(java.nio.file.Path dir) {
        try {
            if (!java.nio.file.Files.isDirectory(dir)) {
                java.nio.file.Files.createDirectories(dir);
            }
            new ProcessBuilder("explorer.exe", dir.toString()).start();
        } catch (Exception ex) {
            statusLabel.setText("打开目录失败：" + ex.getMessage());
        }
    }

    private Node emptyDetail() {
        VBox box = new VBox(6,
                Ui.label("没有可用的版本", "detail-name"),
                Ui.label("点右上角「添加版本」把一个文件夹导进来，或把版本放进 " + engine.root().resolve("versions") + "。", "section-sub"));
        box.setPadding(new Insets(40, 10, 10, 10));
        return box;
    }

    private void applyAccent(VersionEntry version) {
        if (detailCard == null) {
            return;
        }
        detailCard.setStyle("-fx-background-color: " + Accents.rgba(version.accent, 0.22)
                + "; -fx-border-color: " + Accents.rgba(version.accent, 0.42) + ";");
    }

    private Node detailHeader(VersionEntry version) {
        Button rename = new Button();
        rename.getStyleClass().add("pencil-btn");
        rename.setGraphic(Icons.icon(Icons.PENCIL, 13, "ghost-icon"));
        rename.setFocusTraversable(false);
        rename.setOnAction(e -> openRenameDialog(version));

        HBox nameRow = new HBox(6, Ui.label(version.displayName, "detail-name"), rename);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        HBox chips = new HBox(6,
                Ui.label(version.loaderText(), "chip", "chip-accent"),
                Ui.label(version.modsCount > 0 ? version.modsCount + " 个模组" : "纯原版", "chip"),
                Ui.label(sizeText(version.sizeBytes), "chip"));
        if (!VersionGroup.DEFAULT_ID.equals(version.groupId)) {
            chips.getChildren().add(Ui.label(version.groupName, "chip", "group-chip"));
        }

        VBox texts = new VBox(6, nameRow, chips);

        Button launchBtn = new Button();
        launchBtn.getStyleClass().addAll("launch-btn", "icon-btn");
        launchBtn.setGraphic(Icons.icon(Icons.PLAY, 22, "play-icon-lg"));
        launchBtn.setFocusTraversable(false);
        launchBtn.setTooltip(new javafx.scene.control.Tooltip("启动游戏"));
        launchBtn.setOnAction(e -> launch(version, launchBtn));
        launchButton = launchBtn;

        Button openBtn = new Button();
        openBtn.getStyleClass().addAll("ghost-btn", "icon-btn");
        openBtn.setGraphic(Icons.icon(Icons.FOLDER, 18, "ghost-icon-lg"));
        openBtn.setFocusTraversable(false);
        openBtn.setTooltip(new javafx.scene.control.Tooltip("打开版本文件夹"));
        openBtn.setOnAction(e -> openFolder(version));

        // 删除版本：红色垃圾桶，位置在「打开版本文件夹」后面
        Button deleteBtn = new Button();
        deleteBtn.getStyleClass().addAll("trash-btn", "icon-btn");
        deleteBtn.setGraphic(Icons.icon(Icons.TRASH, 17, "trash-icon"));
        deleteBtn.setFocusTraversable(false);
        deleteBtn.setTooltip(new javafx.scene.control.Tooltip("删除这个版本"));
        deleteBtn.setOnAction(e -> confirmDeleteVersion(version));

        boolean chosen = LauncherEngine.get().chosen() == version;
        Button pickBtn = new Button("选择游戏");
        pickBtn.getStyleClass().add("ghost-btn");
        pickBtn.setFocusTraversable(false);
        pickBtn.setTooltip(new javafx.scene.control.Tooltip("在启动页显示并默认启动这个版本"));
        pickBtn.setOnAction(e -> {
            if (overlay == null) {
                return;
            }
            overlay.show(GamePicker.create(overlay, () -> {
                rebuildList();
                showDetail(LauncherEngine.get().chosen());
            }));
        });

        HBox header = new HBox(9, texts, Ui.spacer(), pickBtn, launchBtn, openBtn, deleteBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /* ============================ 删除版本 ============================ */

    /** 删除前的二次确认：真的会消失很久，所以文案写清楚 + 红色按钮。 */
    private void confirmDeleteVersion(VersionEntry version) {
        if (overlay == null) {
            return;
        }
        Label title = Ui.label("删除版本", "dialog-title", "danger-title");
        Label warn = Ui.label("你确定要删除这个版本吗（真的会消失很久！）", "dialog-warn");
        warn.setWrapText(true);
        warn.setMaxWidth(340);
        Label path = Ui.label(version.dir.toString(), "path-pill");
        path.setMaxWidth(340);
        Label note = Ui.label(version.isolation
                ? "版本文件夹里的存档、模组、配置会一起删掉，删完只能重新下载。"
                : "这个版本没开隔离：只删版本本身，根目录的 mods / saves / config 是共用的，不会被删。",
                "dialog-hint");
        note.setWrapText(true);
        note.setMaxWidth(340);

        Button cancel = Ui.ghostButton("取消", null, overlay::hide);
        Button confirm = Ui.primaryButton("删除", null, () -> {
            overlay.hide();
            deleteVersionFiles(version);
        });
        confirm.getStyleClass().add("danger-btn");
        confirm.setMinHeight(34);
        confirm.setPrefHeight(34);

        HBox buttons = new HBox(8, Ui.spacer(), cancel, confirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        showDialog(new VBox(10, title, warn, note, path, buttons), "danger-card");
    }

    /** 后台把版本目录整个删掉，删完重新扫描并选中同组剩下的第一个版本。 */
    private void deleteVersionFiles(VersionEntry version) {
        statusLabel.setText("正在删除「" + version.displayName + "」…");
        String key = version.configKey();
        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                deleteRecursively(version.dir);
            } catch (Exception error) {
                failure = error.getClass().getSimpleName() + " " + error.getMessage();
            }
            String message = failure;
            Platform.runLater(() -> {
                if (message != null) {
                    statusLabel.setText("删除失败：" + message);
                    return;
                }
                // 被删掉的版本既不能继续「选中」，也不能继续当「已选择游戏」
                VersionEntry chosen = LauncherEngine.get().chosen();
                if (chosen != null && key.equals(chosen.configKey())) {
                    LauncherEngine.get().choose(null);
                }
                engine.select(null);
                engine.scanAsync(list -> Platform.runLater(() -> {
                    rebuildGroupRail();
                    if (filterRow != null) {
                        rebuildFilterChips(filterRow);
                    }
                    VersionEntry next = firstInGroup(engine.selectedGroup().id);
                    engine.select(next);
                    rebuildList();
                    showDetail(next);
                    statusLabel.setText("已删除「" + version.displayName + "」");
                }));
            });
        }, "yi-delete-version");
        worker.setDaemon(true);
        worker.start();
    }

    private static void deleteRecursively(java.nio.file.Path dir) throws java.io.IOException {
        if (dir == null || !java.nio.file.Files.exists(dir)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            java.util.List<java.nio.file.Path> all = walk.sorted(java.util.Comparator.reverseOrder())
                    .toList();
            for (java.nio.file.Path path : all) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    private Node pathRow(VersionEntry version) {
        Label pathLabel = Ui.label(version.dir.toString(), "path-pill");
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        Button copy = new Button();
        copy.getStyleClass().addAll("ghost-btn", "icon-btn", "icon-btn-sm");
        copy.setGraphic(Icons.icon(Icons.COPY, 15, "ghost-icon-lg"));
        copy.setFocusTraversable(false);
        copy.setTooltip(new javafx.scene.control.Tooltip("复制路径"));
        copy.setOnAction(e -> copyToClipboard(version.dir.toString()));

        HBox row = new HBox(8, Icons.icon(Icons.FOLDER, 14, "ghost-icon"), pathLabel, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label memoryWarningLabel;
    private boolean devDialogShown;

    private Node memoryRow(VersionEntry version) {
        List<Label> group = new ArrayList<>();

        Label auto = Ui.label("自动", "pill");
        if (version.memoryIsAuto()) {
            auto.getStyleClass().add("active");
        }
        auto.setOnMouseClicked(e -> {
            version.memoryOverrideMb = null;
            version.refreshMemory();
            Ui.selectOne(group, auto);
            memoryLabel.setText(memoryText(version.effectiveMemory()));
            updateMemoryWarning(version);
            engine.persist(version);
        });
        group.add(auto);

        List<Label> presets = new ArrayList<>();
        for (int mb : new int[]{4096, 8192, 16384}) {
            Label pill = Ui.label((mb / 1024) + " GB", "pill");
            if (version.memoryOverrideMb != null && version.memoryOverrideMb == mb) {
                pill.getStyleClass().add("active");
            }
            pill.setOnMouseClicked(e -> {
                version.memoryOverrideMb = mb;
                version.refreshMemory();
                Ui.selectOne(group, pill);
                memoryLabel.setText(memoryText(mb));
                updateMemoryWarning(version);
                engine.persist(version);
            });
            group.add(pill);
            presets.add(pill);
        }

        Label custom = Ui.label("自定义…", "pill");
        if (!version.memoryIsAuto() && !isPresetMemory(version.memoryOverrideMb)) {
            custom.getStyleClass().add("active");
        }
        custom.setOnMouseClicked(e -> openMemoryDialog(version));
        group.add(custom);
        group.forEach(Motion::pressFade);

        memoryLabel.setText(memoryText(version.effectiveMemory()));
        javafx.scene.layout.FlowPane row = settingRow("分配内存", auto,
                presets.get(0), presets.get(1), presets.get(2), custom, memoryLabel);

        memoryWarningLabel = Ui.label("☆ 游戏进程分配内存不足可能会导致游戏崩溃等问题！", "warn-text");
        memoryWarningLabel.setVisible(version.memoryTooLow());
        memoryWarningLabel.setManaged(version.memoryTooLow());

        Label hint = Ui.label("自动：" + MemoryRecommendation.reason(version.modsCount), "field-hint");
        VBox block = new VBox(4, row, hint, memoryWarningLabel);
        return block;
    }

    private static boolean isPresetMemory(Integer memoryMb) {
        return memoryMb != null && (memoryMb == 4096 || memoryMb == 8192 || memoryMb == 16384);
    }

    /** 内存显示：整 GB 时同时给出 MB，方便对照启动参数。 */
    private static String memoryText(int memoryMb) {
        return memoryMb % 1024 == 0
                ? (memoryMb / 1024) + " GB（" + memoryMb + " MB）"
                : memoryMb + " MB";
    }

    private void updateMemoryWarning(VersionEntry version) {
        if (memoryWarningLabel == null) {
            return;
        }
        boolean low = version.memoryTooLow();
        memoryWarningLabel.setVisible(low);
        memoryWarningLabel.setManaged(low);
    }

    /** 自定义内存：自绘内存条（黄=系统已用，青绿=这次要分配），也能直接填 MB 精确到 1 MB。 */
    private void openMemoryDialog(VersionEntry version) {
        long totalBytes = Math.max(1, SystemMonitor.totalMemory());
        long freeBytes = SystemMonitor.freeMemory();
        long usedBytes = Math.max(0, totalBytes - freeBytes);
        int totalMb = (int) (totalBytes / 1048576L);
        int usedMbValue = (int) (usedBytes / 1048576L);
        int maxMb = (int) Math.max(MemoryBar.MIN_MB,
                Math.min(65536L, (long) Math.floor(freeBytes / 1073741824.0) * 1024));
        int currentMb = Math.max(MemoryBar.MIN_MB, Math.min(maxMb, version.effectiveMemory()));

        MemoryBar bar = new MemoryBar();
        bar.configure(totalMb, usedMbValue, maxMb);
        bar.setValue(currentMb);

        TextField mbField = new TextField(String.valueOf(bar.getValue()));
        mbField.getStyleClass().add("dialog-field");
        mbField.setPrefWidth(96);

        Label value = Ui.label("", "field-value");
        Label allocLegend = Ui.label("", "field-hint");
        Runnable refreshValue = () -> {
            value.setText(bar.getValue() + " MB　（" + MemoryRecommendation.asText(bar.getValue()) + "）");
            allocLegend.setText("本次分配 " + MemoryRecommendation.asText(bar.getValue()));
        };

        boolean[] syncing = {false};
        bar.valueProperty().addListener((obs, old, now) -> {
            if (!syncing[0]) {
                syncing[0] = true;
                mbField.setText(String.valueOf(now.intValue()));
                syncing[0] = false;
            }
            refreshValue.run();
        });
        mbField.textProperty().addListener((obs, old, text) -> {
            if (syncing[0]) {
                return;
            }
            String digits = text == null ? "" : text.trim();
            if (digits.isEmpty()) {
                return;
            }
            int typed;
            try {
                typed = Integer.parseInt(digits);
            } catch (NumberFormatException ex) {
                return;
            }
            bar.setValue(typed);
            refreshValue.run();
        });
        refreshValue.run();

        HBox fieldRow = new HBox(8, Ui.label("精确设置", "field-label"), mbField,
                Ui.label("MB（" + MemoryBar.MIN_MB + " – " + maxMb + "）", "field-hint"));
        fieldRow.setAlignment(Pos.CENTER_LEFT);

        Label system = Ui.label(String.format(Locale.ROOT,
                "总内存 %.1f GB · 系统已用 %.1f GB · 可分配 %.1f GB：每 1 GB 一格，也能直接填 MB。",
                SystemMonitor.toGb(totalBytes), SystemMonitor.toGb(usedBytes), SystemMonitor.toGb(freeBytes)),
                "dialog-hint");
        system.setWrapText(true);

        HBox legend = new HBox(14,
                legendItem("legend-used",
                        Ui.label("系统已用 " + MemoryRecommendation.asText(usedMbValue), "field-hint")),
                legendItem("legend-alloc", allocLegend),
                legendItem("legend-free", Ui.label("可分配剩余", "field-hint")));
        legend.setAlignment(Pos.CENTER_LEFT);

        Label warn = Ui.label("☆ 游戏进程分配内存不足可能会导致游戏崩溃等问题！", "warn-text");
        Runnable updateWarn = () -> {
            boolean low = bar.getValue() < version.recommendedMemory();
            warn.setVisible(low);
            warn.setManaged(low);
        };
        bar.valueProperty().addListener((obs, old, now) -> updateWarn.run());
        updateWarn.run();

        VBox content = new VBox(12,
                Ui.label("自定义内存分配", "dialog-title"),
                system,
                bar,
                legend,
                fieldRow,
                value,
                warn,
                dialogButtons("确定", () -> {
                    version.memoryOverrideMb = bar.getValue();
                    version.refreshMemory();
                    engine.persist(version);
                    overlay.hide();
                    showDetail(version);
                }));
        showDialog(content);
    }

    /** 图例：一个色块 + 一行说明。 */
    private Node legendItem(String styleClass, Label text) {
        Region dot = new Region();
        dot.getStyleClass().addAll("legend-dot", styleClass);
        HBox box = new HBox(5, dot, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node javaRow(VersionEntry version) {
        String text = version.javaLabel != null ? version.javaLabel : "自动（要求 Java " + version.javaMajor + "）";
        Label current = Ui.label(text, "pill", "active");
        current.getStyleClass().add("select-pill");
        current.setOnMouseClicked(e -> openJavaDialog(version, current));

        return settingRow("Java 运行时", current,
                Icons.icon(Icons.CHEVRON_DOWN, 12, "ghost-icon"),
                Ui.label("点击可切换本机已安装的 Java", "field-hint"));
    }

    private Node windowRow(VersionEntry version) {
        List<Label> group = new ArrayList<>();

        Label def = Ui.label("默认 1280×720", "pill");
        Label full = Ui.label("全屏", "pill");
        Label custom = Ui.label("自定义…", "pill");
        if ("default".equals(version.windowMode)) {
            def.getStyleClass().add("active");
        } else if (version.isFullscreen()) {
            full.getStyleClass().add("active");
        } else {
            custom.getStyleClass().add("active");
        }
        Label value = Ui.label(version.windowText(), "field-value");

        def.setOnMouseClicked(e -> {
            version.windowMode = "default";
            version.windowWidth = 1280;
            version.windowHeight = 720;
            Ui.selectOne(group, def);
            value.setText(version.windowText());
            engine.persist(version);
        });
        full.setOnMouseClicked(e -> {
            version.windowMode = "fullscreen";
            javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getBounds();
            version.windowWidth = (int) Math.round(screen.getWidth());
            version.windowHeight = (int) Math.round(screen.getHeight());
            Ui.selectOne(group, full);
            value.setText(version.windowText());
            engine.persist(version);
        });
        custom.setOnMouseClicked(e -> openWindowDialog(version));

        group.add(def);
        group.add(full);
        group.add(custom);
        group.forEach(Motion::pressFade);

        return settingRow("窗口尺寸", def, full, custom, value,
                Ui.label("启动时按这个尺寸开窗", "field-hint"));
    }

    /** 自定义窗口分辨率：宽 × 高，附几个常见尺寸。 */
    private void openWindowDialog(VersionEntry version) {
        TextField width = new TextField(String.valueOf(version.windowWidth));
        width.getStyleClass().add("dialog-field");
        width.setPrefWidth(84);
        TextField height = new TextField(String.valueOf(version.windowHeight));
        height.getStyleClass().add("dialog-field");
        height.setPrefWidth(84);

        HBox presets = new HBox(6);
        presets.setAlignment(Pos.CENTER_LEFT);
        for (int[] preset : new int[][]{{1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}}) {
            Label pill = Ui.label(preset[0] + "×" + preset[1], "pill");
            pill.setOnMouseClicked(e -> {
                width.setText(String.valueOf(preset[0]));
                height.setText(String.valueOf(preset[1]));
            });
            presets.getChildren().add(pill);
        }

        HBox sizeRow = new HBox(8, width, Ui.label("×", "field-label"), height,
                Ui.label("像素", "field-hint"));
        sizeRow.setAlignment(Pos.CENTER_LEFT);

        Label error = Ui.label("", "warn-text");
        error.setVisible(false);
        error.setManaged(false);

        VBox content = new VBox(10,
                Ui.label("窗口分辨率", "dialog-title"),
                Ui.label("启动游戏时会传 --width / --height，全屏则改用 --fullscreen。", "dialog-hint"),
                presets,
                sizeRow,
                error,
                dialogButtons("确定", () -> {
                    int w = parseInt(width.getText(), 0);
                    int h = parseInt(height.getText(), 0);
                    if (w < 320 || h < 240 || w > 7680 || h > 4320) {
                        error.setText("宽高要落在 320×240 到 7680×4320 之间");
                        error.setVisible(true);
                        error.setManaged(true);
                        return;
                    }
                    version.windowMode = "custom";
                    version.windowWidth = w;
                    version.windowHeight = h;
                    engine.persist(version);
                    overlay.hide();
                    showDetail(version);
                }));
        showDialog(content);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }


    private Node isolationRow(VersionEntry version) {
        VersionGroup owning = engine.group(version.groupId);
        boolean groupLevel = owning != null && !owning.builtin;

        List<Label> group = new ArrayList<>();
        Label isolated = Ui.label("独立版本隔离", "pill");
        Label shared = Ui.label("共用 mods 目录", "pill");
        (version.isolation ? isolated : shared).getStyleClass().add("active");
        isolated.setOnMouseClicked(e -> {
            Ui.selectOne(group, isolated);
            if (groupLevel) {
                engine.setGroupIsolation(owning, true);
                syncIsolation(owning, true);
                showDetail(version);
            } else {
                version.isolation = true;
                engine.persist(version);
            }
        });
        shared.setOnMouseClicked(e -> {
            if (!version.isolation) {
                return;
            }
            confirmDisableIsolation(() -> {
                Ui.selectOne(group, shared);
                if (groupLevel) {
                    engine.setGroupIsolation(owning, false);
                    syncIsolation(owning, false);
                } else {
                    version.isolation = false;
                    engine.persist(version);
                }
                showDetail(version);
            }, null);
        });
        group.add(isolated);
        group.add(shared);
        group.forEach(Motion::pressFade);
        return settingRow("版本隔离", isolated, shared, Ui.label(groupLevel
                ? "整个「" + owning.name + "」分组一起切换"
                : "隔离后该版本的模组 / 存档互不影响", "field-hint"));
    }

    /** 分组级别的隔离开关：把这个分组里已经扫到的版本一起改掉。 */
    private void syncIsolation(VersionGroup group, boolean isolation) {
        for (VersionEntry entry : engine.versions()) {
            if (entry.groupId.equals(group.id)) {
                entry.isolation = isolation;
            }
        }
    }

    /** 添加版本：选一个文件夹当新分组，扫描里面的 versions。 */
    private void openAddVersionDialog() {
        showAddVersionDialog("", engine.nextGroupName(), true);
    }

    /** 弹窗内容单独抽出来：关隔离要先确认，取消后要原样恢复已填的内容。 */
    private void showAddVersionDialog(String folderText, String nameText, boolean isolationOn) {
        TextField folder = new TextField(folderText);
        folder.getStyleClass().add("dialog-field");
        folder.setPrefWidth(300);

        Button pick = Ui.ghostButton("选择文件夹…", Icons.FOLDER, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择包含版本或 .minecraft 的文件夹");
            try {
                chooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
            } catch (Exception ignored) {
            }
            java.io.File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (chosen != null) {
                folder.setText(chosen.getAbsolutePath());
            }
        });

        HBox folderRow = new HBox(8, folder, pick);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(folder, Priority.ALWAYS);

        TextField groupName = new TextField(nameText);
        groupName.getStyleClass().add("dialog-field");
        groupName.setPrefWidth(240);

        CheckBox isolation = new CheckBox("开启版本隔离（这个文件夹单独成组，模组 / 存档互不影响）");
        isolation.getStyleClass().add("dialog-check");
        isolation.setSelected(isolationOn);
        isolation.setOnAction(e -> {
            if (isolation.isSelected()) {
                return;
            }
            String currentFolder = folder.getText();
            String currentName = groupName.getText();
            overlay.hide();
            confirmDisableIsolation(
                    () -> showAddVersionDialog(currentFolder, currentName, false),
                    () -> showAddVersionDialog(currentFolder, currentName, true));
        });

        Label hint = Ui.label("支持选 .minecraft 根目录、versions 目录，或者某个版本文件夹。", "dialog-hint");
        hint.setWrapText(true);
        Label error = Ui.label("", "warn-text");
        error.setVisible(false);
        error.setManaged(false);

        VBox content = new VBox(10,
                Ui.label("添加版本", "dialog-title"),
                hint,
                folderRow,
                Ui.label("分组名称（不填就用未命名分组）", "field-label"),
                groupName,
                isolation,
                error,
                dialogButtons("添加", () -> {
                    String text = folder.getText() == null ? "" : folder.getText().trim();
                    if (text.isEmpty()) {
                        showError(error, "先选一个文件夹");
                        return;
                    }
                    java.nio.file.Path path;
                    try {
                        path = java.nio.file.Path.of(text);
                    } catch (Exception ex) {
                        showError(error, "路径格式不对：" + ex.getMessage());
                        return;
                    }
                    if (!java.nio.file.Files.isDirectory(path)) {
                        showError(error, "这个文件夹不存在");
                        return;
                    }
                    VersionGroup group = engine.addGroup(path, groupName.getText(), isolation.isSelected());
                    overlay.hide();
                    reloadAfterImport(group);
                }));
        showDialog(content);
    }

    /** 关闭版本隔离前的红色确认窗口。 */
    private void confirmDisableIsolation(Runnable onConfirm, Runnable onCancel) {
        Label title = Ui.label("是否关闭版本隔离？", "dialog-title", "danger-title");
        Label warn = Ui.label(
                "警告：关闭版本隔离可能会导致版本融合导致大量异常与错误，如果你不知道这是做什么的，那就不要去更改它！",
                "dialog-warn");
        warn.setWrapText(true);
        warn.setMaxWidth(360);

        Button cancel = Ui.ghostButton("取消", null, () -> {
            overlay.hide();
            if (onCancel != null) {
                onCancel.run();
            }
        });
        Button confirm = Ui.primaryButton("是的，我知道后果", null, () -> {
            overlay.hide();
            onConfirm.run();
        });
        confirm.getStyleClass().add("danger-btn");
        confirm.setMinHeight(34);
        confirm.setPrefHeight(34);

        HBox buttons = new HBox(8, Ui.spacer(), cancel, confirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        showDialog(new VBox(12, title, warn, buttons), "danger-card");
    }

    private void showError(Label error, String message) {
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
    }

    /** 导入分组后重新扫描，切到这个分组并选中它的第一个版本。 */
    private void reloadAfterImport(VersionGroup group) {
        summary.setText("正在扫描「" + group.name + "」…");
        engine.selectGroup(group);
        engine.scanAsync(list -> Platform.runLater(() -> {
            engine.selectGroup(group);
            rebuildGroupRail();
            if (filterRow != null) {
                rebuildFilterChips(filterRow);
            }
            VersionEntry first = firstInGroup(group.id);
            int count = countInGroup(group.id);
            if (first != null) {
                engine.select(first);
            }
            rebuildList();
            showDetail(first);
            statusLabel.setText(count > 0
                    ? "已添加分组「" + group.name + "」，识别到 " + count + " 个版本"
                    : "分组「" + group.name + "」里没有识别到版本，检查一下文件夹里有没有 <版本名>/<版本名>.json");
        }));
    }

    /** 设置行用 FlowPane：宽度不够就自动换行，保证每个选项都能完整显示。 */
    private javafx.scene.layout.FlowPane settingRow(String title, Node... controls) {
        javafx.scene.layout.FlowPane row = new javafx.scene.layout.FlowPane(8, 6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setRowValignment(javafx.geometry.VPos.CENTER);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().add(Ui.label(title, "field-label"));
        for (Node control : controls) {
            row.getChildren().add(control);
        }
        return row;
    }
    private Node footer(VersionEntry version) {
        HBox row = new HBox(18,
                metaItem("版本类型", "release".equals(version.type) ? "正式版" : version.type),
                metaItem("基础版本", version.gameVersion),
                metaItem("模组数量", version.modsCount + " 个"),
                metaItem("占用空间", sizeText(version.sizeBytes)));
        row.setAlignment(Pos.CENTER_LEFT);

        Region dot = new Region();
        dot.getStyleClass().add("color-dot");
        dot.setStyle("-fx-background-color: " + version.accent + ";");
        dot.setOnMouseClicked(e -> openColorDialog(version));
        javafx.scene.control.Tooltip.install(dot, new javafx.scene.control.Tooltip("更换详情配色"));

        row.getChildren().addAll(Ui.spacer(), dot);
        return row;
    }

    private Node metaItem(String label, String value) {
        return new VBox(1, Ui.label(label, "stat-label"), Ui.label(value, "field-value"));
    }

    private Node divider() {
        Region line = new Region();
        line.getStyleClass().add("soft-divider");
        return line;
    }

    /* ============================ 启动 / 文件 ============================ */

    private void launch(VersionEntry version, Button button) {
        button.setDisable(true);
        button.setText("启动中…");
        statusLabel.setText("正在拼装启动参数…");
        engine.launchAsync(version,
                message -> Platform.runLater(() -> {
                    statusLabel.setText(message);
                    if (message.startsWith("已启动")) {
                        button.setText("游戏中…");
                    } else if (message.startsWith("游戏已退出") || message.startsWith("启动失败")) {
                        button.setText("启动游戏");
                        button.setDisable(false);
                    }
                }),
                error -> Platform.runLater(() -> {
                    statusLabel.setText("启动失败：" + error.getMessage());
                    button.setText("启动游戏");
                    button.setDisable(false);
                }));
    }

    private void openFolder(VersionEntry version) {
        try {
            new ProcessBuilder("explorer.exe", version.dir.toString()).start();
        } catch (Exception ex) {
            statusLabel.setText("打开目录失败：" + ex.getMessage());
        }
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        statusLabel.setText("路径已复制");
    }

    /* ============================ 弹窗 ============================ */

    private void openRenameDialog(VersionEntry version) {
        TextField field = new TextField(version.displayName);
        field.getStyleClass().add("dialog-field");
        field.setPrefWidth(260);

        Label hint = Ui.label("只改启动器里显示的名字，不会重命名版本文件夹。", "dialog-hint");
        hint.setWrapText(true);

        VBox content = new VBox(10,
                Ui.label("重命名版本", "dialog-title"),
                hint,
                field,
                dialogButtons("保存", () -> {
                    saveName(version, field);
                    overlay.hide();
                }));
    }

    private void saveName(VersionEntry version, TextField field) {
        String name = field.getText() == null ? "" : field.getText().trim();
        if (!name.isEmpty() && !name.equals(version.displayName)) {
            version.displayName = name;
            engine.persist(version);
            rebuildList();
            showDetail(version);
        }
    }

    private void openJavaDialog(VersionEntry version, Label current) {
        VBox list = new VBox(4);

        Label auto = Ui.label("自动（按版本要求 Java " + version.javaMajor + "）", "dialog-item");
        if (version.javaPath == null) {
            auto.getStyleClass().add("active");
        }
        auto.setMaxWidth(Double.MAX_VALUE);
        auto.setOnMouseClicked(e -> {
            version.javaPath = null;
            version.javaLabel = null;
            current.setText("自动（要求 Java " + version.javaMajor + "）");
            engine.persist(version);
            overlay.hide();
        });
        list.getChildren().add(auto);

        List<JavaRuntimes.JavaRuntime> runtimes = JavaRuntimes.all(engine.root());
        if (runtimes.isEmpty()) {
            list.getChildren().add(Ui.label("没有扫描到本机 Java，请先安装 JDK", "dialog-hint"));
        }
        for (JavaRuntimes.JavaRuntime runtime : runtimes) {
            Label item = Ui.label(runtime.display() + "  ·  " + runtime.exe().getParent().getParent(),
                    "dialog-item");
            if (runtime.exe().equals(version.javaPath)) {
                item.getStyleClass().add("active");
            }
            item.setMaxWidth(Double.MAX_VALUE);
            item.setOnMouseClicked(e -> {
                version.javaPath = runtime.exe();
                version.javaLabel = runtime.display();
                current.setText(runtime.display());
                engine.persist(version);
                overlay.hide();
            });
            list.getChildren().add(item);
        }

        VBox content = new VBox(10,
                Ui.label("选择 Java 运行时", "dialog-title"),
                Ui.label("选中后会写进这个版本的启动参数。", "dialog-hint"),
                list,
                dialogButtons("取消", overlay::hide));
        showDialog(content);
    }

    private void openColorDialog(VersionEntry version) {
        HBox swatches = new HBox(8);
        swatches.setAlignment(Pos.CENTER_LEFT);
        for (String[] accent : Accents.PALETTE) {
            Region dot = new Region();
            dot.getStyleClass().add("swatch");
            dot.setStyle("-fx-background-color: " + accent[0] + ";");
            if (accent[0].equalsIgnoreCase(version.accent)) {
                dot.getStyleClass().add("active");
            }
            javafx.scene.control.Tooltip.install(dot, new javafx.scene.control.Tooltip(accent[1]));
            dot.setOnMouseClicked(e -> {
                version.accent = accent[0];
                engine.persist(version);
                applyAccent(version);
                rebuildList();
                showDetail(version);
                overlay.hide();
            });
            swatches.getChildren().add(dot);
        }

        VBox content = new VBox(10,
                Ui.label("详情模块配色", "dialog-title"),
                Ui.label("列表小图标与详情卡会一起换成这个色调（同样的透明度）。", "dialog-hint"),
                swatches,
                dialogButtons("取消", overlay::hide));
        showDialog(content);
    }

    private Node dialogButtons(String confirmText, Runnable onConfirm) {
        Button cancel = Ui.ghostButton("取消", null, overlay::hide);
        Button confirm = Ui.primaryButton(confirmText, null, onConfirm);
        confirm.getStyleClass().add("dialog-confirm");
        confirm.setMinHeight(34);
        confirm.setPrefHeight(34);

        HBox row = new HBox(8, Ui.spacer(), cancel, confirm);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private void showDialog(VBox content) {
        showDialog(content, null);
    }

    private void showDialog(VBox content, String extraStyle) {
        if (overlay == null) {
            return;
        }
        content.getStyleClass().add("dialog-card");
        if (extraStyle != null) {
            content.getStyleClass().add(extraStyle);
        }
        content.setPadding(new Insets(18, 20, 16, 20));
        content.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        overlay.show(content);
    }
}
