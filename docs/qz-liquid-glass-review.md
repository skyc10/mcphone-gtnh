# t6 对抗性质量门评审：MCphone 液态玻璃改造（Qz 玻璃踩坑 + 默认开启与配色体系自洽性）

> 评审任务 **t6**（kind=review，round 1，attempt 1，attempt_id `66aa7dd2-cc52-4ee5-871a-c42ebd3e84bd`）· 评审者 **review**
> 仓库 `<repo>` · `git rev-parse HEAD` = `a328673ccddd5cd814c56be94119d18131f455a4`（评审期间未变）
> 被评审：t2（`client/enhance/PhoneGlass.java` 新增 718 行、`PhoneCanvas.java` +81、`api/PhoneWidgets.java` +102/-9、`MCphone.java` +6/-1）、
> t3（`client/hud/PhoneHud.java` 517 行、`client/ClientHooks.java` 净 -5）、t4（`client/scene/PhoneUi.java` +197、`client/apps/ScenePages.java` +190）
> 依据：`docs/qz-liquid-glass-design.md`（t1，88561 B / 1014 行）§1/§2/§5/§6/§8/§9 + `docs/qz-liquid-glass-verification.md`（t5）
> inScope：`mcphone-gtnh/docs/`（本文件）。**未改实现代码**（`src/` 零改动，见 §11 自证）。
> 方法：直接读 diff/源码核对行号（`git diff`、`git log`、`git status`）；Qz 侧读 **官方 4.9.1 sources jar**（`<qz-artifacts>/qz_uilib-4.9.1-sources.jar` 只读解包到 `/tmp/t6qz`）与
> 本地只读克隆 `<Qz-UILib>`；**不采信实现者自述**。所有数值均由本任务自写脚本从 Qz 源常量重算，不引用 t2/t4 的结论。

## 0. 结论

| 项 | 结论 |
| --- | --- |
| verdict | **needs_revision** |
| 契约 verify 命令 | 两条均通过（§10） |
| 10 项重点 | **6 项通过 / 3 项部分通过 / 1 项不通过**（§9 逐条给结论与行号） |
| high / blocker | **0 条**：无崩溃、无加载失败、无静默假阳性被当判据使用、无玻璃被不透明底盖死 |
| medium | **6 条**（F1 档位钳反 + F2 §9.3 数值体系不自洽 + F3 状态栏/导航条墨色偏深 + F4 主面板/内容底板底色实现偏低 + F5 HUD 不跟随玻璃设置 + F8 默认开启代价无游戏内可观测量） |
| low | **7 条**（javadoc 漂移、死表、缩放口径、可发现性、附属 API 条件性回退、诊断零调用点、重复 javadoc） |
| 是否阻断 t7 | **是（需先对账数值）**：t7 若照 `design §9.3` 或 `_t4_evidence.md` 核对，会把「本来就对」判成错、也会漏掉 F4/F3。t7 的参考值必须是 §8 那份（本任务独立复算）。 |

**核心事实（一句话）**：玻璃链路本身是**真声明式**的（`SceneNode.setBackdrop` + 回放器门面），装饰器穿透这条最危险的静默失败**已被 t3 的迁移消除**；
问题集中在**数值体系的三处不同源**（design §9.3 ↔ `PhoneGlass` ↔ `_t4_evidence.md` ↔ 实现）与**两处实现偏离裁定**，属"实现质量门"而非"能不能用"。

---

## 1. 踩坑① 层次契约（BACKDROP 在 BACKGROUND 之前 / 未被不透明底覆盖）

**Qz 侧机制（本任务独立确认，非引用文档）**：`/tmp/t6qz/club/heiqi/uilib/ui/scene/paint/ScenePaintReplayer.java:145-161` 是 `case BACKDROP`（`UiRenderBackends.backdropFilter(...)`），
`:163-181` 是 `case BACKGROUND`（`ctx.fillRect` / `ctx.drawSurface`，即节点自身底色）。命令数组由 `ScenePaintEngine` 按「BACKDROP → BACKGROUND → BORDER」产出，顶层 switch 顺序即执行顺序。
底层混合：`UiRenderContext.fillRect` 用 `GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA`（标准 src-over）；`shader/uiBackdropF.frag:283-284` 输出 `alpha = mix(coverage, blurred.a*coverage, sourceAlphaPass)`，
interior 处 `coverage=1` ⇒ **玻璃层自身是"不透明的模糊背景"** ⇒ 节点底色 `a` 之后成为该像素上**仅有的**背景遮罩，即文档 §2.1 的 `(1-a)` 衰减模型。

| 面 | 载体与行号 | 结论 |
| --- | --- | --- |
| 主面板 | `PhoneUi.java:349-365`（`glassify(panel, PANEL)` :355，`setClipChildren` :358） | ✅ 玻璃与底色都挂在 panel 自身，顺序由 Qz 保证 |
| 状态栏 | `PhoneUi.java:395-428`（`glassify(statusBar, STATUS)` :402，文字色 `PhoneTheme.text()` :405/:421） | ✅ |
| 内容底板 | `PhoneUi.java:430-440`（`glassify(contentSlot, PAGE)` :438） | ✅ |
| 导航条 | `PhoneUi.java:442-455`（`glassify(homeBar, STATUS)` :449） | ✅ |
| 主屏网格底衬 | `PhoneUi.java:517-548`（`glassify(grid, CARD)` :547） | ✅ |
| 卡片/信息行 | `ScenePages.java:75-80`（`applyCardSurface`：圆角 + 底色 + `PhoneGlass.apply`） | ✅ 6 处调用点全部走同一入口 |
| 按钮 | `PhoneWidgets.java:196-223`（`glass` 分支设底 + `apply(btn, BUTTON, forcedTier)`） | ✅ |
| HUD 面板 | `PhoneHud.java:260-282`（内容根 = 定尺寸盒 + `PhoneUi.hudRoot()`） | ✅ 宿主 `chrome(false)`（`PhoneHud.java:209-219`），无 63% 纯黑外壳；实测 `SceneHudHost.java:328` 才用 `HUD_SHELL_BG` |

**近不透明底残留（验收⑨的 grep 依据）**：`grep -rnE '0x[0-9A-Fa-f]{2}[0-9A-Fa-f]{6}' src/main/java/com/november/mcphone/client/{scene,apps,api}/` 命中 40 行，
逐行归类后**没有一处把玻璃盖死**：

- 玻璃面底色全部经唯一入口（实测 alpha 0x23–0x8D，最大值即 THICK/PANEL 的 0x8D = `PhoneUi.GLASS_SURFACE_ALPHA_MAX`，且 `PhoneUi.java:130-139` 有 `>0x8D` 抛断言）；
- ≥0xAA 的命中只有三类：① `PhoneUi.java:79 COL_BORDER=0xFF39404B`（倒角载体边框，§9.3 明确保留）；② accent/状态色（`ScenePages.java:335/:792/:949/:982/:1105/:1154`、`PhoneWidgets.java:159-162 BTN_PRIMARY_BG/HOVER`）；③ §8/§9.3 明确"保留实心"的三处（`ScenePages.java:344/:348` TextArea/长文本、`:536-537` 图片底衬 `0xFF101418`、`:564` 图片 `0xFF000000`），三处均带 `【刻意保留实心】` 注释；④ `BuiltinApps.java` 12 个 App 图标 accent 色（`git diff -- BuiltinApps.java` 为**空**，确认 0 diff）。
- 旧三常量已物理删除：`COL_BG/COL_STATUS_BG/COL_PAGE_BG` 仅剩注释引述（`PhoneUi.java:52/:92/:93/:354` 等），`COL_PANEL` 在 `scene/apps/api` 内已无定义（`ChatUi.java:36` 的定义属 §8 本轮不动的文件）。

**判定：通过。**

---

## 2. 踩坑② 静默失败可判定性（重点：HUD 的 `PaintContextCompositor` 装饰器）

这是文档 §5.1 标注的"反复踩的静默降级"。三条独立核查：

1. **mcphone 是否还在自建渲染后端？** `grep -rn 'instanceof UiRenderContext\|UiRenderContext' src/main/java/` 在 `src/` 内命中 **3 行，全部在 `PhoneGlass.java`**（:119 注释、:201 探测字符串、:208 注释）——**零个 `instanceof`**。
2. **t3 之前的老路径**（文档 §5.1 点名的 `PhoneHud.java:224` 传裸 `UiRenderContext`）**已整体删除**：`PhoneHud.java` 现在只做内容树 + tick 交互，渲染宿主改为 `ClientHudService.register(spec, this::buildContent)`（`PhoneHud.java:220`）；`ClientHooks.java` 的 `onRenderHudPost` 与 Forge 总线注册已删（diff 确认）。
3. **宿主确实会包 scaled**：官方 4.9.1 `SceneHudHost.java:213` `window.frame(backend.scaled(item.scale), …)`；而玻璃走的是 **scene 回放器**（`ScenePaintReplayer:145-161` → `UiRenderBackends.backdropFilter` → `resolveContext()` 解 `ScaledRenderBackend`）⇒ **装饰器穿透按设计成立**。
   旁证：`UiHudRenderListener.java:123` 确实给 HUD 帧传指针 `(0,0)`（踩 HUD 型 §5.6 的静态 -55° 光源，见 §5）。

**判定：通过（原风险已被架构性消除）。**
**但**：可判定性在**游戏内不可读**——`grep -rn 'compositeAlpha\|neutralSurface\|renderPath()\|diagSummary\|refreshDiagnostics' src/main/java/` 的**调用点为零**（命中 12 行全部是 `PhoneGlass.java` 自身的定义/注释），独立复现 t5 的 F4。
设计 §3.3 要求"F3 覆写或设置页一行 `diagSummary()`，值变才打印"，未落地。见 **F9（medium）**。

---

## 3. 踩坑③ `translatedBy` 平移漏传（拖动后玻璃是否留在原地）

- mcphone 侧**没有任何**自定义平移/二次绘制路径：`PhoneUi.java:954-959` 的 `render(...)` 覆写只做 `flushPendingActions()` + `super.render(...)` + `checkScissorLeak()`，没有自己减偏移；`PhoneHud.panelOrigin` 已删（diff），放置交宿主 `HudLayoutResolver/SceneAnchorResolver`。
- 玻璃矩形随 fragment 平移由 Qz 负责；HUD 拖动每帧改 `PhoneCanvas.setHudOffsetX/Y`（`PhoneHud.java:495-500`）⇒ 每帧走 `translatedBy`——**正是这条踩坑的高频路径**。
- 本任务无法在静态层面证明"平移后玻璃仍通过"（需要运行期像素级观察）。

