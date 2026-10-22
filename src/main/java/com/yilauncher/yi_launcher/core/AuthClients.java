package com.yilauncher.yi_launcher.core;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 三种登录：
 * <ul>
 *   <li>离线：本地按名字算 UUID，不发请求；</li>
 *   <li>LittleSkin：Yggdrasil 协议（邮箱 / 密码）；</li>
 *   <li>微软正版：设备代码流（device code）→ Xbox → XSTS → Minecraft 档案。</li>
 * </ul>
 * <p>所有方法都是阻塞的，调用方要放到后台线程里跑。</p>
 */
public final class AuthClients {

    /** 微软 OAuth 的客户端 ID：需要用你自己的 Azure 应用 ID（公共客户端）。 */
    private static final String DEFAULT_MS_CLIENT_ID = "45091b24-c2be-40eb-b6a6-6002e9b95526";

    private static final String LS_BASE = "https://littleskin.cn/api/yggdrasil";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AuthClients() {
    }

    /** 登录失败时带一句人话。 */
    public static final class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }

    /* ============================ 离线 ============================ */

    public static Account offline(String name) {
        Account account = new Account();
        account.kind = Account.Kind.OFFLINE;
        account.name = name;
        account.uuid = AccountStore.offlineUuid(name);
        return account;
    }

    /* ============================ LittleSkin ============================ */

    public static Account littleSkin(String email, String password) {
        String body = "{\"agent\":{\"name\":\"Minecraft\",\"version\":1},"
                + "\"username\":" + quote(email) + ","
                + "\"password\":" + quote(password) + ","
                + "\"clientToken\":" + quote(java.util.UUID.randomUUID().toString()) + ","
                + "\"requestUser\":false}";
        Map<String, Object> auth = postJson(LS_BASE + "/authserver/authenticate", body, null);
        if (auth == null) {
            throw new AuthException("登录失败：服务器没有返回数据");
        }
        if (auth.containsKey("error")) {
            throw new AuthException(littleSkinError(Json.string(auth, "errorMessage", "登录失败")));
        }
        Map<String, Object> profile = Json.map(auth.get("selectedProfile"));
        String id = Json.string(profile, "id", "");
        String name = Json.string(profile, "name", email);

        Account account = new Account();
        account.kind = Account.Kind.LITTLESKIN;
        account.name = name;
        account.uuid = id.replace("-", "");
        account.accessToken = Json.string(auth, "accessToken", "");
        account.skinUrl = littleSkinSkin(id);
        return account;
    }

    private static String littleSkinSkin(String uuid) {
        try {
            Map<String, Object> profile = getJson(
                    LS_BASE + "/sessionserver/session/minecraft/profile/" + uuid + "?unsigned=false", null);
            if (profile == null) {
                return "";
            }
            for (Object raw : Json.list(profile.get("properties"))) {
                Map<String, Object> property = Json.map(raw);
                String value = Json.string(property, "value", "");
                if (value.isBlank()) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                Map<String, Object> textures = Json.map(Json.map(Json.parse(decoded)).get("textures"));
                Map<String, Object> skin = Json.map(textures.get("SKIN"));
                String url = Json.string(skin, "url", "");
                if (!url.isBlank()) {
                    return url;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String littleSkinError(String message) {
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("invalid credentials") || lower.contains("incorrect")
                || lower.contains("无效") || lower.contains("密码")) {
            return "邮箱或密码不对";
        }
        if (lower.contains("token")) {
            return "验证码已过期，请重试";
        }
        return message == null || message.isBlank() ? "登录失败" : message;
    }

    /* ============================ 微软正版 ============================ */

    /** 设备代码流的第二次调用信息。 */
    public record MsDeviceCode(String deviceCode, String userCode, String verificationUri, int intervalSeconds) {
    }

    private static String clientId() {
        String override = System.getProperty("yi.msClientId");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String saved = LauncherConfig.load().ui().msClientId();
        return saved.isBlank() ? DEFAULT_MS_CLIENT_ID : saved;
    }

    public static MsDeviceCode microsoftStart() {
        String body = "client_id=" + encode(clientId())
                + "&scope=" + encode("XboxLive.signin offline_access");
        Map<String, Object> resp = postForm(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", body);
        if (resp == null || resp.containsKey("error")) {
            String detail = resp == null ? "没有响应" : Json.string(resp, "error_description", resp.toString());
            throw new AuthException("微软登录初始化失败：" + detail
                    + "（客户端 ID 无效或还没配置，去「设置 → 账户」填一个 Azure 公共客户端 ID）");
        }
        return new MsDeviceCode(
                Json.string(resp, "device_code", ""),
                Json.string(resp, "user_code", ""),
                Json.string(resp, "verification_uri", "https://microsoft.com/link"),
                Math.max(2, Json.integer(resp, "interval", 5)));
    }

    /**
     * 轮询微软令牌；用户还没在浏览器里完成时会一直等（最多约 5 分钟）。
     */
    public static Account microsoftWait(MsDeviceCode code) {
        String body = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code")
                + "&client_id=" + encode(clientId())
                + "&device_code=" + encode(code.deviceCode());
        Map<String, Object> token = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            Map<String, Object> resp = postForm(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token", body);
            if (resp == null) {
                sleep(code.intervalSeconds());
                continue;
            }
            String error = Json.string(resp, "error", "");
            if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                sleep(code.intervalSeconds());
                continue;
            }
            if (!error.isBlank()) {
                throw new AuthException("微软登录失败：" + Json.string(resp, "error_description", error));
            }
            token = resp;
            break;
        }
        if (token == null) {
            throw new AuthException("等待超时，请重新获取验证码");
        }
        String accessToken = Json.string(token, "access_token", "");

        String xblBody = "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                + "\"RpsTicket\":" + quote("d=" + accessToken) + "},"
                + "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}";
        Map<String, Object> xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody, null);
        String xblToken = Json.string(xbl, "Token", "");
        String uhs = Json.string(Json.map(Json.list(Json.map(xbl.get("DisplayClaims")).get("xui")).get(0)), "uhs", "");
        if (xblToken.isBlank() || uhs.isBlank()) {
            throw new AuthException("Xbox Live 验证失败");
        }

        String xstsBody = "{\"Properties\":{\"SandboxId\":\"RETAIL\","
                + "\"UserTokens\":[" + quote(xblToken) + "]},"
                + "\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
        Map<String, Object> xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody, null);
        if (xsts == null || xsts.containsKey("XErr")) {
            throw new AuthException("XSTS 验证失败：这个微软账号可能没有购买 Minecraft"
                    + (xsts == null ? "" : "（XErr=" + Json.string(xsts, "XErr", "?") + "）"));
        }
        String xstsToken = Json.string(xsts, "Token", "");
        String xstsUhs = Json.string(Json.map(Json.list(Json.map(xsts.get("DisplayClaims")).get("xui")).get(0)),
                "uhs", uhs);

        String mcBody = "{\"identityToken\":" + quote("XBL3.0 x=" + xstsUhs + ";" + xstsToken) + "}";
        Map<String, Object> mc = postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox", mcBody, null);
        String mcToken = Json.string(mc, "access_token", "");
        if (mcToken.isBlank()) {
            throw new AuthException("Minecraft 登录失败");
        }

        Map<String, Object> profile = getJson("https://api.minecraftservices.com/minecraft/profile", mcToken);
        if (profile == null) {
            throw new AuthException("拿不到 Minecraft 档案（这个账号可能没有购买）");
        }
        Account account = new Account();
        account.kind = Account.Kind.MICROSOFT;
        account.name = Json.string(profile, "name", "Player");
        account.uuid = Json.string(profile, "id", "").replace("-", "");
        account.accessToken = mcToken;
        account.skinUrl = skinFromProfile(profile);
        return account;
    }

    private static String skinFromProfile(Map<String, Object> profile) {
        for (Object raw : Json.list(profile.get("skins"))) {
            Map<String, Object> skin = Json.map(raw);
            String url = Json.string(skin, "url", "");
            if (!url.isBlank()) {
                return url;
            }
        }
        return "";
    }

    /* ============================ HTTP 小工具 ============================ */

    private static Map<String, Object> postJson(String url, String body, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return send(builder.build());
    }

    private static Map<String, Object> postForm(String url, String body) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private static Map<String, Object> getJson(String url, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return send(builder.build());
    }

    private static Map<String, Object> send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = response.body();
            if (text == null || text.isBlank()) {
                return null;
            }
            return Json.map(Json.parse(text));
        } catch (Exception error) {
            throw new AuthException("网络请求失败：" + error.getMessage());
        }
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(Math.max(1, seconds) * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
