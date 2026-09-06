# MCphone 附属 App 开发提示词：GTNH 中文维基（WebDisplays 大屏版）

> 使用方法：把本文件全文作为任务提示词发给 AI 会话（或当作开发需求书）。
> 开发完成后，本文件的"验收清单 + 测试协议"就是交付标准。
> 姊妹文档：通用浏览器附属见 `docs/addon-browser-prompt.md`（两者共用 WebDisplays 前置，
> 独立开发、互不依赖）。

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
- 实例（测试用）：`C:\Users\陈\AppData\Roaming\PrismLauncher\instances\GT_New_Horizons_2.9.0-beta-3_Java_17-26`，
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
| **WebDisplays 1.7.10**（montoyo/MobiCyp） | 提供游戏内网页大屏（屏幕方块 + 内嵌 Chromium 浏览器） |
| **MCEF**（WebDisplays 同作者的配套库，内嵌 JCEF Chromium） | WD 的渲染后端，按 WD 对应版本下载 |

- 下载渠道：CurseForge / montoyo.net（网络走代理 `127.0.0.1:7897`）。
  先搜 WebDisplays 的 1.7.10 最新版，再按其"依赖"页找配套 MCEF 版本，不要凭记忆猜版本号。
- **第一步就是实测前置可用性**：把 WD + MCEF 放进实例 mods，启动游戏、进存档、
  放一块 WD 屏幕方块加载任意网页。若 MCEF 原生库在 Java 17+/lwjgl3ify 下崩溃
  （JNI 加载失败、启动闪退、屏幕花屏且无解），**立即停止并报告**，不要继续写代码——
  此时按文末"兜底方案"处理。
- 附属运行时检测：`Class.forName("montoyo.wd....")`（以实际 jar 包名为准）失败时，
  Wiki App 显示"需要安装 WebDisplays 前置"提示页，绝不崩溃。
- 附注：目标站点 gtnh.huijiwiki.com 对非浏览器 User-Agent 返回 403（反爬），
  但 WD 内嵌的是真 Chromium 浏览器，天然不受影响；**设计上零 Java HTTP 请求**
  （搜索也不走 API，见下），这是硬性设计约束，不要引入任何 Java 侧 HTTP 调用。

## 三、要做什么

开发独立附属 mod：**mcphone-addon-wiki**（modId `mcphone_wiki`），
为 MCphone 提供「Wiki」App（id=`wiki`）——游戏内查 GTNH 中文维基，**只服务
`gtnh.huijiwiki.com` 这一个站点**，无 URL 栏、不显示其它任何内容：

1. **点击手机上的 Wiki 图标**（直达型）：
   - 手机 NBT 里存有上次页面 → **恢复大屏并回到隐藏前的界面**（见第 2 条隐藏机制）；
   - 没有记录 → 在玩家面前 3~5 格生成大屏（按玩家水平朝向摆 2x3 或 3x3 方块墙），
     并加载 wiki 首页 `https://gtnh.huijiwiki.com/wiki/首页`；
   - 放置失败（无空位/被阻挡）→ 聊天栏报错，不崩溃。
2. **隐藏与恢复（本 App 的核心需求）**：
   - "隐藏屏幕"：隐藏前先把大屏**所有 TileEntityScreen 的 NBT 完整快照**
     （含 URL 与 WD 保存的任何视图状态，以实际 NBT 结构为准）存入手机 NBT /
     本地 JSON，然后移除屏幕方块；
   - 再次点击 Wiki 图标 = 恢复：按快照重建方块并**写回快照 NBT**，
     打开即回到隐藏前的页面；
   - 如实说明的边界：URL/页面恢复是硬保证；**滚动位置**能否恢复取决于 WD 的
     TileEntity NBT 是否保存该状态——实测后如实报告，能存就存，不能存尽力而为
     （例如恢复后按快照里的滚动偏移重新注入，若 WD 有对应接口）。
3. **Shift + 点击图标**：打开管理页（页面型，Qz 场景 UI）：
   - 搜索框 + "搜索"按钮：**不做任何 API 请求**，直接拼站内搜索页
     `https://gtnh.huijiwiki.com/wiki/Special:Search?search=<URL编码的关键词>`
     在大屏打开；
   - 书签列表（添加当前页面/删除/点击直达）；
   - 历史记录（最近 20 条）；
   - "隐藏屏幕"按钮（同第 2 条）。