**判定：通过（静态层）/ 必须用户手测**（手测项 H6：拖动 HUD 与主屏图标拖拽后玻璃是否跟随、是否消失）。所需证据：游戏内 GIF/截图两帧对比。

---

## 4. 踩坑④ 底色 alpha 与材质档是否配套重定（含前后色值体系）

### 4.1 独立复算（不引用实现者结论）

本任务自写脚本从 **官方 4.9.1 sources jar** 读 `UiGlassMaterial` 常量并重算：`DARK_ULTRA_THIN=0x1A(26)`、`DARK_THIN=0x26(38)`、`DARK_REGULAR=0x33(51)`、`DARK_THICK=0x4D(77)`（与 `getTintAlpha()` = `((tintArgb>>>24)&0xFF)/255f`，`UiGlassMaterial.java:116-118` 口径一致）。
按实现公式 `T = a + t·(1-a/255)` 对 24 个（档×角色）格子重算，与 §9.3 声明值对账：

| 档 | 角色 | 实现节点色 | 合成 T | §9.3 声明 | 差 |
| --- | --- | --- | --- | --- | --- |
| THIN | PANEL | `0x3C14181C` | 89.1 | 0x59 | +0.1 ✅ |
| THIN | PAGE | `0x3C14181C` | 89.1 | 0x59 | +0.1 ✅ |
| THIN | STATUS | `0x2514181C` | 69.5 | 0x40 | **+5.5（偏深）** |
| THIN | CARD / BUTTON | `0x5A14181C` | 114.6 | 0x73 | −0.4 ✅ |
| THIN | BUTTON_HOVER | `0x7E14181C` | 145.2 | 0x8C | **+5.2（偏深）** |
| ULTRA_THIN | PANEL / PAGE | `0x4514181C` | 88.0 | 0x40（文档 ULTRA 行） | **+24（与 THIN 同实）** |
| ULTRA_THIN | CARD / BUTTON | `0x6314181C` | 114.9 | 0x73 | −0.1 ✅ |
| ULTRA_THIN | BUTTON_HOVER | `0x8814181C` | 148.1 | 0x8C | **+8.1** |
| REGULAR | PANEL/PAGE/CARD/BUTTON | `0x6F14181C` | 139.8 | 0x8C | −0.2 ✅ |
| THICK | PANEL/PAGE/CARD/HOVER | `0x8D14181C` | 175.4 | 0xAF | +0.4 ✅ |

**结论：11/24 落在 ±1/255；THIN+STATUS/HOVER、ULTRA 行的 PANEL/PAGE/HOVER 与声明值差 5–24/255。** 详见 **F1/F2/F3**。

### 4.2 「底色 alpha 是节点色还是合成遮罩」——实现与裁定口径不一致（**F4，medium**）

文档 §9.2/§9.3 把「底色 alpha 档」列成 **`0x59`/`0x40`/`0x73`/`0x8C`**，§9.4 的动作是「删除 `COL_BG`（节点底色常量），替换为 `0x59`」⇒ 裁定值是**节点自身底色的 alpha**。
把该值代入 Qz 的真实合成（`T = a + t(1−a/255)`，机制见 §1）得到的是**总遮罩**：

| 面 | 裁定 a（节点色） | 由此得 T | 实现节点色 | 实现 T | 背景被保留的比例（裁定 vs 实现） |
| --- | --- | --- | --- | --- | --- |
| 主面板 PANEL | 0x59 | 0x76 (46%) | **0x3C** | 0x59 (35%) | 59% vs **76%** |
| 内容底板 PAGE | 0x59 | 0x76 | **0x3C** | 0x59 | 59% vs **76%** |
| 状态栏 STATUS | 0x40 | 0x60 (38%) | **0x25** | 0x45 (27%) | 75% vs **85%** |
| 卡片/按钮 | 0x73 | 0x89 | 0x5A | 0x73 | 55% vs 55% ✅（巧合：a 与 T 在该档接近） |
| hover | 0x8C | 0x9D | 0x7E | 0x91 | 45% vs 45% ✅ |

⇒ 主面板/内容底板**比裁定值多透出约 17 个百分点的世界背景**（23.5% 不透明 vs 裁定 35%）；状态栏多透出 10 个百分点。
实现内部是自洽的（`PhoneGlass.java:173-181` 的注释与 :183-190 的表、`PhoneGlass.compositeAlpha()` 都按「T 是合成总遮罩」推导），
**但与 §9.3/§9.4 的字面值不一致**：照文档核对的人会看到 `0x3C≠0x59`，照实现核对的人会看到 `T=0x59 ✔`。两者必居其一需要改，见 requiredFix。

### 4.3 前后色值体系（验收④要求给出）

| 面 | 改动前 | 改动后（AUTO=ULTRA_THIN，会话首个真实默认） | 改动后（AUTO=THIN，真渲染后） | 非玻璃态 |
| --- | --- | --- | --- | --- |
| 主面板 | `0xF20E1116`（95%） | `0x4514181C` | `0x3C14181C` | `0x1FF2F5F8`（12% 中性浅） |
| 内容底板 | `0x900E1116`（56%） | `0x4514181C` | `0x3C14181C` | `0x4C14181C`（30% 深底） |
| 状态栏 | `0x99000000`（60% 纯黑） | `0x2E14181C`\* | `0x2514181C` | `0x1FF2F5F8` |
| 导航条 | 无 | `0x2E14181C`\* | `0x2514181C` | `0x1FF2F5F8` |
| 卡片/信息行 | `0x33FFFFFF`（20% 白） | `0x6314181C` | `0x5A14181C` | `0x1FF2F5F8` |
| 按钮 | `0xFF3A414D`（100%） | `0x6314181C` | `0x5A14181C` | `0xFF3A414D`（沿用旧值） |
| 按钮悬停 | `0xFF4A5462` | `0x8814181C` | `0x7E14181C` | `0xFF4A5462` |
| 主操作按钮 | `0xFF2F5FA8` | 保留实心 | 保留实心 | 同左 |
| 边框 | `0x55FFFFFF` | 保留 | 保留 | 保留 |

\* 当前实现下**不可达**（被 F1 反向上钳为 THIN，得 `0x2514181C`）。

**判定：部分通过**（体系已重定、旧 ⩾95% 遮罩确实淘汰；但节点值与裁定值不同源，见 F4；状态栏/hover 偏深见 F3；状态栏"极薄"不可达见 F1）。

---

## 5. 踩坑⑤ 圆角（倒角载体）与缘带宽度

- Qz 侧圆角属节点自身（`SceneNode.setCornerRadius(int)`，源码 javadoc：uniform 像素语义；`SceneNode.java:1105-1116`），玻璃矩形沿用节点圆角（`ScenePaintReplayer:149-155` 从命令取 cornerRadius）。
- **缘带宽度按短边比例、峰值内移 0.35·band**（文档 §5.4 引 `ChatMarkdownSettings.java:132-135`）；折射位移上限 `min(6+40·lens, panelShortHalfPx·0.8)`（本任务在 `UiBackdropFilterRenderer.java:331-334` **源码确认**）。
- 实现取值：面板 22（`PhoneUi.java:85 PANEL_RADIUS`）、按钮/卡片 12（`PhoneGlass.java:159-160`）、图标盒 `box/3`（`PhoneUi.java:642`，**不上玻璃**）、实心面不变。
- 与拖拽/命中：`PhoneUi.java:606-728` 的命中判据只看 `SceneGeometry.absoluteBox` 与 10px 阈值（`:688`）——`git diff` 确认该段**零改动**，玻璃是 PAINT 级属性（`setBackdrop` 不改几何），与 t4 自述一致。

**判定：圆角按"绝对值 + RADIUS_MIN 下限"落地，符合 Qz 语义；但"按短边比例"这一条本轮未做（固定 12/22）。**
风险点：Qz 自家聊天把 12 提到 20 的理由是"小面板上 12px 读作几乎直角"（§5.4 原文），而 HUD 面板逻辑宽可低到 200 ⇒ 22px 在 200px 宽上占比 11%，缘带可挂；但**按钮在 200px 宽面板下宽约 60–90px、圆角 12px**，属"小面积"区，缘带是否可见**只能手测**。
**判定：通过（口径合规）/ 观感必须用户手测**（手测项：THIN 与 ULTRA_THIN 下按钮/卡片的缘带是否可见）。

---

## 6. 踩坑⑥ 布局误差归因（透明化暴露的既有族）

t3 交付的旧↔新等价性（本任务复核其数学口径，不跑游戏）：

| 观察 | 归因 | 依据 |
| --- | --- | --- |
| 1080p、默认 60% 下终尺寸与旧版一致 | **无回归** | 旧：`h=round(1080·0.62)=670 → clamp[320,1100] → ·0.6 = 402`；新：`designH=670 → designW=375 → 宿主 ·0.6 = 402`（`PhoneHud.java:305-311`）。逐像素一致（t3 自报 (24,339,224,401)，本任务不重复其数值） |
| 缩放 40–49 被宿主夹到 50 | **行为变更（t3 已自报）** | `HudScaleState.MIN_PERCENT=50`（官方 4.9.1 sources 确认）+ `setPercent` 内部 `Math.max(50, …)`；`PhoneHud.java:195-203` 每次 tick 下发 PhoneCanvas 值 ⇒ 旧配置 40 ⇒ 实际 50 |
| 绝对闸门 `[320,1100]/[200,620]` 改在 100% 设计尺寸上生效 | **口径变更（t3 已自报）** | `PhoneHud.java:305-311`；旧实现是"先乘缩放再夹"。**4K 低倍率下终尺寸可大于旧上限（可达 ~2.4 倍线性）** ⇒ 必须手测 |
| 小屏（≈480p 高）低倍率下可小于旧下限 | **回归候选** | 480 高 ⇒ designH=320（下钳）、designW=200（下钳）；50% ⇒ 终尺寸 160×100 物理像素，旧实现的下限是 320×200 |
| 命中盒与渲染盒的镜像 | **部分通过（残余风险）** | `PhoneHud.java:405-463` 逐位复刻 placeAndFrame；但 `SceneHudHost.currentSafeInsets()`（源码 :264）在 mcphone 侧读不到（宿主实例不外露）⇒ 其它 mod `registerAvoidance` 撑大的安全区会让"看到的框≠点得到的框"（§7.2 已登记、§8-9 明确本轮不修） |
| 面板被写 `0x3C` 后世界透出来 | **非布局族**（是 §4.2 的数值问题，不是布局误差） | — |

