/* ==========================================================================
 *  衣启动器 · 模块声明 ｜ module-info.java —— JPMS 模块与依赖
 *  做什么：声明启动器需要的 JDK 模块与第三方模块，并导出主包。
 *  注意：界面全部由代码构建，已无 FXML，因此不再需要 javafx.fxml。
 *  JDK 模块：java.net.http 负责联网下载；java.management / jdk.management
 *            供 ui.SystemMonitor 读取 CPU 与内存。
 * ==========================================================================
 */
module com.yilauncher.yi_launcher {
    requires javafx.controls;

    requires java.management;
    requires jdk.management;
    requires java.net.http;

    // Windows 原生窗口拖动（ui.NativeDrag）与「打开文件夹」（ui.AccountDialog）
    requires com.sun.jna;
    requires com.sun.jna.platform;

    exports com.yilauncher.yi_launcher;
}
