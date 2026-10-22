package com.yilauncher.yi_launcher.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的小动效：Q 弹（回弹过冲）与顺滑淡入淡出。
 * <p>同一个节点上按"属性"注册动画（scale / opacity / translate），同属性新动画会先停掉旧的，
 * 不同属性可以并行——所以弹入和悬停同时发生也不会打架。</p>
 */
public final class Motion {

    private static final String REGISTRY_KEY = "yi.motion";

    /** 弹簧感：先冲过头一点再回弹到位。 */
    public static final Interpolator SPRING = new Interpolator() {
        @Override
        protected double curve(double t) {
            if (t <= 0) {
                return 0;
            }
            if (t >= 1) {
                return 1;
            }
            return 1 - Math.exp(-6.5 * t) * Math.cos(9.5 * t);
        }
    };

    /** 轻一点的回弹，给鼠标悬停用。 */
    public static final Interpolator SOFT_SPRING = new Interpolator() {
        @Override
        protected double curve(double t) {
            if (t <= 0) {
                return 0;
            }
            if (t >= 1) {
                return 1;
            }
            return 1 - Math.exp(-7.5 * t) * Math.cos(11 * t);
        }
    };

    /** 顺滑收尾，用于淡入淡出。 */
    public static final Interpolator SOFT = new Interpolator() {
        @Override
        protected double curve(double t) {
            if (t <= 0) {
                return 0;
            }
            if (t >= 1) {
                return 1;
            }
            double p = 1 - t;
            return 1 - p * p * p;
        }
    };

    public static final Interpolator EASE_IN = new Interpolator() {
        @Override
        protected double curve(double t) {
            return t <= 0 ? 0 : t * t * t;
        }
    };

    private Motion() {
    }

    /* ============================ 底层：按属性注册 ============================ */