**判定：部分通过。** 归因明确、t3 已自报两条口径变更；但小屏低倍率的尺寸回归与 4K 高倍率的上限扩张**都还没有人实测**，必须进 t7 手测（H6）。
**F2（low）**：40–49 的静默夹取需要可发现性（旧配置用户在首帧就看到"缩放 40 显示 50"而无任何提示）。

---

## 7. 重点⑦ 「默认开启的每帧代价是否有可观测量与降级阶梯」+ 宿主级模糊是否被无意打开

- **液态玻璃确实默认开启**：`PhoneCanvas.isGlassEnabled()` 默认 `"true"`（`PhoneCanvas.java:343-345`，缺键/键为 true 都开）、默认档 AUTO（`:364-367`）——符合用户裁定（不以"默认应关闭"判不通过）。
- **宿主级模糊没有被无意打开**：`grep -rn 'BackdropBlur|hostBackgroundBlur|Policy' src/main/java/` 在 mcphone 内**零实现命中**（唯一命中是 `PhoneGlass.java:12` 的注释与一个无关的 `-ExecutionPolicy` 字符串）；Qz 侧默认 `hostBackgroundBlurEnabled = false`（`BackdropBlurConfig.java:47`），**没有任何 mcphone 代码把它打开**。✅
- **降级阶梯**：AUTO 会按 `renderPath()` 解析档位（`PhoneGlass.java:344-359`），叠加 Qz 自带的 SHADER→FIXED_PIPELINE→TINT_FALLBACK 自动降级；用户侧有 `glassEnabled` 开关（逃生舱）。✅
- **可观测量：缺**。`getLastBackdropFilterRenderPath()/detail()` 在游戏内**没有任何出口**（F9），F3 覆写/设置页诊断行/状态变化日志三者都未实现；也没有帧时间统计。唯一能读到三态的是 t5 的**离线 harness**，玩家/手测者读不到。
  另外 §6.3 的 L1/L2 处置要求逐页下发 `BackdropBlurPolicy()`（`:61`/`:93`），mcphone 侧没有一个旋钮接到它（§1.7 的 policy 入口只在 `UiHostRenderSupport.createRenderContext` 上）。用户手里实际只有"档位/强度/开关"三档，**没有"性能预设"**。

**判定：不通过（medium，F8）**——判据是"有可观测量与降级阶梯"，阶梯有（自动降级 + 开关 + 档位），**可观测量没有**。requiredFix 见 F8（最小成本：F3 覆写下加一行 + 值变才打印；不含"默认关闭"这类违背裁定的解法）。

---

## 8. 能力探测桥（崩溃安全网）与附属 API

### 8.1 探测桥（验收⑧）

- 实现：`PhoneGlass.java:235-291`（一次性 static + volatile 缓存）、`:67-72` 的 `probe()` 用 `Class.forName(..., false, cl)` + `getField("DARK_THIN")`，**整个 `try { … } catch (Throwable t) { return ABSENT; }`**⇒ `ClassNotFoundException` / `NoClassDefFoundError` / `ExceptionInInitializerError` 全落静默回落；`:304-320 apply()` / `:323-328 clear()` 也各自 `catch (Throwable)`。
- 所有入口的守卫是 `available() && PhoneCanvas.isGlassEnabled()`（`:231-233`），且 `PhoneCanvas` 只在 `available()` 为真时被触碰（短路）——与 t5 的负控结论一致（本任务静态确认）。
- **定位正确**：探测桥**不是**兼容 4.8 的手段（`MCphone.java:33` 的 `[4.9.0,)` 让 4.8.x 在 FML 排序期直接 `MissingModsException`，早于任何 mcphone 代码），文档 §4.6 的口径与实现一致。

**判定：通过。**

### 8.2 附属 API（验收⑩）

对照 `docs/addon-api.md`（§二核心类型 / §三最小示例 / §七注意事项）：

| 检查 | 结果 |
| --- | --- |
| `PhoneWidgets.TEXT=0xFFE8EDF2` / `MUTED=0xFFB8C4D0` / `PANEL=0x33FFFFFF` / `BORDER=0x55FFFFFF` 逐值未变 | ✅（`PhoneWidgets.java:29-32`，diff 只改注释） |
| 既有公开方法签名零变更（`card/button/primaryButton/infoRow/scrollColumn/title/image/…`） | ✅（diff 只新增 `glassCard`×2、`glassButton`、`glassText`、`glassMuted` 与 2 个私有常量） |
| `button/primaryButton/card` 在玻璃关闭/类缺失时**逐字沿用旧值** | ⚠️ **条件性**：`card()` 回退 `PANEL + 8px`（`PhoneWidgets.java:88-101`）、`button()` 回退 `BTN_BG/BTN_BG_HOVER + 8px`（`:196-223`）成立；但**玻璃开启时**，附属 App 的 `card/button` 底色被换成玻璃令牌（`0x3C`/`0x5A` 深色 + 12px 圆角），而附属 App 的文字仍用**自己的** `PhoneWidgets.TEXT`（浅色）——这正是 F7 的对比度问题，也是"公开 API 语义只新增不改"的一处实际语义变化。见 **F7（low/medium）** |
| `docs/addon-api.md` 是否需要同步说明（`iconColor` 是 accent、`card` 现在会挂玻璃） | 未同步（本轮 inScope 不含该文件）；建议下一轮补一行 |

**判定：通过（签名与常量不变）/ 语义变化已登记为 F7。**

---

## 9. 10 项重点逐条结论

| # | 项 | 结论 | 关键证据（行号） |
| --- | --- | --- | --- |
| ① | 层次契约（BACKDROP 先于 BACKGROUND、逐面确认） | **通过** | `ScenePaintReplayer.java:145/163`；`uiBackdropF.frag:283-284`；`PhoneUi.java:355/:402/:438/:449/:547`；`ScenePages.java:75-80` |
| ② | 静默失败可判定性（HUD 装饰器） | **通过**（风险被架构消除）；可判定性本身见 F9 | `grep` 零 `instanceof UiRenderContext`；`PhoneHud.java:220`；`SceneHudHost.java:213` |
| ③ | `translatedBy` 平移漏传 | **通过（静态）/ 需手测** | `PhoneUi.java:954-959` 无自绘；`PhoneHud.java:495-500` 每帧改偏移 |
| ④ | 底色 alpha 与材质档配套重定 | **部分通过**（见 F2/F3/F4） | §4.1 复算表；`PhoneGlass.java:183-190` |
| ⑤ | 圆角按短边比例、缘带宽度 | **部分通过**（绝对值 + RADIUS_MIN 合规；"按比例"未做；缘带可见性需手测） | `PhoneGlass.java:156-160`；`PhoneUi.java:85/:642`；`UiBackdropFilterRenderer.java:331-334` |
| ⑥ | 布局误差新旧归因 | **部分通过**（归因清晰；小屏/4K 尺寸偏差未实测） | `PhoneHud.java:305-311`；`HudScaleState.MIN_PERCENT=50` |
| ⑦ | 默认开启的每帧代价 / 宿主级模糊 | **不通过**（可观测量缺失；宿主级模糊确认未被打开） | `PhoneCanvas.java:343-345`；`BackdropBlurConfig.java:47`；F8/F9 |
| ⑧ | 能力探测桥（崩溃安全网） | **通过** | `PhoneGlass.java:235-291/:304-328`；`MCphone.java:33` |
| ⑨ | 配色体系自洽（对比度/材质档配文字/实心残留/旧配色淘汰） | **部分通过**（旧配色确实淘汰、实心面有注释与 grep 依据；但 CARD 非玻璃态与文字色/对比度有 3 处问题） | §1 的 grep 归类；F1/F2/F3/F7 |
| ⑩ | 附属 API 兼容 | **通过**（签名/常量不变；语义变化登记为 F7） | `PhoneWidgets.java:29-32/:88-101/:196-223` |

**兼查：依赖下限与 README** ✅
- `MCphone.java:33` = `required-after:qz_uilib@[4.9.0,)`（契约要求，实测一致；实例/服务端 qz_uilib=4.9.1 满足）。
- `README.md:17` 与 `README.en.md:20` 均为 **4.9.0+**，中英一致（`grep -c '4\.9\.0+'` = 1/1，`4.8` 只出现在"4.8.x 不含"的说明性文字里）。

---

## 10. 契约 verify 命令（逐条）

```
$ grep -rn 'UiBackdrop\|UiGlassMaterial\|UiBackdropEffect\|BackdropBlur' <repo>/src/main/java/
→ 命中 11 行，全部在 client/enhance/PhoneGlass.java（:11 :12 :17 :197 :198 :199 :208 :316 :381 :467 :499）；rc=0
$ grep -n 'qz_uilib@' <repo>/src/main/java/com/november/mcphone/MCphone.java
→ 33:     dependencies = "required-after:qz_uilib@[4.9.0,)")；rc=0
```

两条均 **passed**（隔离契约成立；依赖下限正确）。

---

## 11. 未改实现代码自证 + 硬约束

```
$ git status --porcelain -- src
 M src/main/java/com/november/mcphone/MCphone.java
 M src/main/java/com/november/mcphone/api/PhoneWidgets.java
 M src/main/java/com/november/mcphone/client/ClientHooks.java
 M src/main/java/com/november/mcphone/client/PhoneCanvas.java
 M src/main/java/com/november/mcphone/client/apps/ScenePages.java
 M src/main/java/com/november/mcphone/client/hud/PhoneHud.java
 M src/main/java/com/november/mcphone/client/scene/PhoneUi.java
?? src/main/java/com/november/mcphone/client/enhance/PhoneGlass.java
```

- 与本任务开工时**逐字一致**（改动面全部来自 t2/t3/t4）；本任务只新增 `docs/qz-liquid-glass-review.md`。
- `git -C <Qz-UILib> status --porcelain` = 空（只读，未改）。
- 未启动游戏客户端；未 push / 未打 tag / 未发版；未手改 `.agent-teams/`。
- 临时脚本全部落在工作区 `<workspace>\_*.sh|_*.py`（用完即删），仓库内无残留。
- 独立复算脚本（只读 Qz 官方 sources jar）逻辑随本报告 §4.1 一并给出数值，可在 WSL 内重跑。

---

## 12. findings（可执行 requiredFix）

