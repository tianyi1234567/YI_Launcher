/* ==========================================================================
 *  衣启动器 · 入口层 ｜ LauncherApp.java —— 应用启动
 *  做什么：建透明无边框窗口、挂 glass.css、摆居中、放开窗动效。
 *  谁在用：javafx:run / jlink 的 mainClass（pom.xml 里指定的就是它）。
 *  入口：main()（关 LCD 抗锯齿后 launch）、start(Stage)、maybeWriteShot（自检截图）。
 * ========================================================================== */
package com.yilauncher.yi_launcher;

import com.yilauncher.yi_launcher.ui.MainView;
import com.yilauncher.yi_launcher.ui.Motion;
import com.yilauncher.yi_launcher.ui.SceneShot;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.net.URL;

/**
 * 衣启动器入口：无系统边框 + 透明背景，圆角与阴影全部由场景自己绘制。
 */
public class LauncherApp extends Application {

    private static final double WINDOW_WIDTH = 1200;
    private static final double WINDOW_HEIGHT = 760;

    @Override
    public void start(Stage stage) {
        MainView root = new MainView(stage);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);

        URL css = LauncherApp.class.getResource("/com/yilauncher/yi_launcher/css/glass.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("[衣启动器] 未找到 glass.css，界面将使用默认样式。");
        }

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("衣启动器");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        // 开窗前先把"还没展开"的状态摆好，避免第一帧就直接是全亮
        Motion.prepareWindowOpen(root);
        stage.show();

        // 无边框窗口以左上角为锚点，需要手动居中
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        stage.setX(visual.getMinX() + (visual.getWidth() - stage.getWidth()) / 2);
        stage.setY(visual.getMinY() + (visual.getHeight() - stage.getHeight()) / 2);

        // 跟着系统质感开窗：Win11 轻微放大 + 淡入，Win10 只淡入，其它系统维持原状
        Motion.windowOpen(root);

        // 开发用：-Dyi.page=versions 可以直接打开指定页面
        String page = System.getProperty("yi.page");
        if (page != null && !page.isBlank()) {
            root.selectPage(page);
        }

        maybeWriteShot(scene);
    }

    /**
     * 开发自检：-Dyi.shot=路径 时把当前界面回写到 BMP（叠在 -Dyi.backdrop 指定的底色上）并退出，
     * 同时把一份布局树写到同名 .txt。用于在没有截图能力的环境里核对圆角、半透明和排版。
     */
    private void maybeWriteShot(Scene scene) {
        String target = System.getProperty("yi.shot");
        if (target == null || target.isBlank()) {
            return;
        }
        Color backdrop = Color.web(System.getProperty("yi.backdrop", "#DDE4F0"));
        PauseTransition wait = new PauseTransition(Duration.millis(4200));
        wait.setOnFinished(done -> {
            try {
                SceneShot.write(scene, target, backdrop);
                System.out.println("[自检] 已写出界面快照: " + target);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of(target + ".txt"), SceneShot.describe(scene));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            Platform.exit();
        });
        wait.play();
    }

    public static void main(String[] args) {
        // 透明窗口里开启 LCD 次像素抗锯齿会让文字边缘发彩，关掉更接近玻璃质感
        System.setProperty("prism.lcdtext", "false");
        launch(args);
    }
}
