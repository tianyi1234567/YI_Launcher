package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.InstallJobs;

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
    /** 模组资源页发起的下载：一条任务一行，带进度条。 */
    private final VBox tasksCard = new VBox(8);
    private final VBox tasksBox = new VBox(10);

    private final Label overallNote = Ui.label("", "progress-note");
    private final Label modsNote = Ui.label("", "progress-note");
    private final Label loaderNote = Ui.label("", "progress-note");
    private final ProgressBar overallBar = bar();
    private final ProgressBar modsBar = bar();
    private final ProgressBar loaderBar = bar();
    /** 三行下面那行小字：当前在干什么。 */
    private final Label overallAction = Ui.label("", "progress-action");
    private final Label modsAction = Ui.label("", "progress-action");
    private final Label loaderAction = Ui.label("", "progress-action");
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
                Ui.label("把整合包 / 模组直接拖进窗口就能装；模组资源里下载的任务也在这里看进度", "section-sub"));

        buildDropCard();
        buildProgressCard();
        buildTasksCard();
        content.getChildren().addAll(dropCard, progressCard, tasksCard);
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
                row(overallTitle, overallNote, overallBar, overallAction),
                row(modsTitle, modsNote, modsBar, modsAction),
                row(loaderTitle, loaderNote, loaderBar, loaderAction));
        progressCard.getStyleClass().addAll("card", "card-pad");
        progressCard.setVisible(false);
        progressCard.setManaged(false);
    }

    private void buildTasksCard() {
        tasksCard.getChildren().addAll(Ui.label("下载任务", "card-title"), tasksBox);
        tasksCard.getStyleClass().addAll("card", "card-pad");
        tasksCard.setVisible(false);
        tasksCard.setManaged(false);
    }

    private Node row(Label title, Label note, ProgressBar bar, Label action) {
        HBox head = new HBox(8, title, Ui.spacer(), note);
        head.setAlignment(Pos.CENTER_LEFT);
        // 进度条下面再挂一行小字，说明这一路现在在干什么
        VBox box = new VBox(4, head, bar, action);
        box.setPadding(new Insets(2, 0, 4, 0));
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

    /* ============================ 模组资源下载任务 ============================ */

    /**
     * 新建一条下载任务（模组 / 资源包 / 光影），返回句柄供调用方推进度。
     * <p>任务卡一开始隐藏，有任务才出现；任务结束（成功或失败）后那一行会自己消失，
     * 不会在下载中心留一排「已完成」。侧栏「下载中心」的数字用 {@link InstallJobs} 计数。</p>
     */
    public Task startTask(String title, String kind) {
        Task task = new Task(title, kind);
        tasksBox.getChildren().add(0, task.box);
        tasksCard.setVisible(true);
        tasksCard.setManaged(true);
        InstallJobs.begin();
        task.box.setOpacity(0);
        task.box.setTranslateY(-10);
        Motion.opacity(task.box, 1, 200, Motion.SOFT);
        Motion.translate(task.box, 0, 0, 220, Motion.SOFT);
        return task;
    }

    /** 一条下载任务的句柄：进度、完成、失败。全部方法都可以在任意线程调用。 */
    public final class Task {

        private final VBox box;
        private final Label note = Ui.label("准备下载", "progress-action");
        private final ProgressBar bar = bar();
        private long total;
        private boolean closed;

        private Task(String title, String kind) {
            Label name = Ui.label(title, "settings-label");
            name.setMaxWidth(Double.MAX_VALUE);
            Label chip = Ui.label(kind == null || kind.isBlank() ? "下载" : kind, "chip");
            HBox head = new HBox(8, name, Ui.spacer(), chip);
            head.setAlignment(Pos.CENTER_LEFT);
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            box = new VBox(4, head, bar, note);
            box.setPadding(new Insets(2, 0, 4, 0));
        }

        /** 知道总大小时调用一次，进度条才有百分比。 */
        public void size(long bytes) {
            total = bytes;
            Platform.runLater(() -> bar.setProgress(bytes > 0 ? 0 : ProgressBar.INDETERMINATE_PROGRESS));
        }

        public void progress(long done) {
            Platform.runLater(() -> {
                if (total > 0) {
                    bar.setProgress(Math.max(0, Math.min(1, done / (double) total)));
                    note.setText(kb(done) + " / " + kb(total));
                } else {
                    bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    note.setText(kb(done));
                }
            });
        }

        /** 下完：进度条打满、闪一下，1.4 秒后自己从下载中心消失（不留残影）。 */
        public void done(String fileName) {
            Platform.runLater(() -> {
                bar.setProgress(1);
                note.setText("已完成 · " + fileName);
                Motion.pulse(note);
                closeLater(1400);
            });
        }

        /** 失败：红字留 8 秒给人看清，然后同样自己消失。 */
        public void fail(String message) {
            Platform.runLater(() -> {
                bar.setProgress(0);
                note.setText("失败：" + (message == null ? "未知错误" : message));
                note.getStyleClass().add("danger-title");
                closeLater(8000);
            });
        }

        private void closeLater(long ms) {
            PauseTransition wait = new PauseTransition(Duration.millis(ms));
            wait.setOnFinished(event -> close());
            wait.play();
        }

        /** 从任务卡里移除；计数 -1，最后一个任务消失时整张卡收起。 */
        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            Motion.opacity(box, 0, 180, Motion.SOFT);
            Motion.translate(box, 0, -8, 200, Motion.SOFT);
            PauseTransition wait = new PauseTransition(Duration.millis(210));
            wait.setOnFinished(event -> {
                tasksBox.getChildren().remove(box);
                InstallJobs.finish();
                if (tasksBox.getChildren().isEmpty()) {
                    tasksCard.setVisible(false);
                    tasksCard.setManaged(false);
                }
            });
            wait.play();
        }

        private String kb(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return (bytes / 1024) + " KB";
            }
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
    }
    /** 拖入整合包：拖入卡片下滑淡出 → 进度卡从上往下逐层淡入。 */
    public void beginPack(String packName, String loaderLabel) {
        busy = true;
        overallTitle.setText("总进度：安装 " + packName);
        modsTitle.setText("线程：安装模组");
        loaderTitle.setText("线程：安装 " + loaderLabel + " 加载器");
        setProgress(0, 0, 0, "准备中", "等待中", "等待中");
        overallAction.setText("正在安装整合包");
        modsAction.setText("正在解析模组地址");
        loaderAction.setText("正在准备安装环境");

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
        Platform.runLater(() -> {
            setProgress(overall, mods, loader,
                    String.format("总进度 %.0f%%", overall * 100),
                    String.format("%.0f%%", mods * 100),
                    String.format("%.0f%%", loader * 100));
            if (note != null && !note.isBlank()) {
                overallAction.setText(note);
            }
        });
    }

    /** 两路线各自的当前动作（来自安装线程）。 */
    public void pushDetail(String modsText, String loaderText) {
        Platform.runLater(() -> {
            if (modsText != null && !modsText.isBlank()) {
                modsAction.setText(modsText);
            }
            if (loaderText != null && !loaderText.isBlank()) {
                loaderAction.setText(loaderText);
            }
        });
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
