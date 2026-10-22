package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.ModIdentity;
import com.yilauncher.yi_launcher.core.PackInstaller;
import com.yilauncher.yi_launcher.core.Remote;
import com.yilauncher.yi_launcher.core.VersionEntry;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组管理：看选中版本装了哪些模组，并联网认出它们是 CurseForge / Modrinth 上的哪个项目。
 * <p>列表一次性把行都建好（不是虚拟列表），所以滚轮翻看不会重新创建行——
 * 淡入动画只在「打开这个页面」时播一次，且是从下往上逐行淡入。</p>
 */
public final class ModsPage extends BorderPane {

    private final MainView host;
    private final VBox list = new VBox(8);
    private final Label subtitle = Ui.label("", "section-sub");
    private final Label status = Ui.label("", "settings-hint");
    private final Map<String, Image> iconCache = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();

    /** 淡入只在每次「打开页面」播一次；滚轮/刷新都不重播。 */
    private boolean introPlayed;
    /** 换版本 / 重新扫描时作废上一次的联网识别结果。 */
    private int flowToken;

    public ModsPage(MainView host) {
        this.host = host;
        setCenter(build());
    }

    /* ============================ 骨架 ============================ */

    private Node build() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 16, 16, 6));

        VBox titles = new VBox(0,
                Ui.label("模组管理", "section-title"),
                subtitle);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.ALWAYS);

        javafx.scene.control.Button pick = Ui.ghostButton("选择游戏", null, this::openPicker);
        javafx.scene.control.Button refresh = Ui.ghostButton("刷新", null, this::refresh);
        HBox header = new HBox(8, titles, Ui.spacer(), status, pick, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        list.setPadding(new Insets(2, 6, 6, 0));
        ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        main.getChildren().addAll(header, scroll);
        return main;
    }

    /* ============================ 页面生命周期 ============================ */

    /** 每次切到这一页（MainView 只在真的换页时调）。 */
    public void onShown() {
        introPlayed = false;
        VersionEntry entry = LauncherEngine.get().selected();
        if (entry == null) {
            // 没选版本：先让玩家挑一个
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
        Path modsDir = modsDir(entry);
        List<ModIdentity.LocalMod> mods = ModIdentity.scan(modsDir);
        subtitle.setText(entry.displayName + " · " + entry.loaderText() + " · "
                + mods.size() + " 个模组 · " + modsDir);

        list.getChildren().clear();
        rows.clear();
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
        if (!introPlayed) {
            playIntro();
            introPlayed = true;
        }

        // 联网识别：先 CurseForge，再 Modrinth；每认出一个就把这一行补上图标和版本
        Thread worker = new Thread(() -> ModIdentity.enrich(mods, entry.gameVersion,
                (index, remote) -> Platform.runLater(() -> applyRemote(token, index, remote))),
                "yi-mod-identify");
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
        row.versionChip.setText(remote.version().isBlank() ? "未知版本" : remote.version());
        row.versionChip.setVisible(true);
        row.versionChip.setManaged(true);
        row.sourceChip.setText(remote.source());
        row.sourceChip.getStyleClass().removeAll("source-cf", "source-mr");
        row.sourceChip.getStyleClass().add(
                PackInstaller.CURSEFORGE.equals(remote.source()) ? "source-cf" : "source-mr");
        row.sourceChip.setVisible(true);
        row.sourceChip.setManaged(true);
        if (!remote.iconUrl().isBlank()) {
            loadIcon(token, row, remote.iconUrl());
        }
        long identified = rows.stream().filter(item -> item.remote != null).count();
        if (identified == rows.size()) {
            setStatus("已识别 " + identified + " / " + rows.size() + " 个模组");
        } else {
            setStatus("正在识别模组… " + identified + "/" + rows.size());
        }
    }

    /** 图标走后台下载（也要带 UA），解码成功才换上去；失败就留着首字母。 */
    private void loadIcon(int token, Row row, String url) {
        Image cached = iconCache.get(url);
        if (cached != null) {
            row.setIcon(cached);
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                Image image;
                try (InputStream in = Remote.openStream(url).body()) {
                    image = new Image(new ByteArrayInputStream(in.readAllBytes()));
                }
                if (image.isError() || image.getWidth() < 1) {
                    return;
                }
                iconCache.put(url, image);
                Platform.runLater(() -> {
                    if (token == flowToken) {
                        row.setIcon(image);
                    }
                });
            } catch (Exception ignored) {
                // 图标拿不到就不显示，别影响列表
            }
        }, "yi-mod-icon");
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
        StringBuilder meta = new StringBuilder(mod.loader());
        if (!mod.version().isBlank()) {
            meta.append(" · ").append(mod.version());
        }
        meta.append(" · ").append(mod.fileName());
        Label metaLabel = Ui.label(meta.toString(), "mod-meta");
        metaLabel.setMinWidth(0);
        metaLabel.setMaxWidth(Double.MAX_VALUE);

        VBox texts = new VBox(2, name, metaLabel);
        texts.setMinWidth(0);
        texts.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label versionChip = Ui.label("", "mod-chip");
        versionChip.setVisible(false);
        versionChip.setManaged(false);
        Label sourceChip = Ui.label("", "mod-chip");
        sourceChip.setVisible(false);
        sourceChip.setManaged(false);

        HBox node = new HBox(11, icon, texts, versionChip, sourceChip);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("mod-row");
        node.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.control.Tooltip.install(node,
                new javafx.scene.control.Tooltip(mod.fileName()));

        return new Row(node, icon, letter, name, versionChip, sourceChip, mod);
    }

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

    /** 一行模组：节点 + 本地信息 + 识别结果。 */
    private static final class Row {
        final HBox node;
        final StackPane iconBox;
        final Label letter;
        final Label name;
        final Label versionChip;
        final Label sourceChip;
        final ModIdentity.LocalMod local;
        ModIdentity.Remote remote;

        Row(HBox node, StackPane iconBox, Label letter, Label name,
            Label versionChip, Label sourceChip, ModIdentity.LocalMod local) {
            this.node = node;
            this.iconBox = iconBox;
            this.letter = letter;
            this.name = name;
            this.versionChip = versionChip;
            this.sourceChip = sourceChip;
            this.local = local;
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
