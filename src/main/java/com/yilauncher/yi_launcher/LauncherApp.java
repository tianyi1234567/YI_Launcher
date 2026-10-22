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
 * 启动器入口
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
        Motion.prepareWindowOpen(root);
        stage.show();

        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        stage.setX(visual.getMinX() + (visual.getWidth() - stage.getWidth()) / 2);
        stage.setY(visual.getMinY() + (visual.getHeight() - stage.getHeight()) / 2);

        Motion.windowOpen(root);

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
        System.setProperty("prism.lcdtext", "false");
        launch(args);
    }
}
