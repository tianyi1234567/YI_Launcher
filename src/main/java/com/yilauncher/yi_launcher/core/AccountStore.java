/* ==========================================================================
 *  衣启动器 · core 层 ｜ AccountStore.java —— 账户仓库
 *  做什么：读写配置目录下的 accounts.json，维护「当前选中账户」，算离线 UUID。
 *  谁在用：AccountDialog（登录/切换/删除）、MainView（左下角账户卡）、GameLaunch（取令牌与 uuid）。
 *  入口：get()、accounts()、selectedOrGuest()、put()、select()、remove()、save()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账户列表：存在配置目录下的 accounts.json（跟 config.json 放一起）。
 * <p>只存必要字段，令牌明文存本地文件，跟其它启动器一致。</p>
 */
public final class AccountStore {

    private static AccountStore instance;

/* ============================ 单例与对外读写 ============================ */
    public static synchronized AccountStore get() {
        if (instance == null) {
            instance = new AccountStore();
            instance.load();
        }
        return instance;
    }

    private final Path file;
    private final List<Account> accounts = new ArrayList<>();
    private String selectedKey;

    private AccountStore() {
        Path config = LauncherConfig.defaultFile();
        Path dir = config.getParent() == null ? Path.of(".") : config.getParent();
        file = dir.resolve("accounts.json");
    }

    public Path file() {
        return file;
    }

    public synchronized List<Account> accounts() {
        return new ArrayList<>(accounts);
    }

    /** 当前使用的账户；一个都没有时返回 null。 */
    public synchronized Account selected() {
        for (Account account : accounts) {
            if (account.key().equals(selectedKey)) {
                return account;
            }
        }
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    /** 没有账户时用这个占位显示（不写进文件）。 */
    public Account selectedOrGuest() {
        Account account = selected();
        if (account != null) {
            return account;
        }
        Account guest = new Account();
        guest.kind = Account.Kind.OFFLINE;
        guest.name = "Steve";
        guest.uuid = offlineUuid("Steve");
        return guest;
    }

    /** 离线 UUID：和原版离线模式一致（OfflinePlayer:名字 的 MD5）。 */
    public static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    /** 登录成功后保存并切换。 */
    public synchronized Account put(Account account) {
        account.lastLogin = System.currentTimeMillis();
        accounts.removeIf(existing -> existing.key().equals(account.key()));
        accounts.add(0, account);
        selectedKey = account.key();
        save();
        return account;
    }

    public synchronized void select(Account account) {
        if (account == null) {
            return;
        }
        selectedKey = account.key();
        save();
    }

    public synchronized void remove(Account account) {
        if (account == null) {
            return;
        }
        accounts.removeIf(existing -> existing.key().equals(account.key()));
        if (account.key().equals(selectedKey)) {
            selectedKey = accounts.isEmpty() ? null : accounts.get(0).key();
        }
        save();
    }

/* ============================ 落盘：accounts.json ============================ */

    @SuppressWarnings("unchecked")
    private void load() {
        accounts.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Object> root = Json.map(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            selectedKey = Json.string(root, "selected");
            for (Object raw : Json.list(root.get("accounts"))) {
                Map<String, Object> map = Json.map(raw);
                Account account = new Account();
                String kind = Json.string(map, "kind");
                account.kind = "MICROSOFT".equals(kind) ? Account.Kind.MICROSOFT
                        : "LITTLESKIN".equals(kind) ? Account.Kind.LITTLESKIN : Account.Kind.OFFLINE;
                account.name = nullToEmpty(Json.string(map, "name"));
                account.uuid = nullToEmpty(Json.string(map, "uuid"));
                account.accessToken = nullToEmpty(Json.string(map, "accessToken"));
                account.skinUrl = nullToEmpty(Json.string(map, "skinUrl"));
                account.lastLogin = map.get("lastLogin") instanceof Number number ? number.longValue() : 0;
                if (!account.name.isEmpty()) {
                    accounts.add(account);
                }
            }
        } catch (Exception error) {
            System.err.println("[账户] 读取失败：" + error.getMessage());
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("selected", selectedKey);
            List<Object> list = new ArrayList<>();
            for (Account account : accounts) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", account.kind.name());
                map.put("name", account.name);
                map.put("uuid", account.uuid);
                map.put("accessToken", account.accessToken);
                map.put("skinUrl", account.skinUrl);
                map.put("lastLogin", account.lastLogin);
                list.add(map);
            }
            root.put("accounts", list);
            String json = toJson(root);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            System.err.println("[账户] 写入失败：" + error.getMessage());
        }
    }

/* ============================ JSON 小工具 ============================ */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 极简 JSON 写出：只处理 String / Number / Boolean / List / Map。 */
    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(first ? "" : ",").append(quote(String.valueOf(entry.getKey())))
                        .append(':').append(toJson(entry.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(toJson(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
