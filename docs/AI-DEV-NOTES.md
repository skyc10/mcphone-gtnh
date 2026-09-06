# MCphone GTNH —— AI 开发交接文档

> 本文档供后续 AI 会话（或新开发者）快速接手。内容：项目现状、架构、**全部踩坑结论**（每条都是实测付出过代价的）、工作规约与待办。修改代码前请先通读"踩坑清单"。
> 最后更新：v1.0.2 发布后（2026-09-07）。

## 1. 项目是什么

把 november521/mcphone（原版：MC 1.21.1 + NeoForge，作者即本仓库主人 skyc10）移植到 **GTNH 2.9.0-beta-3**（MC 1.7.10 + Forge 1614，Java 17+ 运行时，lwjgl3ify + Angelica）。

- UI 层**整体重写**在 [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib)（4.8+，LGPL-3.0）场景栈上：`UiSurface + McScreenBridge` 宿主模式，原生分辨率渲染。
- 仓库：`https://github.com/skyc10/mcphone-gtnh`（master = 干净线性历史；`dev-history` 分支 = 完整开发过程历史，含每次修复的细节）。
- 本地工程：`E:\zcode\mcphone-gtnh`；Qz-UILib 源码（只读参考/自查用，**禁止修改**）：`E:\zcode\Qz-UILib`。
- 测试实例（PrismLauncher）：
  - `GT_New_Horizons_2.9.0-beta-3_Java_17-26`（日常）
  - `290b3test`（干净对照环境）

## 2. 架构速览

```
com.november.mcphone
├── MCphone               @Mod，依赖 required-after:qz_uilib@[4.8,)
├── ClientProxy/CommonProxy  ClientProxy 负责：按键钩子、openPhoneGui、
│                            initApps(PhoneApi.registerBuiltins)、postInitApps(loadExternalApps)
├── api/                  公开附属 SPI（见 docs/addon-api.md）
│   ├── IPhoneApp         id/名称/iconTexture/iconItem/iconGlyph/isDirectAction/
│   │                     onActivate(ctx,shift)/onShiftActivate(ctx)/createPage(ctx)
│   ├── PhoneContext      运行上下文（PhoneUi 实现了它）：runtime/导航/toast/post/sendToServer/waypoints…
│   ├── PhoneWidgets      自绘按钮（字号可控+悬停变色）/文本/卡片/信息行/滚动列（回调自动延迟）
│   ├── PhoneAppBase      附属便捷基类
│   └── PhoneAppConfig    每 App 持久化 KV（.minecraft/mcphone/appdata/<id>.properties）
├── client/scene/         PhoneUi（AbstractSceneHostWidget 宿主，整棵场景树）
│                         PhoneScreen（McScreenBridge 壳：ESC 先回主屏；条件化延迟关屏）
├── client/apps/          BuiltinApps（10 个内建 App 装配）+ ScenePages（全部页面实现）
├── client/               ClientHooks（按键/clock tick/waypoint 同步应用/关屏 flush/AE2 GUI 诊断日志）
│                         CameraHandler（Pre(ALL) 纯世界抓帧 + Post(ALL) 取景框）
│                         ForceExitWatchdog（退出挂起看门狗，见踩坑 #10）
│                         PhotoStore/NotesStore/PhoneCanvas（配置）/TimeUtil
├── core/ItemPhone        手机物品：NBT（设备名/AE2Key/Waypoints 列表）
└── net/
    ├── NetworkHandler    通道 mcphone：0=末影箱 1=AE2 2=传送(mode,index,name) 3=设备名 4=WaypointSync(S→C)
    └── AppIntegrations   AE2 注册（Proxy handler）/打开 ME（换手+槽位同步+终端自身右击）/安全站绑定（3x3x3）/内置传送
```

- 构建：`gradlew build`（GTNH convention 2.0.20，Jabel，JDK25 工具链）。产物 `build/libs/mcphone-vX.Y.Z.jar`。
- 运行依赖：qz_uilib-4.8.0.jar（实例 mods 里）；编译依赖：`libs/qz_uilib-dev.jar`（仓库内，LGPL 允许）。
- 联动（零编译依赖，全反射）：AE2 rv3-GTNH、ae2fc（通用无线终端 `com.glodblock.github.common.item.ItemWirelessUltraTerminal`）、（附属用）MCEF/WebDisplays。

## 3. 踩坑清单（重要度排序，全部实测）

