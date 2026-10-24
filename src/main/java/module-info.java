module com.yilauncher.yi_launcher {
    requires javafx.controls;
    requires java.management;
    requires jdk.management;
    requires java.net.http;
    requires jdk.httpserver;

    requires com.sun.jna;
    requires com.sun.jna.platform;

    exports com.yilauncher.yi_launcher;
}
