# MCphone GTNH 会话总结（2026-09-09 合并版）

> 本文件由 6 份 2026-09-09 会话文档合并而成（SESSION-HANDOFF-20260909、SESSION-NOTES-2026-09-09、
> SESSION-NOTES-20260909-GUI测试与修复、SESSION-NOTES-20260909-上游功能对比、
> docs/session-summary-2026-09-09、docs/SESSION-20260909-watchdog-v3），过时内容已按 git 实况更正。
> 供后续会话快速恢复上下文。持久交接总入口仍是 `docs/AI-DEV-NOTES.md`；附属开发见 `docs/addon-api.md`、`docs/gtnh-dev-guide.md`。

## 一、当前状态快照（2026-09-09 晚，git 核实）

| 仓库 | 分支 | HEAD | 发布 | 状态备注 |
| --- | --- | --- | --- | --- |
| mcphone-gtnh | master | `bbb1220` | [v1.0.3-beta.2](https://github.com/skyc10/mcphone-gtnh/releases/tag/v1.0.3-beta.2) | **ahead origin/master 3（bbb1220/f05f94c/39745e7 未推）**；工作区未跟踪本文件 |
| mcphone-addon-browser | master | `ce97b1f` | v1.0.1-beta.2 | **工作区有大量未提交改动 = P1 渲染修复（beta.3–beta.5）**，dirty jar 已部署实例，未游戏内验证 |
| mcphone-addon-music | master | `3d3538f` | v0.3.0（正式版） | UI 重设计+按钮溢出修复已发布；实例仍是旧 jar a8a5b19 |
| mcphone-addon-wiki | **main** | `58ae0d0` | 1.0.1-beta.2 | 看门狗 v2+纹理兜底移植版，干净已推 |
| mcphone-main（上游参考） | — | `5bb67de`（≈v1.9.0） | 上游已到 v1.10.1 | 本地克隆过时，刷新方法见第七节 |

- 版本线：主 mod tag v1.0.x；附属纯版本号（browser v1.0.1-beta.x、wiki 1.0.x、music v0.3.0）。构建产物版本来自 git describe（如 mcphone-v1.0.2-master.33+6d21800d55.jar），gradle.properties 的 `version=1.0.0` 是兜底死值，别被误导。
- 产物 jar：`build/libs/`（不带 -dev/-api 后缀的为发布 jar）；编译验证 `./gradlew compileJava`（快），发版验证 `./gradlew build`（worktree 缓存复用约 35s）。
- gh CLI 在 Windows 侧认证（skyc10）。提交风格 `feat:`/`fix:`/`ui:`/`docs:` + 中文描述，署名 skyc10。master 线性干净，过程历史在 `dev-history`；发版由用户打 tag 触发 CI。
- 在研附属（暂缓，本体优先）：browser / wiki / music 三条附属管线已跑通且有发布，不回并本体。

## 二、v1.0.3-beta.1 → beta.2 内容（主 mod 6d21800 = 5da5132 merge + 4 提交）

- `1a17662` 商店独立 App：`ScenePages.storePage()`（ScenePages.java:829-905）、`PhoneUi.orderedForHome()`（:388-398）跳过 needsPurchase、BuiltinApps.store()（id=store 绿宝石图标免费）；商店模式关=旧行为。扣费 consumeAtomic 原子；服务端永远按有价校验（商店模式是纯客户端设置）；Blocks.ender_chest 带下划线。
- `5abfad4` 删主屏拖拽提示+时钟页时长提示（lang 死键 home_drag_hint/playtime_hint 未清）。
- `3381dff`+`b19dc44` ForceExitWatchdog v2 → 已被 `39745e7` v3 取代（见第三节）。
- `c0ca7d5` 每 App 快捷键（见第四节功能 1）。
- `304fd31` GL 裁剪自检（见第四节功能 2）。
- `ecc8754` 常显手机 HUD（见第四节功能 3）。
- `6d21800` README 文档（中英双语+键位表）。
- wiki `58ae0d0`：ExitWatchdog 整文件替换为 browser v2 版（6 处本地化：包名/线程名 mcphone_wiki-ExitWatchdog/日志前缀/日志文件 mcphone_wiki_watchdog.log/WikiScreen.forceClose/删 CEF-active 探测块）+ WikiScreen.forceClose 异步化 + WikiHandle 纹理兜底 + 15s texture_stuck 提示。
- 双看门狗共存结论：主 mod 25-35s 最先强杀（无条件兜底），browser/wiki 各自条件不满足自然退让，Runtime.halt 幂等——最迟 35s 必有强杀。

## 三、退出看门狗 v3（`39745e7`）→ v3.1（`5bedf3b`，2026-09-09 深夜，待游戏内验证）

背景：v2 armed 后零输出（退出冻结期日志死、强杀依赖 JVM 内线程不可靠）。v3 `client/ForceExitWatchdog.java` 三层：

1. **独立文件日志** `<mcDataDir>/mcphone/exit-watchdog.log`（append，256KB 轮转 .1），三条内部线程各 60s 一条 `xxx alive` 心跳。
2. **最小化探测器**：只读 volatile `Minecraft.running`（反射找唯一 volatile boolean 字段），绝不碰 `Thread.getAllStackTraces()`（v2 疑似退出冻结期阻塞点）。连续 2 次 false → 写 flag `.exit-flag-<pid>`。
3. **JVM 外部杀手（仅 Windows）**：生成 `mcphone/ext-watchdog.ps1`（UTF-16LE 带 BOM，含中文路径 PowerShell 5 必须）+ `ext-watchdog-launch.vbs`，`wscript //B` 隐藏启动（直接 powershell 会闪黑框）；flag 出现等 35s `taskkill /F /T /PID`；心跳 `.exit-hb-<pid>` 断更 90s 同样强杀；vbs 的 `WScript.Arguments(0)` 只收 ps1 路径一个参数，参数必须烤进脚本内容。

内部时间线同 v2：daemon 轮询 → 25s 宽限（每 5s 线程摘要）→ halt 策略链（直调 halt → 反射 halt → FML exitJava → System.exit，各独立 daemon 线程）→ 35s 无条件。三线程互为看门狗，beat 超 10s 判死重启同伴。
系统属性：`-Dmcphone.exitwatchdog=false` 禁用；`-Dmcphone.exitwatchdog.externalgrace=<秒>`（默认 35）；`-Dmcphone.exitwatchdog.hbstall=<秒>`（默认 90）。

### v3 游戏内实测失效取证（2026-09-09 22:17/22:37 两次卡死）

- 两会话（pid 41216/40212）内部线程 alive 心跳都在 SoundSystem/JCEF 关闭的同一秒整体停摆，**都没写 .exit-flag-***；冻结是 JVM 级（safepoint/classloader/native），内部 halt 链全部失效。
- 外部杀手也没开火（hb 断更 16 分钟进程还在 = 手动杀的）；当时杀手侧零日志，无法定位是 spawn 没起还是逻辑没触发。
- **dummy 端到端复现结论**：旧模板的 flag 分支和 hb-stall 分支在干净环境都能正常杀（wscript→vbs→ps1 链 ✓）——卡死缺口不在模板逻辑，而在 (1) 冻结时 flag 没人写；(2) 杀手不可观测。
- 教训：PowerShell 里 `$_` 在 wsl bash 内联调用要写 `\$_`；查询命令的 CommandLine 自匹配会让进程计数恒为 1，判断杀手是否退出必须排除查询自身；sed 不能编辑 UTF-16LE 文件（会产出只剩 BOM 的空壳）。

### v3.2 修正（2026-09-10，`33a5d51`+`a2f905b`，jar master.41 已部署）——杀手寿命与会话时长解耦

**v3.1 游戏内实测定罪**（pid 7376 会话，jar36）：部署/加载/spawn 确认/flag 写入全部正常——探测器在 JVM 冻结前一瞬抓到 running=false，flag 23:36:19.302 写下；但杀手 23:30:17 启动带固定 5min TTL，**23:35:29 已自毁**（ext-watchdog.log: `5min deadline, exiting without kill`），flag 落地时无杀手可读，外部击杀永不发生。任何 >5min 的会话必复现。

迭代史（两轮提交）：
- `33a5d51`（凌晨）：① 补完顺延逻辑——工作区残缺版 `$hbStamp` 只声明未使用，jar38 的杀手 TTL 实为固定 150s（90+60），比旧 300s 更短，从未游戏验证即被取证发现；② wscript 确认窗 10s→20s（实测 wscript→PowerShell 冷启动 **11.3s**：7376 会话的杀手 start 行 23:30:28.769 = spawn 后 11.3s，wscript 链其实成功了，10s 窗必超时 → 误判失败转直启**双 spawn**）；③ PING.EXE 活体验证过。
- `a2f905b`（上午）：① 去掉顺延成功时逐 2s 一条的 `heartbeat fresh, deadline extended` 日志（会刷爆文件，顺延是正常路径）；② **vbs 自记日志** `ext-watchdog.vbs.log`（每会话重开）：`vbs launcher alive, spawning powershell` + `vbs Run rc=N Err=描述`，下次失效可直接分层定位 wscript 层 vs vbs 层；wscript 绝对路径调用不赌 PATH；③ dummy 端到端复验全过（hbStall=15s 短周期：hb 顺延跨 deadline=75s 存活 t=102s 无 deadline 行 ✓、stall 击杀 rc=0 ✓、flag 分支 rc=0 ✓、vbs 双参数链+日志 ✓）。

机制：hb mtime 比上次轮询新 → deadline 推到 now+hbStallSec+60s；hb 停跳超 hbStallSec 本就触发 stall 击杀 → 不存在"游戏活着但 deadline 到点"的空窗。

### v3.1 修正（`5bedf3b`）

- **early flag hook**：注册时挂最早 shutdown hook，一进退出流程就写 flag——抢在冻结点前立起外部杀手触发器（冻结场景唯一可靠信号源）。
- **杀手自记日志** `mcphone/ext-watchdog.log`（KLog，UTF8 append）：`=== ext-killer start`（含目标 pid/杀手 pid$/参数）/flag seen/heartbeat stalled Xs/taskkill rc=N/ext-killer exit 全落盘；~~另加 5 分钟无触发自毁防僵尸杀手~~（**此项是 v3.2 定罪的设计缺陷，已被心跳顺延式取代**）。
- **spawn 就绪确认**：spawn 后轮询杀手日志出现新增 10s（started 行），确认失败退回直启 `powershell -WindowStyle Hidden`，再失败才放弃并记日志。
- `touchHb`/`writeFlag` 去 synchronized：不再持 class 锁做文件 I/O（v3 里 FS 卡顿会拖死三条内部线程）。同 pid 单写者无竞争，writeFlag 幂等靠 flagWritten volatile。
- v3.1 杀手模板已 dummy 验证：KLog 日志逐行落盘、stall 分支 rc=0、wscript 链正常。

**下次启动验证（v3.2）**：`mcphone/exit-watchdog.log` 应出现 `=== session start: watchdog v3, pid=...` + `armed` + `external killer confirmed running (ext-watchdog.log grew)`；`mcphone/ext-watchdog.log` 应有 `=== ext-killer start` 行且**不再出现 `5min deadline` / `deadline reached`**；`ext-watchdog.vbs.log` 若缺失 = wscript 层没执行，若有 `Run rc=0` 但无杀手 start = vbs 里 powershell 启动失败；正常退出进程应自然结束或最迟 flag 后 ~35s 被外部击杀（ext-watchdog.log 可查全过程）；若再挂死收三份日志 exit-watchdog.log + ext-watchdog.log + ext-watchdog.vbs.log + shutdown-dump-*.txt；若误杀调大 extGraceSec/hbStallSec 或关掉。

## 四、三大系统能力实现细节（已提交，编译+构建验证过，游戏内手感待验证）

### 功能 1：每 App 快捷键（c0ca7d5）
- `api/IPhoneApp.java`：`default boolean opensInsidePhone() { return !isDirectAction(); }`——页面型热键先开机再进页，直达型不开机直接回调 `onActivate`。
- `client/PhoneCanvas.java`：`hotkey.<appId>` 属性存取（`getAppHotkeys()`/`getHotkey(appId)`/`setHotkey(appId,binding)`，null/空串=移除）。
- `client/AppHotkey.java`：绑定记录。`parse`（`^(?:(SHIFT)\+)?(?:(CTRL)\+)?(?:(ALT)\+)?(.+)$` + `Keyboard.getKeyIndex`）、`serialize`（修饰键固定顺序 SHIFT+/CTRL+/ALT+ 主键大写名）、`matches`/`forApp`/`all`/`capture`。
- `client/AppHotkeys.java`：`onKeyInput`（无 GUI、玩家存活、非 cameraMode 才路由）→ `launch`（直达型用临时 PhoneUi 执行 `onActivate(temp,false)`，App 自开 GuiScreen 则保留否则 `temp.dispose()`）；`shift()/ctrl()/alt()` 按物理左右键任一（含 LMENU/RMENU）。
- `client/scene/ScenePages.java` `appManagerPage()`：每行 hotkeyButton（捕获态高亮+横幅 `msg.mcphone.hotkey_capturing`）。
- `client/scene/PhoneScreen.java` `keyTyped`：`PhoneUi.hotkeyCaptureTarget != null` 时拦截进 `captureHotkey`——Esc 取消；同主键无修饰=清除；否则落盘并 `warnIfConflicts`。
- lang：`msg.mcphone.hotkey_capturing` / `msg.mcphone.hotkey_conflict`（en/zh）。

### 功能 2：渲染 scissor 自检（304fd31）
- `PhoneUi.render()` 末尾 `checkScissorLeak()`：`GL11.glIsEnabled(GL_SCISSOR_TEST)` 残留 → 最多剥 8 层 disable → 按 currentPageId 每页警告一次 → `PhoneCanvas.setClipped(true)`。
- `PhoneCanvas.isClipped()/setClipped()`（volatile，会话级）；设置页显示 `msg.mcphone.clipped_hint`。
- 文档：addon-api.md 第八章（opensInsidePhone 语义）与第九章「渲染安全（GL 状态）」（try/finally、优先 `setClipChildren(true)`、禁改投影/视口/FB）。

### 功能 3：常显手机 HUD（ecc8754）
- 新文件 `client/hud/PhoneHud.java`（单例）：常驻独立 PhoneUi 实例 `hudUi`；`ensureUi` 仅在屏幕尺寸/hudScale 变化时重建（构造后还原 `PhoneUi.ACTIVE` 防抢占）；`panelSize()`=屏高×`PhoneUi.basePanelHeight()`(0.62f)×hudScale% clamp(320,1100)，宽=高×0.56 clamp(200,620)；`panelOrigin()` 九锚点 margin=24；tick 内左键拖拽（位移<4px=点击开机，否则落盘 offset）、Ctrl+滚轮缩放（±10 步进 40–150）、`pointerY=displayHeight-Mouse.getY()-1`；`renderHud()` 与 McScreenBridge.drawScreen 同构（自设 glOrtho→`prepareMainUiRenderState`→compositor/snapshotService beginFrame→`UiHostRenderSupport.createRenderContext(...)`→`hudUi.render(...)`→`GlAttribDepth.popExcess`）；玩家/world null 时 `disposeUi()`。
- `PhoneUi.java`：新增 `public static float basePanelHeight()` 与 `public void setPanelSize(int w,int h)`（clamp 200–900/320–1400）。
- `client/ClientHooks.java`：`keyHud`（G 键 `key.mcphone.hud`）+ onKeyInput 切换（`msg.mcphone.hud_on/off`）；时钟 tick 加 `PhoneHud.get().hasUi()`；`findPhone` 改 public static；新增 `onRenderHudPost`（ElementType.ALL）转发。
- `PhoneCanvas`：`hudEnabled`（默认 true）/`hudAnchor`（九锚点，**默认 CENTER_LEFT**，2026-09-10 由 BOTTOM_RIGHT 改）/`hudOffsetX/Y`（±4096）/`hudScalePercent`（40–150 默认 60）；`clamp` 改包可见 static。

**关键设计决策（Why）**：
1. HUD 不复用 PhoneScreen/PhoneUi 全屏实例——`McScreenBridge.onGuiClosed()`（/home/c/c/Qz-UILib/.../ui/screen/McScreenBridge.java:308）必然 `surface.dispose()` 并关 compositor/snapshot/adapters，PhoneUi 实例不能跨 Screen 复用，关屏必重建；HUD 用独立常驻实例。
2. HUD 缩放不用全局 uiScalePercent——`PhoneUi.uiScalePercent` 是 private static volatile（全局共享），用 `setPanelSize` 显式指定实现独立缩放。
3. 直达型热键用临时 PhoneUi（onActivate 需要上下文）；App 自开屏幕则保留，否则 dispose。
4. 热键捕获在 PhoneScreen.keyTyped 拦截——GUI 内按键走 GuiScreen、GUI 外走 ClientHooks，两路天然分流。
5. appmgr 的 hotkeyButton 点击用 postAction 包裹（回调里改场景树防 CME，PhoneUi.postAction 队列延迟到渲染帧首）。
6. 热键仅键盘不做鼠标（可后续加 MouseEvent 路由）。

### 功能 4：HUD 位置/裁剪修复（2026-09-10，master.38 dirty，待游戏内验证）
- 用户报告：HUD「显示位置不正确，并且只显示了一部分，应该显示在左侧」。根因两个：
  1. **默认锚点错**：`PhoneCanvas.getHudAnchor()` 默认 `BOTTOM_RIGHT`（右侧 NEI 侧栏遮挡），与验收「常显在左侧」矛盾；实例 settings.properties 无 `hudAnchor` 键 → 走默认。
  2. **HUD 面板内容被裁一半**：`ensureUi` 先 `new PhoneUi(phone)`（构造内 `applyPanelSize()` 按全屏×全局 uiScalePercent=150 建面板 562×1004 + buildHomeGrid 大网格），随后 `setPanelSize` 缩到 ~225×402 只改 panel/contentSlot preferred——**主页网格单元（`iconCell` 的 `cellW`/`label.setMaxTextWidth`）与 `devName.setMaxTextWidth(panelW-90)` 都是构建时定值**，大网格塞小面板溢出被 `panel.setClipChildren(true)` 裁掉。uilib 渲染链（pipeline 按 absX/absY 平移、windowClip=null 不裁）排查后无嫌疑。
- 修复：① `PhoneCanvas` 默认锚点改 `CENTER_LEFT`（HUD_ANCHOR_DEF 常量，getHudAnchor 两处引用）；② `PhoneUi` 新增构造器 `(ItemStack, int hudW, int hudH)`——显式面板尺寸一次建对外壳/网格/文本宽度，`PhoneHud.ensureUi` 改用它（删 setPanelSize 调用）；③ `buildHomeGrid` 列数自适应：`panelW>=360 → 3 列，>=210 → 2 列，否则 1 列`（旧 `cellW=max(80,…)` 下限在窄面板必溢出）；④ `setPanelSize` 兜底重建页面（HOME→rebuildPage，App 页→openApp）防再被误用为半套更新。
- 取证方法：uilib 源码在 /home/c/c/Qz-UILib/（sources jar 同目录 build/libs），比反编译 libs/ 里的 dev jar 快得多。
- jar 已部署实例（mcphone-v1.0.2-master.38+b25aff2690-dirty.jar，替换 master.36）。**待办：游戏重启验证 HUD 左中显示 + 内容完整。**

### 功能 5：退出看门狗 v3.2（2026-09-10）→ 已并入第三节「v3.2 修正」，以该节为准

（本节原为凌晨 `33a5d51` 的独立记录：取证结论、残缺工作区修复、20s 确认窗、PING 活体验证——与第三节合并后的 v3.2 章节内容重复，保留标题占位避免外部引用断链，细节看第三节。）

## 五、GUI 测试结果（GTNH 2.9.0-beta-3，329 mods 全载，存档 test）

### ✅ 通过项
- 主 mod：主屏 14 App 正常；未购付费 App「末影箱」不在主屏；P 键开关手机；HUD 常显在左侧；主屏无拖拽提示
- browser：打开、工具栏、地址栏编辑、回车导航全部工作（URL 自动规范化、MCEFCache Cookies/data_1 随导航更新 → CEF 网络栈正常）
- music：打开、正在播放空状态、点歌 tab 切换、音源切换、搜索框整串输入均正常
- wiki：打开后自动导航到 https://gtnh.huijiwiki.com/wiki/首页（中文 URL 编码正确）
- 退出看门狗（v2 时代）：10 秒内 javaw 结束、jcef_helper 无残留，browser/wiki watchdog 均正确检测 running=false、force-close（5000ms 宽限）

### 问题清单（当前状态按 git 事实更新）
1. **[P1] browser 页面不渲染 —— 修复已写好未验证**。根因已定位（beta.4 取证）：帧上传链完好，问题在**绘制侧**——`CefRenderer.render()` 自带路径在 Angelica GLSM 下整面透明（UV 布点缺陷 + GL_ALPHA_TEST 丢弃 CEF OSR 全透明 alpha 帧的片段）；另 GTNH+lwjgl3ify 下 MCEF 的 `mcefUpdate()` RenderTickEvent 不触发，帧上传需自驱动 `N_DoMessageLoopWork()` + `mcefUpdate()`。browser 仓库未提交改动（BrowserHandle/BrowserScreen/McefBridge/AddonStore，+546/-92，version=1.0.1-beta.5）：绕开 CefRenderer.render() 自绘正确 UV(0,0)-(1,1) 四边形+显式关 alpha test/blend、drawScreen 自驱动帧泵、beta.3 地址栏换 vanilla GuiTextField（支持 IME/Ctrl+A/C/V/X）、首次绘制纹理像素读回诊断（日志 `texture probe ... alpha0=N%`，alpha0=100%=CEF 产全透明帧）。dirty jar（v1.0.1-beta.2-master+ce97b1fe72-dirty，9-9 20:24）已部署实例。**待办：改动未提交，需游戏内验证后提交发布。**
2. ~~[P2] music 点歌页右侧溢出~~ —— **已修复**：`3d3538f`「UI redesign + button overflow fix」随 v0.3.0 发布。**实例 mods 仍是旧 jar a8a5b19，手测前需换 v0.3.0 jar。**
3. [搁置] music 搜索提交后无反馈（单机无 FMusic 服务端，连失败提示都没有）。
4. [确认为正确行为] 时钟页显示本局时长/世界总时长是正确的，勿再当 bug。
5. [搁置] 商店购买「末影箱」点击无反应，需有货币环境验证。
6. [待验证] HUD 拖拽/缩放手感（实现只做了编译+构建验证）。

### CUA 操作经验（MC 1.7.10 自绘 UI，无 a11y 元素）
- 全部 strategy=event 坐标点击；**分步 left_mouse_down + left_mouse_up 比合成 left_click 可靠**（世界列表条目只能分步选中）。
- 键盘 key(strategy=event, app_ref) 可靠；单击键字符能进 browser 地址栏。
- **type() 整串输入对 vanilla GuiTextField 和 music 搜索框有效，对 browser 旧自定义地址栏无效**（beta.3 已换 vanilla GuiTextField，应生效待验）。
- **Ctrl+A/Ctrl+V 对 1.7.10 自定义文本框无效**（vanilla GuiTextField 除外）。
- zoom 传 frame_id 会裁剪旧帧——看实时状态必须先 get_app_state 拿新帧再 zoom。
- 进存档：主菜单点「单人游戏」→ 分步点击 test 条目 → 「进入选中的世界」。
- 键位：打开手机 [P]、相机快门 [C]（G 被 GuideNH 占用，但 G 现在是 HUD 开关——注意与 GuideNH 冲突，见待办）。

## 六、架构事实（审查实锤，勿再踩）

1. **GTNH forge fork 1614 双总线未合一**：`PlayerEvent.PlayerLoggedInEvent` 只 post 到 `FMLCommonHandler.bus()`（FML 私有 EventBus），不 post 到 MinecraftForge.EVENT_BUS。登录类监听必须挂 FML 总线（StoreEvents/ChatEvents/NoteEvents 均已改）。一般规律：TickEvent/InputEvent 在 FML 总线，RenderGameOverlayEvent 在 Forge 总线（ClientHooks 两个总线各注册一个实例）。
2. **SimpleImpl C→S handler 在 netty event-loop 线程**；1.7.10 服务端无 addScheduledTask（srg 只映射到 Minecraft）。主 mod 用 SERVER_TASKS ConcurrentLinkedQueue + ServerTaskDrain（ServerTickEvent.END 主线程排水）。
3. **MCEF 上游纹理孤儿 bug**：`CefRenderer.initialize()`（texture_id_[0]=glGenTextures 唯一赋值点）在部署 jar 零调用者 → textureId 恒 0 → 渲染门控永假。browser/wiki 已反射兜底（构造器探测 CefBrowserOsr.renderer_ private 字段 + initialize protected 方法，GL 线程执行一次不重试）。GTNH 下 MCEF 帧上传链同样断裂（见第五节 P1）。
4. **`KeyBinding.keybindArray` 是 private** 且运行时混淆，无公共访问器（源码在 build/rfg/minecraft-src/...）→ 反射扫描唯一静态 List 字段做冲突警告。
5. **`@SubscribeEvent` 注解**：1.7.10 是 `cpw.mods.fml.common.eventhandler.SubscribeEvent`，`net.minecraftforge.fml` 包不存在。
6. **Qz-UILib 渲染入口**（HUD 裸渲染照抄 McScreenBridge.drawScreen）：`UiHostRenderSupport.createRenderContext(nativeW, nativeH, pointerX*sf, pointerY*sf, partialTicks, compositor, snapshotService, runtimeAdapters)`，compositor/snapshotService 必须 per-screen 各自实例。
7. 附属兼容红线：附属 App 永远免费且不被商店过滤（isBuiltin()==false）；api/ 目录零改动。
8. NoteSync/ChatHistory 均分批（NoteSync 8 条/24KB 一批，读写对称 count→offset→total→条目；聊天 30 条/批 meta.text 封顶 600B）。
9. mcphone 本体不写 Mixin：`mixins.mcphone.json` 三个数组为空（mixin 包是 qz_uilib 运行时占位）；`usesMixinDebug` 不需要开。
10. `mcmod.info` 的 `useDependencyInformation:false` 无问题——1.7.10 下 `@Mod` 注解 `required-after:qz_uilib@[4.8,)` 才是有效声明。
11. gradle 形态：`compileOnly(files("libs/qz_uilib-dev.jar"))` + `runtimeOnlyNonPublishable` 是标准形态；`settings.gradle.kts` pluginManagement 的 `mavenLocal()` 是 GTNH 模板标准写法，不是残留。Daemon JVM criteria（toolchain 25）+ Jabel 已启用。
12. 代码卫生：4 处 `System.out.println`（net/AppIntegrations.java:61,180,190、client/ClientHooks.java:91）是文档声明的刻意诊断日志，非垃圾。

## 七、工具坑速查（本机验证过）

**WSL ↔ Windows**：
1. `wsl bash -c` 内 `$VAR`/`$?`/`$(...)`/双引号会被 Windows 外层 shell 吃掉或破坏 → 一律写 /home/c/*.sh（Write 工具写 UNC 路径 + `tr -d '\r'` 去换行符）再 `wsl -- bash -c 'bash /home/c/x.sh'`；或单引号包简单命令。for 循环带 $r 必挂。
2. jar 从 WSL 复制到 Windows 用 UNC：`cp //wsl.localhost/Ubuntu-26.04/home/c/... /c/Users/陈/...`（Git Bash 双斜杠）；WSL 内访问实例用 /mnt/c/Users/陈/AppData/Roaming/PrismLauncher/instances/290b3test/.minecraft。

**gh CLI / GitHub**：
3. gh 中文内联参数被编码破坏 → 必须 `--notes-file` 磁盘文件；**且 --notes-file 也偶发被默认 changelog 顶掉** → 创建后必须 `gh release view --json body --jq '.body|length'` 校验，不对就 `gh release edit --notes-file` 补。
4. `gh release create --target <sha>` 报 422 target_commitish invalid → 去掉 --target（默认打 HEAD，先 push）。
5. gh release upload 重名附件 → --clobber。gh --json 无 title 字段用 name。GitHub API 偶发 EOF/Bad Gateway，sleep 5 重试。

**网络（访问上游 november521/mcphone）**：
6. 不可行：`HTTPS_PROXY=127.0.0.1:8787` 对 github.com 返回 502；`git fetch` 直连超时；WebFetch 对 github.com/api.github.com/jsdelivr/gitee/ghproxy 全部证书错误。
7. **可行**：`curl -s --noproxy '*' https://api.github.com/...`（稳定 200）与 codeload.github.com tarball。查上游源码也可用 gh CLI。⚠️ 旧文档「curl GitHub API 证书不可用」的结论已作废，以本条为准。
   ```bash
   curl -s --noproxy '*' "https://api.github.com/repos/november521/mcphone/commits?sha=main&per_page=15"
   curl -s --noproxy '*' "https://api.github.com/repos/november521/mcphone/releases/latest" | python3 -c "import json,sys; r=json.load(sys.stdin); print(r['tag_name'], r['published_at']); print(r['body'])"
   ```

**进程/杂项**：
8. pgrep -f lwjgl3ify 匹配 bash 命令自身=假阳性 → `ps aux | grep '[f]orgePatches'`。
9. wiki 仓库默认分支 main；git describe 产物名 main.N。

## 八、环境备忘

- **构建**：`wsl -d Ubuntu-26.04 -- bash -c 'cd /home/c/c/<repo> && ./gradlew build --build-cache -x test'`。
- **WSL 测试服务端**：/home/c/gtnh-290b3/server（GTNH 2.9.0-beta-3）。启动必须 `java -Xms3G -Xmx3G -Dfml.readTimeout=180 -Duser.language=en @java9args.txt -jar lwjgl3ify-forgePatches.jar nogui`（普通 java -jar forge universal 会 ClassCastException）。冒烟流程：后台任务方式启动（setsid+nohup 在 wsl bash -c 里会被杀，**用 run_in_background=true 的前台 wsl 命令**）→ grep "Done (" → kill -TERM（写 /home/c/*.sh 执行）→ 验证 EXITED_CLEANLY。
- **Windows 客户端实例**：Prism `290b3test`（/mnt/c/Users/陈/AppData/Roaming/PrismLauncher/instances/290b3test/.minecraft）。mods 现状（2026-09-09 21:02 核实）：
  - mcphone-v1.0.2-master.33+6d21800d55-dirty.jar（主 mod，含 v3 看门狗；旧 master.29 已删除）
  - mcphone-addon-browser-v1.0.1-beta.2-master+ce97b1fe72-dirty.jar（含 P1 渲染修复未提交改动）
  - mcphone-addon-music-a8a5b19.jar（**旧版，v0.3.0 溢出修复未部署，需换**）
  - mcphone-addon-wiki-1.0.0-main.3+58ae0d07c0.jar
  - qz_uilib-4.8.0-4-0.308+7fd570ab83.jar / MCEF-1.7.10.jar / FMusic-1.4.jar / angelica-2.2.10 / lwjgl3ify-3.0.31 / guidenh-1.3.29 等（GTNH 2.9.0-beta-3 全家桶）
  - 旧 jar 备份：mods-backup-20260908
- **MCEF 镜像**：客户端 config/MCEF.cfg forcedMirror=https://github.com/skyc10/mcef-resources/releases/download/v1 已确认；服务端无 MCEF（纯客户端）正常。mirror.jar 源在 browser research/（SHA-1 938bc81902f76e4ddbdd2fd58ed36b3fd5dcfd37）。
- **服务端 mods**：browser-master.5+f0bc6d6 / music-a8a5b19 / wiki-main.3+58ae0d0 / mcphone-master.29+5da5132（均落后客户端，注意配套）。
- 分工红线：AI 测试用无 GUI/自动化方式，游戏内手测由用户做。
- 相关路径：Qz-UILib 源码 /home/c/c/Qz-UILib；FMusic /home/c/c/FMusic；上游克隆 /home/c/c/mcphone-main（5bb67de 过时）；worktree mcphone-gtnh-wt-exit / -wt-store（store 已并 master，wt-store 可清）。

## 九、上游对比结论（november521/mcphone，2026-09-09 核实）

上游 main=v1.10.1（5ac74e6，2026-09-08）；feature/tablet-reader 开发 v1.11.0（平板+阅读 App+照片坐标水印）。v1.9.2 快捷键/快门模糊；v1.9.3 界面缩放；v1.9.4 scissor 自检；v1.10.0 终端 App+副手 HUD（mcphone-terminal 并回本体）；v1.10.1 手机 3D 模型+亮屏同步+opensInsidePhone()+卡槽供电+PhoneMultiLineEditBox。

**GTNH 独有**（上游没有）：传送 App 自带传送点 NBT 跨维度；天气活动建议；游玩时长里程碑/时长解锁商店（双模型，上游是安装/卸载模型）；appmgr 拖拽全局排序；UWT 换手打开 ME 终端；相机滤 HUD。

**剩余差距表（真正 TODO）**：

| 差距项 | 上游版本 | 难度(1.7.10) | 归属 |
| --- | --- | --- | --- |
| 终端卡槽概念（NBT 槽+死亡保留+手机供电） | 1.10.0/1.10.1 | ★★ | 本体 |
| 多页主屏+插入式拖拽（3px 阈值、边条 0.4s 翻页） | 1.9.x | ★★ | 本体（appmgr 已有拖拽基础，缺分页） |
| 聊天图片分片提升上限（Packet250 上限 32767B，现有 JPEG≤8KB 可分片） | 1.7.x | ★★ | 本体 |
| Baubles 饰品槽 | Curios 对应 | ★ | 本体 |
| EMC 定价（IAppPriceProvider SPI 已预留，未接 ProjectE） | — | ★ | 本体 |
| 照片坐标水印 | 1.11.0 开发中 | ★ | 本体 |
| 快门闪光可换模糊 | 1.9.2 | ★ | 本体 |
| 任务书（反射开 FTB Quests GUI，先摸入口类） | 反射 | ★★ | 新附属 |
| 手机 3D 模型+亮屏同步 | 1.10.1 | ★★★ | **不建议**（需自写 IItemRenderer+数据同步，收益低） |
| 平板设备+阅读书城 | 1.11.0 开发中 | ★★★ | **不建议**（GTNH 无统一手册生态，wiki 附属已覆盖） |
| GIF 表情包 | — | — | **不做**（1.7.10 无解码器） |
| 音乐外放唱片仓 | — | — | **不做**（服务端无法传本地音频，与 FMusic 重叠） |
| 资源包换肤（40+ 贴图位） | — | — | **不做**（Qz 场景 UI 改造量极大） |
| 多存储认别（RS2/Tom's） | 1.10.0 | — | **不适用**（GTNH 只有 AE2 系） |

**架构结论**：上游哲学=「装了 X 就多一格」内建 App+反射可选依赖。GTNH 对应策略：轻量联动进本体（反射可选依赖不引编译依赖）——终端卡槽/多页主屏/Baubles/EMC/聊天分片；重依赖走附属（browser/wiki/music 管线已跑通，不回并）。上游 API 里 GTNH 侧仍缺声明能力：`requiredMods()`/`companionMods()`、`opensInsidePhone()` 接口抽象（行为已等效实现）。

## 十、已定决策（勿重新提议）

- **Spotless 保持禁用**（`disableSpotless=true`）：用户明确不启用，Qz-UILib 同款，刻意选择。
- 仓库外指南 /home/c/c/GTNH-DEV-NOTES.md 保留原样（含本机信息）；入库清洗版 docs/gtnh-dev-guide.md（57ba040）为准，两者人工同步。
- GTNH 开发指南对照评估已完成（构建/依赖配置符合、无需改动），未来不必重查。
- 隐私规约：代理端口、127.0.0.1、/home/ 本机路径不入库；会话总结类文件不入库（本文件 untracked）。
- 「上游对比」剩余差距表按本文件第九节开工，不要按更早会话的口头旧表（三项差距已实现关闭）。
- 附属 App 永远免费且不被商店过滤；api/ 目录零改动。

## 十一、遗留待办（按优先级）

1. **P1 browser 渲染修复收尾**：改动未提交（ce97b1f 之上，version 已升 1.0.1-beta.5）→ 游戏内验证（进存档点「览」，看 `texture probe ... alpha0=N%` 日志与页面是否上屏）→ 提交发布 beta.5。
2. **等用户游戏内手测 beta.2 的 17 条清单**（主 mod release 正文：商店 5/快捷键 2/HUD 3/回归 3/浏览器 2/百科 1/通用 1）+ 看门狗 v3 验证（第三节）。
3. **music v0.3.0 jar 部署到实例**（现仍是 a8a5b19 旧版），顺带服务端 mods 四件套均落后，注意配套更新。
4. **主 mod 3 个提交未推 origin**（bbb1220/f05f94c/39745e7），下会话确认后 push。
5. P2 小项：三附属 libs pin 的 mcphone jar 实为 master.20（API 无变化，顺手可升）；lang 死键 home_drag_hint/playtime_hint 可清；SERVER_TASKS 无界队列；先扣物后 unlock 静默失败；wiki stuckSince 不复位、textureId 反射失败每帧 stderr；G 键 HUD 开关与 GuideNH 的键位冲突需确认。
6. 若启动 v1.0.4：优先级推荐 多页主屏★★ → 终端卡槽★★ → EMC 定价★+Baubles★ → 聊天图片分片★★；任务书附属先做入口类调研再立项。
7. EMC/等价交换仅留接口（api/store/IAppPriceProvider SPI）未接（并入 #6）。
8. 会话归档后若上下文丢失：本文件 + memory 的 [[gtnh-mcphone-family]]、[[delegate-investigation-to-subagents]]、[[wsl-windows-tooling-quirks]] 三条记忆可恢复大部分上下文。