### 布局 / Qz-UILib
1. **grow 求解器早退**：COLUMN 里 flexGrow 子项要求兄弟高度"可先验"，否则早退 → 该子项高度=0。症状：主屏空白、相册不可滚、主页键顶到状态栏。修法：所有关键容器用**显式 preferredHeight**（`PhoneUi.contentHeight()` 体系），不要依赖 grow。相关：`PhoneUi.buildShell`、`ScenePages.PageSlot/scrollColumn`。
2. **滚轮必须 `SceneScrolls.attach(runtime, node)`**：`setScrollable(true)` 只声明可滚动，滚轮事件靠 attach 接线，不挂就永远滚不动。
3. **点击回调里禁止直接改树/关屏**：Qz 输入路由在迭代中派发事件，同步 mount/dispose 会抛 ConcurrentModificationException（曾崩客户端）。一切改树/换页/关屏走 `PhoneUi.post(...)`（渲染帧开头 flush）与 `closePhone()`（内部 pendingClose，客户端 tick 且当前屏还是手机时才关——避免误关刚打开的容器 GUI）。
4. **受控输入**：Qz 的 TextInput/TextArea/Slider 是受控组件，`onChange` 只上抛不回写——必须 `value::set` 写回 Signal，保存时从 Signal 读；滑条用 `(v, committing)`，**提交（松手）时才应用重建**，拖动中重建页面会杀死拖动手势。
5. **控件绘制字号固定 16px（Qz 设计限制）**：TextInput/TextArea/Button 的布局度量跟随 `root.getFontSize()`，但**字形绘制用内部节点的固定 16px**（primitive 内 0 处 setFontSize）。已给作者写好 issue（内容见 git log 或向用户索取）。在 Qz 补丁被接受前：自绘控件（PhoneWidgets.button）可控字号；TextArea/TextInput 放大无解。
6. **布局并行求解是间歇性的**：同一份代码有时正常有时塌陷，别因为"之前好的"排除布局问题。

### FML / GTNH 环境
7. **FML ASM 事件监听器必须是 public 具名静态类**：匿名内部类（`new Object(){ @SubscribeEvent }`）或包私有类会让 ASM 代理跨类加载器调用时抛 IllegalAccessError 崩服务端线程（CameraHandler.Overlay、HandSwapTickHook、CrossDimTeleportHook 都是这么修的）。
8. **`@SideOnly(CLIENT)` 不能标 CommonProxy 方法**：专用服务端会裁剪成员导致 NoSuchMethodError。客户端逻辑放 ClientProxy 覆写（ClientProxy 里 `initApps/postInitApps` 调 PhoneApi 注册——**这两个覆写曾丢失导致主屏只剩附属 App，见 #9**）。
9. **注册调用链脆弱**：`MCphone.init → proxy.initApps() → PhoneApi.registerBuiltins()`。改代理类时务必确认这条链还在。
10. **退出挂起**：MCEF/JCEF 关闭钩子卡死在原生 CEF（线程转储实锤：RUNNABLE 无 Java 栈）。看门狗 `ForceExitWatchdog`：shutdown 钩子**自身同步执行**（不能派守护子线程——关闭早期会被杀）8s 转储线程栈到 `mcphone/shutdown-dump.txt`、15s `Runtime.halt(0)`。根治=附属浏览器做 MCEF 惰性初始化。
11. **RFG 映射差异**（对照 MCP 记忆）：`Entity.rayTrace(double,float)`（不是 rayTraceBlocks）、`Entity.setPositionAndUpdate` 存在、槽位包是 `S2FPacketSetSlot`、`Container.inventorySlots`、`Slot.isSlotInInventory/getSlotIndex`。
12. **Jabel/工具链**：Qz-UILib 构建需要 Azul Zulu 25；用户级 `C:\Users\陈\.gradle\gradle.properties` 的 `org.gradle.java.installations.paths` **覆盖**项目配置——新 JDK 要加到用户级那份里。

### AE2 / ae2fc 集成（全反射，无编译依赖）
13. **客户端宿主来自"客户端手里的物品"**：AE2/ae2fc 的 GUI 打开时（`GuiBridge.getGuiObject`），服务端和客户端各自用手持槽位构建宿主。手机 ME App 的流程：服务端把真终端换到手上 → **用 `S2FPacketSetSlot` 同步两个槽位给客户端（槽位号必须是 inventoryContainer 容器槽位号，mainInventory 索引≠容器槽位号——发错会同步到头盔槽，客户端解析成 GuiNull 半残）** → 调 `terminal.getItem().onItemRightClick(...)`（与手持右键字节码等价）→ GUI 关闭后（服务端 tick 钩子检测 `openContainer==inventoryContainer`）换回原物品并再次同步。
14. **Proxy handler 的 `getConfigManager` 必须注册三个设置项**：`SORT_BY/VIEW_MODE/SORT_DIRECTION`（反射，枚举同 AE2 类加载器），否则 ContainerMEMonitorable 第一帧抛 IllegalStateException 崩集成服务端 → session.lock 不释放 → 存档暂时进不去（只能重启游戏进程）。
15. **ae2fc UWT 的 GUI 路径**：UWT 右击（非潜行）委托 AE2 `ToolWirelessTerminal.func_77659_a` → registry.openWirelessTerminalGui → ToolWirelessTerminal.openGui → 虚分发到 `UWT.openGui`（模式开关：潜行右击=切模式）。手持右键能开是因为客户端手里的就是终端——**没有"虚拟栈绕过客户端宿主"的可能**。
16. **绑定**：安全站 = `appeng.tile.misc.TileSecurity`（注意不是访问点/线缆），密钥 = `getLocatableSerial()`，手机 NBT `AE2Key`；绑定已做命中点 3x3x3 邻域搜索。
17. **诊断日志已内置**：打开 ME 时服务端打印 `[mcphone] ME open (real/built-in): ... container=<类名>`；客户端 tick 打印 `[mcphone] client terminal GUI opened: <类名>`。排查 ME 问题先看这两行（fml-client-latest.log）。

