package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherConfig;
import com.yilauncher.yi_launcher.core.LauncherEngine;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 设置页：动效速度 / 界面不透明度 / 窗口圆角。
 * <p>点一下立刻生效（写进 config.json 的 ui 段），下次启动自动套用。</p>
 */
public final class SettingsPage extends BorderPane {

    private final MainView host;
    private final LauncherConfig.UiSettings ui = LauncherEngine.get().config().ui();

    public SettingsPage(MainView host) {
        this.host = host;
        setCenter(buildBody());
    }

    private Node buildBody() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 16, 16, 6));

        VBox header = new VBox(0,
                Ui.label("设置", "section-title"),
                Ui.label("界面观感与动效，改完立刻生效", "section-sub"));

        VBox body = new VBox(12, appearanceCard(), accountCard(), aboutCard());
        body.setPadding(new Insets(2, 6, 4, 0));

        ScrollPane scroll = new ScrollPane(body);
        scroll.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        main.getChildren().addAll(header, scroll);
        return main;
    }

    private VBox appearanceCard() {
        VBox card = new VBox(16,
                Ui.label("外观与动效", "card-title"),
                row("动效速度", "弹出、淡入、滑动等所有动效的播放速度",
                        chips("speed", new String[]{"悠慢", "标准", "轻快", "关闭"},
                                new double[]{0.5, 0.75, 1.0, 3.0}, ui.speed())),
                row("界面不透明度", "100% 时完全不透明；调低后能看见后面的桌面与窗口",
                        chips("opacity", new String[]{"100%", "92%", "85%", "75%"},
                                new double[]{1.0, 0.92, 0.85, 0.75}, ui.opacity())),
                row("窗口圆角", "无边框窗口的圆角大小，最大化时自动变直角",
                        chips("radius", new String[]{"直角", "标准", "大圆角"},
                                new double[]{0, 26, 34}, ui.radius())));
        card.getStyleClass().addAll("card", "card-pad");
        return card;
    }

    /** 账户相关：正版登录的客户端 ID 与 CurseForge Key 都已内置，这里只做说明，不再提供修改入口。 */
    private VBox accountCard() {
        VBox card = new VBox(9,
                Ui.label("账户", "card-title"),
                row("微软正版登录", "", builtinChip("已内置")),
                row("CurseForge 官方源", "", builtinChip("已内置")));
        card.getStyleClass().addAll("card", "card-pad");
        return card;
    }

    /** 一行只读的「已内置」小标签，替代以前的输入框。 */
    private Node builtinChip(String text) {
        Label chip = Ui.label(text, "chip");
        HBox box = new HBox(chip);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private VBox aboutCard() {
        Label hint = Ui.label("衣启动器 v0.1.0 · JavaFX 21", "settings-hint");
        Button open = Ui.ghostButton("打开配置目录", Icons.FOLDER, this::openConfigDir);

        HBox line = new HBox(10, Ui.label("配置文件", "settings-label"), Ui.spacer(), open);
        line.setAlignment(Pos.CENTER_LEFT);

        Label path = Ui.label(LauncherEngine.get().config().file().toString(), "settings-hint");
        path.setWrapText(true);

        VBox card = new VBox(9, Ui.label("关于", "card-title"), hint, line, path);
        card.getStyleClass().addAll("card", "card-pad");
        return card;
    }

    /** 一行设置：左边标题 + 说明，右边一组选项。 */
    private Node row(String title, String hint, Node control) {
        Label hintLabel = Ui.label(hint, "settings-hint");
        hintLabel.setWrapText(true);
        VBox text = new VBox(2, Ui.label(title, "settings-label"), hintLabel);
        text.setMaxWidth(300);

        HBox row = new HBox(14, text, Ui.spacer(), control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /**
     * 一组单选胶囊：点哪个哪个亮，然后把数值应用到主窗口并落盘。
     */
    private HBox chips(String key, String[] names, double[] values, double current) {
        HBox group = new HBox(6);
        group.setAlignment(Pos.CENTER_LEFT);

        Label[] chips = new Label[names.length];
        for (int i = 0; i < names.length; i++) {
            Label chip = Ui.label(names[i], "filter-chip");
            if (Math.abs(values[i] - current) < 0.01) {
                chip.getStyleClass().add("active");
            }
            int index = i;
            chip.setOnMouseClicked(event -> {
                for (Label other : chips) {
                    if (other != null) {
                        other.getStyleClass().remove("active");
                    }
                }
                chip.getStyleClass().add("active");
                apply(key, values[index]);
            });
            Motion.pressFade(chip);
            chips[i] = chip;
            group.getChildren().add(chip);
        }
        return group;
    }

    private void apply(String key, double value) {
        switch (key) {
            case "speed" -> host.setMotionSpeed(value);
            case "opacity" -> host.setUiOpacity(value);
            case "radius" -> host.setUiRadius(value);
            default -> {
            }
        }
    }

    private void openConfigDir() {
        try {
            java.nio.file.Path parent = LauncherEngine.get().config().file().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
                new ProcessBuilder("explorer.exe", parent.toString()).start();
            }
        } catch (Exception ignored) {
        }
    }
}
