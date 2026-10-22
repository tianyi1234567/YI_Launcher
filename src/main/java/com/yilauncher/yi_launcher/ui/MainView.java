/* ==========================================================================
 *  衣启动器 · ui 层 ｜ MainView.java —— 窗口外壳
 *  做什么：透明圆角玻璃、标题栏（拖窗 / 最小化 / 最大化 / 关闭）、左侧导航、页面容器、
 *          贴边拖大、窗口级拖放安装、遮罩弹窗层，并负责界面偏好（不透明度 / 圆角 / 动效速度）。
 *  谁在用：LauncherApp 把它当根节点；各页面反过来调它（selectPage / openVersionSettings / show）。
 *  入口：selectPage()、buildTitleBar()、buildSidebar()、buildOverlay()、installDropTarget()。
 *  分段：界面偏好 ｜ 边框拖动改大小 ｜ 拖拽安装 ｜ 弹窗层 ｜ 玻璃柔光 ｜ 标题栏 ｜ 左侧导航 ｜ 小工具。
 * ========================================================================== */
package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherConfig;
import com.yilauncher.yi_launcher.core.LauncherEngine;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 窗口外壳：透明圆角玻璃 + 标题栏 + 左侧导航 + 页面容器。
 * <p>具体内容由 {@link LaunchPage}、{@link VersionsPage} 提供，点击导航切换。</p>
 */
public final class MainView extends StackPane implements Ui.OverlayHost {

    /** 背景圆角 26px，JavaFX 的 Rectangle 用"圆角直径"，所以要乘 2。 */
    private static final double CORNER_ARC = 52;

    private final Stage stage;
    private final StackPane glass = new StackPane();
    /** 静态底板：背景渐变 + 外发光，缓存后动画不再触发高斯模糊重算。 */
    private final Region backdrop = new Region();
    private final StackPane pageHost = new StackPane();
    private final StackPane overlayRoot = new StackPane();
    private final StackPane overlayCard = new StackPane();
    private final List<Node> navRows = new ArrayList<>();
    private final Map<String, Node> pages = new LinkedHashMap<>();
    /** 当前显示的页面（切走时要通知页面自己收尾，比如模组管理重置淡入动画）。 */
    private Node currentPage;
    /** 下载中心导航项上的小红点。 */
    private javafx.scene.control.Label downloadBadge;
    /** navRow 建小红点时临时存一下，方便外面绑定。 */
    private javafx.scene.control.Label lastBadge;

    private Rectangle blobClip;
    private static final double EDGE = 7;
    private static final double CORNER = 14;
    private static final double MIN_WIDTH = 1000;
    private static final double MIN_HEIGHT = 640;
    private final List<Region> resizeHandles = new ArrayList<>();
    private boolean maximized;
    private Rectangle2D restoreBounds;
    private long overlayToken;
    private SkinHeads.SkinAvatar accountAvatar;
    private javafx.scene.control.Label accountName;
    private javafx.scene.control.Label accountKind;
    private javafx.scene.control.Tooltip accountTip;
    /** 拖文件进来时盖在整窗上的提示层。 */
    private javafx.scene.layout.StackPane dropHint;

    /** 设置页调过的界面参数：圆角半径、整体不透明度。 */
    private double uiRadius = 26;
    private double uiOpacity = 1;
    private boolean closing;

    /* 拖动 / 缩放：拖动事件只记坐标，真正改窗口位置每帧只做一次（防止高回报率鼠标把
       FX 线程刷满、窗口追不上光标）。 */
    private boolean dragging;
    private double dragAnchorStageX;
    private double dragAnchorStageY;
    private double dragAnchorScreenX;
    private double dragAnchorScreenY;
    private double dragPendingScreenX;
    private double dragPendingScreenY;

    private boolean resizing;
    private javafx.scene.Cursor resizeCursor;
    private double resizeAnchorStageX;
    private double resizeAnchorStageY;
    private double resizeAnchorWidth;
    private double resizeAnchorHeight;
    private double resizeAnchorScreenX;
    private double resizeAnchorScreenY;
    private double resizePendingScreenX;
    private double resizePendingScreenY;


    private final javafx.animation.AnimationTimer dragLoop = new javafx.animation.AnimationTimer() {
        @Override
        public void handle(long now) {
            if (!dragging) {
                stop();
                return;
            }
            double x = Math.round(dragAnchorStageX + (dragPendingScreenX - dragAnchorScreenX));
            double y = Math.round(dragAnchorStageY + (dragPendingScreenY - dragAnchorScreenY));
            if (Math.abs(x - stage.getX()) >= 1 || Math.abs(y - stage.getY()) >= 1) {
                stage.setX(x);
                stage.setY(y);
            }
        }
    };

