package com.yilauncher.yi_launcher.ui;

import com.yilauncher.yi_launcher.core.Account;
import com.yilauncher.yi_launcher.core.AccountStore;
import com.yilauncher.yi_launcher.core.AuthClients;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 账户面板：微软正版 / 离线 / LittleSkin 三种登录。
 * <p>所有网络请求都在后台线程跑，结果回到 FX 线程再改界面；
 * 登录成功写进 {@link AccountStore} 并回调宿主刷新左下角卡片。</p>
 */
public final class AccountDialog {

    /** 一次登录流程（比如微软的设备码轮询）；切换模式会让旧的流程作废。 */
    private interface AuthTask {
        void run() throws Exception;
    }

    private final Ui.OverlayHost host;
    private final Runnable onChanged;

    private final List<Label> modeChips = new ArrayList<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private final VBox form = new VBox(9);
    private final VBox current = new VBox(8);
    private final Label status = Ui.label("", "account-status");

    /** 模式一换就 +1，旧线程回来时先比对，避免把过期的验证码/错误贴到新表单上。 */
    private int flowToken;

    private AccountDialog(Ui.OverlayHost host, Runnable onChanged) {
        this.host = host;
        this.onChanged = onChanged;
    }

    public static Node create(Ui.OverlayHost host, Runnable onChanged) {
        return new AccountDialog(host, onChanged).build();
    }

    /* ============================ 骨架 ============================ */

    private Node build() {
        VBox card = new VBox(11);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(17, 19, 15, 19));
        card.setMinWidth(402);
        card.setPrefWidth(402);
        card.setMaxWidth(402);

        VBox titles = new VBox(2,
                Ui.label("账户", "dialog-title"),
                Ui.label("登录后启动游戏会用这个身份进服", "dialog-hint"));
        HBox head = new HBox(10, titles, Ui.spacer(), Ui.ghostButton("关闭", null, host::hide));
        head.setAlignment(Pos.CENTER_LEFT);

        current.getStyleClass().add("account-current");

        HBox chips = new HBox(6,
                chip("微软正版", Account.Kind.MICROSOFT),
                chip("离线", Account.Kind.OFFLINE),
                chip("LittleSkin", Account.Kind.LITTLESKIN));
        chips.setAlignment(Pos.CENTER_LEFT);

        status.setWrapText(true);

        card.getChildren().addAll(head, current, chips, form, status);

