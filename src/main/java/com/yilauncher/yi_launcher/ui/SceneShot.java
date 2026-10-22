/* ==========================================================================
 *  衣启动器 · ui 层 ｜ SceneShot.java —— 开发自检截图
 *  做什么：把场景离屏渲染成 24 位 BMP（叠在指定底色上看半透明/圆角），并导出布局树文本。
 *  谁在用：LauncherApp.maybeWriteShot()，仅在 -Dyi.shot=... 时执行。
 *  入口：write(scene, 路径, 底色)、describe(scene)、hitTest()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.ui;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 开发自检工具：把当前场景合成为 24 位 BMP 文件。
 * <p>透明窗口没法直接用普通截图工具核对，所以给一个"把玻璃叠在指定底色上"的离屏快照，
 * 方便检查圆角、半透明和排版。仅在显式传入 -Dyi.shot=... 时使用，正常启动不会执行。</p>
 */
public final class SceneShot {

    private SceneShot() {
    }

    public static void write(Scene scene, String path, Color backdrop) throws IOException {
        int width = (int) Math.ceil(scene.getWidth());
        int height = (int) Math.ceil(scene.getHeight());

        WritableImage image = new WritableImage(width, height);
        scene.snapshot(image);
        PixelReader reader = image.getPixelReader();

        double bx = backdrop.getRed();
        double by = backdrop.getGreen();
        double bb = backdrop.getBlue();

        int stride = ((width * 3) + 3) / 4 * 4;
        int imageSize = stride * height;
        byte[] rows = new byte[imageSize];

        for (int y = 0; y < height; y++) {
            int rowStart = (height - 1 - y) * stride; // BMP 从下往上存
            for (int x = 0; x < width; x++) {
                Color c = reader.getColor(x, y);
                double alpha = c.getOpacity();
                int r = toByte(c.getRed() * alpha + bx * (1 - alpha));
                int g = toByte(c.getGreen() * alpha + by * (1 - alpha));
                int b = toByte(c.getBlue() * alpha + bb * (1 - alpha));
                int offset = rowStart + x * 3;
                rows[offset] = (byte) b;
                rows[offset + 1] = (byte) g;
                rows[offset + 2] = (byte) r;
            }
        }

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(path))) {
            writeHeader(out, width, height, imageSize);
            out.write(rows);
        }
    }

    private static int toByte(double value) {
        return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    private static void writeHeader(OutputStream out, int width, int height, int imageSize) throws IOException {
        int fileSize = 54 + imageSize;
        byte[] header = new byte[54];
        header[0] = 'B';
        header[1] = 'M';
        putInt(header, 2, fileSize);
        putInt(header, 10, 54);
        putInt(header, 14, 40);
        putInt(header, 18, width);
        putInt(header, 22, height);
        putShort(header, 26, 1);
        putShort(header, 28, 24);
        putInt(header, 34, imageSize);
        putInt(header, 38, 2835);
        putInt(header, 42, 2835);
        out.write(header);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /** 输出场景里带样式类/文本的节点及其在窗口中的位置尺寸，用于核对排版。 */
    public static String describe(javafx.scene.Scene scene) {
        StringBuilder sb = new StringBuilder();
        walk(scene.getRoot(), 0, sb);
        return sb.toString();
    }

    /** 从场景根节点开始做一次命中测试，返回该点从外到里经过的节点链。 */
    public static String hitTest(javafx.scene.Scene scene, double sceneX, double sceneY) {
        StringBuilder sb = new StringBuilder("hit(" + Math.round(sceneX) + "," + Math.round(sceneY) + ")");
        javafx.scene.Node node = scene.getRoot();
        while (node instanceof javafx.scene.Parent parent) {
            javafx.scene.Node hit = null;
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                if (!child.isVisible() || child.isMouseTransparent() || !child.isManaged()) {
                    continue;
                }
                javafx.geometry.Point2D local = child.sceneToLocal(sceneX, sceneY);
                if (child.contains(local)) {
                    hit = child;
                }
            }
            if (hit == null) {
                break;
            }
            sb.append(" > ").append(hit.getClass().getSimpleName()).append(hit.getStyleClass());
            node = hit;
        }
        return sb.toString();
    }

    private static void walk(javafx.scene.Node node, int depth, StringBuilder sb) {
        boolean interesting = node instanceof javafx.scene.control.Labeled || !node.getStyleClass().isEmpty();
        if (interesting) {
            javafx.geometry.Bounds b = node.localToScene(node.getLayoutBounds());
            sb.append("  ".repeat(Math.min(depth, 12))).append(node.getClass().getSimpleName());
            if (!node.getStyleClass().isEmpty()) {
                sb.append('.').append(String.join(".", node.getStyleClass()));
            }
            sb.append(String.format(" [x%.0f y%.0f w%.0f h%.0f]", b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
            if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
                sb.append(" \"").append(labeled.getText()).append('"');
                if (labeled.getWidth() + 0.5 < labeled.prefWidth(-1)) {
                    sb.append(" <CLIPPED>");
                }
            }
            sb.append('\n');
        }
        if (node instanceof javafx.scene.Parent parent && depth < 14) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                walk(child, interesting ? depth + 1 : depth, sb);
            }
        }
    }
}