> **t8 repair-round-2 落地状态（逐条）**：F1 ✅（`PhoneGlass.fullness()` 替换 ordinal 比较）、
> F2 ✅（裁定"以实现为准"：design §9.3 表头改「节点底色 a（渲染值）」+ 新增「合成总遮罩 T」列、§9.4 P1–P5 改写为反解节点色）、
> F3 ✅（`SURFACE_ALPHA[THIN][STATUS] 0x25→0x23` + javadoc + `_t4_evidence.md`）、
> F4 ✅（同 F2，§9.3/§9.4 + javadoc 对照表已同步）、F5 ✅（javadoc 表逐行修正 + 口径说明）、
> F6 ✅（`_t4_evidence.md` §1 加"仅供追溯"行 + 3 处计算错误修正；工作区文件，不入 git）、
> F7 ✅（`docs/addon-api.md` §二/§七 各加玻璃配色与对比度约定）、
> F8 ✅（日志出口：`PhoneGlass.apply()` 成功后值变才打印 `[mcphone] glass: …`；**游戏内 UI 出口本轮不做**，
> 已在 design §3.3 标"未落地"并在 §8 登记为下一轮项）、F9 ✅（同 F8 出口；§3.3 已标注）、
> F10 ✅（`PhoneHud.onGlassSettingsChanged()` + `PhoneUi.refreshGlassShell()` 内调用；不改 ScenePages，因不在 t8 inScope）、
> F11 ✅（`PhoneHud.syncScale()` `<50` 一次性提示 + design §7.3 改「已统一：下限 50/上限 150（mcphone 侧）」；
> **"把 PhoneCanvas 下限提到 50"这条替代路不可用**——PhoneCanvas.java 不在 t8 inScope）、
> F12 ✅（`PhoneUi.java` 重复 javadoc 行已删）。

### F1（medium）`capFor` 用 ordinal 比较档位，`Tier` 序号非单调 ⇒ 状态栏/导航条的"极薄"被反向上钳为薄

- 位置：`src/main/java/com/november/mcphone/client/enhance/PhoneGlass.java:362-368`（`capFor(Role,Tier)`）、`:79-101`（`Tier` 定义）、`:183-190`（`SURFACE_ALPHA`）、`:367-379`（`capFor(Role)`）。
- 事实：`Tier = AUTO(0), THIN(1), ULTRA_THIN(2), REGULAR(3), THICK(4)`；`capFor(STATUS)=THIN`；判据是 `tier.ordinal() > cap.ordinal() ? cap : tier` ⇒ 请求 `ULTRA_THIN(2) > THIN(1)` 被改成 **THIN**（"更薄"变成"更厚"）。
- 本任务独立复核：静态可证（上述 4 处行号 + 枚举序号），且与 t5 的 F1 结论一致。
- 后果：① `SURFACE_ALPHA[ULTRA_THIN][STATUS]=0x2E` 与 `[ULTRA_THIN][PAGE/CARD/BUTTON/HOVER]` 之外的表行为死值；② 档位 chip 里的"极薄"**对状态栏/导航条无效**；③ AUTO 在降级路径（`FIXED_PIPELINE`/`TINT_FALLBACK` ⇒ `ULTRA_THIN`）对状态栏不会变薄，与 §6.3 的 L1/L2 意图相反；④ 设计 §6.2 写死状态栏 = `DARK_ULTRA_THIN`/blur 6/lens 0.3，当前实际是 `DARK_THIN`/blur 8/lens 1.0（`PhoneGlass.java:382-424`）。
- requiredFix：在 `PhoneGlass.java:362-368` 用**与枚举序号解耦的厚度序**替换 ordinal 比较，例如新增
  `private static int fullness(Tier t) { switch (t) { case ULTRA_THIN: return 0; case THIN: return 1; case REGULAR: return 2; case THICK: return 3; default: return 1; } }`
  并把 `capFor(Role, Tier)` 改为 `return fullness(tier) > fullness(cap) ? cap : tier;`（`capFor(Role)` 保持 STATUS→THIN）；同步 `SURFACE_ALPHA` 第 3 行注释与 §9.3 的状态栏文案。

### F2（medium）§9.3 的「裁定总遮罩」与 `PhoneGlass` 的 `SURFACE_ALPHA` 不是同一量，数值差 5–24/255

- 位置：`docs/qz-liquid-glass-design.md:909-921`（§9.3 表，状态栏 `0x40`、卡片/按钮 `0x73`、hover `0x8C`）、`:167-181`（`PhoneGlass` 反解表引用）、`PhoneGlass.java:183-190`。
- 事实：§9.3 的列名是「底色 alpha（新）」且 §9.4 的动作是"删除节点底色常量、替换为 `0x59`"⇒ 裁定值 = **节点自身底色**；而实现的 `SURFACE_ALPHA` 存的是**合成总遮罩 T**（`:42` 注释、`compositeAlpha()` 口径）。
- 本任务独立复算（§4.1）：THIN+STATUS 差 +5.5、THIN+HOVER 差 +5.2、ULTRA+PANEL/PAGE 差 +24、ULTRA+HOVER 差 +8.1、REGULAR/THICK 的 STATUS 差 ±2/−1，其余 ±1 内。
- 后果：§9.3、`PhoneGlass` javadoc、`_t4_evidence.md` 三份数值互不同源，t7 照任一份手测都会误判。
- requiredFix（**二选一，必须只留一份真值**）：
  1. **以裁定为准**：`PhoneGlass.java:183-190` 的 `SURFACE_ALPHA` 改成节点底色值（PANEL/PAGE `0x59`、STATUS `0x40`、CARD/BUTTON `0x73`、HOVER `0x8C`，各档按 `a=(T·255−t·255)/(255−t)` 反解），并在 `docs/qz-liquid-glass-design.md:909-921` 表头把「底色 alpha（新）」改成「**节点底色 a（渲染值）**」、另加一列「合成总遮罩 T = a + t(1−a/255)」；
  2. 或以实现为准：`docs/qz-liquid-glass-design.md:167-181` 的「目标总遮罩 T」表与 `:909-921` 表的该列统一改写为 `T = a + t(1−a/255)` 的**合成值**，并把 §9.4 的替换动作改写为"替换为反解后的节点色（主面板 `0x3C14181C` …）"。
  两种改法都需同步 `PhoneGlass.java:35-48` 的 javadoc 对照表（见 F5）。

### F3（medium）状态栏/导航条的落地墨色比裁定深 5/255（且被 F1 钉死在 THIN）

- 位置：`PhoneGlass.java:186`（`SURFACE_ALPHA[THIN][STATUS]=0x25`）、`PhoneUi.java:401-402`、`:448-449`。
- 事实：THIN + STATUS 的合成 T=69.5（0x45/27%），§9.3 声明 0x40（25%）。设计 §9.3 对状态栏的理由是"条状小面低强度"。
- requiredFix：若 t2 的 `(T·255−t·255)/(255−t)` 反解口径保持不变，把 `PhoneGlass.java:186` 的 STATUS 列 `0x25` 改为按 T=0x40 反解得的 `0x23`（35 ⇒ 34.5）；同时把 `PhoneGlass.java:40` 的 javadoc 描述与 `_t4_evidence.md:26/:28`（该文件另有 3 处计算错误，见 F6）一并更正为 `0x2314181C`。

### F4（medium）主面板/内容底板的节点底色比 §9.3/§9.4 的裁定值低（更透）

- 位置：`PhoneGlass.java:185-186`（PANEL/PAGE 0x3C）、`PhoneUi.java:354-355`、`:436-438`。
- 事实：裁定节点值 `0x59`（35% 不透明），实现 `0x3C`（23.5%）⇒ 世界背景多透出约 17 个百分点（§4.2 表）。
- 判定依据：node 的 alpha 直接决定"背景被保留的比例"（`uiBackdropF.frag:283-284` 的 interior alpha≈1 + `UiRenderContext.fillRect` 的 src-over），两者相差 76% vs 59% 属**肉眼可辨**。
- requiredFix：与 F2 同一处改动；若决定保留实现值，则 `docs/qz-liquid-glass-design.md:911-913`（§9.3 手机主面板/内容底板/状态栏行）与 `:926-928`（§9.4 P1/P3）必须把 `0x59` 改成 `0x3C14181C` 并注明"节点底色口径（= 反解值），合成总遮罩见 T 列"。

### F5（low）`PhoneGlass` javadoc 对照表三行与实现不符（旧值/非玻璃态列正确）

- 位置：`src/main/java/com/november/mcphone/client/enhance/PhoneGlass.java:38/:40/:41/:43/:44`。
- 事实（逐值复核 `SURFACE_ALPHA` + `neutralSurface`）：
  - `:44`「按钮悬停 `0x8C14181C` 140 = 55%」→ 实现 `surface(BUTTON_HOVER,THIN)=0x7E14181C`，合成 T=145（含 `SURFACE_ALPHA[THIN][HOVER]=0x7E`）；t5 的 F3 与 t4 的证据表沿用了错值。
  - `:41`「卡片 非玻璃态 `0x33FFFFFF` 20% 白（沿用旧值）」→ `neutralSurface(CARD)` 实际返回 `0x1FF2F5F8`（`PhoneGlass.java:567-570` 只对 `Role.PAGE` 返回 `0x4C14181C`）；t4 自报的 R3 成立。
  - `:38`「主面板 玻璃态 `0x5914181C` 89 = 35%」→ 该行括号里确实是合成口径，但列名读起来像节点色，与 F4 同源。
- requiredFix：把 `PhoneGlass.java:44` 整行改为
  `* 按钮悬停      0xFF4A5462  255 = 100%    0x7E14181C 126 = 49.4%（合成 T=0x91/57%，刻意比 DARK 系裁定略实以保 hover 反馈）0xFF4A5462 100%（沿用旧值）`；
  把 `PhoneGlass.java:41` 的"非玻璃态（沿用旧值）"改为 `0x1FF2F5F8 12% 中性浅（neutralSurface(CARD) 实际返回值；上一版注释写的 0x33FFFFFF 只对旧实现成立）`；
  在 `PhoneGlass.java:42` 的括号说明后补一句「本表玻璃态一列是**反解后的节点底色**，'合成后 T' 另按 `T = a + t(1−a/255)` 计得，与 §9.3 的数值口径不同（见 review F2）」。

### F6（low）`_t4_evidence.md` 的色值表与实现不符（t7 不得作为参考值）

