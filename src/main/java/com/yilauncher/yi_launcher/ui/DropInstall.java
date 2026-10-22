/* ==========================================================================
 *  衣启动器 · ui 层 ｜ DropInstall.java —— 拖文件安装
 *  做什么：判断拖进来的是整合包还是模组 → 弹二次确认（名称 / 分组 / 装到哪个版本）→ 真正安装。
 *  谁在用：MainView.installDropTarget()（窗口级拖放）。
 *  入口：handle(文件列表)、confirmPack()、confirmMods()、unknown()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.PackInstaller;
import com.yilauncher.yi_launcher.core.VersionEntry;
import com.yilauncher.yi_launcher.core.VersionGroup;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拖文件进窗口后的安装流程。
 * <ul>
 *   <li>.zip 整包（CurseForge / Modrinth）→ 二次确认（名称 + 分组）→ 下载中心播进度；</li>
 *   <li>.jar 模组（可一次多个）→ 二次确认「要安装到 xxx 吗」→ 装进当前版本；</li>
 *   <li>认不出来 → 提示「无法识别此文件…」。</li>
 * </ul>
 */
public final class DropInstall {

    private DropInstall() {
    }

    public static void handle(List<File> files, Ui.OverlayHost overlay, Runnable onInstalled) {
        if (files == null || files.isEmpty()) {
            unknown(overlay);
            return;
        }
        List<Path> packs = new ArrayList<>();
        List<Path> mods = new ArrayList<>();
        boolean bad = false;
        for (File file : files) {
            Path path = file.toPath();
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                if (PackInstaller.inspect(path) != null) {
                    packs.add(path);
                } else {
                    bad = true;
                }
            } else if (lower.endsWith(".jar")) {
                if (PackInstaller.isModJar(path)) {
                    mods.add(path);
                } else {
                    bad = true;
                }
            } else {
                bad = true;
            }
        }
        if (!packs.isEmpty()) {
            confirmPack(packs.get(0), overlay, onInstalled);
            return;
        }
        if (!mods.isEmpty()) {
            confirmMods(mods, overlay, onInstalled, bad);
            return;
        }
        unknown(overlay);
    }

    /* ============================ 模组 ============================ */

    private static void confirmMods(List<Path> jars, Ui.OverlayHost overlay,
                                    Runnable onInstalled, boolean alsoBadFiles) {
        LauncherEngine engine = LauncherEngine.get();
        VersionEntry version = engine.selected();
        if (version == null) {
            HBox buttons = new HBox(8, Ui.spacer(), Ui.primaryButton("知道了", null, overlay::hide));
            buttons.setAlignment(Pos.CENTER_RIGHT);
            overlay.show(card("还没有选择版本",
                    "请先打开或选择一个版本，再把模组拖进来安装。",
                    "也可以在「版本管理」里点一下要装模组的版本。",
                    buttons));
            return;
        }

        StringBuilder names = new StringBuilder();
        int limit = Math.min(jars.size(), 6);
        for (int i = 0; i < limit; i++) {
            names.append(i == 0 ? "" : "\n").append("· ").append(jars.get(i).getFileName());
        }
        if (jars.size() > limit) {
            names.append("\n… 还有 ").append(jars.size() - limit).append(" 个");
        }

        HBox buttons = new HBox(8, Ui.spacer(), Ui.ghostButton("取消", null, overlay::hide));
        javafx.scene.control.Button install = Ui.primaryButton("安装", null, null);
        install.getStyleClass().add("dialog-confirm");
        install.setMinHeight(34);
        install.setPrefHeight(34);
        buttons.getChildren().add(install);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label status = Ui.label("", "account-status");
        install.setOnAction(e -> {
            install.setDisable(true);
            status.setText("正在安装 " + jars.size() + " 个模组…");
            com.yilauncher.yi_launcher.core.InstallJobs.begin();
            Thread worker = new Thread(() -> {
                int ok = 0;
                String error = null;
                for (Path jar : jars) {
                    try {
                        PackInstaller.installMod(jar, version, engine.root());
                        ok++;
                    } catch (Exception failure) {
                        error = failure.getMessage();
                    }
                }
                int done = ok;
                String message = error;
                Platform.runLater(() -> {
                    com.yilauncher.yi_launcher.core.InstallJobs.finish();
                    if (onInstalled != null) {
                        onInstalled.run();
                    }
                    HBox ok2 = new HBox(8, Ui.spacer(), Ui.primaryButton("知道了", null, overlay::hide));
                    ok2.setAlignment(Pos.CENTER_RIGHT);
                    overlay.show(card(message == null ? "模组已安装" : "部分模组安装失败",
                            "已装 " + done + " / " + jars.size() + " 个到「" + version.displayName + "」",
                            message == null ? "目录：" + modsDir(version) : "失败原因：" + message,
                            ok2));
                });
            }, "yi-mod-install");
            worker.setDaemon(true);
            worker.start();
        });
        VBox card = new VBox(9,
                Ui.label("安装模组", "dialog-title"),
                Ui.label("要安装到「" + version.displayName + "」吗？", "dialog-title"),
                hint("版本：" + version.loaderText() + " · "
                        + (version.isolation ? "隔离（装进这个版本的 mods）" : "共用（装进 .minecraft/mods）")),
                hint(names.toString()),
                status,
                buttons);
        if (alsoBadFiles) {
            card.getChildren().add(3, hint("有文件没认出是模组，已跳过"));
        }
        style(card);
        overlay.show(card);
    }

    /* ============================ 整合包 ============================ */

    private static void confirmPack(Path zip, Ui.OverlayHost overlay, Runnable onInstalled) {
        LauncherEngine engine = LauncherEngine.get();
        PackInstaller.Pack pack = PackInstaller.inspect(zip);
        if (pack == null) {
            unknown(overlay);
            return;
        }

        TextField name = new TextField(PackInstaller.defaultName(zip));
        name.getStyleClass().add("dialog-field");
        name.setPrefHeight(34);

        Label info = hint(pack.kind() + " 整合包"
                + (pack.mcVersion().isBlank() ? "" : " · MC " + pack.mcVersion())
                + " · " + pack.loader()
                + (pack.version().isBlank() ? "" : " · v" + pack.version())
                + " · " + pack.manifestMods() + " 个模组在清单里");

        VersionGroup[] chosen = {engine.selectedGroup()};
        FlowPane groups = new FlowPane(6, 6);
        List<Label> chips = new ArrayList<>();
        Runnable highlight = () -> {
            for (Label chip : chips) {
                chip.getStyleClass().remove("active");
                if (chip.getUserData() instanceof VersionGroup group && chosen[0] != null
                        && group.id.equals(chosen[0].id)) {
                    chip.getStyleClass().add("active");
                }
            }
        };
        for (VersionGroup group : engine.groups()) {
            Label chip = Ui.label(group.name, "filter-chip");
            chip.setUserData(group);
            chip.setOnMouseClicked(e -> {
                chosen[0] = group;
                highlight.run();
            });
            Motion.pressFade(chip);
            chips.add(chip);
            groups.getChildren().add(chip);
        }
        highlight.run();

        Label status = Ui.label("", "account-status");
        status.setWrapText(true);

        HBox buttons = new HBox(8, Ui.spacer(), Ui.ghostButton("取消", null, overlay::hide));
        javafx.scene.control.Button install = Ui.primaryButton("安装", null, null);
        install.getStyleClass().add("dialog-confirm");
        install.setMinHeight(34);
        install.setPrefHeight(34);
        buttons.getChildren().add(install);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        install.setOnAction(e -> {
            String target = name.getText() == null ? "" : name.getText().trim();
            if (target.isEmpty()) {
                status.setText("请先给这个整合包起个名字");
                return;
            }
            if (chosen[0] == null) {
                status.setText("请选择要装进哪个分组");
                return;
            }
            Path folder = chosen[0].versionsDir.resolve(target);
            if (Files.exists(folder)) {
                status.setText("这个名字已经存在了：" + target);
                return;
            }
            install.setDisable(true);
            status.setText("正在安装…");
            overlay.hide();

            // 装整合包要联网下载，进度动画得看得见：直接切到下载中心
            DownloadsPage page = overlay instanceof MainView view ? view.openDownloads() : null;
            if (page != null) {
                page.beginPack(target, pack.loader());
            }
            com.yilauncher.yi_launcher.core.InstallJobs.begin();
            Thread worker = new Thread(() -> {
                try {
                    PackInstaller.Result result = PackInstaller.installPack(zip, pack, chosen[0], target,
                            (overall, mods, loader, note) -> {
                                if (page != null) {
                                    page.pushProgress(overall, mods, loader, note);
                                }
                            });
                    Platform.runLater(() -> {
                        com.yilauncher.yi_launcher.core.InstallJobs.finish();
                        if (onInstalled != null) {
                            onInstalled.run();
                        }
                        Runnable showResult = () -> {
                            HBox ok2 = new HBox(8, Ui.spacer(), Ui.primaryButton("知道了", null, overlay::hide));
                            ok2.setAlignment(Pos.CENTER_RIGHT);
                            String detail = "写入 " + result.files() + " 个文件，其中 " + result.mods()
                                    + " 个模组；下载 " + result.downloaded() + " 个"
                                    + (result.failed() > 0 ? "，失败 " + result.failed() + " 个" : "")
                                    + "；版本列表里会多出「" + target + "」";
                            overlay.show(card("整合包已安装", "位置：" + result.folder(), detail, ok2));
                        };
                        if (page != null) {
                            page.finishPack(showResult);
                        } else {
                            showResult.run();
                        }
                    });
                } catch (Exception error) {
                    Platform.runLater(() -> {
                        com.yilauncher.yi_launcher.core.InstallJobs.finish();
                        if (page != null) {
                            page.pushError(String.valueOf(error.getMessage()));
                            page.finishPack(null);
                        }
                        HBox ok2 = new HBox(8, Ui.spacer(), Ui.primaryButton("知道了", null, overlay::hide));
                        ok2.setAlignment(Pos.CENTER_RIGHT);
                        overlay.show(card("安装失败", String.valueOf(error.getMessage()), "", ok2));
                    });
                }
            }, "yi-pack-install");
            worker.setDaemon(true);
            worker.start();
        });

        VBox card = new VBox(10,
                Ui.label("安装整合包", "dialog-title"),
                hint("确认一下名称和要装进的分组"),
                info, name,
                hint("安装到分组"), groups,
                status, buttons);
        style(card);
        card.setMinWidth(430);
        card.setPrefWidth(430);
        card.setMaxWidth(430);
        overlay.show(card);
    }

    /* ============================ 提示 ============================ */

    private static void unknown(Ui.OverlayHost overlay) {
        HBox buttons = new HBox(8, Ui.spacer(), Ui.primaryButton("知道了", null, overlay::hide));
        buttons.setAlignment(Pos.CENTER_RIGHT);
        overlay.show(card("无法识别",
                "无法识别此文件，请确认拖动的文件是整合包或模组！",
                "整合包：CurseForge / Modrinth 的 .zip；模组：带元数据的 .jar。",
                buttons));
    }

    private static Path modsDir(VersionEntry version) {
        LauncherEngine engine = LauncherEngine.get();
        return version.isolation ? version.dir.resolve("mods") : engine.root().resolve("mods");
    }

    private static Label hint(String text) {
        Label label = Ui.label(text, "dialog-hint");
        label.setWrapText(true);
        return label;
    }

    private static void style(VBox card) {
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(17, 19, 15, 19));
        if (card.getMinWidth() < 1) {
            card.setMinWidth(400);
            card.setPrefWidth(400);
            card.setMaxWidth(400);
        }
    }

    private static VBox card(String title, String line1, String line2, Node buttons) {
        VBox card = new VBox(9);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(17, 19, 15, 19));
        card.setMinWidth(400);
        card.setPrefWidth(400);
        card.setMaxWidth(400);
        card.getChildren().add(Ui.label(title, "dialog-title"));
        if (line1 != null && !line1.isBlank()) {
            card.getChildren().add(hint(line1));
        }
        if (line2 != null && !line2.isBlank()) {
            card.getChildren().add(hint(line2));
        }
        card.getChildren().add(buttons);
        return card;
    }
}
