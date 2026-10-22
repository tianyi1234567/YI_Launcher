package com.yilauncher.yi_launcher.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import javafx.stage.Stage;

/**
 * 用 Win32 的「按住标题栏移动」代替自己逐帧改 stage 坐标。
 * <p>JavaFX 拖透明窗口时每次移动都要重绘整块窗口，弱显卡上只能跑到 30 帧；
 * 交给系统搬窗口后由 DWM 直接合成，跟手且不掉帧。</p>
 * <p>失败（非 Windows / 找不到窗口 / JNA 不可用）时返回 false，调用方回退到逐帧方案。</p>
 */
final class NativeDrag {

    private static final int WM_NCLBUTTONDOWN = 0x00A1;
    private static final int HTCAPTION = 2;

    /** JNA 的 User32 没声明这两个方法，补一个子接口。 */
    private interface ExtraUser32 extends User32 {
        ExtraUser32 EXTRA = Native.load("user32", ExtraUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ReleaseCapture();

        int GetDoubleClickTime();
    }

    private static Boolean supported;
    private static WinDef.HWND cachedWindow;

    private NativeDrag() {
    }

    /** 仅 Windows + JNA 可用时启用。 */
    static synchronized boolean supported() {
        if (supported == null) {
            boolean ok;
            try {
                String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
                ExtraUser32.EXTRA.GetDoubleClickTime();
                ok = os.contains("windows");
            } catch (Throwable error) {
                ok = false;
            }
            supported = ok;
        }
        return supported;
    }

    /** 找本进程里第一个可见的顶层窗口（就是启动器主窗口）。 */
    private static WinDef.HWND window() {
        if (cachedWindow != null) {
            return cachedWindow;
        }
        int pid = Kernel32.INSTANCE.GetCurrentProcessId();
        WinDef.HWND[] found = new WinDef.HWND[1];
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
            if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
                found[0] = hwnd;
                return false;
            }
            return true;
        }, null);
        cachedWindow = found[0];
        return cachedWindow;
    }

    /**
     * 开始原生拖动：SendMessage 会一直阻塞到用户松手，期间由系统搬窗口。
     *
     * @return 是否成功进入原生拖动
     */
    static boolean startMove(Stage stage) {
        if (!supported()) {
            return false;
        }
        try {
            WinDef.HWND hwnd = window();
            if (hwnd == null) {
                return false;
            }
            ExtraUser32.EXTRA.ReleaseCapture();
            User32.INSTANCE.SendMessage(hwnd, WM_NCLBUTTONDOWN,
                    new WinDef.WPARAM(HTCAPTION), new WinDef.LPARAM(0));
            syncPosition(stage);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 原生拖动结束后把真实位置写回 Stage，避免后续最大化/还原用到旧坐标。 */
    static void syncPosition(Stage stage) {
        WinDef.HWND hwnd = window();
        if (hwnd == null) {
            return;
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) {
            return;
        }
        double scaleX = stage.getOutputScaleX() > 0 ? stage.getOutputScaleX() : 1;
        double scaleY = stage.getOutputScaleY() > 0 ? stage.getOutputScaleY() : 1;
        stage.setX(rect.left / scaleX);
        stage.setY(rect.top / scaleY);
    }

    static String describe() {
        String handle = "0";
        try {
            WinDef.HWND hwnd = window();
            if (hwnd != null) {
                handle = Pointer.nativeValue(hwnd.getPointer()) + "";
            }
        } catch (Throwable error) {
            handle = "err:" + error.getClass().getSimpleName();
        }
        return "supported=" + supported() + " hwnd=" + handle;
    }
}