- 位置：`<workspace>\_t4_evidence.md:25-31`（§1 表）与 `:42`、`:50-51`。
- 事实（本任务复算）：
  - `:25` 主面板/`:27` 内容槽「合成后 T = 0x53」→ 用实现值 `a=0x3C`、`t=0x26` 精算得 **0x59**（0x53 只在 `a=0x4A` 时成立）；按 t4 自报的公式 `a + t·(1-a/255)`，其"合成后"列无法复现（偏浅 5–6/255）。
  - `:26`/`:28` 状态栏/导航条「合成后 T = 0x31」→ 精算 **0x45**（且当前引擎下拉不到，见 F1）。
  - `:29` 卡片「非玻璃态 `0x1FF2F5F8`」→ 正确（与 `PhoneGlass.neutralSurface` 一致），但 `PhoneGlass.java:41` 的 javadoc 写的是 `0x33FFFFFF`（F5）。
- requiredFix：在该文件 §1 表上方加一行「**本表色值仅供追溯，t7 参考值以 `docs/qz-liquid-glass-review.md` §4.3 为准**」，并把 `:25/:27` 的合成列改为 `0x59`、`:26/:28` 改为 `0x45`（或整表改用 review §4.3 的表）；它是工作区侧证据副本、不在仓库 inScope，改动不进 git。

### F7（low/medium）玻璃开启时附属 App 的 `card/button` 变玻璃面，但附属文字仍用 `PhoneWidgets.TEXT` 浅色

- 位置：`PhoneWidgets.java:88-101`（`card` 玻璃分支设深底 `0x3C/0x5A`）、`:196-223`（`button` 同理）、`:29-30`（`TEXT/MUTED` 未随档变化）；对照 `docs/addon-api.md` §三（最小示例里 `scrollColumn + title + infoRow + primaryButton`）。
- 事实：内置页面的文字走 `PhoneTheme.text()`（浅色，且是**用户可选的 6 套浅色**，`PhoneTheme.java:24-31`），卡片/按钮的玻璃底按裁定"只改底色、不改文字色"⇒ 最坏情况（浅色壁纸 + ULTRA_THIN）浅字落在亮雾底上，`docs/..._t4_evidence.md:111` 自估 ≈1.1:1、本任务按 alpha 合成复算约 2–3.5:1（均低于小字号 4.5:1 基线）。
- 判定：这是 **§9.2 的已知限制（设计层的必然结果）**，不是 t4 的实现缺陷——文字色与底色的配对规则是 t1 裁定的，t4 逐字实现。但"附属 App 的文字**不由** mcphone 控制"这一点在文档里没有写明，属**可发现性缺口**。
- requiredFix：在 `docs/addon-api.md` 的 §二/§七各加一句「玻璃开启时 `card/button` 的底色是玻璃令牌（深色、圆角 12）；附属 App 自定义文字请用 `PhoneWidgets.glassText()/glassMuted()` 或保证底色与文字成对」，并在 t7 手测清单里保留"浅色壁纸 + 小字号"项（H7）。

### F8（medium）默认开启的每帧代价**没有可观测量**（降级阶梯存在，观测出口缺失；宿主级模糊确认未被打开）

- 位置：`PhoneGlass.java:632-669`（诊断 API 有实现无出口）、`ScenePages.java:674-697`（设置页只有开关/档位/强度）。
- 事实：`grep -rn 'compositeAlpha|neutralSurface|renderPath()|diagSummary|refreshDiagnostics' src/main/java/` 的调用点为 **0**；`UiRenderContext.getLastBackdropFilterRenderPath()/Detail()` 在游戏内读不到；`BackdropBlurPolicy.performance()/compatibility()`（§6.3 L1/L2）没有任何旋钮接线。
- requiredFix：在 `ScenePages.java` 的显示分组里加一行只读诊断（文本 = `PhoneGlass.diagSummary()`），并在 `PhoneGlass` 里提供一个"值变才打印"的日志出口（例如在 `PhoneGlass.apply()` 成功后比较 `renderPathLabel()+diagDetail()` 与上次值，变化时 `System.out.println("[mcphone] glass: " + diagSummary())`）；两者都不改默认开启、不改观感。若本轮不做，则 t7 的手测清单里必须明写"帧代价只能靠 F3 目测，无量化出口"，并在 §8 登记为下一轮项。

### F9（low）三态诊断在 `src/` 内零调用点（§3.3 的"值变才打印 / F3 覆写"未落地）

- 与 F8 同源，t5 的 F4 确证；位置同 F8。
- requiredFix：同 F8 的最小出口；若不修，在 `docs` 里把 §3.3 标为"未落地（本轮）"。

### F10（low）`refreshGlassShell()` 只重建 `PhoneUi.ACTIVE`，常显 HUD 用的实例不会被刷新

- 位置：`PhoneUi.java:468-474`（`PhoneUi ui = ACTIVE; if (ui == null) return; ui.rebuildShellTree(); ui.rebuildPage();`）、`PhoneHud.java:274-278`（HUD 实例构造后把 `PhoneUi.ACTIVE` 还原为全屏实例）、`PhoneHud.java:260-282`。
- 事实：只有常显 HUD、没打开全屏手机时，HUD 的 `HudPhoneUi` 不是 `ACTIVE`（`buildContent` 里 `ACTIVE = prev`）⇒ 在设置页改玻璃开关/档位/强度后，**HUD 面板仍是旧档/旧底**，直到 `PhoneHud.closeRegistration()`（G 键关 HUD / 切世界 / 打开 GUI）触发工厂重建。
- requiredFix：在 `PhoneHud.java:260-282` 记录 HUD 实例（已有 `hudUi` 字段），并提供静态入口让 `PhoneUi.refreshGlassShell()` 一并重建它（例如 `PhoneHud.onGlassSettingsChanged()` → `PhoneHud.get().rebuildHudShell()`，内部 `PhoneUi.postAction(() -> { if (hudUi != null) { hudUi.rebuildShellTree(); hudUi.rebuildPage(); } })`），并在 `ScenePages.java:753-757/:801-805` 的三处 `refreshGlassShell()` 调用后补一次该入口。

### F11（low）缩放口径 40–49 被静默夹到 50，无可发现性

- 位置：`PhoneHud.java:195-203`（下发 PhoneCanvas 值）、`PhoneCanvas.java`（`HUD_SCALE_MIN=40`，未改）、`HudScaleState.MIN_PERCENT=50`（官方 4.9.1 sources）。
- requiredFix：在 `PhoneHud.syncScale()` 里当 `PhoneCanvas.getHudScalePercent() < 50` 时打印一行一次性提示（或把 `PhoneCanvas.setHudScalePercent` 的下限提到 50）；同时把 `docs/qz-liquid-glass-design.md:797`（§7.3 的"建议统一到 Qz 口径"）落实为"已统一：下限 50、上限 150（mcphone 侧）"，并在 t7 清单里加"旧配置 40 的用户：HUD 会以 50% 显示"。

### F12（low）`PhoneUi.java:251-252` 重复的 javadoc 行（t4 引入）

- 位置：`PhoneUi.java:251-252`（同一条 `/** 重建当前页… */` 写了两遍）。
- requiredFix：删掉其中一行。

### 维护项（非缺陷，登记备查）

- `PhoneGlass.java:183-190` 的 `SURFACE_ALPHA[AUTO]` 行是死行（`baseAlpha` 的 `row<=0` 兜底 + `resolveTier` 永不返回 AUTO），与注释一致，可保留；若要防御未来直接调用，建议把 `:531` 的兜底从 `THIN.ordinal()` 改成显式常量。
- `CENTER` 系锚点映射到四角的偏移算法（`PhoneHud.java:373-387`）在 CENTER 锚点下与旧的居中语义是否逐像素等价，需手测（H6）。
- `PhoneGlass.renderPath()` 的会话内首次缓存（t5 的 R2/O1）：本任务静态复核确认它**只**被 `autoTier()`（`:356-359`）与 `lensDegradeFactor()`（`:451-456`）在**建树期**读取，实现没有把它当"上不上玻璃"的开关，也没有每帧读它；`AUTO` 默认档在会话首次解析为 `ULTRA_THIN` 属预期，但**需要写进手测说明**（否则用户会把"默认档=极薄"报成 bug）。建议 requiredFix：在 `PhoneCanvas.glassTier()` 的 javadoc（`PhoneCanvas.java:356-362`）补一句"会话首次读取渲染路径时，AUTO 会解析为极薄；切换档位/开关或刷新诊断后按新值"。

---

## 13. t7 该用哪一份数值（评审裁定）

- **必须以本报告 §4.3 表（= `PhoneGlass` 实际产出 + 合成 T）为准**。该表由本任务从官方 4.9.1 sources jar 的 tint 常量独立重算，与 `PhoneGlass.SURFACE_ALPHA`、`neutralSurface`、`RGB_BASE` 三者逐值一致。
- **不得**直接使用 `docs/qz-liquid-glass-design.md:909-921`（§9.3）或 `<workspace>\_t4_evidence.md:25-31` 的数值做通过/不通过判据：前者是"节点色/T 混用"的口径（F2/F4），后者有 3 处计算错误（F6）。
- **三态诊断**：t5 的 `docs/qz-liquid-glass-verification.md` §8/§8.1 的表**可用**（其"门面短路 ⇒ 诊断停在上一帧"的判读与本任务 §2/§11 的结论一致），但本轮游戏内读不到（F9），t7 只能靠观感对照。
- **预期项（不是 bug）**：H3「默认档看起来是极薄」、H4「HUD 缘光恒为静态 -55°（右上）、全屏仍随指针」、O1/R2「AUTO 会话首次=ULTRA_THIN」。
- **必须手测的最小集（本任务补充）**：① 分辨率×倍率：1920×1080@60%（回归基线）/ 3840×2160@50–60%（旧版会上钳到 620×1100，新版可更大）/ 854×480@50%（旧版下钳到 320×200，新版更小）；② 浅色壁纸（雪原/白壁纸）× ULTRA_THIN × 小字号（状态栏 fs14/16、卡片 fs13）；③ 拖动 HUD 与主屏图标拖拽后玻璃是否跟随（踩坑③）；④ 装了其它 HUD mod（`registerAvoidance`）时点击/拖拽命中是否与视觉一致；⑤ 设置页三档/滑条/开关改动后 HUD 是否立即变化（F10）。


---

# 14. t9 复审（round 2，对 t8 repair-round-2 的对抗性复核）