4. **游戏内提示**：放置成功/失败、已隐藏/已恢复、前置缺失，都用聊天栏或
   SceneToast 反馈，中文文案（en_US.lang + zh_CN.lang 双语）。

## 四、技术红线（逐条遵守）

- **不改 WD、不改 MCphone 源码**。对 WD 的全部调用走反射/javap 探查：
  先 `unzip -l` + `javap -p` 研究安装 jar（重点：屏幕方块、TileEntityScreen 或等价
  方块实体的 URL 设置方法与 NBT 读写、多方块拼接/朝向逻辑、是否有公开 API 类），
  再写调用。
- **零 Java HTTP**：不写任何 HttpURLConnection/HttpClient 代码（站点反爬 + 设计约束），
  一切页面展示和导航都交给 WD 的内嵌浏览器。
- NBT 只写自己的键，统一前缀 `mcphone:wiki:`；屏幕快照 NBT 是"原样拷贝原样写回"，
  不要解析或修改 WD 自己的键。
- 世界操作（放/删屏幕方块、读写 TileEntity NBT）必须在**服务端主线程**执行
  （参考 mcphone 的 `net/NetworkHandler.java` 的 sendToServer 模式）。
- 持久化文件放 `.minecraft/mcphone/addons/wiki/`（书签/历史/屏幕快照 JSON；
  若 mcphone 尚未提供 PhoneContext.appDataDir，就自己建这个目录，路径写死约定）。
- 附属崩溃不得拖垮手机本体：自己的 preInit 全程 try-catch，注册失败只打日志。
- 源码语法可用 Java 21+（Jabel 转 J8 字节码），构建配置直接复制 mcphone-gtnh
  工程的 gradle.properties / build.gradle.kts 骨架改名字。

## 五、工程与交付物

1. 新工程 `E:\zcode\mcphone-addon-wiki\`（Gradle，RFG，同 mcphone-gtnh 工具链）。
2. `mcmod.info` 描述模板（务必包含前置声明）：
   ```
   "description": "MCphone 附属：GTNH 中文维基 App。前置依赖：WebDisplays (1.7.10) + MCEF。
   点击手机 Wiki 图标在面前生成网页大屏浏览 gtnh.huijiwiki.com，支持搜索、书签、
   历史与隐藏/恢复（恢复后回到隐藏前页面）。"
   ```
3. jar 产名 `mcphone-addon-wiki-1.0.0.jar`，装入实例 mods 实测。

## 六、验收清单（逐条实测通过才算完成）

1. 装好 WD + MCEF 后游戏可正常进存档，手机 GUI 与各 App 不受影响；
2. 点击 Wiki 图标 → 面前出现大屏并加载 gtnh.huijiwiki.com 首页；
3. 网页可滚动、可点击站内链接跳转（WD 原生能力，验证即可）；
4. Shift+点击 → 管理页搜索框输入"格雷"→ 大屏打开站内搜索结果页；
5. 翻到某个页面后"隐藏屏幕"→ 大屏消失；再点 Wiki 图标 → 恢复大屏并
   **回到隐藏前的那个页面**（滚动位置尽力恢复，实测后如实报告结果）；
6. 退出存档重进：书签/历史/上次页面保留；隐藏状态下重进也能恢复；
7. 卸掉 WD 再启动：Wiki App 显示前置缺失提示，游戏与手机本体不崩溃。

## 七、测试协议（每次改动都走这个循环）

关闭游戏 → 构建 → 替换实例 mods 里的 jar → 启动游戏 → 进存档 →
按验收清单逐项验证 → **关闭游戏**再进行下一版。
崩溃时读 `crash-reports/` 最新报告定位原因后修复，再走一遍循环。

## 八、兜底方案（仅在 WD/MCEF 于 Java 17+ 实测不可用时）

停止开发并报告实测结果（崩溃日志、现象），由用户决定下一步方向
（例如切"纯 mod 内阅读器"形态——该形态需另行解决站点反爬问题，见
`docs/addon-browser-prompt.md` 第八节的思路）。不要在未确认的情况下自行切换。
