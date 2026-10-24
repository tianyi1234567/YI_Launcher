package com.yilauncher.yi_launcher.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 三种登录：
 * <ul>
 *   <li>离线：本地按名字算 UUID，不发请求；</li>
 *   <li>LittleSkin：Yggdrasil 协议（邮箱 / 密码）；</li>
 *   <li>微软正版：授权码 + PKCE（本机回环回调）→ Xbox → XSTS → Minecraft 档案。</li>
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

    /* ============================ 微软正版（授权码流 + PKCE） ============================ */

    private static final String MS_AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String MS_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    /** 请求的权限：XboxLive 登录 + 刷新令牌。 */
    private static final String MS_SCOPE = "XboxLive.signin offline_access";
    /** 等用户在浏览器里点完的上限（分钟）。 */
    private static final long LOGIN_TIMEOUT_MINUTES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 微软正版登录用的客户端 ID（Azure 公共客户端，授权码 + PKCE 流）。
     * <p>已经内置并写死，设置页不再暴露；只有开发者临时调试才用 {@code -Dyi.msClientId=} 覆盖。</p>
     */
    private static String clientId() {
        String override = System.getProperty("yi.msClientId");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return DEFAULT_MS_CLIENT_ID;
    }

    /** 把微软那串英文错误翻成一句「照着点就能修」的中文。 */
    private static String friendlyMicrosoftError(String raw) {
        String text = raw == null ? "" : raw;
        if (text.contains("AADSTS50011") || text.contains("redirect_uri")) {
            return "微软登录失败：Azure 应用里没登记这个回调地址。去 Azure 门户 → 应用注册 → 身份验证 → "
                    + "添加平台「移动和桌面应用」，重定向 URI 填 http://localhost（端口不用写）";
        }
        if (text.contains("AADSTS7000218") || text.contains("client_assertion")) {
            return "微软登录失败：Azure 应用没允许公共客户端流。去 Azure 门户 → 应用注册 → 身份验证 → "
                    + "高级设置 → 允许公共客户端流 = 是";
        }
        if (text.contains("invalid_grant")) {
            return "微软登录失败：授权码已经过期或被用过了，请重新点「用浏览器登录微软账号」";
        }
        return "微软登录失败：" + text;
    }

    /**
     * 一次授权码登录会话。
     * <p>为什么不再用设备码：设备码要用户把一串 code 手敲进网页，旁人截屏 / 肩膀后面看一眼，
     * 就能在有效期内抢先用掉。授权码流把这层风险去掉：</p>
     * <ul>
     *   <li>授权码只回到本机 {@code 127.0.0.1} 的随机端口，而且必须带上只有本进程知道的
     *       {@code code_verifier} 才能换令牌（S256 PKCE），截到授权码也换不出令牌；</li>
     *   <li>回调要校验 {@code state}，别人把授权码硬塞给启动器会被丢掉；</li>
     *   <li>公共客户端不持有 client secret，令牌只留在内存里，换完就丢。</li>
     * </ul>
     */
    public static final class MsAuthSession implements AutoCloseable {

        private final String authUrl;
        private final String codeVerifier;
        private final String redirectUri;
        private final HttpServer server;
        private final CompletableFuture<String> authorizationCode;

        private MsAuthSession(String authUrl, String codeVerifier, String redirectUri,
                              HttpServer server, CompletableFuture<String> authorizationCode) {
            this.authUrl = authUrl;
            this.codeVerifier = codeVerifier;
            this.redirectUri = redirectUri;
            this.server = server;
            this.authorizationCode = authorizationCode;
        }

        /** 用系统浏览器打开这个地址开始登录。 */
        public String authUrl() {
            return authUrl;
        }

        /**
         * 阻塞：等浏览器把授权码送回来 → 带 code_verifier 换令牌 → Xbox → XSTS → Minecraft 档案。
         */
        public Account awaitAccount() {
            String code;
            try {
                code = authorizationCode.get(LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException timeout) {
                throw new AuthException("等了 5 分钟还没在浏览器里登录，请重新点「用浏览器登录」");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AuthException("登录已取消");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof AuthException auth) {
                    throw auth;
                }
                throw new AuthException("微软登录失败：" + (cause == null ? "未知错误" : cause.getMessage()));
            }
            String body = "client_id=" + encode(clientId())
                    + "&scope=" + encode(MS_SCOPE)
                    + "&code=" + encode(code)
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&grant_type=authorization_code"
                    + "&code_verifier=" + encode(codeVerifier);
            Map<String, Object> token = postForm(MS_TOKEN, body);
            if (token == null) {
                throw new AuthException("微软登录失败：令牌接口没有响应");
            }
            String error = Json.string(token, "error", "");
            if (!error.isBlank()) {
                throw new AuthException(friendlyMicrosoftError(Json.string(token, "error_description", error)));
            }
            String accessToken = Json.string(token, "access_token", "");
            if (accessToken.isBlank()) {
                throw new AuthException("微软登录失败：没有拿到访问令牌");
            }
            return loginWithXbox(accessToken);
        }

        @Override
        public void close() {
            try {
                server.stop(0);
            } catch (Exception ignored) {
                // 已经停了就无所谓
            }
        }
    }

    /**
     * 开始一次微软登录：绑一个本机随机端口的回调服务器，生成 PKCE 与 state。
     * <p>拿到 {@link MsAuthSession#authUrl()} 后用浏览器打开，再在后台线程里
     * {@link MsAuthSession#awaitAccount()} 等结果。</p>
     */
    public static MsAuthSession microsoftBegin() {
        String verifier = randomUrlSafe(48);
        String state = randomUrlSafe(24);
        String challenge = pkceChallenge(verifier);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception error) {
            throw new AuthException("微软登录失败：本机回调端口起不来（" + error.getMessage() + "）");
        }
        // 与 Azure 里登记的 http://localhost 完全一致：只带端口、不带路径（微软会自动忽略端口）
        String redirectUri = "http://localhost:" + server.getAddress().getPort();

        CompletableFuture<String> code = new CompletableFuture<>();
        // 根路径和 /callback 都接：万一 Azure 那边登记的是 http://localhost/callback 也能用
        server.createContext("/", exchange -> handleCallback(exchange, state, code));
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "yi-ms-callback");
            thread.setDaemon(true);
            return thread;
        }));

        String authUrl = MS_AUTHORIZE
                + "?client_id=" + encode(clientId())
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_mode=query"
                + "&scope=" + encode(MS_SCOPE)
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256"
                + "&prompt=select_account";

        MsAuthSession session = new MsAuthSession(authUrl, verifier, redirectUri, server, code);
        server.start();
        return session;
    }

    /** 浏览器回调：校验 state，把授权码交给等待方；页面上显示结果，让用户知道可以关掉。 */
    private static void handleCallback(HttpExchange exchange, String expectedState,
                                       CompletableFuture<String> sink) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String state = query.getOrDefault("state", "");
        String code = query.getOrDefault("code", "");
        String error = query.getOrDefault("error", "");
        String description = query.getOrDefault("error_description", "");

        // /favicon.ico 之类的无参请求：只回个说明，别把正在等的登录打断
        if (query.isEmpty()) {
            respond(exchange, 404, "这里是衣启动器的登录回调",
                    "没有带参数。回到启动器重新点「用浏览器登录微软账号」吧。");
            return;
        }

        if (!expectedState.equals(state)) {
            sink.completeExceptionally(new AuthException("微软登录失败：state 对不上，回调可能被伪造"));
            respond(exchange, 400, "回调被拒绝",
                    "state 对不上：这次授权不是本启动器发起的，已经丢弃。请回到启动器重新登录。");
            return;
        }
        if (!code.isBlank()) {
            sink.complete(code);
            respond(exchange, 200, "登录成功", "已经拿到微软授权，可以关掉这个页面回到衣启动器了。");
            return;
        }
        String reason = description.isBlank() ? (error.isBlank() ? "没有收到授权码。" : error) : description;
        sink.completeExceptionally(new AuthException("微软登录失败：" + reason));
        respond(exchange, 400, "登录没有完成", reason);
    }

    /** 回给浏览器的一张极简结果页。 */
    private static void respond(HttpExchange exchange, int status, String title, String message) {
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>" + escapeHtml(title) + "</title>"
                + "<style>body{margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center;"
                + "background:#f4f6fb;color:#25304a;font-family:'Microsoft YaHei',system-ui,sans-serif}"
                + ".card{background:#fff;border-radius:18px;padding:28px 34px;max-width:420px;text-align:center;"
                + "box-shadow:0 12px 40px rgba(40,60,120,.12)}h1{font-size:19px;margin:0 0 10px}"
                + "p{margin:0;font-size:14px;line-height:1.6;color:#5a6784}</style></head><body>"
                + "<div class=\"card\"><h1>" + escapeHtml(title) + "</h1>"
                + "<p>" + escapeHtml(message) + "</p></div></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (Exception ignored) {
            // 浏览器提前断开就算了
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int cut = pair.indexOf('=');
            String key = cut < 0 ? pair : pair.substring(0, cut);
            String value = cut < 0 ? "" : pair.substring(cut + 1);
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** PKCE：code_challenge = BASE64URL(SHA256(code_verifier))，不带 padding。 */
    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new AuthException("微软登录失败：算不出 PKCE 校验串");
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 微软 access token → Xbox Live → XSTS → Minecraft 档案（三种登录共用的最后一段）。 */
    private static Account loginWithXbox(String accessToken) {
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

}
