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

## 单文件版（推荐分发）

- `dist\YI_Launcher.exe`：一个文件就能发，JavaFX 21 + JNA 都打包在里面（约 10 MB），**不含 Java**。
- 双击后先找本机的 Java 21+：JAVA_HOME、注册表、PATH、Program Files 常见 JDK 目录、
  以及 Minecraft 自带的运行时都会查一遍。找到就直接拉起界面；
  找不到（或只装了 Java 8/17）会弹窗提示安装 Java 21，并可一键打开 Temurin / Oracle 官方下载页。
- 首次启动把内置运行库解到 `%LOCALAPPDATA%\YI_Launcher\runtime\<指纹>`，之后再启动是秒开；
  换了新版本会自动清掉旧的解压目录。
- 打包命令：`powershell -ExecutionPolicy Bypass -File .\build-exe.ps1`
  （内部先跑 `package-release.ps1`，再把 `dist\` 的内容打成单文件 exe）。

## CurseForge Key（源码内置）

- 官方 API Key 直接写在 `core/CurseForge.java` 的 `BUILTIN_KEY` 常量里，开箱即用，设置页不提供修改入口。
- 只有开发者临时调试才用 `-Dyi.cfKey=...` 覆盖（不落盘、不影响发布包）。
- 有了它：模组识别走官方指纹接口，图标用官方 256px PNG 缩略图，整合包模组优先官方源下载。

> 注意：Key 在源码里 = 发布即公开。被别人拿去刷接口会被限流（429），
> 所以对外发版前最好换成自己的 Key，或者只发编译后的包、不公开源码。
## 账户规则（Mojang 要求）

- **离线登录 / 皮肤站登录**必须先成功用**正版（微软）账号登录过一次**。
- 第一次点离线或皮肤站登录时，会弹出「先用正版账号登录一次」的提示；
  完成一次微软登录后本机会记住（配置项 `premiumVerified=true`），之后不再拦。
- 微软登录走 **授权码流 + PKCE（S256）**：点「用浏览器登录微软账号」→ 系统浏览器打开微软登录页 →
  授权码回到本机 `http://localhost:<随机端口>/callback`，启动器用只存在内存里的 `code_verifier` 换令牌。
  界面上不会再出现可以被人抄走的「验证码」；回调还会校验 `state`，伪造回调直接被丢弃。
- 客户端 ID 是内置的 Azure 公共客户端（不持有 client secret，设置页不提供修改入口）。
  **Azure 应用侧必须配置**：应用注册 → 身份验证 → 平台选「移动和桌面应用」，
  重定向 URI 填 `http://localhost`（端口不用写），并打开「允许公共客户端流」。
  没配好时登录会直接提示 AADSTS50011 / AADSTS7000218 该去哪里改。

## 发布前检查清单

- [ ] `package-release.ps1` 能跑完（内部是 `mvn -o compile` + JDK `jar` 打包）
- [ ] `dist\run.cmd` 能正常启动，主界面无异常
- [ ] `dist\YI_Launcher.exe` 双击能直接打开（本机装 Java 21 的前提下）
- [ ] 把 Java 藏起来时 exe 会弹「请安装 Java 21」并能跳到下载页
- [ ] 版本管理：选中反馈、删除版本二次确认
- [ ] 模组管理：真实整合包目录能出图标（走 CurseForge 官方缩略图）
- [ ] 模组资源：搜索 / 下载确认 / 小球飞向下载中心 / 下载中心任务完成后自动消失
- [ ] 账户：未验证正版时离线与皮肤站被拦；验证后放行
- [ ] 配置目录里没有任何明文 Key（`config.json` 不含 `curseForgeKey` / `microsoftClientId`）

## 已知限制

- Modrinth 上只有 WebP 图标、且 CurseForge 也找不到的项目，仍显示首字母方块（JavaFX 不支持 WebP）。
- OptiFine 没有公开下载接口，安装器勾选后只会打开官网让用户手动下载。
- bmclapi 缺部分 Forge 构件，镜像安装器失败时会自动快速回退官方源。
