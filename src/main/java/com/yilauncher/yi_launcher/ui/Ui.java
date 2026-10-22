/* ==========================================================================
 *  衣启动器 · ui 层 ｜ Ui.java —— 页面公共零件
 *  做什么：Label / 弹性占位 / 行容器 / 幽灵按钮 / 主按钮 / 单选组等样板，省得每个页面重写。
 *          内部还定义了弹窗宿主接口 OverlayHost（由 MainView 实现）。
 *  谁在用：所有页面与对话框。
 *  入口：label()、spacer()、row()、ghostButton()、primaryButton()、selectOne()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * 页面共用的小工具：Label / 占位 / 按钮样板，避免每个页面重复写一遍。
 */
public final class Ui {

    /** 页面弹窗宿主：由窗口外壳实现，页面通过它把卡片放到玻璃上层的遮罩里。 */
    public interface OverlayHost {
        void show(javafx.scene.Node content);

        void hide();
    }

    private Ui() {
    }

    public static Label label(String text, String... classes) {
        Label label = new Label(text);
        label.getStyleClass().addAll(classes);
        return label;
    }

    /** 水平弹性占位，把后面的内容推到右边。 */
    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** 横向排列且垂直居中。 */
    public static HBox row(double spacing, javafx.scene.Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static Button ghostButton(String text, String icon, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-btn");
        if (icon != null) {
            button.setGraphic(Icons.icon(icon, 13, "ghost-icon"));
            button.setGraphicTextGap(6);
        }
        button.setFocusTraversable(false);
        if (action != null) {
            button.setOnAction(e -> action.run());
        }
        Motion.pressBounce(button);
        return button;
    }

    public static Button primaryButton(String text, String icon, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("launch-btn");
        if (icon != null) {
            button.setGraphic(Icons.icon(icon, 12, "play-icon"));
            button.setGraphicTextGap(8);
        }
        button.setFocusTraversable(false);
        if (action != null) {
            button.setOnAction(e -> action.run());
        }
        Motion.pressBounce(button);
        return button;
    }

    /** 界面原型阶段：点一下假装正在启动，1.4 秒后恢复。 */
    public static void fakeLaunch(Button button) {
        Object busy = button.getProperties().get("yi.busy");
        if (Boolean.TRUE.equals(busy)) {
            return;
        }
        String text = button.getText();
        button.getProperties().put("yi.busy", true);
        button.setText("正在启动…");

        PauseTransition pause = new PauseTransition(Duration.seconds(1.4));
        pause.setOnFinished(done -> {
            button.setText(text);
            button.getProperties().put("yi.busy", false);
        });
        pause.play();
    }

    /** 单选组：点谁谁亮，其余熄灭。 */
    public static void selectOne(Iterable<? extends javafx.scene.Node> group, javafx.scene.Node picked) {
        for (javafx.scene.Node node : group) {
            node.getStyleClass().remove("active");
        }
        if (!picked.getStyleClass().contains("active")) {
            picked.getStyleClass().add("active");
        }
    }
}
