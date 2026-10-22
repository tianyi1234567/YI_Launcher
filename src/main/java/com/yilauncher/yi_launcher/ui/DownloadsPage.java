package com.yilauncher.yi_launcher.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 下载中心：拖文件安装 + 安装进度。
 * <p>拖入整合包后「拖入安装」卡片向下滑出淡出，进度卡逐层从上方淡入；
 * 装完再反向播放，回到拖入安装。</p>
 */
public final class DownloadsPage extends BorderPane {

    private final VBox content = new VBox(12);
    private final VBox dropCard = new VBox(10);
    private final VBox progressCard = new VBox(10);

    private final Label overallNote = Ui.label("", "progress-note");
    private final Label modsNote = Ui.label("", "progress-note");
    private final Label loaderNote = Ui.label("", "progress-note");
    private final ProgressBar overallBar = bar();
    private final ProgressBar modsBar = bar();
    private final ProgressBar loaderBar = bar();
    private final Label overallTitle = Ui.label("总进度：安装整合包", "settings-label");
    private final Label modsTitle = Ui.label("线程：安装模组", "settings-label");
    private final Label loaderTitle = Ui.label("线程：安装模组加载器", "settings-label");

    private boolean busy;

    public DownloadsPage(MainView host) {
        setCenter(build());
    }

    /* ============================ 骨架 ============================ */

    private Node build() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 16, 16, 6));
        VBox header = new VBox(0,
                Ui.label("下载中心", "section-title"),
                Ui.label("把整合包 / 模组直接拖进窗口就能装", "section-sub"));

        buildDropCard();
        buildProgressCard();
        content.getChildren().addAll(dropCard, progressCard);
        content.setPadding(new Insets(2, 6, 4, 0));

        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        main.getChildren().addAll(header, scroll);
        return main;
    }

    private void buildDropCard() {
        Label hint = Ui.label("把 CurseForge / Modrinth 整合包（.zip）或模组（.jar）拖到窗口任意位置", "settings-hint");
        hint.setWrapText(true);

        VBox zone = new VBox(6,
                Ui.label("⬇", "drop-glyph"),
                Ui.label("拖到这里松手即可安装", "drop-title"),
                Ui.label("整合包会先问名称和分组；模组装进当前选择的版本", "settings-hint"));
        zone.getStyleClass().add("drop-zone");
        zone.setAlignment(Pos.CENTER);
        zone.setPadding(new Insets(22, 16, 22, 16));

        dropCard.getChildren().addAll(Ui.label("拖入安装", "card-title"), hint, zone);
        dropCard.getStyleClass().addAll("card", "card-pad");
    }

    private void buildProgressCard() {
        progressCard.getChildren().addAll(
                Ui.label("正在安装", "card-title"),
                row(overallTitle, overallNote, overallBar),
                row(modsTitle, modsNote, modsBar),
                row(loaderTitle, loaderNote, loaderBar));
        progressCard.getStyleClass().addAll("card", "card-pad");
        progressCard.setVisible(false);
        progressCard.setManaged(false);
    }

    private Node row(Label title, Label note, ProgressBar bar) {
        HBox head = new HBox(8, title, Ui.spacer(), note);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(5, head, bar);
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }

    private static ProgressBar bar() {
        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("progress-line");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(8);
        bar.setMinHeight(8);
        return bar;
    }

    /* ============================ 进度 ============================ */

    /** 拖入整合包：拖入卡片下滑淡出 → 进度卡从上往下逐层淡入。 */
    public void beginPack(String packName, String loaderLabel) {
        busy = true;
        overallTitle.setText("总进度：安装 " + packName);
        modsTitle.setText("线程：安装模组");
        loaderTitle.setText("线程：安装 " + loaderLabel + " 加载器");
        setProgress(0, 0, 0, "准备中", "等待中", "等待中");

        // 拖入卡片：下滑 + 淡出
        Motion.translate(dropCard, 0, 22, 170, Motion.SOFT);
        Motion.opacity(dropCard, 0, 150, Motion.SOFT);
        PauseTransition wait = new PauseTransition(Duration.millis(175));
        wait.setOnFinished(done -> {
            dropCard.setVisible(false);
            dropCard.setManaged(false);
            dropCard.setTranslateY(0);
            dropCard.setOpacity(1);
            showProgressRows();
        });
        wait.play();
    }

    private void showProgressRows() {
        progressCard.setVisible(true);
        progressCard.setManaged(true);
        progressCard.setOpacity(1);
        Node[] rows = {progressCard.getChildren().get(1), progressCard.getChildren().get(2),
                progressCard.getChildren().get(3)};
        for (Node row : rows) {
            row.setOpacity(0);
            row.setTranslateY(-14);
        }
        // 逐层从上到下淡入
        for (int i = 0; i < rows.length; i++) {
            final Node row = rows[i];
            after(i * 70, () -> {
                Motion.translate(row, 0, 0, 220, Motion.SOFT);
                Motion.opacity(row, 1, 200, Motion.SOFT);
            });
        }
    }

    /** 收尾：进度卡下滑淡出 → 拖入卡片从上往下淡入。 */
    public void finishPack(Runnable done) {
        busy = false;
        overallBar.setProgress(1);
        modsBar.setProgress(1);
        loaderBar.setProgress(1);
        after(420, () -> {
            Motion.translate(progressCard, 0, 22, 180, Motion.SOFT);
            Motion.opacity(progressCard, 0, 160, Motion.SOFT);
            PauseTransition wait = new PauseTransition(Duration.millis(190));
            wait.setOnFinished(e -> {
                progressCard.setVisible(false);
                progressCard.setManaged(false);
                progressCard.setTranslateY(0);
                progressCard.setOpacity(1);
                dropCard.setVisible(true);
                dropCard.setManaged(true);
                dropCard.setOpacity(0);
                dropCard.setTranslateY(-14);
                Motion.translate(dropCard, 0, 0, 220, Motion.SOFT);
                Motion.opacity(dropCard, 1, 200, Motion.SOFT);
                if (done != null) {
                    done.run();
                }
            });
            wait.play();
        });
    }

    /** 线程回调（任意线程）：转回 FX 线程刷新三条进度。 */
    public void pushProgress(double overall, double mods, double loader, String note) {
        Platform.runLater(() -> setProgress(overall, mods, loader,
                String.format("总进度 %.0f%%", overall * 100),
                String.format("%.0f%%", mods * 100),
                String.format("%.0f%%", loader * 100)));
    }

    public void pushError(String message) {
        Platform.runLater(() -> {
            setProgress(0, 0, 0, "安装失败", "已停止", "已停止");
            overallNote.setText("安装失败：" + message);
        });
    }

    private void setProgress(double overall, double mods, double loader,
                             String overallText, String modsText, String loaderText) {
        overallBar.setProgress(clamp(overall));
        modsBar.setProgress(clamp(mods));
        loaderBar.setProgress(clamp(loader));
        overallNote.setText(overallText);
        modsNote.setText(modsText);
        loaderNote.setText(loaderText);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    public boolean busy() {
        return busy;
    }

    /* ============================ 动画小工具 ============================ */

    private static void after(long millis, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.millis(millis));
        pause.setOnFinished(e -> action.run());
        pause.play();
    }
}
