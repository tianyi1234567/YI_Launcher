# 衣启动器 · 发布说明

## 运行

- 需要 **Java 21 或更高版本**（自带 JavaFX 21 的发行版最省事，如 Zulu 21+ FX 版）。
- 双击 `run.cmd` 即可。
- 目录结构：
  ```
  app.jar      启动器本体
  lib\         JavaFX 21 模块（module path）
  ext\         JNA 5.14（classpath，用于 Windows 原生效果与打开浏览器）
  run.cmd      启动脚本
  ```

## CurseForge Key（源码内置）

- 官方 API Key 直接写在 `core/CurseForge.java` 的 `BUILTIN_KEY` 常量里，开箱即用，设置页不提供修改入口。
- 只有开发者临时调试才用 `-Dyi.cfKey=...` 覆盖（不落盘、不影响发布包）。
- 有了它：模组识别走官方指纹接口，图标用官方 256px PNG 缩略图，整合包模组优先官方源下载。

> 注意：Key 在源码里 = 发布即公开。被别人拿去刷接口会被限流（429），
> 所以对外发版前最好换成自己的 Key，或者只发编译后的包、不公开源码。
## 账户规则（Mojang 要求）

- **离线登录 / 皮肤站登录**必须先成功用**正版（微软）账号登录过一次**。
- 第一次点离线或皮肤站登录时，会弹出「先用正版账号登录一次」的提示；
  完成一次微软设备码登录后本机会记住（配置项 `premiumVerified=true`），之后不再拦。
- 微软登录用的是内置的 Azure 公共客户端 ID（设备码流程），设置页不提供修改入口。

## 发布前检查清单

- [ ] `package-release.ps1` 能跑完（内部是 `mvn -o compile` + JDK `jar` 打包）
- [ ] `dist\run.cmd` 能正常启动，主界面无异常
- [ ] 版本管理：选中反馈、删除版本二次确认
- [ ] 模组管理：真实整合包目录能出图标（走 CurseForge 官方缩略图）
- [ ] 模组资源：搜索 / 下载确认 / 小球飞向下载中心 / 下载中心任务完成后自动消失
- [ ] 账户：未验证正版时离线与皮肤站被拦；验证后放行
- [ ] 配置目录里没有任何明文 Key（`config.json` 不含 `curseForgeKey` / `microsoftClientId`）

## 已知限制

- Modrinth 上只有 WebP 图标、且 CurseForge 也找不到的项目，仍显示首字母方块（JavaFX 不支持 WebP）。
- OptiFine 没有公开下载接口，安装器勾选后只会打开官网让用户手动下载。
- bmclapi 缺部分 Forge 构件，镜像安装器失败时会自动快速回退官方源。
