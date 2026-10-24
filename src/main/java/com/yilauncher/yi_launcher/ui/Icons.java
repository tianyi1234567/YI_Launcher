package com.yilauncher.yi_launcher.ui;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * 线性图标集合（24×24 视窗，路径只负责几何形状，颜色/线宽由 CSS 控制）。
 */
public final class Icons {

    public static final String HOME = "M3.2 10.4 12 3.2l8.8 7.2M5.4 12.2v8.4h4.6V15h4v5.6h4.6v-8.4";
    public static final String LAYERS = "M12 3.2 3.4 7.5 12 11.8l8.6-4.3zM3.4 12.3 12 16.6l8.6-4.3M3.4 16.9 12 21.2l8.6-4.3";
    public static final String DOWNLOAD = "M4 16.2v3.4h16v-3.4M12 3.4v12.2M7.6 11.2 12 15.6 16.4 11.2";
    public static final String GRID = "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z";
    /** 模组管理：一块拼图。 */
    public static final String PUZZLE = "M4.9 4.9h4.5v1.9a1.6 1.6 0 0 0 3.2 0V4.9h4.5v4.5h1.9"
            + "a1.6 1.6 0 0 1 0 3.2h-1.9v4.5h-4.5v-1.9a1.6 1.6 0 0 0-3.2 0v1.9H4.9v-4.5H3"
            + "a1.6 1.6 0 0 1 0-3.2h1.9z";
    /** 复制：两个叠在一起的圆角方块。 */
    public static final String COPY = "M9 3.8h7.2a2.2 2.2 0 0 1 2.2 2.2V13"
            + "M6 7.8h7.8a2.2 2.2 0 0 1 2.2 2.2v7.8a2.2 2.2 0 0 1-2.2 2.2H6"
            + "a2.2 2.2 0 0 1-2.2-2.2v-7.8A2.2 2.2 0 0 1 6 7.8z";
    public static final String USER = "M4.6 20.6v-1.3a4.2 4.2 0 0 1 4.2-4.2h6.4a4.2 4.2 0 0 1 4.2 4.2v1.3"
            + "M4.2 11a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0-7.6 0";
    public static final String SLIDERS = "M6 3.6v5.6M6 12.4v8M12 3.6v10.2M12 17.4v3M18 3.6v2.6M18 9.8v10.6"
            + "M4.1 11.2a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0-3.8 0"
            + "M10.1 15.9a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0-3.8 0"
            + "M16.1 8.4a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0-3.8 0";

    public static final String SEARCH = "M4.4 11a6.4 6.4 0 1 0 12.8 0a6.4 6.4 0 1 0-12.8 0M15.4 15.4 20 20";
    public static final String PLAY = "M8.6 5 18.8 12 8.6 19z";
    public static final String FOLDER = "M3.2 6.4h5.7l1.9 2.3h10v10.9H3.2z";
    public static final String PLUS = "M12 5.4v13.2M5.4 12h13.2";
    public static final String PENCIL = "M4.6 19.4l1-3.9L15.8 5.3l2.9 2.9L8.5 18.4zM14.4 6.7l2.9 2.9";
    public static final String CHEVRON_DOWN = "M6.5 9.5 12 15 17.5 9.5";

    public static final String MINIMIZE = "M5.6 12h12.8";
    public static final String MAXIMIZE = "M5.9 5.9h12.2v12.2H5.9z";
    public static final String CLOSE = "M6.6 6.6 17.4 17.4M17.4 6.6 6.6 17.4";

    /** 删除：垃圾桶。 */
    public static final String TRASH = "M4.8 6.9h14.4M9.6 6.9V4.7h4.8v2.2M6.8 6.9l.9 12.4h8.6l.9-12.4M10.3 10.6v5.4M13.7 10.6v5.4";

    private Icons() {
    }

    /** 生成一个 SVG 路径节点，并把 24×24 的绘制尺寸缩放到目标尺寸。 */
    public static SVGPath path(String data, String styleClass) {
        SVGPath p = new SVGPath();
        p.setContent(data);
        if (styleClass != null && !styleClass.isBlank()) {
            p.getStyleClass().add(styleClass);
        }
        return p;
    }

    /** 生成固定占位的图标容器，避免 HBox/VBox 因为缩放后的边界抖动。 */
    public static StackPane icon(String data, double size, String styleClass) {
        SVGPath p = path(data, styleClass);
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);

        StackPane holder = new StackPane(p);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        return holder;
    }
}