> 任务 **t9**（kind=review，round 2，attempt_id `3ae9241c-6401-4d93-bf69-1e0b07292fec`）· 评审者 **review**
> 复核对象：t8 的修复（`PhoneGlass.java` / `PhoneUi.java` / `PhoneHud.java` / `docs/qz-liquid-glass-design.md` / `docs/addon-api.md`）
> 方法同 round 1：直接读源码与 diff 核对行号，数值一律由本任务从官方 4.9.1 sources jar 的 tint 常量**独立重算**，不采信自述。
> `git rev-parse HEAD` 仍为 `a328673…`（实现者未提交；`PhoneGlass.java` 仍为未跟踪新文件）。

## 14.0 复审结论

| 项 | 结果 |
| --- | --- |
| verdict | **needs_revision**（仅剩**文档数值**要修，实现代码不必再动） |
| round 1 的 12 条 finding | **F1–F7、F10、F11、F12 已解决；F8/F9 已按本轮兜底分支部分解决**（日志出口已落地、游戏内 UI 出口按 §8「下一轮项 0a」登记） |
| 新发现 | **3 条**（F13 medium、F14 low、F15 low），**全部是 `docs/` 文本数值错误**，无一条涉及 `src/` 代码缺陷 |
| high / blocker | **0** |
| 合同 verify | 2/2 passed（见 14.6）；`./gradlew compileJava` 本任务独立重跑 **exit=0** |
| 是否阻断 t7 | **是（但成本极低）**：§9.3「合成总遮罩 T」列有 3 处错值 + 1 处列头与实现不符，t7 若照它判读会误判；修完只是改 5 行文档文本 |

## 14.1 round 1 findings 的逐条复核（独立取证）

| finding | 结论 | 独立证据 |
| --- | --- | --- |
| F1（capFor ordinal 反钳） | **已解决** | `PhoneGlass.java:379-408`：`capFor(Role,Tier)` 改为 `fullness(tier) > fullness(cap) ? cap : tier`，`fullness()` 用 0/1/2/3 显式厚度序，`Tier` javadoc（`:93-99`）明写"枚举序号不是厚度序"。本任务独立枚举：STATUS 请求 `ULTRA_THIN/THIN/REGULAR/THICK` → `ULTRA_THIN / THIN / THIN / THIN`（"更薄"不再被加厚）；PANEL/PAGE 仍可达 THICK；CARD/BUTTON 上限 REGULAR。**设计 §6.2「状态栏 = DARK_ULTRA_THIN / blur 6 / lens 0.3」现在真的生效**（`blurRadius(ULTRA_THIN)=6`、`lensFactor=0.8`、`materialName=DARK_ULTRA_THIN`） |
| F2（§9.3 与实现不同源） | **已解决** | §9.3 表头已改「节点底色 a（渲染值）」并新增「合成总遮罩 T」列 + `T = a + t·(1−a/255)` 口径段（`design:928-933`）；§9.4 P1–P5 改用反解节点色（`:952-956`）。本任务逐值复核：§9.3 的**节点底色 a 列**与 `PhoneGlass.SURFACE_ALPHA`（`:201-205`）**逐格一致** |
| F3（状态栏偏深 5/255） | **实现已解决 / 文档 T 列错值** | 实现 `0x25→0x23`（`PhoneGlass.java:202`）经我复算确实把合成 T 从 69 拉到 67.8，方向正确；但文档把该格 T 写成 **0x46(70)**，实为 **0x44(67.8)** → 见 F13 |
| F4（主面板/内容底板更透） | **已解决（按 review F2 的"以实现为准"分支）** | 实现保留 `0x3C`，§9.3/§9.4 改为 `0x3C14181C` + T=0x5B（口径段明确"本节早期版本把裁定总遮罩写进名为底色 alpha 的列，本版已修"）。本任务认可该分支：**实现与文档现在同源**，无"多真值" |
| F5（javadoc 三行漂移） | **已解决** | `PhoneGlass.java:41` 现写 `0x2314181C`+合成 T=0x46（值待修，见 F13）、`:42` 卡片非玻璃态改 `0x1FF2F5F8（neutralSurface(CARD) 实际返回值）`（R3 确证项已修）、`:46` hover 改 `0x7E14181C` + T=0x91 + "刻意比 DARK 系裁定略实以保 hover 反馈"、`:44` 补「反解节点底色 vs 合成 T」口径句 |
| F6（t4 证据表） | **已解决（不在 git）** | 工作区 `_t4_evidence.md` §1 加"仅供追溯、t7 以 review §4.3 为准"裁定行并按新值修正；属工作区产物，不进 git |
| F7（附属 API 语义/对比度） | **已解决** | `docs/addon-api.md` §二 + §七各加玻璃令牌说明（13 行纯新增，diff 已核）：明写 `card/button` 的底色是玻璃令牌、`primaryButton` 仍实心、附属应使用 `glassText()/glassMuted()` 或保证"底色与文字成对"、关闭态回落旧值且签名未变。**公开常量/签名仍零变更**（`PhoneWidgets.java:29-32` 未变） |
| F8（⑦项可观测量） | **部分解决（本轮兜底分支）** | `PhoneGlass.java:332-337` `apply()` 成功后调 `logDiagnosticsIfChanged()`；`:718-741` 用 `lastLoggedDiag` 做"值变才打印"，输出 `[mcphone] glass: backdrop 路径: … \| 诊断: …`；`:721-724` 暴露 `diagnosticSeen()`。**游戏内 UI 出口（设置页 / F3 覆写）按 `design:861-866`「下一轮项 0a」登记为未落地** —— 属队长裁定的兜底分支，故本轮记为"部分解决 + 已登记"，不重复计为阻塞 |
| F9（三态零出口） | **部分解决** | 日志出口落地（同上）；`design:365-372` 新增「落地状态」段，明确"日志侧已落地 / 游戏内 UI 出口本轮未落地"。**但该项在验收⑦ 下仍是"可观测量仅日志、无游戏内量化出口"**，本任务 14.5 记为"部分通过" |
| F10（HUD 不跟随玻璃设置） | **已解决** | `PhoneHud.java:95-104` 新增 `public static void onGlassSettingsChanged()`：取实例+`hudUi` 引用 → `PhoneUi.postAction` → **引用自校验** `if (hud.hudUi != ui) return;` 再 `rebuildShellTree()+rebuildPage()`；`PhoneUi.java:476-483` `refreshGlassShell()` 末尾调用它。本任务复核：① 引用自校验能防"post 期间 HUD 被关/重建"造成的错对象重建；② `ScenePages` 三处**没有**再单独调用（grep 全仓仅 `PhoneUi.java:482` 一处调用点）⇒ 无双重重建；③ 无 HUD / 无实例时 no-op |
| F11（缩放静默夹取） | **已解决** | `PhoneHud.java:225-234`：`percent < HUD_SCALE_MIN_HOST(50)` 且 `percent != warnedLowScalePercent` 时打一行提示（值变才再提示），不改盘上配置值；`design:867-869`（§8 0b）与 `design:806`（§7.3）同步为"已统一：下限 50 / 上限 150（mcphone 侧）" |
| F12（重复 javadoc） | **已解决** | `PhoneUi.java` 该处 `/** 重建当前页… */` 现只出现 1 次（diff 显示删掉重复行） |

## 14.2 新发现（本轮唯一未通过项）

### F13（medium）§9.3/§9.4/`PhoneGlass` javadoc 的「合成总遮罩 T」列有 3 处算错，且 1 处列头与实现不符

修 F2 时新增的「合成总遮罩 T」列**没有按它自己声明的公式 `T = a + t·(1−a/255)` 计算**，本任务用官方 4.9.1 tint 常量逐格重算：

| 位置 | 文档写的 T | 按 `a`+`t` 实算 | 差 |
| --- | --- | --- | --- |
| `docs/qz-liquid-glass-design.md:940` 页面卡片/信息行（a=`0x5A`, t=38） | **0x7D（49%）** | **0x73（114.6/255=45%）** | +10/255（文档把卡片写得比实际深 10） |
| `docs/qz-liquid-glass-design.md:939` 状态栏（a=`0x23`, t=38） | **0x46（27%）** | **0x44（67.8/255=27%）** | +2/255 |
| `docs/qz-liquid-glass-design.md:937`/`:938` 主面板/内容底板（a=`0x3C`, t=38） | 0x5B（36%） | 0x59（89.1/255=35%） | +2/255 |
| `docs/qz-liquid-glass-design.md:942` 自绘按钮 | 0x7D（49%） | 0x73 | 同卡片行 |
| `PhoneGlass.java:39`/`:40` javadoc（a=`0x3C`） | 0x5B/36% | 0x59/35% | +2/255 |
| `PhoneGlass.java:41` javadoc（a=`0x23`） | 0x46/27% | 0x44/27% | +2/255 |
| `PhoneGlass.java:42`/`:45` javadoc（a=`0x5A`） | 0x7D/49% | 0x73/45% | +10/255 |

另：`PhoneGlass.java:43` 仍写「THICK 档 T 约 `0x8D~0x9A`」，而 `0x8D` 是**节点底色**不是 T（THICK 行的 T 实为 0xA2–0xAF），列头与数值混用。

**影响**：t7 手测清单若照 §9.3 判读，会把"卡片实际 45% 不透明"判成"未达 49% 的预期"；`0x7D` 与 `0x73` 相差 10/255，属可判读偏差。这几格又恰好是 revise 时新写的唯一"真值表"，属"复述错误"。

**requiredFix（纯文本，5 行）**：
1. `docs/qz-liquid-glass-design.md:940`：`**0x7D（49%）**` → `**0x73（45%）**`（该行两处：卡片与按钮共用值）。
2. `docs/qz-liquid-glass-design.md:939`：`**0x46（27%）**` → `**0x44（27%）**`。
3. `docs/qz-liquid-glass-design.md:937`、`:938`：`**0x5B（36%）**` → `**0x59（35%）**`；`:942` 的 `0x7D（49%）` → `0x73（45%）`。
4. `PhoneGlass.java:39/:40`：`T=0x5B/36%` → `T=0x59/35%`；`:41`：`T=0x46/27%` → `T=0x44/27%`；`:42/:45`：`T=0x7D/49%` → `T=0x73/45%`。
5. `PhoneGlass.java:43` 的「THICK 档 T 约 0x8D~0x9A」改为「THICK 行的**节点 a** 为 0x7A~0x8D、**合成 T** 为 0xA2~0xAF」；并建议在同一段附上本报告 §14.3 的权威表（避免再次手工推导出错）。

