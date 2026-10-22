/* ==========================================================================
 *  衣启动器 · ui 层 ｜ GamePicker.java —— 「选择游戏」面板
 *  做什么：先选分组、再点版本，点中即写进 config（chosenVersion）并回调刷新启动页。
 *  谁在用：LaunchPage 的版本胶囊、ModsPage、VersionsPage。
 *  入口：create(overlay, onPicked)。
 * ========================================================================== */
package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.Accents;
import com.yilauncher.yi_launcher.core.LauncherEngine;
import com.yilauncher.yi_launcher.core.VersionEntry;
import com.yilauncher.yi_launcher.core.VersionGroup;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 「选择游戏」面板：先选分组，再点版本。
 * <p>点中即生效（写进 config.json 的 chosenVersion），启动页主模块会一直显示它，
 * 直到这个版本被删掉或换了分组。</p>
 */
public final class GamePicker {

    private GamePicker() {
    }

    public static Node create(Ui.OverlayHost overlay, Runnable onPicked) {
        LauncherEngine engine = LauncherEngine.get();

        VBox card = new VBox(11);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(17, 19, 15, 19));
        card.setMinWidth(430);
        card.setPrefWidth(430);
        card.setMaxWidth(430);

        Label hint = Ui.label("先挑一个分组，再点里面的版本；选中后会一直显示在启动页", "dialog-hint");
        hint.setWrapText(true);

        FlowPane groupWrap = new FlowPane(6, 6);
        groupWrap.setPrefWrapLength(390);

        VBox list = new VBox(6);
        ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().addAll("list-scroll", "version-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(240);
        scroll.setPrefHeight(240);
        scroll.setMaxHeight(240);

        VersionGroup[] current = {engine.selectedGroup()};
        List<Label> chips = new ArrayList<>();

        Runnable highlight = () -> {
            for (Label chip : chips) {
                chip.getStyleClass().remove("active");
                Object data = chip.getUserData();
                if (current[0] != null && data instanceof VersionGroup group
                        && group.id.equals(current[0].id)) {
                    chip.getStyleClass().add("active");
                }
            }
        };

        Runnable fillList = () -> {
            list.getChildren().clear();
            VersionGroup group = current[0];
            if (group == null) {
                list.getChildren().add(Ui.label("还没有分组", "dialog-hint"));
                return;
            }
            List<VersionEntry> picked = new ArrayList<>();
            for (VersionEntry entry : engine.versions()) {
                if (group.id.equals(entry.groupId)) {
                    picked.add(entry);
                }
            }
            if (picked.isEmpty()) {
                list.getChildren().add(Ui.label("这个分组里没有版本", "dialog-hint"));
                return;
            }
            for (VersionEntry entry : picked) {
                list.getChildren().add(row(entry, overlay, onPicked, engine));
            }
        };

        for (VersionGroup group : engine.groups()) {
            Label chip = Ui.label(group.name, "filter-chip");
            chip.setUserData(group);
            chip.setOnMouseClicked(e -> {
                current[0] = group;
                highlight.run();
                fillList.run();
            });
            Motion.pressFade(chip);
            chips.add(chip);
            groupWrap.getChildren().add(chip);
        }
        highlight.run();
        fillList.run();

        HBox footer = new HBox(8, Ui.spacer(), Ui.ghostButton("取消", null, overlay::hide));
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(Ui.label("选择游戏", "dialog-title"), hint, groupWrap, scroll, footer);
        return card;
    }

    private static Node row(VersionEntry entry, Ui.OverlayHost overlay, Runnable onPicked,
                            LauncherEngine engine) {
        Label badge = new Label(shortLoader(entry.loader));
        badge.getStyleClass().add("loader-badge");
        badge.setStyle("-fx-background-color: linear-gradient(to bottom right, "
                + entry.accent + ", " + Accents.shade(entry.accent, 0.78) + ");");

        VBox texts = new VBox(1,
                Ui.label(entry.displayName, "version-name"),
                Ui.label(entry.loaderText() + " · " + entry.gameVersion, "version-meta"));
        Region spacer = Ui.spacer();
        boolean chosen = engine.chosen() == entry;
        Label tag = Ui.label(chosen ? "已选中" : "", "version-tag");

        HBox row = new HBox(10, badge, texts, spacer, tag);
        row.getStyleClass().add("version-row");
        if (chosen) {
            row.getStyleClass().add("active");
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> {
            engine.choose(entry);
            overlay.hide();
            if (onPicked != null) {
                onPicked.run();
            }
        });
        Motion.pressFade(row);
        return row;
    }

    private static String shortLoader(String loader) {
        return switch (loader) {
            case "Fabric" -> "Fa";
            case "Forge" -> "Fo";
            case "NeoForge" -> "N";
            case "Quilt" -> "Q";
            case "OptiFine" -> "OF";
            default -> "原";
        };
    }
}
