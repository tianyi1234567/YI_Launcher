package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.Account;
import com.yilauncher.yi_launcher.core.AccountStore;
import com.yilauncher.yi_launcher.core.AuthClients;
import com.yilauncher.yi_launcher.core.LauncherEngine;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 账户页面（不是弹窗）：正版登录 / 离线登录 / 皮肤站登录 / 我的账户。
 * <p>正版那张卡上有一道循环流动的金光；离线登录会先问一句「别忘补票」，
 * 想看正版就去微软商店。登录逻辑全部沿用 {@link AuthClients}。</p>
 */
public final class AccountPage extends BorderPane {

    /** 微软《我的世界》购买页。 */
    private static final String BUY_URL =
            "https://www.xbox.com/zh-CN/games/store/minecraft-java-bedrock-edition-for-pc/9NXP44L49SHJ";

    private final MainView host;
    private final VBox list = new VBox(12);
    private final VBox accountBox = new VBox(9);
    private final Label status = Ui.label("", "account-status");
    private final List<Button> buttons = new ArrayList<>();
    /** 页面滚动容器：点「去正版登录」时滚回顶部那张金光卡。 */
    private ScrollPane scroller;
    private int flowToken;

    public AccountPage(MainView host) {
        this.host = host;
        setCenter(build());
    }