### F14（low）ULTRA_THIN 行的 PANEL/PAGE/CARD/BUTTON/HOVER 五个值偏离"极薄"目标，且 ULTRA 的 hover 比 THIN 的 hover 更黑（方向反转）

`SURFACE_ALPHA` 的 ULTRA 行按本轮给定数字落地为 `{0x2D,0x2D,0x23,0x45,0x45,0x80}`（`PhoneGlass.java:203`），本任务按同一公式复算其实际合成 T 与"极薄档应有的更薄"关系：

| 角色 | 极薄目标（"比 THIN 更透"） | ULTRA 实际 T | THIN 实际 T | 结论 |
| --- | --- | --- | --- | --- |
| PANEL/PAGE | < 89（THIN 面板 0x59） | **0x42（66）** | 0x59（89） | ✅ 更透 |
| CARD/BUTTON | < 115 | **0x58（88）** | 0x73（115） | ✅ 更透 |
| BUTTON_HOVER | < 145（THIN hover 0x91） | **0x8D（141）** | 0x91（145） | ⚠️ 更透但极接近（a=0x80 已到角色上限附近，差 4/255） |
| STATUS | 目标 T=0x40（64） | **0x39（57）** | 0x44（68） | ✅ 更透，但比"目标 0x40"再薄 7/255 |

⇒ 与 round 1 的 F1 不同，本轮修复后**方向正确**（极薄确实更透），故仅为 low；但设计 §6.2/§9.2 里"ULTRA_THIN = 0x40 系"的目标值现在既不等于 ULTRA 的 PANEL（0x42）也不等于其 STATUS（0x39），建议要么把 §9.2 的 `DARK_ULTRA_THIN` 目标列改成实际两值（面板 0x42 / 条状 0x39），要么把 `SURFACE_ALPHA` 该行改成严格反解（`t=26` 下：面板 0x2B→T=0x40、卡片 0x43→T=0x59、hover 0x88→T=0x8C），二者取一。

### F15（low）§9.2 的「底色 alpha 档」列未同步到新口径

`docs/qz-liquid-glass-design.md:916-919` 仍写 `0x40 / 0x59`、`0x59 / 0x73`、`0x73 / 0x8C`、`0x8C+` —— 在 §9.3 已改口径（a + T 两列）之后，这四行成为全篇唯一的"旧口径残留"，读者无法判断它们指 a 还是 T。

requiredFix：把 §9.2 该列改为「合成总遮罩 T 档（见 §9.3 的 T 列）」并同步值（THIN：面板 0x59 / 卡片 0x73 / hover 0x91；ULTRA：面板 0x42 / 条状 0x39 / 卡片 0x58；REGULAR：面板 0x8C / 条状 0x76；THICK：面板 0xAF / 条状 0xA2）。

## 14.3 权威数值表（本次修订后的唯一真值，t7 以此为准 —— 取代 §4.3）

> 口径：`节点 a` = `PhoneGlass.SURFACE_ALPHA` 实际取值（渲染时叠加在玻璃之上的实心填充 alpha）；`T` = 合成总遮罩 `a + t·(1−a/255)`，`t` 取自官方 4.9.1 `UiGlassMaterial`（`DARK_ULTRA_THIN 0x1A` / `DARK_THIN 0x26` / `DARK_REGULAR 0x33` / `DARK_THICK 0x4D`）。本表由本任务独立重算，与 `PhoneGlass.java:201-205` 逐格一致。

| 档（tint） | PANEL | PAGE | STATUS | CARD | BUTTON | HOVER |
| --- | --- | --- | --- | --- | --- | --- |
| THIN (t=38) | `0x3C14181C` / T=0x59 | `0x3C14181C` / T=0x59 | `0x2314181C` / T=0x44 | `0x5A14181C` / T=0x73 | `0x5A14181C` / T=0x73 | `0x7E14181C` / T=0x91 |
| ULTRA_THIN (t=26) | `0x2D14181C` / T=0x42 | `0x2D14181C` / T=0x42 | `0x2314181C` / T=0x39 | `0x4514181C` / T=0x58 | `0x4514181C` / T=0x58 | `0x8014181C` / T=0x8D |
| REGULAR (t=51) | `0x6F14181C` / T=0x8C | `0x6F14181C` / T=0x8C | `0x5414181C` / T=0x76 | `0x6F14181C` / T=0x8C | `0x6F14181C` / T=0x8C | `0x8714181C` / T=0x9F |
| THICK (t=77) | `0x8D14181C` / T=0xAF | `0x8D14181C` / T=0xAF | `0x7A14181C` / T=0xA2 | `0x8D14181C` / T=0xAF | `0x7A14181C` / T=0xA2 | `0x8D14181C` / T=0xAF |

**会话默认路径**：AUTO 在会话首次（`renderPath()==NONE`）⇒ ULTRA_THIN 行；真渲染后 ⇒ THIN 行。**可用档位**（F1 修复后）：PANEL/PAGE 四档全可达；CARD/BUTTON/HOVER 最高 REGULAR；STATUS 最高 THIN（ULTRA_THIN 请求保留）。
**非玻璃态**：PANEL/STATUS/CARD/BUTTON `0x1FF2F5F8`；PAGE `0x4C14181C`；未改的 `§4.3` 旧表里 `0x4514181C`/`0x6314181C`/`0x8814181C`/`0x2E14181C` 等列**作废**。

## 14.4 10 项重点的 round-2 结论（只列与 round 1 相比的变化）

| # | round 2 结论 | 与 round 1 的差异 |
| --- | --- | --- |
| ① 层次契约 | **通过** | 未变（t8 未动挂载点：`PhoneUi.java:355/:402/:438/:449` + `buildHomeGrid` 的 `glassify(grid, CARD)` 仍在） |
| ② 静默失败可判定性 | **通过（装饰器穿透）**；可判定性见⑦ | 未变；本轮新增日志出口后"能判定"程度提高 |
| ③ translatedBy | **通过（静态）/ 需手测** | 未变 |
| ④ 底色 alpha 与材质档配套 | **通过（节点值同源）** | **由 round 1 的"部分通过"升为通过**：实现与 §9.4/§9.3 的 a 列逐格一致；残留的 T 列算错见 F13（不改变节点值） |
| ⑤ 圆角/缘带 | **部分通过（绝对值合规；短边比例未做；缘带可见性需手测）** | 未变（t8 未动圆角） |
| ⑥ 布局误差归因 | **部分通过（归因清晰；小屏/4K 未实测）** | 未变 |
| ⑦ 默认开启代价 | **部分通过** | **由 round 1 的"不通过"升为部分通过**：日志出口已落地（值变才打印），但游戏内仍无量化出口（§8 0a 已登记）；宿主级模糊仍确认未被打开（mcphone 零 `BackdropBlur*`） |
| ⑧ 能力探测桥 | **通过** | 未变（`probe()` 仍 `try{…}catch(Throwable)` 全静默；`:332-337` 新增的 `logDiagnosticsIfChanged()` 包在 `try` 内且自身也 `catch(Throwable)`，不引入新崩溃面） |
| ⑨ 配色体系自洽 | **通过（+2 条文档修正）** | **由 round 1 的"部分通过"升为通过**：CARD 非玻璃态已改为 `0x1FF2F5F8`、旧配色仍物理淘汰、实心面注释齐全；剩 F13/F14/F15 三条文档项 |
| ⑩ 附属 API 兼容 | **通过** | `addon-api.md` 已补玻璃语义（F7 闭环），常量/签名仍零变更 |

**兼查**：`MCphone.java:33` 仍为 `required-after:qz_uilib@[4.9.0,)`；`README.md:17` / `README.en.md:20` 仍中英一致 `4.9.0+`。✅

## 14.5 关于 ⑦ 的最终口径（避免把"已登记"误读成"已解决"）

- **已具备**：降级阶梯（AUTO 按 `renderPath()` 解析 + Qz 的 SHADER/FIXED/TINT 三级自动降级 + `glassEnabled` 开关 + 档位/强度）；**日志可观测量**（`[mcphone] glass: …`，值变才打印，`PhoneGlass.java:332-337`/`:718-741`）。
- **仍缺**：游戏内 UI 出口（设置页只读行 / F3 覆写）⇒ 用户手测时**帧代价与三态仍只能在日志里看**；`design:861-866` 已作为"下一轮项 0a"登记。
- 因此 ⑦ 记为**部分通过**，不再是 round 1 的"不通过"，也不作为 t7 的阻塞项。

## 14.6 本轮 verify 命令（独立重跑）

```
$ ./gradlew compileJava -q   # 在 <repo>
EXIT=0
$ grep -rn 'UiBackdrop\|UiGlassMaterial\|UiBackdropEffect\|BackdropBlur' src/main/java/ | wc -l   → 11
$ grep -rln 'UiBackdrop\|UiGlassMaterial\|UiBackdropEffect\|BackdropBlur' src/main/java/         → 仅 client/enhance/PhoneGlass.java
$ grep -n 'qz_uilib@' src/main/java/com/november/mcphone/MCphone.java                            → 33: required-after:qz_uilib@[4.9.0,)
```

## 14.7 给 t7 的最终提示（本轮修订）

1. **数值以本报告 §14.3 为准**（§4.3 已作废）；§9.3 的**节点 a 列**同样正确可用，但其 **T 列**有 F13 的 3 处错值，修好前不要照用。
2. 新增手测项：**设置页把玻璃开关/档位/强度改一次后，常显 HUD 面板应立即变化**（F10 的修复点；之前在 HUD 侧不会变），且**不应出现第二次整体闪动**（检查是否双重重建）。
3. 新增手测项：把 `hudScalePercent` 设为 40（旧配置）→ 应看到一行 `[mcphone] HUD 缩放 40% 低于宿主下限 50%…` 提示，且 HUD 以 50% 显示（F11）。
4. 三态诊断：改档/开关/开关玻璃后看 `fml-client-latest.log` 里 `[mcphone] glass: backdrop 路径: …`（只在该值变化时出现）；日志仍不能替代帧代价测量。


---

# 15. t12 复审（round 3，对 t11 repair-round-3 的复核 · 终审）

> 任务 **t12**（kind=review，round 3，attempt_id `82750b51-2fae-464d-802e-35f0f6559530`）· 评审者 **review**
> 复核对象：t11 的 2 项修复（`PhoneGlass.java` javadoc 4 行 + `docs/qz-liquid-glass-design.md` §9.2 一列）
> 方法同前：逐行读源码/文档，数值一律用官方 4.9.1 sources jar 的 tint 常量**逐格重算**；`git status` 与编译独立重跑。