    private final javafx.animation.AnimationTimer resizeLoop = new javafx.animation.AnimationTimer() {
        @Override
        public void handle(long now) {
            if (!resizing) {
                stop();
                return;
            }
            applyResize();
        }
    };

    public MainView(Stage stage) {
        this.stage = stage;
        getStyleClass().add("window-root");

        pageHost.getStyleClass().add("page-host");

        glass.getStyleClass().add("glass");
        glass.setMinSize(940, 580);

        // 底板层：背景渐变 + 外发光，缓存成位图后动画期间不再逐帧重算模糊
        backdrop.getStyleClass().add("glass-backdrop");
        backdrop.setMouseTransparent(true);
        backdrop.setCache(true);
        backdrop.setCacheHint(javafx.scene.CacheHint.SPEED);

        Pane blobs = buildBlobs();
        // 柔光斑是静态的，也缓存起来（径向渐变逐帧重绘很贵）
        blobs.setCache(true);
        blobs.setCacheHint(javafx.scene.CacheHint.SPEED);

        BorderPane body = new BorderPane();
        body.setTop(buildTitleBar());
        body.setLeft(buildSidebar());
        body.setCenter(pageHost);

        showPage("启动");
        buildOverlay();

        glass.getChildren().addAll(blobs, body, overlayRoot, buildDropHint());
        getChildren().addAll(backdrop, glass);
        buildResizeHandles();
        installDropTarget();

        // 套用设置页保存的界面偏好（动画速度 / 不透明度 / 圆角）
        applySavedUi();

        // Alt+F4 / 任务栏关闭也走同一套关窗动画，别只有 ✕ 才有动画
        stage.setOnCloseRequest(event -> {
            if (closing) {
                return;
            }
            event.consume();
            closeWindow();
        });
        // 兜底：万一某一帧没收到 MOUSE_RELEASED（拖到窗外松手、切窗口等），
        // 松开鼠标就结束拖动/缩放，别让位图缓存一直挂着
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
            endDrag();
            endResize();
        });
    }

    /** 关窗：先播 220ms 的收拢 + 淡出，再真正退出。 */
    private void closeWindow() {
        if (closing) {
            return;
        }
        closing = true;
        // 连底板一起淡出（底板不再是 glass 的一部分）
        Motion.windowClose(this, Platform::exit);
    }

    /* ============================ 界面偏好（设置页） ============================ */
    private void applySavedUi() {
        LauncherConfig.UiSettings ui = LauncherEngine.get().config().ui();
        Motion.setSpeed(ui.speed());
        uiOpacity = ui.opacity();
        uiRadius = ui.radius();
        applyGlassRadius();
        applyUiOpacity();
    }

    /** 圆角同时作用在底板、柔光裁剪上；最大化时临时归零。 */
    private void applyGlassRadius() {
        double radius = maximized ? 0 : uiRadius;
        backdrop.setStyle("-fx-background-radius: " + round(radius) + "px;"
                + "-fx-border-radius: " + round(radius) + "px;");
        setArc(blobClip, radius * 2);
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f", value);
    }

    /** 不透明度同时作用在底板与内容层（底板独立后，只调 glass 会露出不透明白底）。 */
    private void applyUiOpacity() {
        glass.setOpacity(uiOpacity);
        backdrop.setOpacity(uiOpacity);
    }

    /** 设置页：界面整体不透明度（0.6 – 1.0）。 */
    public void setUiOpacity(double value) {
        uiOpacity = Math.max(0.6, Math.min(1.0, value));
        applyUiOpacity();
        persistUi();
    }


    /** 设置页：窗口圆角（0 – 34 px）。 */
    public void setUiRadius(double value) {
        uiRadius = Math.max(0, Math.min(34, value));
        applyGlassRadius();
        persistUi();
    }

    /** 设置页：动效速度倍率。 */
    public void setMotionSpeed(double value) {
        Motion.setSpeed(value);
        persistUi();
    }

    private void persistUi() {
        LauncherConfig.UiSettings ui = LauncherEngine.get().config().ui();
        ui.motionSpeed = Motion.speed();
        ui.uiOpacity = uiOpacity;
        ui.cornerRadius = uiRadius;
        LauncherEngine.get().config().save();
    }

    public LauncherConfig.UiSettings uiSettings() {
        return LauncherEngine.get().config().ui();
    }


    /* ============================ 边框拖动改大小 ============================ */
    /** 透明窗口的四边 / 四角拖拽手柄：拖动时改 stage 的位置和尺寸。 */
    private void buildResizeHandles() {
        resizeHandles.clear();
        addResizeHandle(javafx.scene.Cursor.N_RESIZE, Pos.TOP_CENTER, EDGE, false);
        addResizeHandle(javafx.scene.Cursor.S_RESIZE, Pos.BOTTOM_CENTER, EDGE, false);
        addResizeHandle(javafx.scene.Cursor.W_RESIZE, Pos.CENTER_LEFT, EDGE, false);
        addResizeHandle(javafx.scene.Cursor.E_RESIZE, Pos.CENTER_RIGHT, EDGE, false);
        addResizeHandle(javafx.scene.Cursor.NW_RESIZE, Pos.TOP_LEFT, CORNER, true);
        addResizeHandle(javafx.scene.Cursor.NE_RESIZE, Pos.TOP_RIGHT, CORNER, true);
        addResizeHandle(javafx.scene.Cursor.SW_RESIZE, Pos.BOTTOM_LEFT, CORNER, true);
        addResizeHandle(javafx.scene.Cursor.SE_RESIZE, Pos.BOTTOM_RIGHT, CORNER, true);
    }

    private void addResizeHandle(javafx.scene.Cursor cursor, Pos position, double size, boolean corner) {
        Region handle = new Region();
        handle.setCursor(cursor);
        handle.setStyle("-fx-background-color: transparent;");
        handle.setMinSize(corner ? size : (isVertical(cursor) ? Region.USE_PREF_SIZE : size),
                corner ? size : (isVertical(cursor) ? size : Region.USE_PREF_SIZE));
        if (corner) {
            handle.setPrefSize(size, size);
            handle.setMaxSize(size, size);
        } else if (isVertical(cursor)) {
            handle.setPrefHeight(size);
            handle.setMaxHeight(size);
            handle.setMaxWidth(Double.MAX_VALUE);
        } else {
            handle.setPrefWidth(size);
            handle.setMaxWidth(size);
            handle.setMaxHeight(Double.MAX_VALUE);
        }
        StackPane.setAlignment(handle, position);
        StackPane.setMargin(handle, Insets.EMPTY);
        installResize(handle, cursor);
        resizeHandles.add(handle);
        getChildren().add(handle);
    }

    private static boolean isVertical(javafx.scene.Cursor cursor) {
        return cursor == javafx.scene.Cursor.N_RESIZE || cursor == javafx.scene.Cursor.S_RESIZE
                || cursor == javafx.scene.Cursor.NW_RESIZE || cursor == javafx.scene.Cursor.NE_RESIZE
                || cursor == javafx.scene.Cursor.SW_RESIZE || cursor == javafx.scene.Cursor.SE_RESIZE;
    }

    private void installResize(Region handle, javafx.scene.Cursor cursor) {
        handle.setOnMousePressed(e -> {
            if (maximized) {
                return;
            }
            resizing = true;
            resizeCursor = cursor;
            resizeAnchorStageX = stage.getX();
            resizeAnchorStageY = stage.getY();
            resizeAnchorWidth = stage.getWidth();
            resizeAnchorHeight = stage.getHeight();
            resizeAnchorScreenX = e.getScreenX();
            resizeAnchorScreenY = e.getScreenY();
            resizePendingScreenX = resizeAnchorScreenX;
            resizePendingScreenY = resizeAnchorScreenY;
            glass.setCache(true);
            glass.setCacheHint(javafx.scene.CacheHint.SPEED);
            resizeLoop.start();
            e.consume();
        });
        handle.setOnMouseDragged(e -> {
            if (!resizing) {
                return;
            }
            // 只记最新坐标，真正改尺寸交给每帧一次的 resizeLoop
            resizePendingScreenX = e.getScreenX();
            resizePendingScreenY = e.getScreenY();
            e.consume();
        });
        handle.setOnMouseReleased(e -> endResize());
    }

    private void endResize() {
        if (!resizing) {
            return;
        }
        applyResize();
        resizing = false;
        glass.setCache(false);
    }

    /** 把最新鼠标坐标换算成窗口位置与尺寸；位置/尺寸都取整，避免半像素重采样让文字发虚。 */
    private void applyResize() {
        javafx.scene.Cursor cursor = resizeCursor;
        if (cursor == null) {
            return;
        }
        double dx = resizePendingScreenX - resizeAnchorScreenX;
        double dy = resizePendingScreenY - resizeAnchorScreenY;
        double x = resizeAnchorStageX;
        double y = resizeAnchorStageY;
        double w = resizeAnchorWidth;
        double h = resizeAnchorHeight;
        double minW = Math.max(stage.getMinWidth(), MIN_WIDTH);
        double minH = Math.max(stage.getMinHeight(), MIN_HEIGHT);

        boolean west = cursor == javafx.scene.Cursor.W_RESIZE
                || cursor == javafx.scene.Cursor.NW_RESIZE
                || cursor == javafx.scene.Cursor.SW_RESIZE;
        boolean east = cursor == javafx.scene.Cursor.E_RESIZE
                || cursor == javafx.scene.Cursor.NE_RESIZE
                || cursor == javafx.scene.Cursor.SE_RESIZE;
        boolean north = cursor == javafx.scene.Cursor.N_RESIZE
                || cursor == javafx.scene.Cursor.NW_RESIZE
                || cursor == javafx.scene.Cursor.NE_RESIZE;
        boolean south = cursor == javafx.scene.Cursor.S_RESIZE
                || cursor == javafx.scene.Cursor.SW_RESIZE
                || cursor == javafx.scene.Cursor.SE_RESIZE;

        if (west) {
            w = Math.max(minW, resizeAnchorWidth - dx);
            x = resizeAnchorStageX + (resizeAnchorWidth - w);
        } else if (east) {
            w = Math.max(minW, resizeAnchorWidth + dx);
        }
        if (north) {
            h = Math.max(minH, resizeAnchorHeight - dy);
            y = resizeAnchorStageY + (resizeAnchorHeight - h);
        } else if (south) {
            h = Math.max(minH, resizeAnchorHeight + dy);
        }

        double rx = Math.round(x);
        double ry = Math.round(y);
        double rw = Math.round(w);
        double rh = Math.round(h);
        if (Math.abs(rx - stage.getX()) >= 0.5) {
            stage.setX(rx);
        }
        if (Math.abs(ry - stage.getY()) >= 0.5) {
            stage.setY(ry);
        }
        if (Math.abs(rw - stage.getWidth()) >= 0.5) {
            stage.setWidth(rw);
        }
        if (Math.abs(rh - stage.getHeight()) >= 0.5) {
            stage.setHeight(rh);
        }
    }
    /* ============================ 拖拽安装 ============================ */

    /** 窗口级拖拽：拖 .zip 整合包 / .jar 模组进来播放遮罩，松手交给 DropInstall。 */
    private void installDropTarget() {
        // 挂在 Scene 上：切到任何页面、拖到任何子控件上都还能收到拖拽事件
        sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null || scene == oldScene) {
                return;
            }
            scene.setOnDragOver(event -> {
                if (event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
                event.consume();
            });
            scene.setOnDragEntered(event -> {
                if (event.getDragboard().hasFiles()) {
                    showDropHint(true);
                }
            });
            scene.setOnDragExited(event -> showDropHint(false));
            scene.setOnDragDropped(event -> {
                boolean hasFiles = event.getDragboard().hasFiles();
                showDropHint(false);
                if (hasFiles) {
                    DropInstall.handle(event.getDragboard().getFiles(), this, this::afterInstall);
                }
                event.setDropCompleted(hasFiles);
                event.consume();
            });
        });
    }

    /** 拖拽时盖在整窗上的提示层：中间一张卡片写着"松手就能安装"。 */
    private Node buildDropHint() {
        VBox card = new VBox(4,
                Ui.label("松手就能安装", "drop-title"),
                Ui.label("整合包会先问名称和分组；模组装进当前选择的版本", "drop-hint"));
        card.getStyleClass().add("drop-overlay-card");
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane layer = new StackPane(card);
        layer.getStyleClass().add("drop-overlay");
        layer.setVisible(false);
        layer.setManaged(false);
        layer.setMouseTransparent(true);
        dropHint = layer;
        return layer;
    }

    private void showDropHint(boolean show) {
        if (dropHint == null || show == dropHint.isVisible()) {
            return;
        }
        dropHint.setVisible(show);
        dropHint.setManaged(show);
        if (show) {
            dropHint.setOpacity(0);
            Motion.opacity(dropHint, 1, 140, Motion.SOFT);
        }
    }

    /** 拖拽安装要用的页面：没建过就提前建好（不切过去），这样进度条能在后台先播。 */
    public DownloadsPage downloadsPage() {
        Node existing = pages.get("下载中心");
        if (existing instanceof DownloadsPage page) {
            return page;
        }
        DownloadsPage page = new DownloadsPage(this);
        page.getStyleClass().add("page");
        pages.put("下载中心", page);
        return page;
    }

    /** 切到下载中心并返回它（装整合包时用：进度动画得看得见）。 */
    public DownloadsPage openDownloads() {
        DownloadsPage page = downloadsPage();
        selectPage("下载中心");
        return page;
    }

    /** 安装完成后重新扫描并刷新受影响的页面。 */
    private void afterInstall() {
        LauncherEngine.get().scanAsync(list -> Platform.runLater(() -> {
            if (pages.get("启动") instanceof LaunchPage page) {
                page.refresh();
            }
            if (pages.get("版本管理") instanceof VersionsPage page) {
                page.reload();
            }
            // 下载中心已经只剩「拖入安装 + 安装进度」，装完不需要额外刷新
        }));
    }

    /* ============================ 弹窗层 ============================ */


    private void buildOverlay() {
        Region scrim = new Region();
        scrim.getStyleClass().add("scrim");
        scrim.setOnMouseClicked(e -> hide());

        overlayCard.getStyleClass().add("overlay-card");
        overlayCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        overlayRoot.getStyleClass().add("overlay");
        overlayRoot.getChildren().addAll(scrim, overlayCard);
        overlayRoot.setVisible(false);
    }

    @Override
    public void show(Node content) {
        overlayToken++;
        overlayCard.getChildren().setAll(content);
        overlayRoot.setVisible(true);
        overlayRoot.toFront();
        // 小面板跳出来：从 0.9 倍弹到原大小，带一点过冲
        Motion.popIn(overlayCard);
    }

    @Override
    public void hide() {
        if (!overlayRoot.isVisible()) {
            return;
        }
        long token = ++overlayToken;
        Motion.popOut(overlayCard, 150, () -> {
            if (token != overlayToken) {
                // 淡出期间又开了新面板，别把新的清掉
                return;
            }
            overlayRoot.setVisible(false);
            overlayCard.getChildren().clear();
            overlayCard.setOpacity(1);
            overlayCard.setScaleX(1);
            overlayCard.setScaleY(1);
            overlayCard.setTranslateY(0);
        });
    }

    /** 供自检 / 外部调用：按名字切换页面（"启动" / "版本管理"，也接受 launch / versions 别名）。 */
    public void selectPage(String name) {
        String key = switch (name) {
            case "launch", "home" -> "启动";
            case "versions", "version" -> "版本管理";
            default -> name;
        };
        for (Node row : navRows) {
            if (key.equals(row.getUserData())) {
                activateNav(row);
                return;
            }
        }
    }

    private void showPage(String name) {
        Node page = pages.get(name);
        if (page == null) {
            // 延迟到第一次显示时才创建：页面在宿主已经拿到真实尺寸后再进场景，
            // 可以避免 JavaFX 先按 0/无穷宽度排一次版、把内部控件的尺寸算坏。
            page = "启动".equals(name) ? new LaunchPage(this::openVersionSettings, this)
                    : "版本管理".equals(name) ? new VersionsPage(this)
                    : "模组管理".equals(name) ? new ModsPage(this)
                    : "下载中心".equals(name) ? new DownloadsPage(this)
                    : "设置".equals(name) ? new SettingsPage(this)
                    : buildPlaceholder(name);
            pages.put(name, page);
            page.getStyleClass().add("page");
        }
        // 真的换页了才通知：同一个页面重复点不算「重新打开」，模组管理的淡入不会重播
        if (currentPage != null && currentPage != page && currentPage instanceof ModsPage mods) {
            mods.onHidden();
        }
        if (!pageHost.getChildren().contains(page) || pageHost.getChildren().size() != 1) {
            // 只把当前页面留在场景里：残留页面会让 StackPane 拿到过大的最小尺寸，
            // 进而把命中区域撑到侧边栏上，导致切页后左侧点不动。
            pageHost.getChildren().setAll(page);
        }
        page.setVisible(true);
        page.setManaged(true);
        // 页面刚进场景时 JavaFX 会把焦点交给第一个输入框（搜索框），看起来像一直在输入
        Motion.pageIn(page);
        page.setFocusTraversable(true);
        Platform.runLater(page::requestFocus);
        currentPage = page;
        if (page instanceof ModsPage mods) {
            mods.onShown();
        }
    }

    /** 启动页的「版本设置」：切到版本管理，并直接打开这个版本的详情。 */
    public void openVersionSettings(com.yilauncher.yi_launcher.core.VersionEntry entry) {
        if (entry != null) {
            com.yilauncher.yi_launcher.core.VersionGroup group =
                    com.yilauncher.yi_launcher.core.LauncherEngine.get().group(entry.groupId);
            if (group != null) {
                com.yilauncher.yi_launcher.core.LauncherEngine.get().selectGroup(group);
            }
            com.yilauncher.yi_launcher.core.LauncherEngine.get().select(entry);
        }
        showPage("版本管理");
        Node page = pages.get("版本管理");
        if (page instanceof VersionsPage versions) {
            versions.focusSelected();
        }
        selectPage("版本管理");
    }

    private Node buildPlaceholder(String name) {
        VBox card = new VBox(6,
                Ui.label(name, "section-title"),
                Ui.label("这个页面还在开发中，先看看启动页和版本管理页吧。", "section-sub"));
        card.getStyleClass().addAll("card", "card-pad");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane wrap = new StackPane(card);
        wrap.setPadding(new Insets(0, 16, 16, 6));
        return wrap;
    }

    /* ============================ 玻璃内部的柔光 ============================ */

    private Pane buildBlobs() {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);

        Region violet = blob("blob-violet", 620, 620);
        violet.layoutXProperty().bind(pane.widthProperty().multiply(0.01).subtract(160));
        violet.layoutYProperty().bind(pane.heightProperty().multiply(-0.22));

        Region mint = blob("blob-mint", 620, 620);
        mint.layoutXProperty().bind(pane.widthProperty().subtract(470));
        mint.layoutYProperty().bind(pane.heightProperty().subtract(390));

        Region peach = blob("blob-peach", 460, 460);
        peach.layoutXProperty().bind(pane.widthProperty().multiply(0.40));
        peach.layoutYProperty().bind(pane.heightProperty().multiply(-0.12));

        pane.getChildren().addAll(violet, mint, peach);
        blobClip = clipRounded(pane, CORNER_ARC);
        return pane;
    }

    private Region blob(String styleClass, double width, double height) {
        Region region = new Region();
        region.getStyleClass().add(styleClass);
        region.setPrefSize(width, height);
        region.setMinSize(width, height);
        region.setMaxSize(width, height);
        region.setMouseTransparent(true);
        return region;
    }

    /* ============================ 标题栏 ============================ */

    /** 左上角贴图：优先加载 resources/texture/main.png，缺失时退回文字标记。 */
    private Node buildLogoMark() {
        URL url = MainView.class.getResource("/com/yilauncher/yi_launcher/texture/main.png");
        if (url == null) {
            return Ui.label("衣", "logo-glyph");
        }
        ImageView icon = new ImageView(new Image(url.toExternalForm()));
        icon.setFitWidth(34);
        icon.setFitHeight(34);
        icon.setSmooth(false); // 16×16 像素贴图放大时保持像素风
        Rectangle clip = new Rectangle(34, 34);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        icon.setClip(clip);
        return icon;
    }

    private Node buildTitleBar() {
        StackPane logo = new StackPane(buildLogoMark());
        logo.getStyleClass().add("logo-image");
        logo.setMinSize(34, 34);
        logo.setPrefSize(34, 34);
        logo.setMaxSize(34, 34);

        // 只留品牌名与版本角标，标题栏不再放「Minecraft Launcher · 玻璃主题预览」小字。
        VBox names = new VBox(-1, Ui.label("衣启动器", "brand-title"));
        names.setAlignment(Pos.CENTER_LEFT);
        names.setMaxHeight(Region.USE_PREF_SIZE);

        HBox brand = new HBox(8, logo, names, Ui.label("v0.1.0", "chip-mini"));
        brand.setAlignment(Pos.CENTER_LEFT);

        HBox bar = new HBox(brand, Ui.spacer(),
                windowButton(Icons.MINIMIZE, b -> stage.setIconified(true)),
                windowButton(Icons.MAXIMIZE, b -> toggleMaximize()),
        windowButton(Icons.CLOSE, b -> closeWindow(), "win-btn-close"));
        bar.getStyleClass().add("titlebar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setSpacing(6);
        installWindowDrag(bar);
        return bar;
    }

    private Button windowButton(String icon, Consumer<Button> action, String... extraClasses) {
        Button button = new Button();
        button.setGraphic(Icons.icon(icon, 13, "win-icon"));
        button.getStyleClass().add("win-btn");
        button.getStyleClass().addAll(extraClasses);
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.accept(button));
        return button;
    }

    /** 无边框窗口：按住标题栏拖动，双击切换最大化。 */
    private void installWindowDrag(Node handle) {
        handle.setOnMousePressed(e -> {
            if (isInsideControl(e.getTarget())) {
                return;
            }
            // 双击标题栏最大化 / 还原（放在 maximized 判断之前，最大化状态下也能双击还原）
            if (e.getClickCount() >= 2) {
                toggleMaximize();
                return;
            }
            if (maximized) {
                return;
            }

            // 首选：让 Windows 自己搬窗口（SendMessage 会阻塞到松手）。
            // 这样拖动期间 JavaFX 完全不重绘，弱显卡上也不会掉到 30 帧。
            try {
                if (NativeDrag.startMove(stage)) {
                    return;
                }
            } catch (Throwable ignored) {
                // JNA 缺失 / 系统调用失败：走下面的逐帧方案
            }
            dragging = true;
            dragAnchorStageX = stage.getX();
            dragAnchorStageY = stage.getY();
            dragAnchorScreenX = e.getScreenX();
            dragAnchorScreenY = e.getScreenY();

            dragPendingScreenX = dragAnchorScreenX;
            dragPendingScreenY = dragAnchorScreenY;
            // 回退方案：拖动时整块内容缓存成位图，每帧只要平移
            glass.setCache(true);
            glass.setCacheHint(javafx.scene.CacheHint.SPEED);
            dragLoop.start();
        });
        handle.setOnMouseDragged(e -> {
            if (!dragging) {
                return;
            }
            // 高回报率鼠标一秒能发上千个事件；这里只记最新坐标，
            // 真正移动窗口交给 dragLoop 每帧一次，窗口才不会越拖越飘。
            dragPendingScreenX = e.getScreenX();
            dragPendingScreenY = e.getScreenY();
        });
        handle.setOnMouseReleased(e -> endDrag());
    }

    private void endDrag() {
        if (!dragging) {
            return;
        }
        // 松手前把最后一帧没落地的位移补上
        double x = Math.round(dragAnchorStageX + (dragPendingScreenX - dragAnchorScreenX));
        double y = Math.round(dragAnchorStageY + (dragPendingScreenY - dragAnchorScreenY));
        stage.setX(x);
        stage.setY(y);
        dragging = false;
        glass.setCache(false);
    }
    private boolean isInsideControl(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof ButtonBase) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void toggleMaximize() {
        if (maximized) {
            maximized = false;
            glass.getStyleClass().remove("maximized");
            backdrop.getStyleClass().remove("maximized");
            getStyleClass().remove("maximized");
            applyGlassRadius();
            if (restoreBounds != null) {
                stage.setX(restoreBounds.getMinX());
                stage.setY(restoreBounds.getMinY());
                stage.setWidth(restoreBounds.getWidth());
                stage.setHeight(restoreBounds.getHeight());
            }
            setResizeEnabled(true);
        } else {
            restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            Rectangle2D visual = Screen.getPrimary().getVisualBounds();
            maximized = true;
            glass.getStyleClass().add("maximized");
            backdrop.getStyleClass().add("maximized");
            getStyleClass().add("maximized");
            applyGlassRadius();
            stage.setX(visual.getMinX());
            stage.setY(visual.getMinY());
            stage.setWidth(visual.getWidth());
            stage.setHeight(visual.getHeight());
            setResizeEnabled(false);
        }
    }

    /** 最大化时把边框拖拽手柄关掉，不然会跟系统窗口的边缘抢鼠标。 */
    private void setResizeEnabled(boolean enabled) {
        for (Region handle : resizeHandles) {
            handle.setMouseTransparent(!enabled);
            handle.setVisible(enabled);
        }
    }

    /* ============================ 左侧导航 ============================ */

    private Node buildSidebar() {
        // 侧栏：上半是导航，下半是账户卡（卡片要放下头像 + 名字 + 登录方式，所以比图标栏宽）
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(152);
        sidebar.setMinWidth(152);
        sidebar.setMaxWidth(152);

        VBox accountNav = navRow("账户", Icons.USER, false, null);
        // 「账户」不切页面，直接弹出账户面板
        accountNav.setOnMouseClicked(e -> openAccountDialog());
        sidebar.getChildren().addAll(
                navRow("启动", Icons.HOME, true, null),
                navRow("版本管理", Icons.LAYERS, false, null),
                navRow("模组管理", Icons.PUZZLE, false, null),
                navRow("下载中心", Icons.DOWNLOAD, false, "0"),
                navRow("模组资源", Icons.GRID, false, null),
                accountNav,
                navRow("设置", Icons.SLIDERS, false, null));

        // 下载中心的小红点：显示「正在安装 / 下载」的任务数，没任务就整个藏起来
        if (lastBadge != null) {
            downloadBadge = lastBadge;
            downloadBadge.textProperty().bind(
                    com.yilauncher.yi_launcher.core.InstallJobs.active().asString());
            downloadBadge.visibleProperty().bind(
                    com.yilauncher.yi_launcher.core.InstallJobs.active().greaterThan(0));
            downloadBadge.managedProperty().bind(downloadBadge.visibleProperty());
            lastBadge = null;
        }

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        sidebar.getChildren().addAll(grow, buildAccountCard());
        return sidebar;
    }

    /** 竖排导航按钮：图标在上、文字在下（高度和原来一致，只是横向长度跟侧栏一起变宽）。 */
    private VBox navRow(String text, String icon, boolean active, String badge) {
        VBox row = new VBox(4);
        row.getStyleClass().add("nav-item");
        row.setAlignment(Pos.CENTER);
        row.setUserData(text);
        row.setMaxWidth(Double.MAX_VALUE);

        StackPane iconBox = new StackPane(Icons.icon(icon, 19, "nav-icon"));
        iconBox.getStyleClass().add("nav-icon-box");
        if (badge != null) {
            javafx.scene.control.Label dot = Ui.label(badge, "nav-badge");
            StackPane.setAlignment(dot, Pos.TOP_RIGHT);
            StackPane.setMargin(dot, new Insets(-2, -10, 0, 0));
            iconBox.getChildren().add(dot);
            lastBadge = dot;
        }

        row.getChildren().addAll(iconBox, Ui.label(text, "nav-label"));

        if (active) {
            row.getStyleClass().add("active");
        }
        navRows.add(row);
        row.setOnMouseClicked(e -> activateNav(row));
        Motion.pressFade(row);
        return row;
    }

    private void activateNav(Node target) {
        for (Node row : navRows) {
            row.getStyleClass().remove("active");
        }
        if (!target.getStyleClass().contains("active")) {
            target.getStyleClass().add("active");
        }
        showPage(String.valueOf(target.getUserData()));
    }

    /** 侧栏底部账户卡：长方形，左边皮肤头像、右边名字 + 登录方式，点一下打开账户面板。 */
    private Node buildAccountCard() {
        accountAvatar = SkinHeads.avatar("", 40);

        accountName = Ui.label("Steve", "account-name");
        accountName.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        accountName.setMinWidth(0);
        accountName.setMaxWidth(Double.MAX_VALUE);

        accountKind = Ui.label("离线", "account-kind", "kind-offline");

        VBox text = new VBox(3, accountName, accountKind);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox card = new HBox(8, accountAvatar, text);
        card.getStyleClass().add("account-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setMaxWidth(Double.MAX_VALUE);

        accountTip = new javafx.scene.control.Tooltip("点击登录 / 切换账户");
        accountTip.setShowDelay(javafx.util.Duration.millis(420));
        javafx.scene.control.Tooltip.install(card, accountTip);
        card.setOnMouseClicked(e -> openAccountDialog());
        Motion.pressFade(card);

        refreshAccountCard();
        return card;
    }

    /** 登录 / 切号 / 退出后刷新左下角卡片。 */
    private void refreshAccountCard() {
        if (accountAvatar == null) {
            return;
        }
        com.yilauncher.yi_launcher.core.Account account =
                com.yilauncher.yi_launcher.core.AccountStore.get().selectedOrGuest();
        accountAvatar.setSkin(account.skinUrl);
        accountName.setText(account.name);
        accountKind.setText(account.kindLabel());
        accountKind.getStyleClass().removeAll("kind-ms", "kind-offline", "kind-ls");
        accountKind.getStyleClass().add(switch (account.kind) {
            case MICROSOFT -> "kind-ms";
            case LITTLESKIN -> "kind-ls";
            case OFFLINE -> "kind-offline";
        });
        if (accountTip != null) {
            accountTip.setText(account.name + " · " + account.kindLabel() + "\n点击登录 / 切换账户");
        }
    }

    /** 打开账户面板（微软正版 / 离线 / LittleSkin）。 */
    private void openAccountDialog() {
        show(AccountDialog.create(this, this::refreshAccountCard));
    }

    /* ============================ 小工具 ============================ */

    private Rectangle clipRounded(Node node, double arc) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        if (node instanceof Region region) {
            clip.widthProperty().bind(region.widthProperty());
            clip.heightProperty().bind(region.heightProperty());
        }
        node.setClip(clip);
        return clip;
    }

    private void setArc(Rectangle clip, double arc) {
        if (clip != null) {
            clip.setArcWidth(arc);
            clip.setArcHeight(arc);
        }
    }
}
