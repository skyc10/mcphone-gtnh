# 会话总结：系统层优化三连（2026-09-09）

> 本文件供后续会话快速接手。本次会话在 mcphone-gtnh 上对齐上游 mcphone-main 的三大系统能力，全部完成并提交。架构/工作规约仍以 [AI-DEV-NOTES.md](AI-DEV-NOTES.md) 为准，附属开发见 [addon-api.md](addon-api.md)。

## 本次成果（4 个提交）

| 提交 | 内容 |
| --- | --- |
| `c0ca7d5` | 每 App 键盘快捷键：appmgr 绑定 UI + 无 GUI 直接触发 |
| `304fd31` | 渲染帧末 GL 裁剪（scissor）自检与自动修复 |
| `ecc8754` | 常显手机 HUD：背包检测/拖拽/锚点/Ctrl+滚轮缩放/G 键开关 |
| `6d21800` | README 补充新功能（中英双语）+ 键位表；附属 mod 链接本已在位 |

构建状态：`./gradlew build` 通过（含以上全部提交）。README 简介句、功能表、键位表均已同步英文版。

## 功能 1：每 App 快捷键

- `api/IPhoneApp.java`：新增 `default boolean opensInsidePhone() { return !isDirectAction(); }`——页面型热键先开机再进页，直达型不开机直接回调 `onActivate`。
- `client/PhoneCanvas.java`：`hotkey.<appId>` 属性存取（`getAppHotkeys()`/`getHotkey(appId)`/`setHotkey(appId,binding)`，null/空串=移除）。
- `client/AppHotkey.java`：绑定记录。`parse`（`^(?:(SHIFT)\+)?(?:(CTRL)\+)?(?:(ALT)\+)?(.+)$` + `Keyboard.getKeyIndex`）、`serialize`（修饰键固定顺序 SHIFT+/CTRL+/ALT+ + 主键大写名）、`matches`/`forApp`/`all`/`capture`。
- `client/AppHotkeys.java`：`onKeyInput`（无 GUI、玩家存活、非 cameraMode 才路由）→ `launch`（直达型用临时 PhoneUi 执行 `onActivate(temp,false)`，若 App 自己开了 GuiScreen 则保留，否则 `temp.dispose()`）；`shift()/ctrl()/alt()` 按物理左右键任一（含 LMENU/RMENU=Alt）。
- `client/scene/ScenePages.java` `appManagerPage()`：每行新增 hotkeyButton（捕获态高亮+横幅提示 `msg.mcphone.hotkey_capturing`）。
- `client/scene/PhoneScreen.java` `keyTyped`：`PhoneUi.hotkeyCaptureTarget != null` 时拦截按键进 `captureHotkey`——Esc 取消；同主键无修饰=清除；否则 `AppHotkey.capture(...)` 落盘并 `warnIfConflicts`。
- lang：`msg.mcphone.hotkey_capturing` / `msg.mcphone.hotkey_conflict`（en/zh）。

## 功能 2：渲染 scissor 自检

- `PhoneUi.render()` 末尾 `checkScissorLeak()`：`GL11.glIsEnabled(GL_SCISSOR_TEST)` 残留→最多剥 8 层 disable→按 currentPageId 每页警告一次（`[mcphone] scissor leak detected on page '...'`）→ `PhoneCanvas.setClipped(true)`。
- `PhoneCanvas.isClipped()/setClipped()`（volatile，会话级）；设置页显示 `msg.mcphone.clipped_hint`。
- 文档：addon-api.md **第八章「每 App 快捷键」**（opensInsidePhone 语义、限制）与**第九章「渲染安全（GL 状态）」**（try/finally、优先 `setClipChildren(true)`、禁改投影/视口/FB）。

## 功能 3：常显手机 HUD

- **新文件** `client/hud/PhoneHud.java`（单例 `get()`/`init()`）：常驻独立 PhoneUi 实例 `hudUi`；`ensureUi` 仅在屏幕尺寸/hudScale 变化时重建（构造后还原 `PhoneUi.ACTIVE` 防抢占）；`panelSize()`=屏高×`PhoneUi.basePanelHeight()`(0.62f)×hudScale% clamp(320,1100)，宽=高×0.56 clamp(200,620)；`panelOrigin()` 九锚点 margin=24；tick 内左键拖拽（位移<4px=点击开机，否则落盘 offset）、Ctrl+滚轮缩放（±10 步进，40–150）、`pointerY=displayHeight-Mouse.getY()-1`（GL 左下原点）；`renderHud()` 与 McScreenBridge.drawScreen 同构：自设 `glOrtho(0,w,h,0)`+viewport→`prepareMainUiRenderState`→compositor/snapshotService beginFrame→`UiHostRenderSupport.createRenderContext(...)`→`hudUi.render(...)`→`GlAttribDepth.popExcess`；玩家/world 为 null 时 `disposeUi()`。
- `client/scene/PhoneUi.java`：新增 `public static float basePanelHeight()` 与 `public void setPanelSize(int w,int h)`（clamp 200–900/320–1400，重写 panel preferred 尺寸）。
- `client/ClientHooks.java`：`keyHud`（G 键，`key.mcphone.hud`）+ onKeyInput 切换开关（`msg.mcphone.hud_on/hud_off`）；时钟 tick 条件加 `PhoneHud.get().hasUi()`；`findPhone` 改 **public static**（HUD 包外调用）；新增 `onRenderHudPost(RenderGameOverlayEvent.Post)` ElementType.ALL 转发。
- `PhoneCanvas`：`hudEnabled`（默认 true）/`hudAnchor`（九锚点，默认 BOTTOM_RIGHT）/`hudOffsetX/Y`（±4096）/`hudScalePercent`（40–150 默认 60）；`clamp` 改包可见 static。
- lang：`key.mcphone.hud`、`msg.mcphone.hud_on/off`（en/zh）。