        refreshCurrent();
        showMode(initialMode());
        return card;
    }

    /** 默认打开哪种模式：已经在用的登录方式优先。 */
    private Account.Kind initialMode() {
        Account account = AccountStore.get().selected();
        return account == null ? Account.Kind.OFFLINE : account.kind;
    }

    private Label chip(String text, Account.Kind kind) {
        Label chip = Ui.label(text, "filter-chip");
        chip.setUserData(kind);
        chip.setOnMouseClicked(e -> showMode(kind));
        Motion.pressFade(chip);
        modeChips.add(chip);
        return chip;
    }

    private void showMode(Account.Kind kind) {
        flowToken++;
        actionButtons.clear();
        for (Label chip : modeChips) {
            chip.getStyleClass().remove("active");
            if (chip.getUserData() == kind) {
                chip.getStyleClass().add("active");
            }
        }
        form.getChildren().setAll(switch (kind) {
            case MICROSOFT -> microsoftForm();
            case LITTLESKIN -> littleSkinForm();
            case OFFLINE -> offlineForm();
        });
        setStatus("", false);
    }

    /* ============================ 当前账户 ============================ */

    private void refreshCurrent() {
        current.getChildren().clear();
        Account account = AccountStore.get().selected();
        if (account == null) {
            current.getChildren().add(Ui.label("还没登录 · 当前以游客 Steve 启动", "account-current-name"));
        } else {
            String uuid = account.uuid == null ? "" : account.uuid.replace("-", "");
            VBox text = new VBox(3,
                    new HBox(6, Ui.label(account.name, "account-current-name"),
                            Ui.label(account.kindLabel(), kindStyle(account.kind))),
                    Ui.label(uuid.isBlank() ? "本机离线档案" : "UUID " + uuid.substring(0, Math.min(8, uuid.length())),
                            "account-current-sub"));
            ((HBox) text.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, SkinHeads.avatar(account.skinUrl, 40), text, spacer,
                    Ui.ghostButton("退出登录", null, this::logout));
            row.setAlignment(Pos.CENTER_LEFT);
            current.getChildren().add(row);
        }

        // 存了多个账户时列出来，点一下切换
        List<Account> all = AccountStore.get().accounts();
        if (all.size() > 1) {
            for (Account other : all) {
                if (account != null && other.key().equals(account.key())) {
                    continue;
                }
                HBox row = new HBox(8, SkinHeads.avatar(other.skinUrl, 24),
                        Ui.label(other.name, "account-switch-name"),
                        Ui.label(other.kindLabel(), kindStyle(other.kind)));
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("account-switch");
                row.setCursor(javafx.scene.Cursor.HAND);
                row.setOnMouseClicked(e -> {
                    AccountStore.get().select(other);
                    refreshCurrent();
                    notifyChanged();
                });
                Motion.pressFade(row);
                current.getChildren().add(row);
            }
        }
    }

    private void logout() {
        AccountStore.get().remove(AccountStore.get().selected());
        refreshCurrent();
        notifyChanged();
        setStatus("已退出，回到游客 Steve", false);
    }

    private static String kindStyle(Account.Kind kind) {
        return switch (kind) {
            case MICROSOFT -> "kind-chip kind-ms";
            case LITTLESKIN -> "kind-chip kind-ls";
            case OFFLINE -> "kind-chip kind-offline";
        };
    }

    /* ============================ 三种表单 ============================ */

    private Node offlineForm() {
        Account selected = AccountStore.get().selected();
        String preset = selected != null && selected.kind == Account.Kind.OFFLINE ? selected.name : "Steve";
        TextField name = field("游戏内名字，例如 Steve", preset);
        Button go = action("离线登录", () -> {
            String player = name.getText() == null ? "" : name.getText().trim();
            if (!player.matches("[A-Za-z0-9_]{3,16}")) {
                setStatus("名字要用 3-16 位字母、数字或下划线", true);
                return;
            }
            finish(AuthClients.offline(player), "");
        });
        name.setOnAction(e -> go.fire());

        Label hint = Ui.label("离线登录不联网、不需要密码，只在本机按名字算出 UUID。", "dialog-hint");
        hint.setWrapText(true);
        return new VBox(9, hint, name, go);
    }

    private Node littleSkinForm() {
        TextField email = field("LittleSkin 邮箱或用户名", "");
        PasswordField password = new PasswordField();
        password.getStyleClass().add("dialog-field");
        password.setPromptText("密码");
        password.setPrefHeight(34);

        Button go = action("登录 LittleSkin", null);
        go.setOnAction(e -> {
            String user = email.getText() == null ? "" : email.getText().trim();
            String pass = password.getText() == null ? "" : password.getText();
            if (user.isEmpty() || pass.isEmpty()) {
                setStatus("邮箱和密码都要填", true);
                return;
            }
            go.setDisable(true);
            setStatus("正在登录 LittleSkin…", false);
            async(() -> finish(AuthClients.littleSkin(user, pass), ""));
        });
        password.setOnAction(e -> go.fire());
        email.setOnAction(e -> password.requestFocus());

        Button register = Ui.ghostButton("去 littleskin.cn 注册", null,
                () -> openUrl("https://littleskin.cn/auth/register"));
        HBox row = new HBox(6, Ui.label("没有账号？", "dialog-hint"), register);
        row.setAlignment(Pos.CENTER_LEFT);

        Label hint = Ui.label("登录后会读皮肤站里这个角色的皮肤当头像。", "dialog-hint");
        hint.setWrapText(true);
        return new VBox(9, email, password, hint, row, go);
    }

    private Node microsoftForm() {
        Label hint = Ui.label("点下面的按钮拿一个设备验证码，在浏览器里输入即可——启动器会自己等到授权完成。",
                "dialog-hint");
        hint.setWrapText(true);

        Label code = Ui.label("", "ms-code");
        Label link = Ui.label("", "dialog-hint");
        link.setWrapText(true);

        VBox codeBox = new VBox(7, code, link,
                new HBox(6,
                        Ui.ghostButton("打开浏览器", null, () -> openUrl(link.getText())),
                        Ui.ghostButton("复制代码", null, () -> copy(code.getText()))));
        codeBox.getStyleClass().add("ms-code-box");
        codeBox.setVisible(false);
        codeBox.setManaged(false);

        Button start = action("获取微软验证码", null);
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
                    setStatus("在浏览器里输入 " + device.userCode() + " 完成授权，这里会自动等到登录成功…", false);
                });
                openUrl(device.verificationUri());
                Account account = AuthClients.microsoftWait(device);
                if (token == flowToken) {
                    finish(account, "");
                }
            });
        });

        Label tip = Ui.label("内置的公共客户端 ID 已经配好，正版登录可以直接用；想换成自己的 Azure 应用，去「设置 → 账户」覆盖。",
                "account-tip");
        tip.setWrapText(true);
        return new VBox(9, hint, start, codeBox, tip);
    }

    /* ============================ 完成 / 线程 ============================ */

    private void finish(Account account, String extra) {
        AccountStore.get().put(account);
        Platform.runLater(() -> {
            refreshCurrent();
            for (Button button : actionButtons) {
                button.setDisable(false);
            }
            setStatus("已登录：" + account.name + " · " + account.kindLabel() + (extra == null ? "" : extra), false);
            notifyChanged();
            // 停一下让用户看到结果，再自动收起面板
            PauseTransition wait = new PauseTransition(Duration.millis(700));
            wait.setOnFinished(done -> host.hide());
            wait.play();
        });
    }

    private void async(AuthTask task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                String message = error.getMessage();
                if (message == null || message.isBlank()) {
                    message = "操作失败：" + error.getClass().getSimpleName();
                }
                String text = message;
                Platform.runLater(() -> {
                    setStatus(text, true);
                    for (Button button : actionButtons) {
                        button.setDisable(false);
                    }
                });
            }
        }, "yi-auth");
        thread.setDaemon(true);
        thread.start();
    }
    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /* ============================ 小工具 ============================ */

    private Button action(String text, Runnable run) {
        Button button = Ui.primaryButton(text, null, null);
        button.getStyleClass().add("dialog-confirm");
        button.setMinHeight(36);
        button.setPrefHeight(36);
        button.setMaxWidth(Double.MAX_VALUE);
        if (run != null) {
            button.setOnAction(e -> run.run());
        }
        actionButtons.add(button);
        return button;
    }

    private TextField field(String prompt, String text) {
        TextField field = new TextField(text == null ? "" : text);
        field.setPromptText(prompt);
        field.getStyleClass().add("dialog-field");
        field.setPrefHeight(34);
        return field;
    }

    private void setStatus(String text, boolean error) {
        boolean empty = text == null || text.isBlank();
        status.setText(empty ? "" : text);
        status.getStyleClass().remove("error");
        if (error) {
            status.getStyleClass().add("error");
        }
        status.setVisible(!empty);
        status.setManaged(!empty);
    }

    /** 用系统默认浏览器打开；JNA 不可用时退化成复制到剪贴板。 */
    private static void openUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecute(null, "open", url, null, null, 1);
        } catch (Throwable error) {
            copy(url);
        }
    }

    private static void copy(String text) {
        String value = text == null ? "" : text;
        if (!Platform.isFxApplicationThread()) {
            // 微软流程里 openUrl 是在后台线程调的，剪贴板必须回 FX 线程
            Platform.runLater(() -> copy(value));
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
