/* ==========================================================================
 *  衣启动器 · core 层 ｜ Accents.java —— 版本配色
 *  做什么：8 套预置强调色（含中文名）；随机取一套给新导入的版本，并提供调深/转 rgba。
 *  谁在用：VersionsPage（列表与详情同色）、GamePicker、VersionsScanner。
 *  入口：PALETTE、random()、shade(hex, factor)、rgba(hex, alpha)。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** 版本配色：导入时随机取一个，之后可以在详情页里改。 */
public final class Accents {

    public static final String[][] PALETTE = {
            {"#6C7CFF", "靛蓝"}, {"#4FD1C5", "薄荷"}, {"#F0A24B", "暖橙"}, {"#A98BE0", "薰衣草"},
            {"#F0739A", "樱粉"}, {"#4CBF6A", "苔绿"}, {"#7C8AA8", "雾蓝"}, {"#E2645E", "砖红"}};

    private Accents() {
    }

    public static String random() {
        return PALETTE[ThreadLocalRandom.current().nextInt(PALETTE.length)][0];
    }

    /** 把 #RRGGBB 调暗（factor < 1）或调亮，用于做渐变的第二个色标。 */
    public static String shade(String hex, double factor) {
        String value = hex == null ? "#6C7CFF" : hex.replace("#", "");
        if (value.length() < 6) {
            value = "6C7CFF";
        }
        int r = clamp((int) Math.round(Integer.parseInt(value.substring(0, 2), 16) * factor));
        int g = clamp((int) Math.round(Integer.parseInt(value.substring(2, 4), 16) * factor));
        int b = clamp((int) Math.round(Integer.parseInt(value.substring(4, 6), 16) * factor));
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    public static String rgba(String hex, double alpha) {
        String value = hex == null ? "#6C7CFF" : hex.replace("#", "");
        if (value.length() < 6) {
            value = "6C7CFF";
        }
        int r = Integer.parseInt(value.substring(0, 2), 16);
        int g = Integer.parseInt(value.substring(2, 4), 16);
        int b = Integer.parseInt(value.substring(4, 6), 16);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)", r, g, b, alpha);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