## 关键设计决策（Why）

1. **HUD 不复用 PhoneScreen/PhoneUi 全屏实例**：`McScreenBridge.onGuiClosed()`（/home/c/c/Qz-UILib/.../ui/screen/McScreenBridge.java:308）必然 `surface.dispose()` 并关闭 compositor/snapshot/adapters——PhoneUi 实例不能跨 Screen 复用，关屏必重建。HUD 用独立常驻实例，开机时全屏实例照旧另建。
2. **HUD 缩放不用全局 uiScalePercent**：`PhoneUi.uiScalePercent` 是 private static volatile（全局共享，构造时消费）→ 用 `setPanelSize` 显式指定尺寸实现 HUD 独立缩放。
3. **直达型热键用临时 PhoneUi**：`onActivate` 需要上下文；若 App 自开屏幕则保留为当前界面，否则 dispose 临时实例。
4. **热键捕获在 PhoneScreen.keyTyped 拦截**：GUI 打开时按键走 GuiScreen，GUI 外按键才到 ClientHooks——两路天然分流，不冲突。
5. **appmgr 的 hotkeyButton 点击用 postAction 包裹**：点击回调里改场景树防 CME（PhoneUi.postAction 队列延迟到渲染帧首）。
6. **热键仅键盘不做鼠标**（1.7.10 可后续加 MouseEvent 路由），修饰键按物理左右任一。

## 踩坑清单（1.7.10 环境特有，后续勿再踩）

- **`KeyBinding.keybindArray` 是 private** 且运行时混淆，无公共访问器（源码在 `build/rfg/minecraft-src/java/net/minecraft/client/settings/KeyBinding.java`）→ `AppHotkeys.registeredKeybinds()` 用**反射**扫描 KeyBinding 唯一静态 List 字段做冲突警告。
- **`@SubscribeEvent` 注解**：1.7.10 是 `cpw.mods.fml.common.eventhandler.SubscribeEvent`，`net.minecraftforge.fml.common.eventhandler` 包不存在。
- **事件总线分家**：TickEvent/InputEvent 在 FML 总线（`FMLCommonHandler.instance().bus()`），RenderGameOverlayEvent 在 Forge 总线（`MinecraftForge.EVENT_BUS`）——ClientHooks 两个总线各注册了一个实例。
- **Qz-UILib 渲染入口**（HUD 裸渲染照抄 McScreenBridge.drawScreen）：`UiHostRenderSupport.createRenderContext(nativeW, nativeH, pointerX*sf, pointerY*sf, partialTicks, compositor, snapshotService, runtimeAdapters)`，compositor/snapshotService 必须 per-screen（或 per-host）各自实例。
- 编译验证用 `./gradlew compileJava`（快），发版验证用 `./gradlew build`。

## 环境备忘

- 项目：`/home/c/c/mcphone-gtnh`（WSL2，MC 1.7.10 Forge 1614 / GTNH 2.9.0-beta-3）。
- 上游参考：`/home/c/c/mcphone-main`（NeoForge 1.21.1，本地 checkout 停 v1.9.0；查 v1.9.1–v1.10.1 源码用 `gh` CLI，curl GitHub API 证书在本环境不可用）。
- Qz-UILib 源码：`/home/c/c/Qz-UILib`。
- 提交风格：`feat:`/`fix:`/`ui:`/`docs:` + 中文描述。

## 会话范围说明

本总结覆盖至 `6d21800`。`39745e7`（退出看门狗 v3，2026-09-09 20:29）为另一会话产物，未在本会话验证过。

## 可选后续（未做）

- HUD 拖拽/缩放的实际游戏内手感验证（本会话只做了编译+构建验证）。
- 热键支持鼠标键（Mouse 事件路由）。
- scissor 泄漏警告接入更完善的附属开发者引导。