    private Node build() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 16, 16, 6));
        VBox header = new VBox(0,
                Ui.label("账户", "section-title"),
                Ui.label("正版、离线、皮肤站都能用；换账户随时在这里切", "section-sub"));

        accountBox.getStyleClass().addAll("card", "card-pad");
        list.getChildren().addAll(microsoftCard(), offlineCard(), skinCard(), accountBox, status);
        list.setPadding(new Insets(2, 6, 6, 0));
        refreshAccount();

        scroller = new ScrollPane(list);
        scroller.getStyleClass().addAll("list-scroll", "detail-scroll");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        main.getChildren().addAll(header, scroller);
        return main;
    }

    /* ============================ 正版登录（金光） ============================ */

    private Node microsoftCard() {
        Label title = Ui.label("正版登录", "account-card-title");
        Label hint = Ui.label("拿一个设备验证码，在浏览器里输入即可；启动器会自己等到授权完成。",
                "settings-hint");
        hint.setWrapText(true);

        Label code = Ui.label("", "ms-code");
        Label link = Ui.label("", "dialog-hint");
        link.setWrapText(true);
        HBox codeButtons = new HBox(6,
                Ui.ghostButton("打开浏览器", null, () -> openUrl(link.getText())),
                Ui.ghostButton("复制代码", null, () -> copy(code.getText())));
        VBox codeBox = new VBox(7, code, link, codeButtons);
        codeBox.getStyleClass().add("ms-code-box");
        codeBox.setVisible(false);
        codeBox.setManaged(false);

        Button start = Ui.primaryButton("获取微软验证码", null, null);
        start.setOnAction(e -> {
            final int token = ++flowToken;
            start.setDisable(true);
            codeBox.setVisible(false);
            codeBox.setManaged(false);
            setStatus("正在向微软请求验证码…", false);
            async(() -> {
                AuthClients.MsDeviceCode device = AuthClients.microsoftStart();
                Platform.runLater(() -> {
                    if (token != flowToken) {
                        return;
                    }
                    code.setText(device.userCode());
                    link.setText(device.verificationUri());
                    codeBox.setVisible(true);
                    codeBox.setManaged(true);
                    setStatus("在浏览器里输入 " + device.userCode() + "，这里会自动等到登录成功…", false);
                });
                openUrl(device.verificationUri());
                Account account = AuthClients.microsoftWait(device);
                if (token == flowToken) {
                    // 正版登录成功 = 这个玩家确实有游戏，之后离线 / 皮肤站登录才放行
                    LauncherEngine.get().config().markPremiumVerified();
                    finish(account);
                }
            });
        });
        buttons.add(start);

        VBox box = new VBox(9, title, hint, start, codeBox);
        box.getStyleClass().add("card-pad");
        return goldCard(box);
    }

    /** 外面套一层「金光流动」的卡片。 */
    private Node goldCard(Node content) {
        StackPane card = new StackPane();
        card.getStyleClass().addAll("card", "account-hero");
        card.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(content);

        Region shine = new Region();
        shine.getStyleClass().add("gold-shine");
        shine.setMouseTransparent(true);
        shine.setPrefWidth(150);
        shine.setMaxWidth(150);

        // 光带放在单独的图层里裁圆角：只裁光带，不裁卡片本身的阴影
        StackPane shineLayer = new StackPane(shine);
        shineLayer.setMouseTransparent(true);
        shineLayer.setPickOnBounds(false);
        StackPane.setAlignment(shine, Pos.CENTER_LEFT);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        clip.setArcWidth(52);
        clip.setArcHeight(52);
        shineLayer.setClip(clip);
        card.getChildren().add(shineLayer);

        DoubleProperty phase = new SimpleDoubleProperty(0);
        shine.translateXProperty().bind(
                phase.multiply(card.widthProperty().add(340)).subtract(200));
        Timeline loop = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(phase, 0, Interpolator.LINEAR)),
                new KeyFrame(Duration.seconds(2.6), new KeyValue(phase, 1, Interpolator.LINEAR)));
        loop.setCycleCount(Animation.INDEFINITE);
        loop.play();
        return card;
    }

    /* ============================ 正版前置校验（Mojang 规则） ============================ */

    /**
     * 离线 / 皮肤站登录的前置条件：必须先成功用正版（微软）账号登录过一次。
     * <p>Mojang 的 EULA 里，离线与第三方登录只给确实拥有游戏的玩家用；
     * 我们没法在这里验证购买凭证，所以要求「登录过正版」当门槛。</p>
     *
     * @return true = 可以继续登录；false = 已经弹出提示，调用方直接返回
     */
    private boolean requirePremium() {
        if (LauncherEngine.get().config().premiumVerified()) {
            return true;
        }
        showPremiumRequired();
        return false;
    }

    private void showPremiumRequired() {
        Label title = Ui.label("先用正版账号登录一次", "dialog-title", "danger-title");
        Label warn = Ui.label("按 Mojang 的规则，离线登录和皮肤站登录只提供给确实拥有《我的世界》的玩家。\n"
                + "请先在「正版登录」那张卡里用微软账号登录一次（免费，只是验证你买过游戏），"
                + "之后离线 / 皮肤站就能随便用。", "dialog-hint");
        warn.setWrapText(true);
        warn.setMaxWidth(360);

        Button cancel = Ui.ghostButton("知道了", null, host::hide);
        Button go = Ui.primaryButton("去正版登录", null, () -> {
            host.hide();
            if (scroller != null) {
                Motion.translate(scroller, 0, 0, 1, Motion.SOFT);
                scroller.setVvalue(0);
            }
            setStatus("在上面那张金色卡片里点「获取微软验证码」", false);
        });
        HBox buttonsRow = new HBox(8, Ui.spacer(), cancel, go);
        buttonsRow.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(12, title, warn, buttonsRow);
        card.getStyleClass().addAll("dialog-card", "danger-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        host.show(card);
    }

    /* ============================ 离线登录（补票提示） ============================ */

    private Node offlineCard() {
        Label title = Ui.label("离线登录", "account-card-title");
        Label hint = Ui.label("不联网、不需要密码，只在本机按名字算出 UUID"
                + "（需要先用正版账号登录过一次）。", "settings-hint");
        hint.setWrapText(true);

        Account selected = AccountStore.get().selected();
        String preset = selected != null && selected.kind == Account.Kind.OFFLINE ? selected.name : "Steve";
        TextField name = new TextField(preset);
        name.getStyleClass().add("dialog-field");
        name.setPromptText("游戏内名字，例如 Steve");
        name.setPrefHeight(34);
        name.setPrefWidth(260);

        Label warning = Ui.label("游玩开心了不要忘记补票哦", "repay-title");
        Button buy = Ui.ghostButton("是的，我想补票", null,
                () -> openUrl(BUY_URL));
        buy.getStyleClass().add("repay-buy");
        Button later = Ui.ghostButton("不，我想之后补票", null, null);
        HBox choices = new HBox(8, buy, later);
        choices.setAlignment(Pos.CENTER_LEFT);
        VBox repay = new VBox(7, warning, choices);
        repay.getStyleClass().add("repay-box");
        repay.setVisible(false);
        repay.setManaged(false);

        Button go = Ui.primaryButton("离线登录", null, null);
        go.setOnAction(e -> {
            // Mojang 规则：没正版登录过不许用离线
            if (!requirePremium()) {
                return;
            }
            String player = name.getText() == null ? "" : name.getText().trim();
            if (!player.matches("[A-Za-z0-9_]{3,16}")) {
                setStatus("名字要用 3-16 位字母、数字或下划线", true);
                return;
            }
            // 先问补票，再真正登录
            repay.setVisible(true);
            repay.setManaged(true);
            go.setDisable(true);
            later.setOnAction(done -> {
                repay.setVisible(false);
                repay.setManaged(false);
                go.setDisable(false);
                loginOffline(player);
            });
        });
        name.setOnAction(e -> go.fire());
        buttons.add(go);

        VBox box = new VBox(9, title, hint, name, repay, go);
        box.getStyleClass().addAll("card", "card-pad");
        return box;
    }

    private void loginOffline(String player) {
        setStatus("正在创建离线账户…", false);
        Account account = AuthClients.offline(player);
        finish(account);
    }

    /* ============================ 皮肤站登录 ============================ */

    private Node skinCard() {
        Label title = Ui.label("其他登录 · 皮肤站", "account-card-title");
        Label hint = Ui.label("用 littleskin.cn 的账号登录，会读这个角色的皮肤当头像"
                + "（同样需要先用正版账号登录过一次）。", "settings-hint");
        hint.setWrapText(true);

        TextField email = new TextField();
        email.getStyleClass().add("dialog-field");
        email.setPromptText("LittleSkin 邮箱或用户名");
        email.setPrefHeight(34);
        email.setPrefWidth(260);

        PasswordField password = new PasswordField();
        password.getStyleClass().add("dialog-field");
        password.setPromptText("密码");
        password.setPrefHeight(34);
        password.setPrefWidth(260);

        Button go = Ui.primaryButton("登录皮肤站", null, null);
        go.setOnAction(e -> {
            if (!requirePremium()) {
                return;
            }
            String user = email.getText() == null ? "" : email.getText().trim();
            String pass = password.getText() == null ? "" : password.getText();
            if (user.isEmpty() || pass.isEmpty()) {
                setStatus("邮箱和密码都要填", true);
                return;
            }
            go.setDisable(true);
            setStatus("正在登录皮肤站…", false);
            async(() -> finish(AuthClients.littleSkin(user, pass)));
        });
        password.setOnAction(e -> go.fire());
        buttons.add(go);

        HBox row = new HBox(6, Ui.label("没有账号？", "settings-hint"),
                Ui.ghostButton("去 littleskin.cn 注册", null,
                        () -> openUrl("https://littleskin.cn/auth/register")));
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(9, title, hint, email, password, row, go);
        box.getStyleClass().addAll("card", "card-pad");
        return box;
    }

    /* ============================ 我的账户 ============================ */

    private void refreshAccount() {
        accountBox.getChildren().clear();
        // 卡片样式在 build() 里已经加过，这里只管内容
        Account current = AccountStore.get().selected();
        accountBox.getChildren().add(Ui.label("我的账户", "account-card-title"));

        if (current == null) {
            accountBox.getChildren().add(Ui.label("还没有登录，随便挑一种方式在上面登录吧。", "settings-hint"));
            return;
        }

        HBox head = new HBox(10,
                SkinHeads.avatar(current.skinUrl, 36),
                new VBox(2,
                        Ui.label(current.name, "account-name"),
                        Ui.label(current.kindLabel(), "settings-hint")));
        head.setAlignment(Pos.CENTER_LEFT);
        accountBox.getChildren().add(head);

        List<Account> all = AccountStore.get().accounts();
        if (all.size() > 1) {
            VBox others = new VBox(6);
            for (Account account : all) {
                if (account.key().equals(current.key())) {
                    continue;
                }
                HBox item = new HBox(8, SkinHeads.avatar(account.skinUrl, 24),
                        Ui.label(account.name + " · " + account.kindLabel(), "settings-hint"));
                item.setAlignment(Pos.CENTER_LEFT);
                item.getStyleClass().add("account-switch-row");
                item.setOnMouseClicked(e -> {
                    AccountStore.get().select(account);
                    refreshAccount();
                    notifyChanged();
                });
                others.getChildren().add(item);
            }
            accountBox.getChildren().addAll(Ui.label("切换账户", "settings-hint"), others);
        }

        Button logout = Ui.ghostButton("退出登录", null, () -> {
            AccountStore.get().remove(current);
            refreshAccount();
            notifyChanged();
            setStatus("已退出登录", false);
        });
        accountBox.getChildren().add(logout);
    }

    /* ============================ 收尾 ============================ */

    private void finish(Account account) {
        AccountStore.get().put(account);
        Platform.runLater(() -> {
            for (Button button : buttons) {
                button.setDisable(false);
            }
            refreshAccount();
            notifyChanged();
            setStatus("已登录：" + account.name + " · " + account.kindLabel(), false);
        });
    }

    private void notifyChanged() {
        if (host != null) {
            host.refreshAccountCardNow();
        }
    }

    private void setStatus(String text, boolean error) {
        status.setText(text == null ? "" : text);
        status.getStyleClass().remove("error");
        if (error) {
            status.getStyleClass().add("error");
        }
    }

    private void async(Runnable task) {
        Thread worker = new Thread(() -> {
            try {
                task.run();
            } catch (Exception error) {
                Platform.runLater(() -> {
                    for (Button button : buttons) {
                        button.setDisable(false);
                    }
                    setStatus(String.valueOf(error.getMessage()), true);
                });
            }
        }, "yi-account");
        worker.setDaemon(true);
        worker.start();
    }

    /** 用系统默认浏览器打开；JNA 不可用时退化成 cmd start，再不行就把地址复制走。 */
    private static void openUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String target = url.trim();
        try {
            com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecute(null, "open", target, null, null, 1);
            return;
        } catch (Throwable ignored) {
            // 退到 cmd
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", target).start();
        } catch (Exception error) {
            copy(target);
        }
    }

    private static void copy(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
    }
}