    @SuppressWarnings("unchecked")
    private static Map<String, Animation> registry(Node node) {
        Object existing = node.getProperties().get(REGISTRY_KEY);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Animation>) map;
        }
        Map<String, Animation> created = new HashMap<>();
        node.getProperties().put(REGISTRY_KEY, created);
        return created;
    }

    /** 全局动效速度倍率：>1 更快，<1 更慢（设置页可调）。 */
    private static double speed = 1.0;

    public static void setSpeed(double value) {
        speed = Math.max(0.35, Math.min(3.0, value));
    }

    public static double speed() {
        return speed;
    }

    private static void play(Node node, String key, Animation anim) {
        Map<String, Animation> running = registry(node);
        Animation previous = running.get(key);
        if (previous != null) {
            previous.stop();
        }
        EventHandler<ActionEvent> chained = anim.getOnFinished();
        anim.setOnFinished(event -> {
            if (chained != null) {
                chained.handle(event);
            }
            if (running.get(key) == anim) {
                running.remove(key);
            }
        });
        running.put(key, anim);
        anim.setRate(speed);
        anim.play();
    }

    /* ============================ 单属性动画 ============================ */

    public static void opacity(Node node, double to, int ms, Interpolator ease) {
        if (node == null) {
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setToValue(to);
        fade.setInterpolator(ease);
        play(node, "opacity", fade);
    }

    public static void scale(Node node, double to, int ms, Interpolator ease) {
        if (node == null) {
            return;
        }
        ScaleTransition scale = new ScaleTransition(Duration.millis(ms), node);
        scale.setToX(to);
        scale.setToY(to);
        scale.setInterpolator(ease);
        play(node, "scale", scale);
    }

    public static void translate(Node node, double x, double y, int ms, Interpolator ease) {
        if (node == null) {
            return;
        }
        TranslateTransition move = new TranslateTransition(Duration.millis(ms), node);
        move.setToX(x);
        move.setToY(y);
        move.setInterpolator(ease);
        play(node, "translate", move);
    }

    /* ============================ 组合效果 ============================ */

    /**
     * 小面板入场：淡入 + 上浮回弹。
     * <p>故意不缩放：缩放会让面板里的文字在"还没完全展开"时发虚，
     * 上浮只改位置，文字始终按 1:1 渲染。</p>
     */
    public static void popIn(Node node) {
        if (node == null) {
            return;
        }
        node.setOpacity(0);
        node.setScaleX(1);
        node.setScaleY(1);
        node.setTranslateY(16);
        opacity(node, 1, 200, SOFT);
        translate(node, 0, 0, 340, SPRING);
    }

    /** 面板收起：下沉 + 淡出（同样不缩放）。 */
    public static void popOut(Node node, int ms, Runnable done) {
        if (node == null) {
            if (done != null) {
                done.run();
            }
            return;
        }
        node.setScaleX(1);
        node.setScaleY(1);
        translate(node, 0, 12, ms, EASE_IN);
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setToValue(0);
        fade.setInterpolator(EASE_IN);
        fade.setOnFinished(event -> {
            if (done != null) {
                done.run();
            }
        });
        play(node, "opacity", fade);
    }

    /** 换页：从右侧滑入 + 淡入。 */
    public static void pageIn(Node node) {
        if (node == null) {
            return;
        }
        node.setOpacity(0);
        node.setTranslateX(18);
        node.setScaleX(1);
        node.setScaleY(1);
        opacity(node, 1, 200, SOFT);
        translate(node, 0, 0, 330, SPRING);
    }

    /** 依次淡入：卡片、列表错峰出现。只动透明度，不缩放/位移，文字始终清晰。 */
    public static void stagger(List<? extends Node> nodes, int stepMs, int maxItems) {
        if (nodes == null) {
            return;
        }
        int index = 0;
        for (Node node : nodes) {
            if (node == null) {
                continue;
            }
            node.setOpacity(0);
            PauseTransition delay = new PauseTransition(Duration.millis((long) index * stepMs));
            delay.setOnFinished(event -> opacity(node, 1, 220, SOFT));
            delay.play();
            index++;
            if (index >= maxItems) {
                break;
            }
        }
    }

    public static void stagger(List<? extends Node> nodes, int stepMs) {
        stagger(nodes, stepMs, 12);
    }

    /** 状态变化时轻轻闪一下（只改透明度，避免文字被缩放变糊）。 */
    public static void pulse(Node node) {
        if (node == null) {
            return;
        }
        opacity(node, 0.45, 110, SOFT);
        PauseTransition back = new PauseTransition(Duration.millis(115));
        back.setOnFinished(event -> opacity(node, 1, 320, SPRING));
        back.play();
    }

    /**
     * 宽度动画：直接改 min/pref/max，走布局而不是缩放，
     * 文字永远按 1:1 渲染，不会糊。
     */
    public static void width(javafx.scene.layout.Region node, double target, int ms) {
        if (node == null) {
            return;
        }
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.millis(ms),
                        new KeyValue(node.prefWidthProperty(), target, SPRING),
                        new KeyValue(node.minWidthProperty(), target, SPRING),
                        new KeyValue(node.maxWidthProperty(), target, SPRING)));
        play(node, "width", timeline);
    }

    /** 同上，但可以指定缓动（搜索框拉长用 SOFT，避免冲出容器宽度）。 */
    public static void width(javafx.scene.layout.Region node, double target, int ms, Interpolator ease) {
        if (node == null) {
            return;
        }
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.millis(ms),
                        new KeyValue(node.prefWidthProperty(), target, ease),
                        new KeyValue(node.minWidthProperty(), target, ease),
                        new KeyValue(node.maxWidthProperty(), target, ease)));
        play(node, "width", timeline);
    }


    /** 鼠标移上去用内边距缩进：走布局，文字清晰不模糊。 */
    public static void hoverIndent(javafx.scene.layout.Region node, double indent) {
        if (node == null) {
            return;
        }
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> indent(node, indent, 150, SOFT_SPRING));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, event -> indent(node, 0, 220, SPRING));
    }

    private static void indent(javafx.scene.layout.Region node, double extra, int ms, Interpolator ease) {
        Object storedBase = node.getProperties().get("yi.indentBase");
        Object storedFrame = node.getProperties().get("yi.indentFrame");
        javafx.geometry.Insets currentPadding = node.getPadding();
        javafx.geometry.Insets baseFrame = storedFrame instanceof javafx.geometry.Insets insets
                ? insets
                : (currentPadding != null ? currentPadding : node.getInsets());
        double baseLeft = storedBase instanceof Number number ? number.doubleValue() : baseFrame.getLeft();
        node.getProperties().put("yi.indentBase", baseLeft);
        node.getProperties().put("yi.indentFrame", baseFrame);

        double from = currentPadding != null ? currentPadding.getLeft() : baseLeft;
        javafx.beans.property.DoubleProperty left =
                new javafx.beans.property.SimpleDoubleProperty(from);
        left.addListener((obs, old, now) -> node.setPadding(new javafx.geometry.Insets(
                baseFrame.getTop(), baseFrame.getRight(), baseFrame.getBottom(), now.doubleValue())));
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(left, baseLeft + extra, ease)));
        play(node, "indent", timeline);
    }

    /* ============================ 交互手感 ============================ */

    /** 按下变淡、松开回弹——只用透明度，文字不会被缩放糊掉（标签类用这个）。 */
    public static void pressFade(Node node) {
        if (node == null) {
            return;
        }
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> opacity(node, 0.72, 90, SOFT));
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> opacity(node, 1, 320, SPRING));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, event -> opacity(node, 1, 240, SPRING));
    }

    /** 按钮的 Q 弹点击：按下缩一点，松开弹回原位。 */
    public static void pressBounce(Node node) {
        if (node == null) {
            return;
        }
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> scale(node, 0.94, 90, SOFT));
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> scale(node, 1, 340, SPRING));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, event -> scale(node, 1, 240, SPRING));
    }

    /** 一键装好"悬停缩进 + 按下变淡"，全程不缩放。 */
    public static void interactive(javafx.scene.layout.Region node) {
        hoverIndent(node, 3);
        pressFade(node);
    }

    /* ============================ 系统适配 ============================ */

    /** 0 = 其它系统（维持原状），1 = Windows 10，2 = Windows 11。 */
    public static final int OS_WINDOW_LEVEL = detectWindows();

    private static int detectWindows() {
        String name = System.getProperty("os.name", "");
        if (!name.toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            return 0;
        }
        if (name.contains("11")) {
            return 2;
        }
        return windowsBuild() >= 22000 ? 2 : 1;
    }

    /** 读 HKLM\...\CurrentVersion\CurrentBuild；读不到就返回 0。只在类初始化时跑一次。 */
    private static int windowsBuild() {
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", "CurrentBuild")
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            process.waitFor();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("CurrentBuild\\s+REG_SZ\\s+(\\d+)").matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 开窗前：把初始状态摆好（必须在 stage.show() 之前调用，否则第一帧就先亮起来了）。
     */
    public static void prepareWindowOpen(Node root) {
        if (root == null) {
            return;
        }
        switch (OS_WINDOW_LEVEL) {
            case 2 -> {
                root.setOpacity(0);
                root.setScaleX(0.90);
                root.setScaleY(0.90);
                root.setTranslateY(20);
            }
            case 1 -> {
                root.setOpacity(0);
                root.setScaleX(1);
                root.setScaleY(1);
                root.setTranslateY(14);
            }
            default -> {
            }
        }
    }

    /**
     * 窗口打开动画：照着系统的开关窗质感来。
     * <p>Win11 放大 + 淡入；Win10 淡入 + 上移；其它系统不动。</p>
     * <p>改成"等两帧真正画完再播"：之前用固定 120ms 延迟，机器一慢就会在窗口已经
     * 显示出来之后才开播，看起来像没有动画。</p>
     */
    public static void windowOpen(Node root) {
        if (root == null || OS_WINDOW_LEVEL == 0) {
            return;
        }
        int level = OS_WINDOW_LEVEL;
        afterPulses(2, () -> {
            opacity(root, 1, level == 2 ? 280 : 240, SOFT);
            translate(root, 0, 0, level == 2 ? 460 : 320, level == 2 ? SPRING : SOFT);
            if (level == 2) {
                scale(root, 1, 460, SOFT);
            }
        });
    }

    /** 等 n 个渲染帧之后再执行：保证首帧已经是"缩小 + 透明"的初始态。 */
    private static void afterPulses(int frames, Runnable action) {
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            private int seen;

            @Override
            public void handle(long now) {
                if (++seen < frames) {
                    return;
                }
                stop();
                action.run();
            }
        };
        timer.start();
    }

    /** 窗口关闭动画；其它系统直接执行回调。重复调用只会执行一次回调。 */
    public static void windowClose(Node root, Runnable done) {
        if (root == null || OS_WINDOW_LEVEL == 0) {
            if (done != null) {
                done.run();
            }
            return;
        }
        if (Boolean.TRUE.equals(root.getProperties().get("yi.closing"))) {
            return;
        }
        root.getProperties().put("yi.closing", true);

        int ms = OS_WINDOW_LEVEL == 2 ? 220 : 160;
        if (OS_WINDOW_LEVEL == 2) {
            scale(root, 0.94, ms, EASE_IN);
            translate(root, 0, 14, ms, EASE_IN);
        }
        FadeTransition fade = new FadeTransition(Duration.millis(ms), root);
        fade.setToValue(0);
        fade.setInterpolator(EASE_IN);
        fade.setOnFinished(event -> {
            if (done != null) {
                done.run();
            }
        });
        play(root, "opacity", fade);
    }
}