## 15.0 结论

| 项 | 结果 |
| --- | --- |
| verdict | **pass** |
| t11 的两项修复 | **均正确落地**（`PhoneGlass.java:39/:40/:41/:42/:45/:43/:44` + `design §9.2:914/:916-919/:921-923`） |
| t11 提出的"§14.3 的 REGULAR hover 0x9F 算不出来" | **不成立（t11 算错）**——见 15.1；本任务已验证 §14.3 **24/24 格**全部与 `SURFACE_ALPHA × tint` 逐格一致 |
| 本轮新发现的真实残留 | **1 条 low**：`design §9.4` 的 P1/P3/P4 三处"替换方向"仍写旧 T 值（0x5B/0x5B/0x7D）；**本任务已直接修正**（docs 属本任务 inScope），故无未解决 finding |
| high / blocker / medium | **0 / 0 / 0** |
| 实现代码是否被 t11 改动 | **零行为改动**：`SURFACE_ALPHA` 4 行 × 6 列、`NEUTRAL_LIGHT/SOLID_DARK`、`RGB_BASE`、`RADIUS_MIN`、`BUTTON_RADIUS/CARD_RADIUS` 全部与 t9 复审时逐值一致（本轮脚本逐值断言） |
| 合同 verify | 2/2 passed；`./gradlew compileJava` exit=0 |

> **由此，round 1/2 的 12 条 finding 与 round 2 的 2 条 finding（R2-F1/F2）全部关闭**，无未解决项 ⇒ 符合"verdict=pass 时不得存在未解决的 high/blocker"以及本轮已无任何未解决 finding。

## 15.1 复核 t11 提出的疑点 ①（REGULAR hover）：**t11 算错，§14.3 正确**

t11 写：「REGULAR/HOVER 的 a=0x87(135)、t=51 ⇒ 135+17=152 = 0x98（非 0x9F）」。

- 其错处：把 **t 当成了 51/255 = 0.2 直接相加**，漏了 `(1 − a/255)` 这一乘子。本报告与实现统一使用的公式是 `T = a + t·(1 − a/255)`。
- 正确复算：`a = 0x87 = 135`，`t = 51`，`1 − 135/255 = 120/255 = 0.4706`；`T = 135 + 51 × 0.4706 = 135 + 24.0 = 159.0` ⇒ **0x9F**。
- 同类复核：THICK/HOVER `a=0x8D=141, t=77, 1−141/255=114/255` ⇒ `141 + 34.4 = 175.4` ⇒ **0xAF**（t11 对这条判断为"对的"，同样印证公式口径）。
- **本任务已把 §14.3 全表 24 格重跑一遍**（脚本直接从 `PhoneGlass.java` 的 `SURFACE_ALPHA` 字面量 + 官方 tint 复算）：**24/24 全部匹配**，`MISMATCHES: none`。
- 结论：**不需要开新一轮修 §14.3**；t11 未自行改 review 的处理方式正确（未按错误结论改权威表）。t11 未在 §9.2 补 hover 也正确（§9.2 的列只列"每档代表值"，hover 属角色维度，权威 (a,T) 逐角色表在 §14.3）。

## 15.2 本轮真实残留（已由本任务修正）与 t11 的 ②/③

**残留（R2-F3 已关闭）**：`design §9.4` 的「替换方向」列有三处没跟上 §9.3 的修正——
`P1 :959`「合成总遮罩 T=0x5B/36%」、`P3 :961`「T=0x5B/36%」、`P4 :962`「T=0x7D/49%」。
本任务已改写为 **0x59/35%、0x59/35%、0x73/45%**（与 §9.3 的 T 列、与 review §14.3 逐格一致），并顺手把 P1 里"旧文档写的 0x59 是 T 口径"的表述改准确（现在 §9.3 本身就是 a+T 双列）。
复核后 `grep -n '0x5B\|T=0x7D' docs/qz-liquid-glass-design.md` 在 §9.3/§9.4 内**已无残留**（仅剩 §9.3 上方"t9 修正"说明句中作为**历史记载**的旧值，属刻意保留）。

**t11 的疑点 ②**（它逐字落地的 4 个档值与 §14.3 一致）：**确认成立**（本任务逐值比对：ULTRA 0x42/0x39/0x58、THIN 0x59/0x73/0x91、REGULAR 0x8C/0x76、THICK 0xAF/0xA2 与 §14.3 相同）。

**t11 的疑点 ③**（`PhoneGlass` 反解注释里 ULTRA 行 PANEL/PAGE「纯反解 0x2E」与 §14.3 的 a=0x2D 差约 1/255）：**确认为可接受的记录**，非缺陷。口径说明：`0x2D`(45) 是本轮按"手工裁定 + T 目标 0x40~0x42"落表的值；纯反解（T=0x40 时 `a=(64·255−26·255)/229=42.3 ⇒ 0x2A`；t11 记的 0x2E 对应的是另一个口径）与之相差 ≤3/255，落在"目标带"内，且**不影响任何渲染路径**（`SURFACE_ALPHA` 是唯一真值，注释只是推导记录）。本任务不打回，仅在此登记口头修正建议（下一轮若再动该注释，可把"纯反解值"标注为"目标带内，见 review §14.3"）。

## 15.3 10 项重点的 round-3 终局结论

| # | 结论 | 依据（本轮复核） |
| --- | --- | --- |
| ① 层次契约 | **通过** | `ScenePaintReplayer:145 BACKDROP → :163 BACKGROUND`；`fillRect` src-over；`uiBackdropF.frag:283-284` interior alpha≈1。挂载点：`PhoneUi.java:355/:402/:438/:449` + `buildHomeGrid` 的 `glassify(grid, CARD)`；`ScenePages:75-80`；`PhoneWidgets:196-223`。t11 未触碰 |
| ② 静默失败可判定性 | **通过** | `src/` 零 `instanceof UiRenderContext`；HUD 走 `ClientHudService.register`（`PhoneHud.java:220`）→ scene 回放器 → `UiRenderBackends`（宿主 `SceneHudHost.java:213` 包 scaled）；原 `PhoneHud` 自建 compositor 路径已删（diff 确认） |
| ③ translatedBy | **通过（静态）/ 需手测** | `PhoneUi.render` 覆写只 flush + scissor 检查；无自绘二次平移。手测项：拖动 HUD / 主屏图标后玻璃是否跟随 |
| ④ 底色 alpha 与材质档配套 | **通过** | §9.3 a 列 ≡ `SURFACE_ALPHA`；§9.2 已改 T 档并注明口径；§9.4 P1–P5 本轮修完 ⇒ 本次审阅中**全仓库不再存在第二套数值口径** |
| ⑤ 圆角/缘带 | **部分通过**（固定 12/22 + `RADIUS_MIN=10` 合规；"按短边比例"未做；缘带可见性需手测） | `PhoneGlass.java:164-168`；`PhoneUi.java:85/:642`；`UiBackdropFilterRenderer.java:331-334` |
| ⑥ 布局误差归因 | **部分通过**（归因清晰；小屏/4K 未实测） | `PhoneHud.java:305-311`、`HudScaleState.MIN_PERCENT=50`、1080p@60% 与旧版一致 |
| ⑦ 默认开启代价 | **部分通过**（阶梯 + 日志可观测量已具备；游戏内 UI 出口登记为下一轮 0a） | `PhoneGlass.java:332-337/:718-741`；`design:365-372`（§3.3 落地状态）、`:861-866`（§8 下一轮项 0a）；宿主级模糊仍未打开（mcphone 零 `BackdropBlur*`，Qz `hostBackgroundBlurEnabled=false`） |
| ⑧ 能力探测桥 | **通过** | `probe()` `try{…}catch(Throwable)` 全静默回落；`apply()` 内 `logDiagnosticsIfChanged()` 亦包在 `try` 内且自身 `catch(Throwable)`，不新增崩溃面 |
| ⑨ 配色体系自洽 | **通过** | 旧三常量物理淘汰（仅注释引述）；近不透明残留仅 `COL_BORDER`/accent/§8 明确实心面；`neutralSurface(CARD)=0x1FF2F5F8` 已在 javadoc 与 design 双处写对；对比度已知限制（R1）已登记手测 |
| ⑩ 附属 API 兼容 | **通过** | `PhoneWidgets.java:29-32` 四常量与全部公开签名未变；`addon-api.md` §二/§七已补玻璃语义（13 行纯新增） |

**兼查**：`MCphone.java:33` = `required-after:qz_uilib@[4.9.0,)` ✅；`README.md:17` / `README.en.md:20` 中英一致 `4.9.0+` ✅。

## 15.4 本轮 verify（独立重跑）

```
$ grep -rn 'UiBackdrop\|UiGlassMaterial\|UiBackdropEffect\|BackdropBlur' src/main/java/ | wc -l   → 11（全部在 PhoneGlass.java）
$ grep -n 'qz_uilib@' src/main/java/com/november/mcphone/MCphone.java                              → 33: required-after:qz_uilib@[4.9.0,)
$ ./gradlew compileJava -q                                                                          → EXIT=0
$ git -C <Qz-UILib> status --porcelain                                                      → 空
```

`git status --porcelain -- src` 与 t9 复审时逐行一致（仅 t11 的纯注释改动）——**t12 未改任何 `src/` 文件**。

## 15.5 交付给 t7 的最终版本（终审）

1. **数值**：以本报告 **§14.3** 为准（24/24 格本任务已复算通过）；`§4.3` 作废。`design §9.2/§9.3/§9.4` 与之同源，可交叉引用。
2. **预期项（非 bug）**：H3 默认档看起来是极薄（AUTO 会话首次=ULTRA_THIN）、H4 HUD 缘光恒静态 -55°（全屏仍随指针）。
3. **新增手测**：设置页改玻璃后常显 HUD 应立即变化且不二次闪动；`hudScalePercent=40` 旧配置应出现一行提示、HUD 以 50% 显示。
4. **观测量**：`fml-client-latest.log` 里 `[mcphone] glass: …`（仅值变时出现，可用于判定 SHADER/FIXED/TINT/NONE）；帧代价仍无游戏内量化出口。
5. **必须手测的观感项**（AI 能力边界）：缘带可见性/模糊观感（⑤）、浅色壁纸×极薄×小字号对比度（R1）、拖拽后玻璃跟随（③）、有其它 HUD mod 时命中盒（⑥）、分辨率×倍率三组（⑥）。
