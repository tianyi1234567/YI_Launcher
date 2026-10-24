package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.Favorites;
import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.LoaderInstaller;
import com.yilauncher.yi_launcher.core.LoaderVersions;
import com.yilauncher.yi_launcher.core.ModDownload;
import com.yilauncher.yi_launcher.core.ModSearch;
import com.yilauncher.yi_launcher.core.MinecraftRoot;
import com.yilauncher.yi_launcher.core.VanillaVersions;
import com.yilauncher.yi_launcher.core.VersionEntry;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
import javafx.util.StringConverter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模组资源：选模块（模组 / 资源包 / 光影 / 收藏 / 原版）→ 搜索界面。
 * <p>搜索同时查 CurseForge 与 Modrinth，同一个项目只留 CurseForge 那条；
 * 结果分页显示，选中后底部出现 下载 / 查看详情 / 收藏。
 * 下载会弹二次确认（选安装到的版本），选过「不再提醒」后本次搜索内直接下载。</p>
 */
public final class ResourcesPage extends BorderPane {

    /** 查资料时可选的三家网站。 */
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

    private static final int PAGE_SIZE = 15;
    private static final int FAVORITE_PAGE_SIZE = 12;

    private final MainView host;
    private final StackPane area = new StackPane();
    private final VBox chooser = new VBox(12);
    private final List<Node> cards = new ArrayList<>();

    /* 搜索界面 */
    private final VBox searchView = new VBox(10);
    private final TextField searchField = new TextField();
    private final Label searchStatus = Ui.label("", "settings-hint");
    private final VBox results = new VBox(8);
    private final HBox searchActions = new HBox(8);
    private final Label searchSelected = Ui.label("", "mod-actions-label");
    private final Label pageLabel = Ui.label("", "settings-hint");
    private final Button prevPage = Ui.ghostButton("上一页", null, null);
    private final Button nextPage = Ui.ghostButton("下一页", null, null);
    private final Button downloadButton = Ui.ghostButton("下载", Icons.DOWNLOAD, null);
    private final Button detailButton = Ui.ghostButton("查看详情", Icons.GRID, null);
    private final Button favoriteButton = Ui.ghostButton("收藏", Icons.PLUS, null);

    /* 收藏界面 */
    private final VBox favoritesView = new VBox(10);
    private final TextField favoriteSearch = new TextField();
    private final VBox favoritesList = new VBox(8);
    private final HBox favoriteFilters = new HBox(6);
    private final Label favoriteStatus = Ui.label("", "settings-hint");
    private final Label favoritePageLabel = Ui.label("", "settings-hint");
    private final Button favoritePrev = Ui.ghostButton("上一页", null, null);
    private final Button favoriteNext = Ui.ghostButton("下一页", null, null);
    private final Button unfavoriteButton = Ui.ghostButton("取消收藏", Icons.CLOSE, null);

    /* 原版下载界面 */
    private final VBox vanillaView = new VBox(10);
    private final TextField vanillaSearch = new TextField();
    private final VBox vanillaList = new VBox(8);
    private final HBox vanillaFilters = new HBox(6);
    private final Label vanillaStatus = Ui.label("", "settings-hint");
    private final Label vanillaPageLabel = Ui.label("", "settings-hint");
    private final Button vanillaPrev = Ui.ghostButton("上一页", null, null);
    private final Button vanillaNext = Ui.ghostButton("下一页", null, null);
    private List<VanillaVersions.Version> vanillaVersions = new ArrayList<>();
    private String vanillaFilter = "release";
    private String vanillaQuery = "";
    private int vanillaPage;

    // 图标缓存在 IconCache 里，全启动器共用

    /** 切换界面的世代号：过期的动画回调对不上号就丢掉，不会把新界面改透明。 */
    private int switchToken;
    private Timeline switcher;
    private ModSearch.Kind searchKind = ModSearch.Kind.MOD;
    private String searchQuery = "";
    private int page;
    private boolean hasNext;
    private int searchToken;

    private ModSearch.Hit selected;
    private Favorites.Item selectedFavorite;
    private String favoriteFilter = "ALL";
    private int favoritePage;

    /** 本次搜索里点过「确认（不再提醒）」的版本：离开搜索界面就清空。 */
    private VersionEntry alwaysTarget;

    public ResourcesPage(MainView host) {
        this.host = host;
        setCenter(build());
    }

    /* ============================ 选择模块 ============================ */

