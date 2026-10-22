package com.yilauncher.yi_launcher.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 账户头像。
 * <p>皮肤图里的脸在 (8, 8) 起的 8×8 区域（高清皮肤按分辨率等比放大）。
 * 这里先在自己这边用「最近邻」把脸摊成目标像素尺寸，再交给 ImageView 按 1:1 画，
 * 渲染器不再做插值，8×8 的小头像也不会糊。</p>
 * <p>没有皮肤（离线账户 / 皮肤站没设皮肤）时用资源里的 steve.png。</p>
 */
public final class SkinHeads {

    /** 本地默认头像：texture 目录里 8×8 的史蒂夫脸。 */
    private static final String LOCAL_HEAD = "/com/yilauncher/yi_launcher/texture/steve.png";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 下载到的整张皮肤，按 URL 缓存，避免每次刷新卡片都重新拉一遍。 */
    private static final Map<String, Image> SOURCES = new HashMap<>();

    private static Image local;
    private static Image drawn;

    private SkinHeads() {
    }

    /** 资源里的本地头像；读不到就退回代码画的史蒂夫。 */
    public static synchronized Image localHead() {
        if (local != null) {
            return local;
        }
        try (InputStream in = SkinHeads.class.getResourceAsStream(LOCAL_HEAD)) {
            if (in != null) {
                Image image = new Image(in, 0, 0, true, false);
                if (!image.isError() && image.getWidth() >= 8) {
                    local = image;
                    return local;
                }
            }
        } catch (Exception ignored) {
        }
        local = drawnSteve();
        return local;
    }

    /** 代码画的史蒂夫（64×64，脸画在 (8,8)）：只在资源缺失时用。 */
    private static Image drawnSteve() {
        if (drawn != null) {
            return drawn;
        }
        WritableImage image = new WritableImage(64, 64);
        PixelWriter writer = image.getPixelWriter();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                writer.setColor(x, y, Color.web("#C58A62"));
            }
        }
        String[] face = {
                "HHHHHHHH", "HHHHHHHH", "SSSSSSSS", "SWISSWIS",
                "SSSSSSSS", "SSNSSNSS", "SSMMMMSS", "SSSSSSSS",
        };
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                writer.setColor(8 + x, 8 + y, switch (face[y].charAt(x)) {
                    case 'H' -> Color.web("#3D2C1E");
                    case 'W' -> Color.web("#F2F2F2");
                    case 'I' -> Color.web("#4A5F8A");
                    case 'N' -> Color.web("#B27950");
                    case 'M' -> Color.web("#8A5745");
                    default -> Color.web("#C58A62");
                });
            }
        }
        drawn = image;
        return drawn;
    }

    /**
     * 把皮肤图的「脸」按最近邻放大成 size×size。
     * <p>整张皮肤（宽 ≥ 64）取 (8·s, 8·s) 起的 8·s 方块，s = 宽 / 64；
     * 已经是裁好的小图（8×8 / 16×16）就整张当脸用。</p>
     */
    static Image head(Image source, int size) {
        if (source == null || size <= 0) {
            return localHead();
        }
        double width = source.getWidth();
        double height = source.getHeight();
        PixelReader reader = source.getPixelReader();
        if (width < 1 || height < 1 || reader == null) {
            return localHead();
        }
        double originX;
        double originY;
        double spanX;
        double spanY;
        if (width >= 64) {
            double scale = width / 64.0;
            originX = 8 * scale;
            originY = 8 * scale;
            spanX = 8 * scale;
            spanY = 8 * scale;
        } else {
            originX = 0;
            originY = 0;
            spanX = width;
            spanY = Math.min(width, height);
        }
        WritableImage out = new WritableImage(size, size);
        PixelWriter writer = out.getPixelWriter();
        int limitX = (int) width - 1;
        int limitY = (int) height - 1;
        for (int y = 0; y < size; y++) {
            int sy = clamp((int) (originY + y * spanY / size), limitY);
            for (int x = 0; x < size; x++) {
                int sx = clamp((int) (originX + x * spanX / size), limitX);
                writer.setColor(x, y, reader.getColor(sx, sy));
            }
        }
        return out;
    }

    private static int clamp(int value, int limit) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, limit);
    }

    /** 头像节点：固定尺寸、圆角裁切，皮肤没下好时先显示本地史蒂夫。 */
    public static SkinAvatar avatar(String skinUrl, int size) {
        SkinAvatar avatar = new SkinAvatar(size);
        avatar.setSkin(skinUrl);
        return avatar;
    }

    /** 从皮肤地址异步拉图；失败就保持本地头像。 */
    private static void loadAsync(String url, Consumer<Image> onDone) {
        Thread thread = new Thread(() -> {
            Image loaded = null;
            try {
                HttpResponse<byte[]> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(12))
                                .header("User-Agent", "YI_Launcher/0.1.0")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200 && response.body().length > 0) {
                    Image image = new Image(new ByteArrayInputStream(response.body()), 0, 0, true, false);
                    if (!image.isError() && image.getWidth() >= 8 && image.getHeight() >= 8) {
                        loaded = image;
                    }
                }
            } catch (Exception ignored) {
            }
            Image result = loaded;
            if (result != null) {
                synchronized (SkinHeads.class) {
                    SOURCES.put(url, result);
                }
            }
            Platform.runLater(() -> onDone.accept(result));
        }, "yi-skin");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized Image source(String url) {
        return SOURCES.get(url);
    }

    /** 头像控件：换账户时只调 {@link #setSkin(String)}，不用重建节点。 */
    public static final class SkinAvatar extends StackPane {

        private final ImageView view = new ImageView();
        private final int size;
        private String url = "";

        SkinAvatar(int size) {
            this.size = size;
            getStyleClass().add("skin-avatar");
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);

            view.setSmooth(false);
            view.setPreserveRatio(false);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setImage(head(localHead(), size));

            Rectangle clip = new Rectangle(size, size);
            clip.setArcWidth(size * 0.42);
            clip.setArcHeight(size * 0.42);
            view.setClip(clip);

            getChildren().add(view);
        }

        /** 传空字符串 = 用本地史蒂夫。 */
        public void setSkin(String skinUrl) {
            String next = skinUrl == null ? "" : skinUrl.trim();
            url = next;
            if (next.isEmpty()) {
                view.setImage(head(localHead(), size));
                return;
            }
            Image cached = source(next);
            if (cached != null) {
                view.setImage(head(cached, size));
                return;
            }
            view.setImage(head(localHead(), size));
            loadAsync(next, image -> {
                if (image != null && next.equals(url)) {
                    view.setImage(head(image, size));
                }
            });
        }

        public int size() {
            return size;
        }
    }
}