### 其它
18. **相机纯净截图**：抓帧在 `RenderGameOverlayEvent.Pre(ALL)`（HUD 未绘制），取景框在 Post 画，拍照当帧不画取景框。
19. **主对话外发jar**：给用户装 jar 时**先确认 build 成功再复制**（曾把编译失败的旧 jar 推出去过）。
20. 仓库 policy：master 干净线性；完整过程历史在 `dev-history` 分支；发版 = 打 tag 推送（CI 的 release-tags 工作流自动构建 4 个 jar 并发 Release，随后用 `gh release edit` 补中文说明）；版本号 bug 修复递增 patch。

## 4. 当前状态（v1.0.2）

- 已发布：v1.0.0（首版）→ v1.0.1（ME 修复）→ v1.0.2（相册滚动修复）。
- 已验证正常：主屏 10 图标、时钟/天气/便签/设置/应用管理、末影箱直达、传送（含跨维度）、相册设壁纸、拍照纯净画面、退出 15s 自动结束、ME 终端（本体验证至"内置终端正常 + UWT 槽位同步修复后待复测"）。
- 已知限制/待办：
  - **便签/输入框文字放大**：受 #5 限制，等 Qz 上游补丁（issue 已拟好交给用户）；
  - **附属浏览器 App**（mcphone-addon-browser，用户自研，已从 mods 移出待回归）：回归时做 MCEF 惰性初始化根治退出挂起；`docs/addon-browser-prompt.md`、`docs/addon-wiki-prompt.md` 是其开发提示词；
  - UWT 打开若再异常：先用 #17 的两行日志对照手持/手机路径；
  - `gen_icons.py` 可重新生成 10 枚应用图标（输出 `assets/mcphone/textures/ui/`）。

## 5. 工作规约（与用户约定，务必遵守）

1. **测试由用户手动执行**：AI 只负责改代码、构建、把 jar 装进两个实例的 mods、列测试清单。用户反馈现象/截图/崩溃时间点。
2. **崩溃处理**：派子代理（Explore）读 `crash-reports/crash-<时间>-server/client.txt`；子代理并发受限时 AI 直接小范围读报告。
3. **测试循环**：改代码 → 构建 → **关游戏 → 换 jar → 重启 → 用户测** → 下轮。
4. **发版**：用户确认后打 tag（v1.0.3…）推 master+tag，CI 自动发布，`gh release edit` 补中文说明。jar 必须在 tag 后 clean 重建（Tags.VERSION 才正确）。
5. **Qz-UILib 本体禁止修改**（用户明确要求）；需要 Qz 能力时先查它的公开 API，实在没有就写在 mcphone 内（如自绘按钮）。
6. **改附属前先问**：附属 mod（浏览器等）暂缓，本体优先。
7. 网络走本地代理 `127.0.0.1:7897`（git clone/curl/gh 都可能需要）。
8. 提交署名：`user.name=skyc10`（用户 GitHub 账号，gh 已登录）。

## 6. 快速上手清单（新 AI 会话）

1. 读本文档 + `docs/addon-api.md` + README。
2. `git log --oneline` 看最近提交；`git log dev-history` 看过程细节。
3. 构建验证：`./gradlew build -x test`；产物在 `build/libs/`。
4. 装实例：复制 `*-dirty.jar`（非 dev/sources/api）到两个实例的 `.minecraft/mods/mcphone-1.0.0.jar`。
5. 日志位置：`<实例>/.minecraft/logs/fml-client-latest.log`；崩溃：`crash-reports/`；手机数据：`.minecraft/mcphone/`。
6. 遇到渲染/布局异常先对照踩坑 #1-#6；服务端崩溃对照 #7-#10；ME 问题对照 #13-#17。