    private Node build() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 16, 16, 6));

        chooser.getChildren().addAll(
                new VBox(0, Ui.label("模组资源", "section-title"),
                        Ui.label("要什么点什么~", "section-sub")),
                cardGrid());
        chooser.setPadding(new Insets(0, 4, 4, 0));

        buildSearchView();
        buildFavoritesView();
        buildVanillaView();

        area.getChildren().add(chooser);
        VBox.setVgrow(area, Priority.ALWAYS);
        main.getChildren().add(area);
        return main;
    }

    /** 左列：模组（大）+ 原版下载；右列：资源包 / 光影 / 收藏。 */
    private Node cardGrid() {
        VBox mod = card("搜索 / 下载模组", "按名字、英文名或项目 id 找模组", Icons.PUZZLE, "mod", 360);
        VBox vanilla = card("原版下载", "把指定的 Minecraft 版本补齐到本地", Icons.DOWNLOAD, "vanilla", 104);

        VBox pack = card("资源包", "材质、界面、音乐包都在这", Icons.FOLDER, "pack", 150);
        VBox shader = card("光影包", "让画面更好看（Iris / OptiFine）", Icons.GRID, "shader", 150);
        VBox favorite = card("收藏", "收藏过的模组、材质、光影", Icons.PLAY, "favorite", 150);

        VBox left = new VBox(12, mod, vanilla);
        VBox.setVgrow(mod, Priority.ALWAYS);
        VBox right = new VBox(12, pack, shader, favorite);
        for (Node node : right.getChildren()) {
            VBox.setVgrow(node, Priority.ALWAYS);
        }
        right.setMinWidth(300);
        right.setPrefWidth(340);

        HBox.setHgrow(left, Priority.ALWAYS);
        HBox row = new HBox(12, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        VBox.setVgrow(row, Priority.ALWAYS);
        return row;
    }

    /** 一个模块卡片：颜色由样式类决定。 */
    private VBox card(String title, String subtitle, String icon, String colorClass, double minHeight) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(Icons.icon(icon, 24, "resource-icon"));
        Label name = Ui.label(title, "resource-title");
        Label hint = Ui.label(subtitle, "resource-sub");
        hint.setWrapText(true);

        VBox box = new VBox(6, iconLabel, name, hint);
        box.getStyleClass().addAll("resource-card", "resource-card-" + colorClass);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(20, 20, 20, 20));
        box.setMinWidth(0);
        box.setMinHeight(minHeight);
        box.setMaxWidth(Double.MAX_VALUE);

        if ("mod".equals(colorClass)) {
            box.setOnMouseClicked(e -> openSearch(ModSearch.Kind.MOD));
        } else if ("pack".equals(colorClass)) {
            box.setOnMouseClicked(e -> openSearch(ModSearch.Kind.RESOURCE_PACK));
        } else if ("shader".equals(colorClass)) {
            box.setOnMouseClicked(e -> openSearch(ModSearch.Kind.SHADER));
        } else if ("favorite".equals(colorClass)) {
            box.setOnMouseClicked(e -> openFavorites());
        } else if ("vanilla".equals(colorClass)) {
            box.setOnMouseClicked(e -> openVanilla());
        } else {
            box.setOnMouseClicked(e -> openSearch(ModSearch.Kind.MOD));
        }
        Motion.pressFade(box);
        cards.add(box);
        return box;
    }

    /* ============================ 搜索界面 ============================ */

    private void buildSearchView() {
        Button back = Ui.ghostButton("← 返回", null, this::back);
        back.getStyleClass().add("resource-back");

        searchField.getStyleClass().addAll("search-field", "search-capsule");
        searchField.setPromptText("搜索模组名 / 英文名 / 项目 id");
        searchField.setPrefWidth(430);
        searchField.setOnAction(e -> {
            searchQuery = searchField.getText() == null ? "" : searchField.getText().trim();
            search(0);
        });

        HBox bar = new HBox(10, back, searchField, Ui.spacer(), searchStatus);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("resource-search-bar");

        results.setPadding(new Insets(0, 6, 8, 0));
        ScrollPane scroll = new ScrollPane(results);
        scroll.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        prevPage.setOnAction(e -> search(Math.max(0, page - 1)));
        nextPage.setOnAction(e -> search(page + 1));
        HBox pager = new HBox(8, prevPage, pageLabel, nextPage);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.getStyleClass().add("resource-pager");

        downloadButton.setOnAction(e -> downloadSelected());
        detailButton.setOnAction(e -> openDetails(selected == null ? "" : selected.title(),
                selected == null ? "" : keywordOf(selected.slug(), selected.title())));
        favoriteButton.setOnAction(e -> favoriteSelected());
        searchActions.getStyleClass().add("mod-actions");
        searchActions.setAlignment(Pos.CENTER_LEFT);
        searchActions.setVisible(false);
        searchActions.setMouseTransparent(true);
        searchActions.setMaxHeight(Region.USE_PREF_SIZE);
        searchActions.getChildren().addAll(searchSelected, Ui.spacer(),
                downloadButton, detailButton, favoriteButton);
        StackPane.setAlignment(searchActions, Pos.BOTTOM_CENTER);
        StackPane.setMargin(searchActions, new Insets(0, 10, 10, 0));

        results.setPadding(new Insets(0, 6, 76, 0));
        StackPane stack = new StackPane(scroll, searchActions);
        VBox.setVgrow(stack, Priority.ALWAYS);
        searchView.getChildren().setAll(bar, stack, pager);
        searchView.setPadding(new Insets(0, 4, 4, 0));
    }

    private void openSearch(ModSearch.Kind kind) {
        searchKind = kind;
        searchQuery = "";
        page = 0;
        selected = null;
        alwaysTarget = null;
        searchField.setText("");
        searchStatus.setText("正在搜索…");
        // 先清掉上一次的搜索结果：否则切模块的一瞬间会闪一下旧列表
        results.getChildren().clear();
        pageLabel.setText("");
        prevPage.setDisable(true);
        nextPage.setDisable(true);
        refreshActions();
        switchTo(searchView, () -> {
            searchField.requestFocus();
            search(0);
        });
    }

    private void search(int wanted) {
        final int target = Math.max(0, wanted);
        final int token = ++searchToken;
        final ModSearch.Kind kind = searchKind;
        final String query = searchQuery;
        searchStatus.setText("正在搜索…");
        prevPage.setDisable(true);
        nextPage.setDisable(true);
        showLoading(results, "正在搜索…");
        Thread worker = new Thread(() -> {
            try {
                ModSearch.Page result = ModSearch.search(query, kind, target, PAGE_SIZE);
                Platform.runLater(() -> {
                    if (token != searchToken) {
                        return;
                    }
                    page = target;
                    hasNext = result.hasNext();
                    renderHits(result.hits());
                    pageLabel.setText("第 " + (page + 1) + " 页");
                    prevPage.setDisable(page == 0);
                    nextPage.setDisable(!hasNext);
                    searchStatus.setText(result.hits().isEmpty()
                            ? "没有搜到结果" : ("共 " + result.hits().size() + " 个结果"));
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (token == searchToken) {
                        searchStatus.setText("搜索失败：" + error.getMessage());
                        prevPage.setDisable(page == 0);
                    }
                });
            }
        }, "yi-resource-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderHits(List<ModSearch.Hit> hits) {
        hideLoading(results);
        results.getChildren().clear();
        selected = null;
        refreshActions();
        for (ModSearch.Hit hit : hits) {
            results.getChildren().add(hitRow(hit));
        }
        if (hits.isEmpty()) {
            results.getChildren().add(Ui.label("换个关键词试试", "version-meta"));
        }
        fadeRows(results);
    }

    private Node hitRow(ModSearch.Hit hit) {
        StackPane row = resultRow(hit.iconUrl(), hit.title(), hit.author() + " · " + mod(hit.summary()),
                downloads(hit.downloads()), hit.source().equals(com.yilauncher.yi_launcher.core.PackInstaller.CURSEFORGE)
                        ? "source-cf" : "source-mr", hit.source());
        row.setOnMouseClicked(e -> {
            selected = hit;
            highlight(resultRowList(), row);
            refreshActions();
        });
        return row;
    }

    /** 一条结果行（搜索结果与收藏共用）。 */
    private StackPane resultRow(String iconUrl, String title, String subtitle, String right, String chipClass, String chip) {
        Label letter = Ui.label(initial(title), "mod-icon-letter");
        StackPane icon = new StackPane(letter);
        icon.getStyleClass().add("mod-icon");
        icon.setMinSize(36, 36);
        icon.setMaxSize(36, 36);
        if (iconUrl != null && !iconUrl.isBlank()) {
            loadIcon(icon, iconUrl);
        }

        Label name = Ui.label(title, "mod-name");
        Label meta = Ui.label(subtitle, "mod-meta");
        meta.setMaxWidth(Double.MAX_VALUE);
        VBox texts = new VBox(2, name, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label downloads = Ui.label(right, "mod-chip");
        Label source = Ui.label(chip, "mod-chip");
        if (chipClass != null && !chipClass.isBlank()) {
            source.getStyleClass().add(chipClass);
        }

        HBox content = new HBox(11, icon, texts, downloads, source);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(9, 12, 9, 12));

        Region highlight = new Region();
        highlight.getStyleClass().add("mod-row-hl");
        highlight.setMouseTransparent(true);
        highlight.setOpacity(0);
        highlight.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        StackPane node = new StackPane(highlight, content);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("mod-row");
        node.setMaxWidth(Double.MAX_VALUE);
        return node;
    }

    private List<StackPane> resultRowList() {
        List<StackPane> rows = new ArrayList<>();
        for (Node node : results.getChildren()) {
            if (node instanceof StackPane pane) {
                rows.add(pane);
            }
        }
        return rows;
    }

    private void highlight(List<StackPane> rows, StackPane chosen) {
        for (StackPane row : rows) {
            Node badge = row.lookup(".mod-row-hl");
            if (badge instanceof Region region) {
                double target = row == chosen ? 1 : 0;
                if (Math.abs(region.getOpacity() - target) < 0.01) {
                    continue;
                }
                Motion.opacity(region, target, 150, target > 0.5 ? Motion.SOFT : Motion.EASE_IN);
            }
        }
    }

    private void refreshActions() {
        boolean any = selected != null;
        searchActions.setVisible(any);
        searchActions.setMouseTransparent(!any);
        if (any) {
            searchSelected.setText("已选 " + selected.title());
        }
    }

    /* ============================ 下载 ============================ */

    private void downloadSelected() {
        if (selected == null) {
            return;
        }
        if (alwaysTarget != null) {
            startDownload(selected, alwaysTarget);
            return;
        }
        openDownloadDialog(selected);
    }

    /** 二次确认：选装到哪个版本，取消 / 确认 / 确认（不再提醒）。 */
    private void openDownloadDialog(ModSearch.Hit hit) {
        ComboBox<VersionEntry> versions = new ComboBox<>();
        versions.getStyleClass().add("dialog-field");
        for (VersionEntry entry : LauncherEngine.get().versions()) {
            versions.getItems().add(entry);
        }
        versions.setConverter(new StringConverter<>() {
            @Override
            public String toString(VersionEntry entry) {
                return entry == null ? "" : entry.displayName + "（" + entry.loaderText() + "）";
            }

            @Override
            public VersionEntry fromString(String text) {
                return null;
            }
        });
        VersionEntry current = LauncherEngine.get().selected();
        if (current != null) {
            versions.getSelectionModel().select(current);
        } else if (!versions.getItems().isEmpty()) {
            versions.getSelectionModel().select(0);
        }
        versions.setPrefWidth(300);

        VBox card = new VBox(11);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox titles = new VBox(2,
                Ui.label("下载 · " + hit.title(), "dialog-title"),
                Ui.label("选一个版本，文件会放进它的 mods 文件夹", "dialog-hint"));
        HBox head = new HBox(10, titles, Ui.spacer(), Ui.ghostButton("取消", null, host::hide));
        head.setAlignment(Pos.CENTER_LEFT);

        Button confirm = Ui.primaryButton("确认", null, () -> {
            VersionEntry target = versions.getValue();
            host.hide();
            if (target != null) {
                startDownload(hit, target);
            }
        });
        Button always = Ui.ghostButton("确认（不再提醒）", null, () -> {
            VersionEntry target = versions.getValue();
            host.hide();
            if (target != null) {
                alwaysTarget = target;
                startDownload(hit, target);
            }
        });
        HBox buttons = new HBox(8, Ui.spacer(), confirm, always);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(head, Ui.label("安装到", "field-label"), versions, buttons);
        host.show(card);
    }

    private void startDownload(ModSearch.Hit hit, VersionEntry entry) {
        searchStatus.setText("正在下载 " + hit.title() + "…");
        String gameVersion = entry == null ? "" : entry.gameVersion;
        // 1) 下载中心里先挂一条任务；2) 一个小球飞过去，告诉用户「东西去那边了」
        DownloadsPage.Task task = host.downloadsPage().startTask(hit.title(),
                kindLabel(hit.kind()));
        host.flyToDownloads(downloadButton);
        Thread worker = new Thread(() -> {
            try {
                var dir = ModDownload.targetDir(entry);
                ModDownload.File file = ModDownload.resolve(hit, gameVersion);
                task.size(file.size());
                var saved = ModDownload.download(file, dir,
                        done -> task.progress(done));
                task.done(saved.getFileName().toString());
                Platform.runLater(() -> searchStatus.setText("已下载到 " + saved.getFileName()));
            } catch (Exception error) {
                task.fail(error.getMessage() == null ? "未知错误" : error.getMessage());
                Platform.runLater(() -> searchStatus.setText("下载失败：" + error.getMessage()));
            }
        }, "yi-resource-download");
        worker.setDaemon(true);
        worker.start();
    }

    private static String kindLabel(String kind) {
        String value = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "RESOURCE_PACK", "RESOURCEPACK" -> "资源包";
            case "SHADER" -> "光影";
            default -> "模组";
        };
    }

    /* ============================ 查看详情 / 收藏 ============================ */

    private void favoriteSelected() {
        if (selected == null) {
            return;
        }
        boolean has = Favorites.has(selected.kind(), selected.id());
        if (has) {
            Favorites.remove(selected.kind(), selected.id());
            searchStatus.setText("已取消收藏 " + selected.title());
        } else {
            Favorites.add(new Favorites.Item(selected.kind(), selected.source(), selected.id(),
                    selected.slug(), selected.title(), selected.iconUrl(), selected.pageUrl(),
                    System.currentTimeMillis()));
            searchStatus.setText("已收藏 " + selected.title());
        }
    }

    /** 结果区还没内容时放一个转圈，别让用户对着空白发呆。 */
    private void showLoading(VBox box, String text) {
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.getStyleClass().add("search-loading");
        spinner.setPrefSize(40, 40);
        spinner.setMaxSize(40, 40);
        spinner.setMinSize(40, 40);
        VBox loading = new VBox(10, spinner, Ui.label(text, "version-meta"));
        loading.setAlignment(Pos.CENTER);
        loading.setPadding(new Insets(70, 0, 40, 0));
        loading.getStyleClass().add("search-loading-box");
        box.getChildren().setAll(loading);
    }

    /** 清掉转圈（结果渲染前调用）。 */
    private void hideLoading(VBox box) {
        box.getChildren().removeIf(node -> node.getStyleClass().contains("search-loading-box"));
    }

    /** 查看详情：跟模组管理里选中模组同款面板。 */
    private void openDetails(String title, String keyword) {
        VBox card = new VBox(11);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox titles = new VBox(2,
                Ui.label("查看详情 · " + (title == null || title.isBlank() ? "这个项目" : title), "dialog-title"),
                Ui.label("当前查看系统未全部完成，仅支持搜索", "dialog-hint"));
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

    /* ============================ 收藏界面 ============================ */

    private void buildFavoritesView() {
        Button back = Ui.ghostButton("← 返回", null, this::back);
        back.getStyleClass().add("resource-back");

        favoriteSearch.getStyleClass().addAll("search-field", "search-capsule");
        favoriteSearch.setPromptText("在收藏里搜");
        favoriteSearch.setPrefWidth(320);
        favoriteSearch.textProperty().addListener((obs, old, value) -> {
            favoritePage = 0;
            renderFavorites();
        });

        HBox bar = new HBox(10, back, favoriteSearch, Ui.spacer(), favoriteStatus);
        bar.setAlignment(Pos.CENTER_LEFT);

        bar.getStyleClass().add("resource-search-bar");

        String[][] filters = {{"ALL", "全部"}, {"MOD", "模组"}, {"RESOURCE_PACK", "材质包"}, {"SHADER", "光影包"}};
        for (String[] item : filters) {
            Label chip = Ui.label(item[1], "filter-chip");
            chip.setUserData(item[0]);
            if ("ALL".equals(item[0])) {
                chip.getStyleClass().add("active");
            }
            chip.setOnMouseClicked(e -> {
                favoriteFilter = item[0];
                for (Node node : favoriteFilters.getChildren()) {
                    node.getStyleClass().remove("active");
                }
                chip.getStyleClass().add("active");
                favoritePage = 0;
                renderFavorites();
            });
            favoriteFilters.getChildren().add(chip);
        }
        favoriteFilters.setAlignment(Pos.CENTER_LEFT);

        favoritesList.setPadding(new Insets(0, 6, 8, 0));
        ScrollPane scroll = new ScrollPane(favoritesList);
        scroll.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        favoritePrev.setOnAction(e -> {
            favoritePage = Math.max(0, favoritePage - 1);
            renderFavorites();
        });
        favoriteNext.setOnAction(e -> {
            favoritePage++;
            renderFavorites();
        });
        HBox pager = new HBox(8, favoritePrev, favoritePageLabel, favoriteNext);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.getStyleClass().add("resource-pager");

        favoritesView.getChildren().setAll(bar, favoriteFilters, scroll, pager);
        favoritesView.setPadding(new Insets(0, 4, 4, 0));
    }

    private void openFavorites() {
        favoritePage = 0;
        favoriteFilter = "ALL";
        favoriteSearch.setText("");
        for (Node node : favoriteFilters.getChildren()) {
            node.getStyleClass().remove("active");
        }
        if (!favoriteFilters.getChildren().isEmpty()) {
            favoriteFilters.getChildren().get(0).getStyleClass().add("active");
        }
        switchTo(favoritesView, this::renderFavorites);
    }

    private void renderFavorites() {
        List<Favorites.Item> all = Favorites.all();
        String query = favoriteSearch.getText() == null ? "" : favoriteSearch.getText().trim().toLowerCase(Locale.ROOT);
        List<Favorites.Item> filtered = new ArrayList<>();
        for (Favorites.Item item : all) {
            if (!"ALL".equals(favoriteFilter) && !item.kind().equals(favoriteFilter)) {
                continue;
            }
            if (!query.isBlank()
                    && !(item.title().toLowerCase(Locale.ROOT).contains(query)
                    || item.slug().toLowerCase(Locale.ROOT).contains(query))) {
                continue;
            }
            filtered.add(item);
        }
        int pages = Math.max(1, (filtered.size() + FAVORITE_PAGE_SIZE - 1) / FAVORITE_PAGE_SIZE);
        favoritePage = Math.min(favoritePage, pages - 1);
        int from = favoritePage * FAVORITE_PAGE_SIZE;
        int to = Math.min(filtered.size(), from + FAVORITE_PAGE_SIZE);

        favoritesList.getChildren().clear();
        selectedFavorite = null;
        for (int index = from; index < to; index++) {
            favoritesList.getChildren().add(favoriteRow(filtered.get(index), index, filtered.size()));
        }
        if (filtered.isEmpty()) {
            favoritesList.getChildren().add(Ui.label(
                    all.isEmpty() ? "还没有收藏，去搜索页点个项目收藏吧" : "这个筛选下没有收藏", "version-meta"));
        }
        favoritePageLabel.setText("第 " + (favoritePage + 1) + " / " + pages + " 页");
        favoriteStatus.setText(filtered.size() + " 个收藏");
        favoritePrev.setDisable(favoritePage == 0);
        fadeRows(favoritesList);
        favoriteNext.setDisable(favoritePage >= pages - 1);
    }

    private Node favoriteRow(Favorites.Item item, int index, int total) {
        String kindLabel = switch (item.kind()) {
            case "RESOURCE_PACK" -> "材质包";
            case "SHADER" -> "光影";
            default -> "模组";
        };
        StackPane row = resultRow(item.iconUrl(), item.title(),
                kindLabel + " · " + (item.source().isBlank() ? "" : item.source()),
                "第 " + (index + 1) + " 个", "source-cf", "");
        row.setOnMouseClicked(e -> {
            selectedFavorite = item;
            List<StackPane> rows = new ArrayList<>();
            for (Node node : favoritesList.getChildren()) {
                if (node instanceof StackPane pane) {
                    rows.add(pane);
                }
            }
            highlight(rows, row);
        });

        Button download = Ui.ghostButton("下载", Icons.DOWNLOAD, () -> openDownloadDialog(hitFrom(item)));
        download.getStyleClass().add("favorite-remove");
        Button detail = Ui.ghostButton("查看详情", null,
                () -> openDetails(item.title(), keywordOf(item.slug(), item.title())));
        detail.getStyleClass().add("favorite-remove");
        Button remove = Ui.ghostButton("取消收藏", null, () -> {
            Favorites.remove(item.kind(), item.id());
            renderFavorites();
        });
        remove.getStyleClass().add("favorite-remove");
        if (row.getChildren().size() > 1 && row.getChildren().get(1) instanceof HBox content) {
            content.getChildren().addAll(download, detail, remove);
        }
        return row;
    }

    /** 收藏条目转成一条搜索结果：下载 / 查看详情跟搜索页共用同一套逻辑。 */
    private static ModSearch.Hit hitFrom(Favorites.Item item) {
        return new ModSearch.Hit(item.kind(), item.source(), item.id(), item.slug(),
                item.title(), "", "", item.iconUrl(), item.pageUrl(), 0);
    }
    /* ============================ 原版下载 ============================ */

    private void buildVanillaView() {
        Button back = Ui.ghostButton("← 返回", null, this::back);
        back.getStyleClass().add("resource-back");

        vanillaSearch.getStyleClass().addAll("search-field", "search-capsule");
        vanillaSearch.setPromptText("搜索版本号，例如 1.20.1");
        vanillaSearch.setPrefWidth(320);
        vanillaSearch.textProperty().addListener((obs, old, value) -> {
            vanillaQuery = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            vanillaPage = 0;
            renderVanilla();
        });

        HBox bar = new HBox(10, back, vanillaSearch, Ui.spacer(), vanillaStatus);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("resource-search-bar");

        String[][] filters = {{"release", "正式版"}, {"snapshot", "快照"}, {"ALL", "全部"}};
        for (String[] item : filters) {
            Label chip = Ui.label(item[1], "filter-chip");
            chip.setUserData(item[0]);
            if ("release".equals(item[0])) {
                chip.getStyleClass().add("active");
            }
            chip.setOnMouseClicked(e -> {
                vanillaFilter = item[0];
                for (Node node : vanillaFilters.getChildren()) {
                    node.getStyleClass().remove("active");
                }
                chip.getStyleClass().add("active");
                vanillaPage = 0;
                renderVanilla();
            });
            vanillaFilters.getChildren().add(chip);
        }
        vanillaFilters.setAlignment(Pos.CENTER_LEFT);

        vanillaList.setPadding(new Insets(0, 6, 8, 0));
        ScrollPane scroll = new ScrollPane(vanillaList);
        scroll.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        vanillaPrev.setOnAction(e -> {
            vanillaPage = Math.max(0, vanillaPage - 1);
            renderVanilla();
        });
        vanillaNext.setOnAction(e -> {
            vanillaPage++;
            renderVanilla();
        });
        HBox pager = new HBox(8, vanillaPrev, vanillaPageLabel, vanillaNext);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.getStyleClass().add("resource-pager");

        vanillaView.getChildren().setAll(bar, vanillaFilters, scroll, pager);
        vanillaView.setPadding(new Insets(0, 4, 4, 0));
    }

    private void openVanilla() {
        switchTo(vanillaView, this::loadVanillaManifest);
    }

    /** 版本清单镜像优先，拿过一次就缓存住。 */
    private void loadVanillaManifest() {
        if (!vanillaVersions.isEmpty()) {
            renderVanilla();
            return;
        }
        vanillaStatus.setText("正在读取版本列表…");
        showLoading(vanillaList, "正在读取版本列表…");
        Thread worker = new Thread(() -> {
            try {
                List<VanillaVersions.Version> list = VanillaVersions.manifest();
                Platform.runLater(() -> {
                    vanillaVersions = list;
                    vanillaPage = 0;
                    renderVanilla();
                });
            } catch (Exception error) {
                Platform.runLater(() -> vanillaStatus.setText("获取版本列表失败：" + error.getMessage()));
            }
        }, "yi-vanilla-manifest");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderVanilla() {
        List<VanillaVersions.Version> filtered = new ArrayList<>();
        for (VanillaVersions.Version version : vanillaVersions) {
            if (!"ALL".equals(vanillaFilter) && !version.type().equals(vanillaFilter)) {
                continue;
            }
            if (!vanillaQuery.isBlank() && !version.id().toLowerCase(Locale.ROOT).contains(vanillaQuery)) {
                continue;
            }
            filtered.add(version);
        }
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        vanillaPage = Math.min(vanillaPage, pages - 1);
        int from = vanillaPage * PAGE_SIZE;
        int to = Math.min(filtered.size(), from + PAGE_SIZE);

        vanillaList.getChildren().clear();
        for (int index = from; index < to; index++) {
            vanillaList.getChildren().add(vanillaRow(filtered.get(index)));
        }
        if (filtered.isEmpty()) {
            vanillaList.getChildren().add(Ui.label(
                    vanillaVersions.isEmpty() ? "还没有读到版本列表" : "这个筛选下没有版本", "version-meta"));
        }
        fadeRows(vanillaList);
        vanillaPageLabel.setText("第 " + (vanillaPage + 1) + " / " + pages + " 页");
        vanillaStatus.setText(filtered.size() + " 个版本");
        vanillaPrev.setDisable(vanillaPage == 0);
        vanillaNext.setDisable(vanillaPage >= pages - 1);
    }

    private Node vanillaRow(VanillaVersions.Version version) {
        Label letter = Ui.label(initial(version.id()), "mod-icon-letter");
        StackPane icon = new StackPane(letter);
        icon.getStyleClass().add("mod-icon");
        icon.setMinSize(36, 36);
        icon.setMaxSize(36, 36);

        Label name = Ui.label(version.id(), "mod-name");
        String date = version.releaseTime() <= 0 ? "" : java.time.Instant.ofEpochMilli(version.releaseTime())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
        Label meta = Ui.label(version.typeLabel() + (date.isBlank() ? "" : " · " + date), "mod-meta");
        VBox texts = new VBox(2, name, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label hint = Ui.label("点击安装", "mod-chip");
        HBox content = new HBox(11, icon, texts, hint);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(9, 12, 9, 12));

        Region highlight = new Region();
        highlight.getStyleClass().add("mod-row-hl");
        highlight.setMouseTransparent(true);
        highlight.setOpacity(0);
        highlight.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        StackPane row = new StackPane(highlight, content);
        row.getStyleClass().add("mod-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> openInstallDialog(version));
        return row;
    }

    /** 点原版版本：二次界面选加载器（可带版本）+ 是否高清修复。 */
    private void openInstallDialog(VanillaVersions.Version version) {
        ComboBox<LoaderVersions.Loader> loaderBox = new ComboBox<>();
        loaderBox.getItems().addAll(LoaderVersions.loaders());
        loaderBox.getSelectionModel().select(LoaderVersions.NONE);
        loaderBox.setPrefWidth(260);

        Label versionLabel = Ui.label("加载器版本", "field-label");
        ComboBox<String> versionBox = new ComboBox<>();
        versionBox.setEditable(true);
        versionBox.setPrefWidth(260);
        versionBox.setDisable(true);
        versionBox.setPromptText("选加载器后自动列出，也可以手填");

        Label hint = Ui.label("只安装原版 " + version.id() + "。", "dialog-hint");
        hint.setWrapText(true);

        CheckBox optifine = new CheckBox("同时安装 高清修复（OptiFine）");
        optifine.getStyleClass().add("dialog-check");

        // 只要「加载器 + 高清修复」同时选上就把兼容性问题的提示摆出来
        Label warn = Ui.label("", "dialog-warn");
        warn.setWrapText(true);
        warn.setMaxWidth(330);
        warn.setVisible(false);
        warn.setManaged(false);
        Runnable refreshWarn = () -> {
            LoaderVersions.Loader now = loaderBox.getValue();
            boolean both = now != null && !now.id().isBlank() && optifine.isSelected();
            warn.setText(both ? "高清修复和 " + now.label()
                    + " 一起用，常出现渲染异常、启动崩溃、模组冲突，不建议同时安装。" : "");
            warn.setVisible(both);
            warn.setManaged(both);
        };

        loaderBox.valueProperty().addListener((obs, old, now) -> {
            boolean hasLoader = now != null && !now.id().isBlank();
            versionBox.getItems().clear();
            versionBox.setDisable(!hasLoader);
            versionLabel.setVisible(hasLoader);
            versionLabel.setManaged(hasLoader);
            refreshWarn.run();
            if (!hasLoader) {
                hint.setText("只安装原版 " + version.id() + "。");
                return;
            }
            hint.setText("正在读取 " + now.label() + " 的版本…");
            Thread worker = new Thread(() -> {
                List<String> versions = LoaderVersions.list(now, version.id());
                Platform.runLater(() -> {
                    versionBox.getItems().setAll(versions);
                    if (!versions.isEmpty()) {
                        versionBox.getSelectionModel().select(0);
                    }
                    hint.setText(versions.isEmpty()
                            ? "没拿到版本列表，可以直接手填 " + now.label() + " 版本号"
                            : now.label() + " 共 " + versions.size() + " 个版本，默认最新");
                });
            }, "yi-loader-versions");
            worker.setDaemon(true);
            worker.start();
        });
        optifine.selectedProperty().addListener((obs, old, now) -> refreshWarn.run());

        VBox card = new VBox(10);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox titles = new VBox(2,
                Ui.label("安装 " + version.id(), "dialog-title"),
                Ui.label("顺便装模组加载器，或者高清修复", "dialog-hint"));
        HBox head = new HBox(10, titles, Ui.spacer(), Ui.ghostButton("取消", null, host::hide));
        head.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);

        Button start = Ui.primaryButton("开始安装", null, () -> {
            LoaderVersions.Loader loader = loaderBox.getValue();
            String loaderVersion = versionBox.getValue() == null ? "" : versionBox.getValue().trim();
            boolean wantOptifine = optifine.isSelected();
            boolean hasLoader = loader != null && !loader.id().isBlank();
            if (hasLoader && loaderVersion.isBlank()) {
                hint.setText("请选择或填写 " + loader.label() + " 的版本");
                return;
            }
            if (hasLoader && wantOptifine) {
                // 两者同时装：先看一眼兼容性提醒，确认后才真正开始
                confirmOptifine(loader.label(), () -> {
                    host.hide();
                    installVersion(version, loader, loaderVersion, true);
                }, () -> host.show(card));
                return;
            }
            host.hide();
            installVersion(version, loader, loaderVersion, wantOptifine);
        });

        VBox optifineBox = new VBox(6, optifine, warn);
        card.getChildren().addAll(head,
                Ui.label("模组加载器", "field-label"), loaderBox,
                versionLabel, versionBox,
                hint, optifineBox, start);
        host.show(card);
    }

    /** 同时选高清修复 + 加载器时的兼容性提醒。 */
    private void confirmOptifine(String loaderLabel, Runnable onContinue, Runnable onBack) {
        VBox card = new VBox(12);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label warning = Ui.label("高清修复和 " + loaderLabel + " 这类模组加载器一起用，"
                + "容易出现渲染异常、启动崩溃、优化类模组冲突。"
                + "想让游戏更流畅，建议只留其中一种。", "dialog-hint");
        warning.setWrapText(true);
        warning.setMaxWidth(330);

        Button back = Ui.ghostButton("返回修改", null, () -> {
            host.hide();
            if (onBack != null) {
                onBack.run();
            }
        });
        Button go = Ui.primaryButton("仍然安装", null, () -> {
            host.hide();
            onContinue.run();
        });
        HBox buttons = new HBox(8, Ui.spacer(), back, go);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(Ui.label("兼容性提醒", "dialog-title"), warning, buttons);
        host.show(card);
    }

    /** 真正开始安装：原版 →（可选）加载器 →（可选）提示高清修复。 */
    private void installVersion(VanillaVersions.Version version, LoaderVersions.Loader loader,
                                String loaderVersion, boolean optifine) {
        vanillaStatus.setText("准备安装 " + version.id() + "…");
        Path root = LauncherEngine.get().root();
        Path versionsDir = MinecraftRoot.versions(root);
        Thread worker = new Thread(() -> {
            try {
                VanillaVersions.install(root, versionsDir, version.id(), (note, ratio) ->
                        Platform.runLater(() -> vanillaStatus.setText(
                                note + " " + Math.round(ratio * 100) + "%")));
                if (loader != null && !loader.id().isBlank()) {
                    Platform.runLater(() -> vanillaStatus.setText(
                            "安装 " + loader.label() + " " + loaderVersion + "…"));
                    LoaderInstaller.ensure(root, versionsDir, loader.id(), version.id(), loaderVersion,
                            (note, ratio) -> Platform.runLater(() -> vanillaStatus.setText(note)));
                }
                Platform.runLater(() -> {
                    vanillaStatus.setText(version.id() + " 安装完成");
                    LauncherEngine.get().scanAsync(list -> {
                    });
                });
                if (optifine) {
                    // 高清修复没有公开的下载接口，装完把官网打开让用户自己选版本
                    openUrl("https://optifine.net/downloads");
                }
            } catch (Exception error) {
                Platform.runLater(() -> vanillaStatus.setText("安装失败：" + error.getMessage()));
            }
        }, "yi-vanilla-install");
        worker.setDaemon(true);
        worker.start();
    }

    /* ============================ 动画 / 工具 ============================ */

    /**
     * 换界面：整块淡出 → 换内容 → 整块淡入。
     * <p>以前是「逐块错峰淡出 + 固定延时再换」，点快了就会出现某个方块慢半拍；
     * 从搜索页返回时 chooser 还停在透明度 0，看上去「模块全没了但还能点」。
     * 现在动画结束时把值写死（透明度 1 / 位移 0），并用世代号丢掉过期回调，
     * 无论怎么打断都一定回到完整可见的状态。</p>
     */
    private void switchTo(Node target, Runnable after) {
        int token = ++switchToken;
        Node current = area.getChildren().isEmpty() ? null : area.getChildren().get(0);
        if (current == target) {
            settle(target);
            if (after != null) {
                after.run();
            }
            return;
        }
        if (current == chooser) {
            // 按住方块时那块会变淡，先归位，免得淡出时它比别的方块亮一档
            for (Node card : cards) {
                Motion.settle(card);
            }
        }
        if (current == null) {
            install(target, token, after);
            return;
        }
        Runnable swap = () -> install(target, token, after);
        animate(current, 1, 0, 0, -12, 150, Motion.EASE_IN, swap);
    }

    private void install(Node target, int token, Runnable after) {
        if (token != switchToken) {
            return;
        }
        area.getChildren().setAll(target);
        settle(target);
        animate(target, 0, 1, 22, 0, 220, Motion.SOFT, () -> {
            if (token == switchToken && after != null) {
                after.run();
            }
        });
    }

    /** 把节点（以及选择界面的所有方块）摆回「完全可见」，绝不留下透明但吃点击的状态。 */
    private void settle(Node node) {
        if (node == chooser) {
            for (Node card : cards) {
                Motion.settle(card);
            }
        }
        Motion.settle(node);
    }

    /** 同一时刻只跑一条切换动画：透明度与位移一起改，结束时把值写死。 */
    private void animate(Node node, double fromOpacity, double toOpacity,
                         double fromY, double toY, int ms, Interpolator ease, Runnable done) {
        if (switcher != null) {
            switcher.stop();
            switcher = null;
        }
        node.setOpacity(fromOpacity);
        node.setTranslateY(fromY);
        Timeline line = new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(node.opacityProperty(), toOpacity, ease),
                new KeyValue(node.translateYProperty(), toY, ease)));
        line.setOnFinished(event -> {
            node.setOpacity(toOpacity);
            node.setTranslateY(toY);
            if (switcher == line) {
                switcher = null;
            }
            if (done != null) {
                done.run();
            }
        });
        switcher = line;
        line.play();
    }

    /** 列表逐行淡入（间隔 22ms），翻页 / 刷新时不生硬。 */
    private void fadeRows(VBox box) {
        int index = 0;
        for (Node node : box.getChildren()) {
            if (!(node instanceof StackPane)) {
                continue;
            }
            node.setOpacity(0);
            node.setTranslateY(8);
            PauseTransition delay = new PauseTransition(
                    Duration.millis(Math.min(320, index * 22) / Math.max(0.2, Motion.speed())));
            delay.setOnFinished(event -> {
                Motion.opacity(node, 1, 180, Motion.SOFT);
                Motion.translate(node, 0, 0, 200, Motion.SOFT);
            });
            delay.play();
            index++;
        }
    }

    private void back() {
        // 回到选择模块：清掉「不再提醒」，下次进搜索重新弹确认框
        alwaysTarget = null;
        switchTo(chooser, null);
    }

    /** 图标走共用的 IconCache（4 线程 + 缓存），搜索结果多时也不会卡。 */
    private void loadIcon(StackPane box, String url) {
        IconCache.load(url, image -> box.getChildren().setAll(iconView(image)));
    }

    private static ImageView iconView(Image image) {
        ImageView view = new ImageView(image);
        view.setFitWidth(36);
        view.setFitHeight(36);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(36, 36);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        view.setClip(clip);
        return view;
    }

    private static String keywordOf(String slug, String title) {
        if (slug != null && !slug.isBlank()) {
            return slug;
        }
        return title == null ? "" : title;
    }

    private static String mod(String summary) {
        if (summary == null) {
            return "";
        }
        String text = summary.replace('\n', ' ').trim();
        return text.length() > 90 ? text.substring(0, 90) + "…" : text;
    }

    private static String downloads(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    private static String initial(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void openUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecute(null, "open", url.trim(), null, null, 1);
            return;
        } catch (Throwable ignored) {
            // 退到 cmd
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url.trim()).start();
        } catch (Exception ignored) {
        }
    }
}
