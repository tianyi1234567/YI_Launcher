package com.yilauncher.yi_launcher.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * 自绘内存条：黄色段 = 系统已使用，青绿色段 = 这次要分配给游戏的内存，灰色 = 还没用上的部分。
 * <p>每 1 GB 一个刻度，点击 / 拖动可以改分配值。全部用 Region 自己排版，
 * 不依赖 JavaFX 的 Slider skin，所以不会像 Slider 那样把轨道撑到对话框外面去。</p>
 */
public final class MemoryBar extends Pane {

    /** 允许的最小分配值。 */
    public static final int MIN_MB = 512;

    private static final double TRACK_H = 10;
    private static final double THUMB_R = 7.5;

    private final Region track = new Region();
    private final Region usedSeg = new Region();
    private final Region allocSeg = new Region();
    private final java.util.List<Region> ticks = new java.util.ArrayList<>();
    private final Circle thumb = new Circle(THUMB_R);
    private final Rectangle clip = new Rectangle();

    private final IntegerProperty value = new SimpleIntegerProperty(6144);

    private long totalMb = 1;
    private long usedMb;
    private int maxMb = 8192;
    private boolean dragging;

    public MemoryBar() {
        getStyleClass().add("mem-bar");
        track.getStyleClass().add("mem-bar-track");
        usedSeg.getStyleClass().add("mem-bar-used");
        allocSeg.getStyleClass().add("mem-bar-alloc");
        thumb.getStyleClass().add("mem-bar-thumb");
        getChildren().addAll(track, usedSeg, allocSeg, thumb);
        // 统一裁剪：无论怎么布局都不会画到条外面
        setClip(clip);
        setMinHeight(26);
        setPrefHeight(26);
        setMaxWidth(Double.MAX_VALUE);
        setCursor(Cursor.HAND);

        value.addListener((obs, old, now) -> {
            updateFractions();
            requestLayout();
        });

        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragging = true;
            updateFromX(event.getX());
            event.consume();
        });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (dragging) {
                updateFromX(event.getX());
                event.consume();
            }
        });
        addEventHandler(MouseEvent.MOUSE_RELEASED, event -> dragging = false);
    }

    public IntegerProperty valueProperty() {
        return value;
    }

    public int getValue() {
        return value.get();
    }

    /** 直接设值（精确 MB 用），只做上下限夹取，不对齐整 GB。 */
    public void setValue(int memoryMb) {
        value.set(Math.max(MIN_MB, Math.min(maxMb, memoryMb)));
    }

    /** 配置条：总内存、系统已用、可分配上限（都按 MB）。 */
    public void configure(long totalMb, long usedMb, int maxMb) {
        this.totalMb = Math.max(1, totalMb);
        this.usedMb = Math.max(0, Math.min(this.totalMb - 1, usedMb));
        this.maxMb = Math.max(MIN_MB, maxMb);
        rebuildTicks();
        updateFractions();
        requestLayout();
    }

    /** 一 GB 一个刻度，数量跟着可分配上限走。 */
    private void rebuildTicks() {
        getChildren().removeAll(ticks);
        ticks.clear();
        int gb = Math.max(1, maxMb / 1024);
        int insertAt = Math.max(0, getChildren().indexOf(thumb));
        for (int i = 1; i <= gb; i++) {
            Region tick = new Region();
            tick.getStyleClass().add("mem-bar-tick");
            tick.setMouseTransparent(true);
            tick.setUserData(i);
            ticks.add(tick);
            getChildren().add(insertAt + i - 1, tick);
        }
    }

    private void updateFractions() {
        // 进度条本身按比例排版，layoutChildren 里算宽度，这里只保证值合法
        if (value.get() > maxMb) {
            value.set(maxMb);
        }
    }

    private void updateFromX(double x) {
        double width = getWidth();
        if (width <= 0) {
            return;
        }
        double usedW = width * (usedMb / (double) totalMb);
        double freeW = Math.max(1, width - usedW);
        double ratio = (x - usedW) / freeW;
        int snapped = (int) Math.round(ratio * maxMb / 1024.0) * 1024;
        // 拖到最左也给最小分配值，避免 0 MB
        setValue(Math.max(MIN_MB, snapped));
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        double trackY = (height - TRACK_H) / 2;
        double usedW = width * (usedMb / (double) totalMb);
        double freeW = Math.max(1, width - usedW);
        double allocW = freeW * (value.get() / (double) maxMb);

        track.resizeRelocate(0, trackY, width, TRACK_H);
        usedSeg.resizeRelocate(0, trackY, Math.max(0, usedW), TRACK_H);
        allocSeg.resizeRelocate(usedW, trackY, Math.max(0, allocW), TRACK_H);

        for (Region tick : ticks) {
            if (!(tick.getUserData() instanceof Integer gb)) {
                continue;
            }
            double x = usedW + freeW * (gb * 1024.0 / maxMb);
            tick.resizeRelocate(x - 0.5, trackY - 3, 1, TRACK_H + 6);
        }

        double thumbX = usedW + allocW;
        thumb.setCenterX(thumbX);
        thumb.setCenterY(height / 2);

        clip.setWidth(width);
        clip.setHeight(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        return 26;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 26;
    }
}
