# MCphone 附属 App 开发提示词：浏览器（WebDisplays 版）

> 使用方法：把本文件全文作为任务提示词发给 AI 会话（或当作开发需求书）。
> 开发完成后，本文件的"验收清单 + 测试协议"就是交付标准。

---

## 一、项目背景（先读）

- 目标平台：**GTNH 2.9.0-beta3**（MC 1.7.10 + Forge 1614 + lwjgl3ify，运行时 Java 17–26）。
- 手机本体 mod：**MCphone**，工程在 `E:\zcode\mcphone-gtnh`（RetroFuturaGradle 构建链，
  Java 21+ 语法经 Jabel 编译为 Java 8 字节码，Gradle 9.3.1，代理 127.0.0.1:7897 已写在
  gradle.properties，手动注册的 JDK 在 `E:\zcode\jdks\`）。
- 附属接入口（SPI）：`src/main/java/com/november/mcphone/api/IPhoneApp.java` +
  `api/PhoneApi.java`，说明文档 `docs/addon-api.md`。App 分两类：
  - **页面型**：`createPage(PhoneUi)` 返回 Qz-UILib 场景树根节点；
  - **直达型**（`isDirectAction()=true`）：点击图标立即执行 `onActivate(ui, shift)`。
- 实例（测试用）：`<Prism 实例目录>/GT_New_Horizons_2.9.0-beta-3_Java_17-26`，
  通过 PrismLauncher 启动，mods 在 `.minecraft\mods\`。
- UI 框架：Qz-UILib（modId `qz_uilib`，包 `club.heiqi.uilib`），声明式场景树 +
  Signal 响应式；控件 `ui.scene.control.*`（SceneButton/SceneLabel/SceneTextInput/
  SceneScrollContainer/SceneToast 等）。规则：树只建一次，动态外观用
  `runtime().bind(signal, ...)` 派生；不要在渲染线程做磁盘/网络 IO。

## 二、前置 mod（必须写进附属 mod 的介绍里）

本附属依赖以下前置 mod，**必须**在附属的 mcmod.info 描述、README、以及游戏内
提示里明确声明；附属启动时应检测前置是否存在：

| 前置 | 说明 |
|------|------|
| **WebDisplays 1.7.10**（montoyo/MobiCyp） | 提供游戏内网页大屏（屏幕方块 + 浏览器逻辑） |
| **MCEF**（WebDisplays 同作者的配套库，内嵌 JCEF Chromium） | WD 的渲染后端，按 WD 对应版本下载 |

- 下载渠道：CurseForge / montoyo.net（网络走代理 `127.0.0.1:7897`）。
  先搜 WebDisplays 的 1.7.10 最新版，再按其"依赖"页找配套 MCEF 版本，不要凭记忆猜版本号。
- **第一步就是实测前置可用性**：把 WD + MCEF 放进实例 mods，启动游戏、进存档、
  放一块 WD 屏幕方块加载任意网页。若 MCEF 原生库在 Java 17+/lwjgl3ify 下崩溃
  （JNI 加载失败、启动闪退、屏幕花屏且无解），**立即停止并报告**，不要继续写代码——
  此时按文末"兜底方案"切换方向。
- 附属运行时检测：`Class.forName("montoyo.wd....")`（以实际 jar 包名为准）失败时，
  浏览器 App 显示"需要安装 WebDisplays 前置"提示页，绝不崩溃。

## 三、要做什么

开发独立附属 mod：**mcphone-addon-browser**（modId `mcphone_browser`），
为 MCphone 提供「浏览器」App（id=`browser`）：

1. **点击手机上的浏览器图标**（直达型）：
   - 手机 NBT 里存有上次 URL → 在玩家面前 3~5 格生成（或恢复）一块 WD 大屏
     （建议 2x3 或 3x3 方块墙，按玩家水平朝向摆放），并加载该 URL；
   - 没有记录 → 打开 URL 输入页（页面型管理页）。
2. **Shift + 点击图标**：打开管理页（页面型），包含：
   - URL 输入框 + "打开大屏"按钮；
   - 书签列表（可添加/删除/点击直达）；
   - 历史记录（最近 20 条）；
   - "隐藏屏幕"按钮：移除当前大屏方块，URL 仍存手机 NBT；
     再次点击浏览器图标即恢复（WD 机制会重新加载页面，属预期行为，向用户说明）。
3. **游戏内提示**：放置成功/失败（无空位/未绑定）、前置缺失，都用聊天栏或
   SceneToast 反馈，中文文案（en_US.lang + zh_CN.lang 双语）。

## 四、技术红线（逐条遵守）

- **不改 WD、不改 MCphone 源码**。对 WD 的全部调用走反射/javap 探查：
  先 `unzip -l` + `javap -p` 研究安装 jar（重点：屏幕方块、TileEntityScreen 或等价
  方块实体的 URL 设置方法、多方块拼接/朝向逻辑、是否有公开 API 类），再写调用。
- NBT 只写自己的键，统一前缀 `mcphone:browser:`（上次 URL、书签、历史都存
  手机 ItemStack NBT 或客户端配置，不要写进 WD 的方块 NBT）。
- 世界操作（放/删屏幕方块）必须在**服务端主线程**执行（参考 mcphone 的
  `net/NetworkHandler.java` 的 sendToServer 模式，自己开 channel 或复用其封装）。
- 持久化文件放 `.minecraft/mcphone/addons/browser/`（书签/历史 JSON；
  若 mcphone 尚未提供 PhoneContext.appDataDir，就自己建这个目录，路径写死约定）。
- 附属崩溃不得拖垮手机本体：自己的 preInit 全程 try-catch，注册失败只打日志。
- 源码语法可用 Java 21+（Jabel 转 J8 字节码），构建配置直接复制 mcphone-gtnh
  工程的 gradle.properties / build.gradle.kts 骨架改名字。

## 五、工程与交付物

1. 新工程 `E:\zcode\mcphone-addon-browser\`（Gradle，RFG，同 mcphone-gtnh 工具链）。
2. `mcmod.info` 描述模板（务必包含前置声明）：
   ```
   "description": "MCphone 附属：浏览器 App。前置依赖：WebDisplays (1.7.10) + MCEF。
   点击手机浏览器图标在面前生成网页大屏，支持隐藏/恢复、书签与历史。"
   ```
3. jar 产名 `mcphone-addon-browser-1.0.0.jar`，装入实例 mods 实测。

## 六、验收清单（逐条实测通过才算完成）

1. 装好 WD + MCEF 后游戏可正常进存档，手机 GUI 与各 App 不受影响；
2. 点击浏览器图标 → 面前出现大屏并加载 https://wiki.gtnewhorizons.com/ ；
3. 网页可滚动、可点击链接（WD 原生能力，验证即可）；
4. Shift+点击 → 管理页可输入 URL、添加/删除书签、点书签直达；
5. "隐藏屏幕"后大屏消失，再点图标恢复且 URL 不变；
6. 退出存档重进：书签/上次 URL 保留；隐藏状态下重进也能恢复；
7. 卸掉 WD 再启动：浏览器 App 显示前置缺失提示，游戏与手机本体不崩溃。

## 七、测试协议（每次改动都走这个循环）

关闭游戏 → 构建 → 替换实例 mods 里的 jar → 启动游戏 → 进存档 →
按验收清单逐项验证 → **关闭游戏**再进行下一版。
崩溃时读 `crash-reports/` 最新报告定位原因后修复，再走一遍循环。

## 八、兜底方案（仅在 WD/MCEF 于 Java 17+ 实测不可用时启用）

改为纯 mod 内「阅读器」App（不依赖任何前置）：
异步抓取网页 → 解析正文 → Qz-UILib 富文本渲染（标题/正文/图片/链接跳转），
对 MediaWiki 站点（GTNH wiki）走 `api.php` 拿干净正文；域名走 mcphone 配置白名单。
切换前先给出可行性结论报告，经确认后再实施。
