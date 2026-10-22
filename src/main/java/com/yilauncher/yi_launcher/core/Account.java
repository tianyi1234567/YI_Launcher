/* ==========================================================================
 *  衣启动器 · core 层 ｜ Account.java —— 账户数据模型
 *  做什么：一条账户记录（类型 / 名字 / uuid / 令牌 / 皮肤地址）+ Kind 枚举。
 *  谁在用：AccountStore（存取）、AuthClients（登录结果）、AccountDialog、MainView、GameLaunch。
 *  入口：Kind、key()（同账号重复登录的覆盖依据）、kindLabel()、shortUuid()。
 * ========================================================================== */
package com.yilauncher.yi_launcher.core;

/** 一个登录过的账户：正版（微软）/ 离线 / LittleSkin 皮肤站。 */
public final class Account {

    public enum Kind {
        MICROSOFT, OFFLINE, LITTLESKIN
    }

    public Kind kind = Kind.OFFLINE;
    public String name = "";
    /** 不带横线的 uuid。 */
    public String uuid = "";
    /** 正版 / LS 站的访问令牌，离线为空。 */
    public String accessToken = "";
    /** 皮肤 PNG 地址（可空，空就用史蒂夫）。 */
    public String skinUrl = "";
    public long lastLogin;

    public String kindLabel() {
        return switch (kind) {
            case MICROSOFT -> "正版";
            case LITTLESKIN -> "LS站";
            case OFFLINE -> "离线";
        };
    }

    /** 唯一标识：同一账号重复登录会覆盖。 */
    public String key() {
        return kind + ":" + uuid;
    }

    public String shortUuid() {
        return uuid == null ? "" : uuid.replace("-", "");
    }
}
