/* ==========================================================================
 *  衣启动器 · core 层 ｜ InstallJobs.java —— 安装任务计数
 *  做什么：一个可被界面监听的计数器，装/下任务开始时 +1、结束 -1。
 *  谁在用：DropInstall、MainView（侧栏「下载中心」小红点，0 就隐藏）。
 *  入口：begin()、finish()、active()（读计数）、count()、reset()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * 正在进行的安装 / 下载任务计数。
 * <p>侧栏「下载中心」小红点用它当真实数字：有任务才显示，装完自动消失。
 * 计数用属性（javafx.base），界面那边监听它刷新即可。</p>
 */
public final class InstallJobs {

    private static final SimpleIntegerProperty ACTIVE = new SimpleIntegerProperty(0);

    private InstallJobs() {
    }

    /** 当前进行中的任务数（只读属性）。 */
    public static ReadOnlyIntegerProperty active() {
        return ACTIVE;
    }

    public static int count() {
        return ACTIVE.get();
    }

    /** 开始一个任务。 */
    public static void begin() {
        ACTIVE.set(ACTIVE.get() + 1);
    }

    /** 结束一个任务（不会掉到负数）。 */
    public static void finish() {
        ACTIVE.set(Math.max(0, ACTIVE.get() - 1));
    }

    /** 异常兜底：直接清零。 */
    public static void reset() {
        ACTIVE.set(0);
    }
}
