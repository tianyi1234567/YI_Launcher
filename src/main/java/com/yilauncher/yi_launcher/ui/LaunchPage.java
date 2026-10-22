package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.MemoryRecommendation;
import com.yilauncher.yi_launcher.core.SearchIndex;
import com.yilauncher.yi_launcher.core.VersionEntry;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 启动页：真实扫描出的版本卡片 + 系统监控（CPU / 内存 / 时间）+ 最近启动 + 右侧信息栏。
 */
public final class LaunchPage extends BorderPane {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.CHINA);

    private final LauncherEngine engine = LauncherEngine.get();

    private final Label cpuValue = Ui.label("--", "stat-value");
    private final Label cpuUnit = Ui.label("%", "stat-unit");
    private final Label cpuAside = Ui.label("100%", "stat-aside");
    private final ProgressBar cpuBar = thinBar();

    private final Label memValue = Ui.label("--", "stat-value");
    private final Label memUnit = Ui.label("GB", "stat-unit");
    private final Label memAside = Ui.label("-- GB", "stat-aside");
    private final ProgressBar memBar = thinBar();

    private final Label clockValue = Ui.label("--:--:--", "stat-value");
    private final Label clockAside = Ui.label("系统时间", "stat-aside");

    private final Label heroVersion = Ui.label("--", "hero-version");
    private final Label heroGreeting = Ui.label("今天准备玩什么？", "hero-greeting");
    private final Label heroEyebrow = Ui.label("当前选择的游戏版本", "hero-eyebrow");
    private final Label heroLoader = Ui.label("--", "chip", "chip-accent");
    private final Label heroMods = Ui.label("--", "chip");
    private final Label heroJava = Ui.label("--", "chip");
    private final Label heroMemory = Ui.label("--", "chip");
    private final Label heroStatus = Ui.label("", "hero-eyebrow");

    private final HBox recentList = new HBox(10);
    /** 进搜索时「向下弹出 + 淡出」的模块：hero / 监控条 / 最近启动 / 公告 / 运行状态。 */
    private final java.util.List<Node> dropTargets = new java.util.ArrayList<>();
    private String lastHeroKey;
    private final java.util.function.Consumer<VersionEntry> openSettings;
    /** 公告点开更新记录时用：把弹窗放到窗口外壳的遮罩层里。 */
    private final Ui.OverlayHost overlay;

    private Button heroLaunch;
    private Button heroPick;
    private Button heroFolder;
    private Button heroSettings;
    private HBox heroChips;

    /* ---- 搜索模式 ---- */
    private HBox headerTitles;
    private HBox searchBox;
    private TextField searchField;
    private VBox normalBody;
    private VBox searchPanel;
    private VBox searchResults;
    private Label searchHint;
    private VBox rail;
    private StackPane bodyStack;
    private boolean searchMode;
    private long searchToken;
    private long resultToken;
    private String searchFilter = "全部";
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(200));

    public LaunchPage(java.util.function.Consumer<VersionEntry> openSettings, Ui.OverlayHost overlay) {
        this.openSettings = openSettings;
        this.overlay = overlay;
        setCenter(buildMain());
        rail = buildRail();
        setRight(rail);
        startMonitor();
        refreshHero();
        rebuildRecent();
        engine.scanAsync(list -> Platform.runLater(() -> {
            refreshHero();
            rebuildRecent();
        }));
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (searchMode && event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                exitSearch();
                event.consume();
            }
        });
    }

    /* ============================ 主区域 ============================ */

    private Node buildMain() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 16, 16, 6));

        Node hero = buildHero();
        Node stats = buildStats();
        Node recent = buildRecent();
        dropTargets.addAll(List.of(hero, stats, recent));
        normalBody = new VBox(12, hero, stats, recent);
        bodyStack = new StackPane(normalBody, buildSearchPanel());
        VBox.setVgrow(bodyStack, Priority.ALWAYS);

        main.getChildren().addAll(buildHeader(), bodyStack);
        Platform.runLater(() -> Motion.stagger(new java.util.ArrayList<>(normalBody.getChildren()), 45, 4));
        return main;
    }

    private Node buildHeader() {
        headerTitles = new HBox(0);
        headerTitles.getChildren().add(new VBox(0,
                Ui.label("启动游戏", "section-title"),
                Ui.label("选择版本，来一场酣畅淋漓的mc！", "section-sub")));

        searchField = new TextField();
        searchField.setPromptText("搜索版本 / 模组 / 光影…");
        searchField.getStyleClass().add("search-field");
        searchField.setMinHeight(26);
        searchField.setFocusTraversable(false);
        searchField.setMaxWidth(Double.MAX_VALUE);
        searchField.setMinWidth(2600);
        searchField.setPrefWidth(2600);
        searchField.setMaxWidth(2600);

        searchBox = new HBox(6, Icons.icon(Icons.SEARCH, 16, "search-icon"), searchField);
        searchBox.getStyleClass().add("search-box");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setMinWidth(220);
        searchBox.setPrefWidth(220);
        searchBox.setMaxWidth(220);

        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(searchBox.widthProperty());
        clip.heightProperty().bind(searchBox.heightProperty());
        clip.arcWidthProperty().bind(searchBox.heightProperty());
        clip.arcHeightProperty().bind(searchBox.heightProperty());
        searchBox.setClip(clip);

        searchBox.setOnMouseClicked(event -> enterSearch());
        searchField.setOnMouseClicked(event -> enterSearch());
        searchField.textProperty().addListener((obs, old, now) -> {
            if (searchMode) {
                searchDebounce.playFromStart();
            }
        });
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                exitSearch();
            }
        });
        searchDebounce.setOnFinished(event -> refreshResults());

        HBox header = new HBox(headerTitles, Ui.spacer(), searchBox);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /* ============================ 搜索模式 ============================ */

    /** 点搜索框：标题淡出 → 搜索框向左拉长 → 下方/右侧模块淡出 → 结果面板淡入。 */
    private void enterSearch() {
        if (searchMode) {
            return;
        }
        searchMode = true;
        long token = ++searchToken;
        double target = Math.max(320, getWidth() - 38);

        searchField.setFocusTraversable(true);
        searchField.requestFocus();

        fadeOut(searchBox, 80, null);
        fadeOut(headerTitles, 80, () -> {
            if (token != searchToken) {
                return;
            }
            headerTitles.setManaged(false);
            headerTitles.setVisible(false);
        });

        java.util.List<Node> ordered = new java.util.ArrayList<>(dropTargets);
        ordered.sort((a, b) -> Double.compare(
                b.localToScene(b.getBoundsInLocal()).getMaxY(),
                a.localToScene(a.getBoundsInLocal()).getMaxY()));
        for (int i = 0; i < ordered.size(); i++) {
            dropOut(ordered.get(i), 220, i * 28);
        }

        after(360, () -> {
            if (token != searchToken) {
                return;
            }
            normalBody.setVisible(false);
            normalBody.setManaged(false);
            rail.setVisible(false);
            rail.setManaged(false);
            for (Node module : dropTargets) {
                module.setCache(false);
            }

            searchBox.setTranslateX(0);
            searchBox.setMinWidth(target);
            searchBox.setPrefWidth(target);
            searchBox.setMaxWidth(target);
            fadeIn(searchBox, 120);
        });

        after(470, () -> {
            if (token != searchToken) {
                return;
            }
            searchPanel.setVisible(true);
            searchPanel.setManaged(true);
            searchPanel.setTranslateY(6);
            fadeIn(searchPanel, 140);
            Motion.translate(searchPanel, 0, 0, 200, Motion.SOFT);
            refreshResults();
        });
    }

    /**
     * 退出搜索：面板先收回，然后**先把搜索框缩回去、再恢复标题**。
     * <p>顺序很重要：之前是「标题恢复 + 搜索框还在 1000px 宽」，HBox 挤不下就互相错位了。</p>
     */
    private void exitSearch() {
        if (!searchMode) {
            return;
        }
        searchMode = false;
        long token = ++searchToken;
        searchDebounce.stop();

        fadeOut(searchPanel, 90, null);
        fadeOut(searchBox, 70, null);

        after(80, () -> {
            if (token != searchToken) {
                return;
            }
            searchPanel.setVisible(false);
            searchPanel.setManaged(false);

            Motion.width(searchBox, 220, 1, Motion.SOFT);
            searchBox.setMinWidth(220);
            searchBox.setPrefWidth(220);
            searchBox.setMaxWidth(220);

            headerTitles.setManaged(true);
            headerTitles.setVisible(true);
            normalBody.setVisible(true);
            normalBody.setManaged(true);
            rail.setVisible(true);
            rail.setManaged(true);

            searchField.setText("");
            searchField.setFocusTraversable(false);
            requestFocus();
        });

        after(100, () -> {
            if (token != searchToken) {
                return;
            }
            fadeIn(searchBox, 90);
            fadeIn(headerTitles, 90);
            fadeIn(normalBody, 110);
            fadeIn(rail, 110);
            for (Node target : dropTargets) {
                target.setTranslateY(0);
                Motion.opacity(target, 1, 130, Motion.SOFT);
                target.setCache(false);
            }
            refreshHero();
        });
    }

    /**
     * 进搜索时模块的离场：先停一下（错峰），然后向下弹出 + 淡出。
     * <p>只动透明度与位移，且先缓存成位图，帧开销很低。</p>
     */
    private void dropOut(Node node, int ms, int delay) {
        if (node == null) {
            return;
        }
        node.setCache(true);
        node.setCacheHint(javafx.scene.CacheHint.SPEED);
        PauseTransition wait = new PauseTransition(
                Duration.millis(delay / Math.max(0.2, Motion.speed())));
        wait.setOnFinished(event -> {
            Motion.opacity(node, 0, ms, Motion.EASE_IN);
            Motion.translate(node, 0, 40, ms, Motion.EASE_IN);
        });
        wait.play();
    }



    /** 淡出：临时把子树缓存成位图，避免每帧重绘内部卡片；结束后关掉缓存。 */
    private void fadeOut(Node node, int ms, Runnable done) {
        if (node == null) {
            if (done != null) {
                done.run();
            }
            return;
        }
        node.setCache(true);
        node.setCacheHint(javafx.scene.CacheHint.SPEED);
        Motion.opacity(node, 0, ms, Motion.SOFT);
        after(ms, () -> {
            node.setOpacity(0);
            node.setCache(false);
            if (done != null) {
                done.run();
            }
        });
    }

    /** 淡入：同样先缓存再淡，结束清掉缓存，文字恢复 1:1 渲染。 */
    private void fadeIn(Node node, int ms) {
        if (node == null) {
            return;
        }
        node.setCache(true);
        node.setCacheHint(javafx.scene.CacheHint.SPEED);
        node.setOpacity(0);
        Motion.opacity(node, 1, ms, Motion.SOFT);
        after(ms + 20, () -> {
            node.setOpacity(1);
            node.setCache(false);
        });
    }

    private Node buildSearchPanel() {
        HBox filters = new HBox(6);
        filters.setAlignment(Pos.CENTER_LEFT);
        String[] names = {"全部", "版本", "模组", "光影", "资源包", "存档"};
        List<Label> chips = new java.util.ArrayList<>();
        for (String name : names) {
            Label chip = Ui.label(name, "filter-chip");
            if (name.equals(searchFilter)) {
                chip.getStyleClass().add("active");
            }
            chip.setOnMouseClicked(event -> {
                searchFilter = name;
                for (Label other : chips) {
                    other.getStyleClass().remove("active");
                }
                chip.getStyleClass().add("active");
                refreshResults();
            });
            Motion.pressFade(chip);
            chips.add(chip);
            filters.getChildren().add(chip);
        }

        Label back = Ui.label("退出搜索 · Esc", "filter-chip");
        back.setOnMouseClicked(event -> exitSearch());
        Motion.pressFade(back);
        filters.getChildren().addAll(Ui.spacer(), back);

        searchHint = Ui.label("输入关键字，搜索版本 / 模组 / 光影 / 资源包 / 存档", "search-hint");
        searchResults = new VBox(6);
        searchResults.setPadding(new Insets(2, 4, 4, 0));

        ScrollPane scroll = new ScrollPane(searchResults);
        scroll.getStyleClass().add("list-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        searchPanel = new VBox(10, filters, searchHint, scroll);
        searchPanel.getStyleClass().add("search-panel");
        searchPanel.setOpacity(0);
        searchPanel.setVisible(false);
        searchPanel.setManaged(false);
        return searchPanel;
    }

    private void refreshResults() {
        if (!searchMode || searchResults == null) {
            return;
        }
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        long token = ++resultToken;
        searchResults.getChildren().clear();
        if (query.isEmpty()) {
            searchHint.setText("输入关键字，搜索版本 / 模组 / 光影 / 资源包 / 存档");
            return;
        }
        searchHint.setText("正在搜索「" + query + "」…");
        String filter = searchFilter;

        Thread worker = new Thread(() -> {
            List<SearchIndex.Hit> hits = SearchIndex.search(engine.root(), engine.selected(),
                    engine.versions(), query, filter);
            Platform.runLater(() -> {
                if (token != resultToken || !searchMode) {
                    return;
                }
                showHits(hits, query, filter);
            });
        }, "yi-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void showHits(List<SearchIndex.Hit> hits, String query, String filter) {
        searchResults.getChildren().clear();
        String suffix = "全部".equals(filter) ? "" : "（筛选：" + filter + "）";
        if (hits.isEmpty()) {
            searchHint.setText("没有找到「" + query + "」" + suffix);
            return;
        }
        searchHint.setText("找到 " + hits.size() + " 个结果" + suffix);
        for (SearchIndex.Hit hit : hits) {
            searchResults.getChildren().add(resultRow(hit));
        }
        Motion.stagger(new java.util.ArrayList<>(searchResults.getChildren()), 16, 12);
    }

    private Node resultRow(SearchIndex.Hit hit) {
        Label tag = Ui.label(hit.kind(), "result-tag");
        VBox texts = new VBox(1,
                Ui.label(hit.title(), "result-title"),
                Ui.label(hit.subtitle(), "result-sub"));
        texts.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(10, tag, texts, Ui.spacer(),
                Icons.icon(Icons.FOLDER, 13, "ghost-icon"));
        row.getStyleClass().add("search-result");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setOnMouseClicked(event -> openHit(hit));
        Motion.pressFade(row);
        return row;
    }

    private void openHit(SearchIndex.Hit hit) {
        if (hit.version() != null) {
            engine.select(hit.version());
            exitSearch();
            refreshHero();
            rebuildRecent();
            return;
        }
        java.nio.file.Path path = hit.path();
        if (path == null) {
            return;
        }
        try {
            java.nio.file.Path target = java.nio.file.Files.isDirectory(path) ? path : path.getParent();
            if (target != null) {
                new ProcessBuilder("explorer.exe", target.toString()).start();
            }
        } catch (Exception ignored) {
        }
    }

    /** 延时执行：跟着动效速度倍率一起缩放，保证和动画节奏对得上。 */
    private void after(int ms, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.millis(ms / Math.max(0.2, Motion.speed())));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private Node buildHero() {
        VBox content = new VBox(9);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMaxWidth(430);

        HBox versionRow = new HBox(9, heroVersion, heroLoader);
        versionRow.setAlignment(Pos.BASELINE_LEFT);

        heroChips = new HBox(6, heroMods, heroJava, heroMemory);
        heroChips.setAlignment(Pos.CENTER_LEFT);

        heroLaunch = Ui.primaryButton("启动游戏", Icons.PLAY, null);
        heroLaunch.setOnAction(e -> {
            if (engine.chosen() == null) {
                openGamePicker();
            } else {
                launch(heroTarget(), heroLaunch);
            }
        });

        heroPick = Ui.ghostButton("选择游戏", null, this::openGamePicker);
        versionRow.setCursor(javafx.scene.Cursor.HAND);
        versionRow.setOnMouseClicked(e -> openGamePicker());

        heroSettings = Ui.ghostButton("版本设置", null, () -> {
            if (openSettings != null) {
                openSettings.accept(heroTarget());
            }
        });
        heroFolder = Ui.ghostButton("打开目录", Icons.FOLDER, () -> openFolder(heroTarget()));

        HBox actions = new HBox(9, heroLaunch, heroPick, heroSettings, heroFolder);
        actions.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(heroEyebrow, versionRow, heroGreeting, heroChips, actions, heroStatus);
        VBox.setMargin(actions, new Insets(5, 0, 0, 0));

        StackPane hero = new StackPane(buildHeroDecor(), content);
        hero.getStyleClass().add("hero");
        hero.setMinHeight(212);
        hero.setPrefHeight(212);
        hero.setMaxHeight(212);
        StackPane.setAlignment(content, Pos.CENTER_LEFT);
        return hero;
    }

    /** 装饰用的两枚半透明"方块"，让主卡片有一点点世界的味道。 */
    private Node buildHeroDecor() {
        Rectangle large = new Rectangle(104, 104);
        large.setArcWidth(26);
        large.setArcHeight(26);
        large.setFill(Color.web("#FFFFFF", 0.30));
        large.setRotate(13);

        Rectangle small = new Rectangle(60, 60);
        small.setArcWidth(18);
        small.setArcHeight(18);
        small.setFill(Color.web("#FFFFFF", 0.20));
        small.setRotate(-8);
        small.setX(30);
        small.setY(62);

        javafx.scene.Group group = new javafx.scene.Group(large, small);
        group.setMouseTransparent(true);
        StackPane.setAlignment(group, Pos.CENTER_RIGHT);
        StackPane.setMargin(group, new Insets(6, 54, 0, 0));
        return group;
    }

    /** 启动页主模块显示的就是「选择游戏」里挑定的版本；没挑过就显示"请选择游戏"。 */
    private VersionEntry heroTarget() {
        return engine.chosen();
    }
    private void refreshHero() {
        VersionEntry target = heroTarget();
        if (target == null) {
            heroEyebrow.setText("还没有选择游戏");
            heroVersion.setText("请选择游戏");
            heroGreeting.setText("点下面的按钮，挑一个版本开始");
            setShown(heroGreeting, true);
            setShown(heroLoader, false);
            setShown(heroChips, false);
            setShown(heroSettings, false);
            setShown(heroFolder, false);
            setShown(heroPick, false);
            if (heroLaunch != null) {
                heroLaunch.setDisable(false);
                heroLaunch.setText("选择游戏");
            }
            lastHeroKey = "";
            return;
        }
        heroEyebrow.setText("当前选择的游戏版本");
        heroVersion.setText(target.displayName);
        if (!target.configKey().equals(lastHeroKey)) {
            lastHeroKey = target.configKey();
            Motion.pulse(heroVersion);
        }
        heroGreeting.setText("");
        setShown(heroGreeting, false);
        setShown(heroLoader, true);
        setShown(heroChips, true);
        setShown(heroFolder, true);
        setShown(heroPick, true);
        setShown(heroSettings, true);
        heroLoader.setText(target.loaderText());
        heroMods.setText(target.modsCount > 0 ? target.modsCount + " 个模组"
                : ("原版".equals(target.loader) ? "纯原版" : "无模组"));
        heroJava.setText("Java " + target.javaMajor);
        heroMemory.setText("内存 " + MemoryRecommendation.asText(target.effectiveMemory())
                + (target.memoryIsAuto() ? "（自动）" : ""));
        if (heroLaunch != null) {
            heroLaunch.setDisable(false);
            heroLaunch.setText("启动游戏");
        }
    }

    private static void setShown(Node node, boolean shown) {
        if (node == null) {
            return;
        }
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** 打开「选择游戏」面板；选完只刷新主模块，其它页面下次进来再看。 */
    private void openGamePicker() {
        overlay.show(GamePicker.create(overlay, () -> {
            refreshHero();
            rebuildRecent();
        }));
    }

    /** 外部装完东西后刷新主模块与最近列表。 */
    public void refresh() {
        refreshHero();
        rebuildRecent();
    }

    private void launch(VersionEntry version, Button button) {
        if (version == null) {
            return;
        }
        engine.select(version);
        refreshHero();
        button.setDisable(true);
        button.setText("启动中…");
        heroStatus.setText("正在拼装启动参数…");
        engine.launchAsync(version,
                message -> Platform.runLater(() -> {
                    heroStatus.setText(message);
                    if (message.startsWith("已启动")) {
                        button.setText("游戏中…");
                        refreshHero();
                        rebuildRecent();
                    } else if (message.startsWith("游戏已退出") || message.startsWith("启动失败")) {
                        button.setText("启动游戏");
                        button.setDisable(false);
                    }
                }),
                error -> Platform.runLater(() -> {
                    heroStatus.setText("启动失败：" + error.getMessage());
                    button.setText("启动游戏");
                    button.setDisable(false);
                }));
    }

    private void openFolder(VersionEntry version) {
        if (version == null) {
            return;
        }
        try {
            new ProcessBuilder("explorer.exe", version.dir.toString()).start();
        } catch (Exception ex) {
            heroStatus.setText("打开目录失败：" + ex.getMessage());
        }
    }

    /* ============================ 系统监控 ============================ */

    private Node buildStats() {
        HBox strip = new HBox(0,
                statCard("CPU 占用率", cpuAside, cpuValue, cpuUnit, cpuBar),
                statDivider(),
                statCard("内存占用", memAside, memValue, memUnit, memBar),
                statDivider(),
                clockCard());
        strip.getStyleClass().add("stat-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getChildren().forEach(node -> {
            if (node instanceof VBox) {
                HBox.setHgrow(node, Priority.ALWAYS);
            }
        });
        return strip;
    }

    private Region statDivider() {
        Region line = new Region();
        line.getStyleClass().add("stat-divider");
        HBox.setMargin(line, new Insets(2, 14, 2, 14));
        return line;
    }

    private VBox statCard(String title, Label aside, Label value, Label unit, ProgressBar bar) {
        HBox head = new HBox(Ui.label(title, "stat-label"), Ui.spacer(), aside);
        head.setAlignment(Pos.CENTER_LEFT);

        HBox valueRow = new HBox(0, value, unit);
        valueRow.setAlignment(Pos.BASELINE_LEFT);

        VBox card = new VBox(6, head, valueRow, bar);
        card.getStyleClass().add("stat-card");
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        card.setPrefWidth(0);
        return card;
    }

    private VBox clockCard() {
        HBox head = new HBox(Ui.label("当前时间", "stat-label"), Ui.spacer(), clockAside);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, head, clockValue, Ui.label("系统时间 · 每秒刷新", "stat-hint"));
        card.getStyleClass().add("stat-card");
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        card.setPrefWidth(0);
        return card;
    }

    private static ProgressBar thinBar() {
        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("stat-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    private void startMonitor() {
        refreshStats();
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshStats()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void refreshStats() {
        LocalDateTime now = LocalDateTime.now();
        clockValue.setText(now.format(CLOCK));
        clockAside.setText(now.format(DATE));

        double cpu = SystemMonitor.cpuLoad();
        cpuValue.setText(String.format(Locale.ROOT, "%.0f", cpu * 100));
        cpuBar.setProgress(cpu);

        long total = SystemMonitor.totalMemory();
        long used = SystemMonitor.usedMemory();
        memValue.setText(String.format(Locale.ROOT, "%.1f", SystemMonitor.toGb(used)));
        memUnit.setText("GB");
        memAside.setText(String.format(Locale.ROOT, "共 %.0f GB", SystemMonitor.toGb(total)));
        memBar.setProgress(total > 0 ? (double) used / total : 0);
    }

    /* ============================ 最近启动 ============================ */

    private VBox buildRecent() {
        HBox head = new HBox(Ui.label("最近启动", "card-title"), Ui.spacer(),
                Ui.label("您最近启动的三个版本", "card-hint"));
        head.setAlignment(Pos.CENTER_LEFT);

        recentList.setFillHeight(true);
        VBox card = new VBox(10, head, recentList);
        card.getStyleClass().addAll("card", "card-pad");
        return card;
    }

    private void rebuildRecent() {
        recentList.getChildren().clear();
        List<VersionEntry> recent = engine.recentPlayed(3);
        if (recent.isEmpty()) {
            recentList.getChildren().add(Ui.label("还没有启动记录，去版本管理挑一个版本吧", "recent-meta"));
            return;
        }
        for (VersionEntry version : recent) {
            Node tile = recentRow(version);
            HBox.setHgrow(tile, Priority.ALWAYS);
            recentList.getChildren().add(tile);
        }
        Motion.stagger(new java.util.ArrayList<>(recentList.getChildren()), 40, 3);
    }

    /** 一张最近启动卡片：版本名 + 信息 + 右侧圆形启动按钮，点卡片本身切到该版本。 */
    private Node recentRow(VersionEntry version) {
        VBox texts = new VBox(1, Ui.label(version.displayName, "recent-name"),
                Ui.label(version.loaderText() + " · Java " + version.javaMajor, "recent-meta"));
        texts.setMaxWidth(Double.MAX_VALUE);

        Button play = new Button();
        play.getStyleClass().add("round-btn");
        play.setGraphic(Icons.icon(Icons.PLAY, 11, "round-icon"));
        play.setFocusTraversable(false);
        play.setOnAction(e -> {
            buttonBusy(play);
            launch(version, play);
        });

        HBox foot = new HBox(8,
                Ui.label(version.modsCount > 0 ? version.modsCount + " 模组" : "无模组", "recent-time"),
                Ui.spacer(), play);
        foot.setAlignment(Pos.CENTER_LEFT);

        VBox tile = new VBox(8, texts, foot);
        tile.getStyleClass().add("recent-card");
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setMinWidth(150);
        tile.setOnMouseClicked(e -> {
            engine.select(version);
            refreshHero();
            rebuildRecent();
        });
        return tile;
    }

    private void buttonBusy(Button button) {
        button.setDisable(true);
    }

    /* ============================ 右侧信息栏 ============================ */

    private VBox buildRail() {
        VBox noticeCard = buildNoticeCard();
        VBox statusCard = buildStatusCard();
        dropTargets.addAll(List.of(noticeCard, statusCard));
        VBox rail = new VBox(noticeCard, statusCard);
        rail.getStyleClass().add("rail");
        rail.setPrefWidth(300);
        rail.setMinWidth(300);
        rail.setMaxWidth(300);
        return rail;
    }

    private VBox buildNoticeCard() {
        Label all = Ui.label("全部 ›", "card-hint", "clickable");
        all.setOnMouseClicked(e -> openChangelog());
        Motion.pressFade(all);

        HBox head = new HBox(Ui.label("公告", "card-title"), Ui.spacer(), all);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(0, head,
                noticeRow("0.1.0 版本已基本完成", "今天 · 重要", true),
                noticePlaceholder(),
                noticePlaceholder());
        card.getStyleClass().addAll("card", "card-pad");
        VBox.setMargin(card.getChildren().get(0), new Insets(0, 0, 4, 0));
        return card;
    }

    /** 单条公告：整行可点，点开「历史更新记录」二次窗口。 */
    private Node noticeRow(String title, String date, boolean important) {
        Region dot = new Region();
        dot.getStyleClass().add("notice-dot");

        Label titleLabel = Ui.label(title, "notice-title");
        if (important) {
            titleLabel.getStyleClass().add("notice-title-strong");
        }
        VBox texts = new VBox(0, titleLabel, Ui.label(date, "notice-date"));
        HBox row = new HBox(8, dot, texts);
        row.getStyleClass().addAll("notice-item", "clickable");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> openChangelog());
        Motion.pressFade(row);
        VBox.setMargin(row, new Insets(0, -8, 0, -8));
        return row;
    }

    /** 空公告位：看不见，但仍占一行高度，公告少了版面不缩。 */
    private Node noticePlaceholder() {
        HBox row = (HBox) noticeRow("", "", false);
        row.setVisible(false);
        return row;
    }


    /* ============================ 公告 · 历史更新记录 ============================ */

    /** 公告点击后的二次窗口：滚动查看历史更新记录。 */
    private void openChangelog() {
        VBox list = new VBox(8,
                changelogSection("0.1.0", "今天 · 基本完成",
                        "版本管理：分组栏位、添加版本、版本隔离确认",
                        "启动：扫描真实 .minecraft 目录、自动挑 Java、缺库自动下载",
                        "内存：按模组数量自动推荐，可自定义滑块与精确 MB",
                        "界面：玻璃主题与顺滑动画，窗口可拖动、可改大小",
                        "快捷目录：一键打开 Mod / 存档 / 截图 / 光影",
                        "账户：微软正版 / 离线 / LittleSkin 登录，左下角显示皮肤头像与登录方式"),
                changelogSection("0.0.9", "上周",
                        "版本列表、详情卡与详情模块配色",
                        "扫描本机 Java 运行时（Corretto / Zulu / 1.8）",
                        "启动参数可预览、可干跑校验"),
                changelogSection("0.0.x", "最早的原型",
                        "启动页实时显示 CPU / 内存 / 系统时间",
                        "真实启动进程，日志落在 .minecraft/logs",
                        "网页原型迁移到 JavaFX 玻璃界面"));
        list.setPadding(new Insets(2, 2, 2, 0));

        ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().add("list-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportWidth(368);
        scroll.setPrefViewportHeight(252);

        Button close = Ui.primaryButton("知道了", null, this::closeChangelog);
        close.getStyleClass().add("dialog-confirm");
        close.setMinHeight(34);
        close.setPrefHeight(34);
        HBox foot = new HBox(8, Ui.spacer(), close);
        foot.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10,
                Ui.label("历史更新记录", "dialog-title"),
                Ui.label("衣启动器 · 从原型到 0.1.0", "dialog-hint"),
                scroll,
                foot);
        if (overlay == null) {
            return;
        }
        content.getStyleClass().add("dialog-card");
        content.setPadding(new Insets(18, 20, 16, 20));
        content.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        overlay.show(content);
    }

    private void closeChangelog() {
        if (overlay != null) {
            overlay.hide();
        }
    }

    /** 一条版本记录：版本号 + 日期 + 若干条更新点。 */
    private Node changelogSection(String version, String date, String... lines) {
        HBox head = new HBox(8, Ui.label(version, "changelog-version"),
                Ui.label(date, "changelog-date"));
        head.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4, head);
        box.getStyleClass().add("changelog-item");
        for (String line : lines) {
            Label item = Ui.label("· " + line, "changelog-line");
            item.setWrapText(true);
            box.getChildren().add(item);
        }
        return box;
    }

    private VBox buildStatusCard() {
        HBox head = new HBox(Ui.label("运行状态", "card-title"), Ui.spacer(), Ui.label("正常", "card-hint"));
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(0, head,
                statusRow("dot-ok", "网络连接", "已连接"),
                statusRow("dot-ok", "版本目录", engine.root().getFileName().toString()),
                statusRow("dot-warn", "账户", "离线"));
        card.getStyleClass().addAll("card", "card-pad");
        VBox.setMargin(card.getChildren().get(0), new Insets(0, 0, 4, 0));
        return card;
    }

    private Node statusRow(String dotStyle, String name, String value) {
        Region dot = new Region();
        dot.getStyleClass().add(dotStyle);

        HBox row = new HBox(7, dot, Ui.label(name, "status-name"), Ui.spacer(),
                Ui.label(value, "status-value"));
        row.getStyleClass().add("status-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
