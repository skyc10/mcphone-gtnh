# MCphone 液态玻璃改造设计蓝图（Qz-UILib 4.9.1 契约钉死版）

> 本文是 **MCphone GTNH 液态玻璃改造**（team `mcphone-liquid-glass-v3`，task t1）的唯一设计依据。
> 后续实现（t2+）与独立评审（review/verification）以本文的签名、行号、口径为准；与本文冲突的
> 二手转述一律作废。
>
> 作者：`recon`（只读侦察 + 设计笔记）。**本轮只写 `docs/` 与 `libs/`，未改 `src/`，未改 Qz-UILib。**
>
> 证据来源（全部本机可复现，命令见附录 A）：
> - 官方 4.9.1 制品与源码：`<qz-artifacts>/qz_uilib-4.9.1.jar`、`<qz-artifacts>/qz_uilib-4.9.1-sources.jar`
>   （全文逐字引用时路径写作 **`QzSrc:...`** = 该 sources jar 解包后的 `club/heiqi/uilib/...` 条目；
>   解包目录 `/tmp/qz491src`，仅为当次侦察产物）。
> - 官方 4.9.0 制品与源码：`<qz-artifacts>/qz_uilib-4.9.0.jar`、`<qz-artifacts>/qz_uilib-4.9.0-sources.jar`。
> - 旧件：`<qz-artifacts>/backup/qz_uilib-dev-4.8.0-4-0.308.jar`、`<qz-artifacts>/backup/qz_uilib-4.8.0-server.jar`。
> - 仓库编译依赖：`libs/qz_uilib-dev.jar`（官方 4.9.1-dev，`mcmod.info` 版本串 `4.9.1`）。
> - mcphone 侧：`<repo>/src/main/java/...`（路径写作 **`MC:...`**）。
> - FML 1.7.10 源码（RFG 解出的原版）：`<repo>/build/rfg/minecraft-src/java/cpw/mods/fml/...`（写作 **`FML:...`**）。
>
> 行号一律用「文件:行」形式；**jar 内条目名**用于与字节码制品对账，**源码行号**用于读签名语义。

---

## 0. 摘要：钉死的 10 条结论

| # | 结论 | 依据 |
| --- | --- | --- |
| C1 | 液态玻璃 API 存在且本机可用，但 **4.8.0 tag 里没有**：`UiBackdrop.java` / `UiGlassMaterial.java` 在 4.8.0 的 `ls-tree` 计数 = **0** | §4.1 |
| C2 | **含玻璃的最小已发布版本 = 4.9.0**（4.9.1 只加 HUD 编辑态 18 个类，玻璃签名逐字不变） | §4.2 / §4.3 |
| C3 | mcphone 依赖下限必须是 **`required-after:qz_uilib@[4.9.0,)`**（现为 `[4.8,)`，`MC:MCphone.java:28`）；改后任何 4.8.x 的 qz_uilib 都会让 mcphone 在 **FML 加载期**直接失败 | §4.4 / §4.5 |
| C4 | **层次契约**：`BACKDROP` 命令在同节点 `BACKGROUND` **之前**发出，故节点自身半透明底色叠在玻璃**之上** ⇒ **底色 alpha 必须重定**，否则把玻璃盖死 | §2.1 |
| C5 | 三态判定唯一真值来源 = `UiRenderContext.getLastBackdropFilterRenderPath()` + `getLastBackdropFilterDetail()`；枚举四值归类：`SHADER`=真渲染；`TINT_FALLBACK`/`FIXED_PIPELINE`=降级；`NONE`=静默不绘 | §2.3 / §3 |
| C6 | **诊断是"最近一次"的全局静态值，不是逐节点、也不是每帧复位**；门面 `resolveContext()` 返回 null 的静默路径**根本不写诊断**，会读到上一帧旧值 —— 这是"无异常无日志"的根因 | §3.2 |
| C7 | 装饰器穿透只在 `UiRenderBackends.resolveContext()` 里解 **`ScaledRenderBackend`** 一种壳；Qz 自家 HUD 宿主**恰好**用 `backend.scaled(scale)`（GUI scale≠1 时必包），故 HUD 玻璃必须走门面 | §5.1 |
| C8 | **HUD 宿主不注入输入源**（`HudWindowFactory:16` javadoc 明写），且 Qz 自带的拖动编辑宿主是**聊天输入屏**（`HudEditService`）⇒ mcphone 必须**保留自己的**拖拽 / Ctrl+滚轮 / G 键 / hover / 点击 | §7 |
| C9 | `HudSpec.chrome(true)`（默认）的宿主外壳底色是 `HUD_SHELL_BG = 0xA0000000`（63% 纯黑）⇒ 手机 HUD 装玻璃**必须 `chrome(false)`**，面板自己承担表面 | §7.4 |
| C10 | 旧配色**直接淘汰**（非可选皮肤）、玻璃**默认开启**；本轮给的是**默认材质档/强度 + 性能降级阶梯**，不是"多套方案对比" | §6 / §9 |

---

## 1. 玻璃 API 契约（逐字签名 + 可定位证据）

### 1.1 `UiBackdrop` —— 节点级背后滤镜声明（值对象）

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 类型 | `public final class UiBackdrop` | `QzSrc:ui/render/UiBackdrop.java:14`；jar 条目 `club/heiqi/uilib/ui/render/UiBackdrop.class` |
| 经典档工厂 | `public static UiBackdrop of(UiGlassMaterial material, int blurRadius)` | `QzSrc:.../UiBackdrop.java:31` |
| 液态玻璃工厂 | `public static UiBackdrop liquidGlass(UiGlassMaterial material, int blurRadius, float lensStrength)` | `QzSrc:.../UiBackdrop.java:36`（**三参**；二手转述常漏 `blurRadius`） |
| 完整配方入口 | `public static UiBackdrop of(UiBackdropEffect effect, int blurRadius, float saturation)` | `QzSrc:.../UiBackdrop.java:41` |
| 读取 | `getBlurRadius()` `:45`、`getEffect()` `:50`、`getSaturation()` `:54` | 同文件 |
| 是否发命令 | `public boolean isActive()` | `QzSrc:.../UiBackdrop.java:59` |

`isActive()` 语义（**决定要不要发 BACKDROP 命令**，`:59-67`）：
- effect 为液态 ⇒ 恒 `true`（`blurRadius=0` 也算，材质自带产出）；
- effect 带材质档（非液态）⇒ 仅 `blurRadius > 0` 为 true；
- 旧语义（effect==null）⇒ `blurRadius > 0 || saturation != 1.0`。

> 陷阱：`isActive()==true` 而 `blurRadius=0` 且 `effect=null` 之外的组合，只有液态/材质支路才算有效，
> 不要自己写 `blurRadius>0` 之类的前置判断——交给 `isActive()`。

不可变 + `equals/hashCode`（`:70-89`）⇒ 可安全随 fragment 复用（`SceneNode` 侧有同值短路，见 §1.4）。

### 1.2 `UiBackdropEffect` —— 完整配方（家族 + 材质档 + 液态强度）

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 类型 | `public final class UiBackdropEffect` | `QzSrc:ui/render/UiBackdropEffect.java:21`；jar 条目 `.../UiBackdropEffect.class` |
| 家族枚举 | `public enum Family { CLASSIC, LIQUID_GLASS }` | `QzSrc:.../UiBackdropEffect.java:24`（`CLASSIC :26` / `LIQUID_GLASS :28`）；jar 条目 `.../UiBackdropEffect$Family.class` |
| 经典工厂 | `public static UiBackdropEffect classic(UiGlassMaterial material)` | `:45`（material 为 null ⇒ 单例 `CLASSIC_NONE`，`:31/:46-48`） |
| 液态工厂 | `public static UiBackdropEffect liquidGlass(UiGlassMaterial material, float lensStrength)` | `:53`；`lensStrength` 由 `UiNumbers.clamp01` 夹到 `[0,1]`（`:41`） |
| 读取 | `getFamily() :57`、`getMaterial() :62`、`getLensStrength() :66` | 同文件 |
| 液态启用判据 | `public boolean isLiquid()` = `family==LIQUID_GLASS && lensStrength > 0` | `:71-73` |
| 材质 tint 归属 | `public boolean carriesMaterialTint()` = `material != null` | `:76-78`（**宿主判"是否还需自行补 tint 面"**） |

### 1.3 `UiGlassMaterial` —— iOS 材质档（8 档 + 基色常量）

类型：`public enum UiGlassMaterial`（`QzSrc:ui/render/UiGlassMaterial.java:32`；jar 条目 `.../UiGlassMaterial.class`）。
构造参数顺序 = `(vibrancy, tintArgb, luminanceLift, edgeHighlight, innerLightTop, innerShadowBottom, noiseAmount)`（`:76-85`）。

| 档 | 行 | vibrancy | tintArgb | luminanceLift | edgeHighlight | innerLightTop | innerShadowBottom | noiseAmount |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ULTRA_THIN` | `:35` | 1.30 | `0x1AFFFFFF` | 0.020 | 0.055 | 0.045 | 0.012 | 1.2/255 |
| `THIN` | `:38` | 1.38 | `0x26FFFFFF` | 0.028 | 0.065 | 0.055 | 0.016 | 1.4/255 |
| `REGULAR` | `:41` | 1.45 | `0x33FFFFFF` | 0.040 | 0.075 | 0.065 | 0.020 | 1.6/255 |
| `THICK` | `:44` | 1.50 | `0x4DFFFFFF` | 0.055 | 0.085 | 0.075 | 0.024 | 1.8/255 |
| `DARK_ULTRA_THIN` | `:47` | 1.18 | `0x1A181818` | 0.012 | 0.040 | 0.028 | 0.026 | 1.2/255 |
| `DARK_THIN` | `:50` | 1.22 | `0x26181818` | 0.018 | 0.045 | 0.032 | 0.030 | 1.4/255 |
| `DARK_REGULAR` | `:53` | 1.28 | `0x33181818` | 0.026 | 0.050 | 0.036 | 0.036 | 1.6/255 |
| `DARK_THICK` | `:56` | 1.35 | `0x4D181818` | 0.034 | 0.055 | 0.040 | 0.042 | 1.8/255 |

（tint alpha 十进制：`0x1A`=26≈10%、`0x26`=38≈15%、`0x33`=51≈20%、`0x4D`=77≈30%。）

读取器：`getVibrancy() :87`、`getTintArgb() :91`、`getLuminanceLift() :95`、`getEdgeHighlight() :99`、
`getInnerLightTop() :103`、`getInnerShadowBottom() :107`、`getNoiseAmount() :111`、`getTintAlpha() :116`、
`getTintRed/Green/Blue() :121/:126/:131`、`dark() :136`（`name().startsWith("DARK_")`）。

**基色常量**：`public static final int DARK_TINT_BASE_RGB = 0x181818`（`:59`）。
类 javadoc `:14-17` 写明取值依据：iOS `systemBlack` 是 **0.096（约 0x18）而非纯黑**，
"黑蒙层吃掉亮度故需正向补偿而非继续压暗" —— 即 §5.5 的踩坑来源。

### 1.4 节点挂载入口（scene 侧，mcphone 直接可用）

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 挂玻璃 | `public SceneNode setBackdrop(UiBackdrop backdrop)` | `QzSrc:ui/scene/node/SceneNode.java:1262`；`null` 关闭 |
| 绑定器内部通道 | `public SceneNode __writeBackdrop(UiBackdrop backdrop)` | `:1274`（置 `surfaceOwned`，绑定器专用；应用侧**不要**用） |
| 读回 | `public UiBackdrop getBackdrop()` | `:1290` |
| 写守卫 | `requireSurfaceWritable("backdrop")` | `:1263` ⇒ 被 `SceneSurfaceBinder` 接管后，公开 `setBackdrop` 会**拒绝**应用侧静态写（同 `SceneSurfaceBinder.java:113-114` 注释） |
| 同值短路 | `applyBackdrop`：同引用或 `equals` ⇒ 直接返回 | `:1279-1287` |
| 底色 | `public SceneNode setBackgroundColor(int argb)` / `getBackgroundColor()` | `:1208` / `:1235` |
| 圆角 | `setCornerRadius(int)` / `setCornerRadius(tl,tr,br,bl)` | `:1741` / `:1780` |
| 浮雕高度 | `public SceneNode __setSurfaceElevation(float)`（内部）+ `__getSurfaceElevation()` | `:1241` / `:1251`（`-1` = 普通绘制路径） |

**圆角来源**：`setBackdrop` 的 javadoc（`:1256-1258`）与 `UiBackdrop` 的 javadoc（`UiBackdrop.java:12`）
都明确：**节点圆角沿用节点自身 `cornerRadius`，不在 `UiBackdrop` 里重复表达**。

### 1.5 宿主门面：`UiRenderBackends`（HUD / 屏幕桥专用，scene 抽象层刻意不含）

类型 `public final class UiRenderBackends`（`QzSrc:ui/render/UiRenderBackends.java:15`；jar 条目 `club/heiqi/uilib/ui/render/UiRenderBackends.class`）。

三个 `backdropFilter` 重载：

| # | 签名 | 证据 |
| --- | --- | --- |
| 1 | `backdropFilter(UiRenderBackend backend, int left, int top, int right, int bottom, int blurRadius, float saturation, int cornerRadius)` | `:32`（转发为 classic(null)） |
| 2 | `… , int cornerRadius, UiGlassMaterial material)` | `:51`（转发为 `UiBackdropEffect.classic(material)`，`:53-54`） |
| 3 | `… , int cornerRadius, UiBackdropEffect effect)` | `:70`（**只在这里做 scaled 穿透 + 累计缩放换算**，`:76-79`） |
| 4 | `backdropFilter(UiRenderBackend backend, int logLeft, int logTop, int logRight, int logBottom, UiBackdrop backdrop, UiBorderRadiusResolver.ResolvedCornerRadii cornerRadii)` | `:98-99`（**scene 回放器专用**：入参 logical px；`backdrop==null || !isActive()` ⇒ 直接 return，`:100-102`） |

批次（同一视觉层兄弟玻璃共享一次背景采样）：

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 开批次 | `public static void beginBackdropBatch(UiRenderBackend backend)` | `:126` |
| 关批次 | `public static void endBackdropBatch(UiRenderBackend backend)` | `:134`（严格配对） |
| 语义 | 批次内版本号冻结：兄弟玻璃互不透过（对齐 iOS 同一 visual effect 层级），且快照 tile 复用以 `contentRevision` 为键 ⇒ 一屏 N 块玻璃 = **1 次捕获** | `UiRenderContext.java:279-305`（`beginBackdropBatch :291` / `endBackdropBatch :296`） |

**穿透实现（关键）**：`private static UiRenderContext resolveContext(UiRenderBackend backend)`
（`:149-162`）循环下钻，**只认 `ScaledRenderBackend`**（`:156-158`），链长上限 8（`:152`）；
`private static float accumulatedScale(...)`（`:165-174`）累乘缩放。
`ScaledRenderBackend` 的唯一生产者 = `UiRenderBackend.scaled(float)`（`QzSrc:ui/render/UiRenderBackend.java:38-40`，
`scale==1` 时返回自身 ⇒ 零开销）。

### 1.6 诊断 API（唯一真值来源）

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 取路径 | `public static BackdropFilterRenderPath UiRenderContext.getLastBackdropFilterRenderPath()` | `QzSrc:ui/render/UiRenderContext.java:340-342`（trampoline 到 `UiBackdropFilterRenderer.getLastRenderPath()`，`:44-46`） |
| 取说明 | `public static String UiRenderContext.getLastBackdropFilterDetail()` | `:349-351`（→ `UiBackdropFilterRenderer.getLastDetail()`，`:51-53`） |
| 枚举 | `public enum BackdropFilterRenderPath { NONE("none"), SHADER("shader"), FIXED_PIPELINE("fixed-pipeline"), TINT_FALLBACK("tint-fallback") }` + `getLabel()` | `QzSrc:ui/render/BackdropFilterRenderPath.java:6-10`（标签字面量 `:7-10`）、`getLabel() :23`；jar 条目 `.../BackdropFilterRenderPath.class` |

`getScreenWidth() :175` / `getScreenHeight() :179` / `getMouseX() :183` / `getMouseY() :187`
（`QzSrc:ui/render/UiRenderContext.java`）—— 后两者是 §5.6「光源=指针」的输入。

### 1.7 配置与策略（降级阶梯的旋钮）

`public final class BackdropBlurConfig`（单例，`QzSrc:ui/render/BackdropBlurConfig.java:29/:31`）
默认值（逐条）：

| 字段 | 默认 | 行 |
| --- | --- | --- |
| `maxBlurRadius`（元素级上限，范围 `[0,128]`） | `48` | `:40` |
| `hostBackgroundBlurEnabled`（宿主级背景模糊） | **`false`**（默认关，避免每帧全屏快照拖 FPS） | `:47`（注释 `:44-45`） |
| `hostBackgroundBlurStrength` | `1.0F`（`[0,3]`） | `:55` |
| `shaderEnabled` | `true` | `:64` |
| `shaderBlurRadiusLimit`（shader 内 texel 上限） | `56.0F` | `:72` |
| `fixedPipelineEnabled` | `true` | `:79` |
| `fixedPipelineSampleCount`（`[4,16]`） | `8` | `:87` |
| `tintFallbackEnabled` | `true` | `:94` |
| `snapshotPoolSize`（`[8,128]`） | `32` | `:104` |
| `downsampleThreshold`（半径超此值降采样） | `16` | `:112` |
| `maxDownsampleFactor` | `4` | `:120` |
| `tileSize`（atlas 复用单元，2 的幂） | `128` | `:128` |
| `contentVersionTrackingEnabled` | `true` | `:135` |
| `separableBlurEnabled` | `true` | `:142` |
| 记录渲染路径诊断 | `true` | `:146-149` |

`public final class BackdropBlurPolicy`（`QzSrc:ui/render/BackdropBlurPolicy.java:13`）预设：

| 预设 | 行 | 关键值 |
| --- | --- | --- |
| `MAX_BLUR_RADIUS`（常量） | `:16` | `128` |
| `inheritGlobal()` | `:43` | 全字段未声明（继承全局） |
| `disabled()` | `:52` | `enabled=false` |
| `performance()` | `:61` | hostBlur=**0.7**、maxBlurRadius=**32**、shader/fixed/tint 全开 |
| `quality()` | `:77` | hostBlur=**1.2**、maxBlurRadius=**64**、全开 |
| `compatibility()` | `:93` | hostBlur=0.9、maxBlurRadius=32、**shaderEnabled=false**、fixed+tint 开 |

`resolveEnabled(:240)` / `resolveHostBackgroundBlurEnabled(:250)` / `resolveHostBackgroundBlurStrength(:263)` /
`resolveMaxBlurRadius(:275)` / `resolveShaderEnabled(:286)` / `resolveFixedPipelineEnabled(:298)` /
`resolveTintFallbackEnabled(:311)` / `merge(:214)`。
页面级覆盖单入口：`UiHostRenderSupport.createRenderContext(..., BackdropBlurPolicy)`（`QzSrc:ui/host/UiHostRenderSupport.java:101-107`，
不带 policy 的 8 参重载在 `:79-85` 默认 `inheritGlobal()`）。

### 1.8 通用 HUD API（`ClientHudService` 家族）

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 服务 | `public abstract class ClientHudService` | `QzSrc:ui/hud/api/ClientHudService.java:4`；jar `club/heiqi/uilib/ui/hud/api/ClientHudService.class` |
| 单例 | `public static ClientHudService getInstance()` | `:6`（延迟反射加载 `club.heiqi.uilib.client.hud.ClientHudServiceImpl`，`:17-27`；**服务端不得调用**，`:5`） |
| 注册 | `public abstract HudRegistration register(HudSpec spec, HudWindowFactory factory)` | `:12` |
| 占位避免重叠 | `public abstract HudRegistration registerAvoidance(String id, HudAvoidanceProvider provider)` | `:14` |
| 内容工厂 | `@FunctionalInterface public interface HudWindowFactory { SceneNode build(SceneRuntime runtime); }` | `HudWindowFactory.java:19` / `:20` / `:27` |
| **输入契约** | javadoc：`宿主未注入输入源，窗口不接收输入；节点无需关心 HUD 宿主细节` | `HudWindowFactory.java:16` |
| 规格 | `public final class HudSpec` + `Builder`（`builder(String id) :33`） | `HudSpec.java:6` / `:53` / `:33`；jar `.../HudSpec.class`、`.../HudSpec$Builder.class` |
| 规格字段 | `getId :34`、`getAnchor :35`、`getVisibility :36`、`getMargin :37`、`getStackOrder :38`、`getMinWidth :40`、`getMaxWidth :42`、`isChrome :50` | 同文件 |
| Builder 默认 | `anchor=TOP_LEFT :55`、`visibility=GAMEPLAY_ONLY :56`、`margin=8 :57`、`maxWidth=Integer.MAX_VALUE :60`、**`chrome=true :61`** | 同文件 |
| 锚点 | `public enum HudAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }` | `HudAnchor.java:4-8`（**只有四角，无 CENTER**） |
| 可见性 | `public enum HudVisibility { GAMEPLAY_ONLY, IN_WORLD }` | `HudVisibility.java:4-9`（`GAMEPLAY_ONLY` = 世界内且无普通 GuiScreen） |
| 句柄 | `public interface HudRegistration extends AutoCloseable { void close(); boolean isClosed(); }` | `HudRegistration.java:4` / `:7` / `:9`（幂等关闭，`:5`） |
| 占位提供者 | `@FunctionalInterface public interface HudAvoidanceProvider { HudInsets getInsets(); }`（null = 无占位） | `HudAvoidanceProvider.java:4` / `:7` |
| 边距值 | `public final class HudInsets`：`NONE :7`、`HudInsets(l,t,r,b) :14`、`getLeft/Top/Right/Bottom :26-29`、`plus :32` | `HudInsets.java` |

HUD 缩放（公开读取入口）：

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 取缩放状态 | `public synchronized HudScaleState HudToolbarService.scale(String hudId)` | `QzSrc:ui/hud/api/HudToolbarService.java:102` |
| 缩放状态 | `public final class HudScaleState`：`MIN_PERCENT=50 :13`、`MAX_PERCENT=200 :14`、`DEFAULT_PERCENT=100 :15`、`STEP_PERCENT=10 :16`、`percent() :38`、`factor() :39`、`setPercent(int) :42`（夹取）、`zoomIn/zoomOut/reset :52-54` | `HudScaleState.java` |
| 工具栏规格 | `HudToolbarSpec.DEFAULT_GAP_PX=4 :34`、`DEFAULT_THICKNESS_PX=28 :41`、默认按钮玻璃配方 `UiBackdrop.liquidGlass(UiGlassMaterial.DARK_THIN, 6, 1.0f)` `:43-44` | `HudToolbarSpec.java` |

HUD 布局（用户放置真值）：

| 成员 | 签名 | 证据 |
| --- | --- | --- |
| 放置值 | `public final class HudPlacement`：`of(anchor,x,y) :30`、`defaultOf(anchor,margin) :35`、`getAnchor :40`、`getOffsetX :43`、`getOffsetY :46`、`translate(dx,dy) :56`、`withOffset(x,y) :64` | `HudPlacement.java` |
| 布局服务 | `public final class HudLayoutService`：`getInstance() :69`、`revision() :74`、`isEditing() :79`、`placement(hudId) :88`、`beginEdit() :114`、`setDraft(hudId,placement) :121`、`resetDraft(hudId) :133`、`resetAllDraft() :145`、`commitEdit() :172`、`commit(hudId,placement) :203`、`reset(hudId) :215`、`resetAll() :231` | `HudLayoutService.java` |
| 解析器（公开纯函数） | `public static AnchorRect HudLayoutResolver.resolve(HudPlacement placement, int viewportWidth, int viewportHeight, …)` `:32`、`clamp(...) :63` | `HudLayoutResolver.java` |
| 锚点数学（公开纯函数） | `public static ResolvedViewport SceneAnchorResolver.resolveViewport(boolean right, boolean bottom, …)` `:155`、内部值类 `ResolvedViewport :172` | `QzSrc:ui/scene/overlay/SceneAnchorResolver.java` |
| 编辑服务（4.9.1 新增） | `public final class HudEditService`；`HudEditTarget`（builder 缺省 = `HudPlacement.defaultOf(BOTTOM_LEFT, 8)`，`DEFAULT_MARGIN_PX=8 :33`，`getDefaultPlacement :14`、`getPreviewFactory :12`、`getToolbarSpec :16`） | `HudEditService.java:41`；`HudEditTarget.java:25` / `:33` |

> **编辑宿主的真实范围（关键，见 §7.5）**：`HudEditService` 的 javadoc（`:17-19`）写死"当前打开的
> **聊天输入屏**（编辑宿主）在编辑态为这些目标渲染预览浮层、命中拖动并写 `HudLayoutService` 草稿"。
> 即 Qz 提供的"拖动 HUD 布局"只在**聊天输入屏打开时**存在，不是任意时刻可用的通用编辑态。

### 1.9 层次契约（必须写进实现与评审 checklist）

1. **命令顺序**：BACKDROP 必须在同节点 BACKGROUND **之前**发出。
   - 契约文字：`QzSrc:ui/scene/paint/PaintCommandType.java:20-22`
     「BACKDROP 必须在同节点的 BACKGROUND **之前**发出——玻璃是"背景被改色"，节点的半透明填充色要叠在玻璃之上，才是 iOS 那层"气泡即玻璃"；顺序反了会把玻璃盖住（等价没接）」。
   - 同一句话在三处复述（互为交叉验证）：`SceneNode.java:1256-1258`（`setBackdrop` javadoc）、
     `ScenePaintEngine.java:415-416`（产出点注释）。
2. **产出点**（顺序即上一条的落地）：`QzSrc:ui/scene/paint/ScenePaintEngine.java:401-438`
   —— 先 `node.getBackdrop()` 非 null 且 `isActive()` ⇒ `out.add(PaintCommand.backdrop(...))`（`:417-427`），
   **其后**才是 `bgColor != 0` 的 `PaintCommand.background(...)`（`:429-438`），再 `BORDER`（`:440-452`）。
3. **推论（本文最要命的一条）**：节点自身的半透明底色是**乘在玻璃之上**的一层实心填充，
   会按 `(1-a)` 衰减 shader 算出的折射缘带与镜面高光 ⇒ **底色 alpha 必须重定**，
   不能用旧的全不透明/≈95% 不透明值（§2.1 / §5.3）。
4. **浮雕分支例外**：`__getSurfaceElevation() >= 0` 时**不走**上面那条普通路径，改走
   `SceneSurfaceReliefPainter.paint(...)`（`ScenePaintEngine.java:410-412`），该分支同样保证
   「backdrop 先、background 后」（`QzSrc:ui/scene/paint/SceneSurfaceReliefPainter.java:37-46`），
   但**矩形换成了 `face`**（见 §5.4）。

---

## 2. 渲染与降级口径

### 2.1 底色 alpha 必须重定（数值口径）

玻璃上叠的底色 alpha 有三个后果：**(a)** 衰减缘带/高光 `(1-a)`；**(b)** 决定文字对比度；
**(c)** 决定"面板是玻璃还是实心板"的观感。

Qz 自家聊天 3.0 的实测口径（`QzSrc:internal/chat3/ChatMarkdownSettings.java`）：

| 面 | 无玻璃（旧） | 有玻璃（新） | 证据 |
| --- | --- | --- | --- |
| 容器底 | `0xF2171B20`（95% 不透明） | **`0x59`**（35%） | `:125` vs `:215` |
| 气泡底 | `0xF2242B33`（95%） | **`0x73`**（45%） | `:139` vs `:213` |
| 输入条底 | — | **`0x73`**（45%） | `:217` |
| 总开关 | — | `glassEnabled = true`（默认开） | `:192` |
| 模糊/强度 | — | `glassBlurRadiusPx = 8`、`glassLensStrength = 0.5` | `:194` / `:196` |

其 alpha 定值的原文理由（**直接对应 §5.3**，`:207-211`）：
> "取 0x73（45%）而不是初版的 0x8C（55%）：这层底色是**乘在玻璃之上**的一层实心填充，会把 shader
> 算出的折射缘带与镜面高光按 (1-a) 衰减掉。真机反馈「缘带黑黑的、没有光泽」时实测侧缘镜面
> **+4/255 被压到 +2**，而同一位置的变暗有 **-23/255** —— 材质档自己已有 0.20 的黑 tint 在压背景，
> 实心层再叠 55% 属于双重遮罩。降到 45% 后玻璃与高光才透得出来，正文对比度由材质 tint 兜住。"

### 2.2 渲染管线（真实执行顺序）

`QzSrc:ui/render/UiBackdropFilterRenderer.java` 的 `render(...)`（`:104-129`）：

1. `policy = context == null ? inheritGlobal() : context.getBackdropBlurPolicy()`（`:106-107`）；
2. `policy.resolveEnabled(config)==false` ⇒ `recordPath(NONE, "disabled by page policy")` 并 return（`:109-112`）；
3. 几何非法或"无视觉产出" ⇒ `recordPath(NONE, "skipped")` 并 return（`:116-119`）；
   - `noVisualEffect = effect == null && blurRadius <= 0 && saturation == 1.0`（`:116-117`）。
     注意：**带材质档时 `blur=0` 仍有产出**（`backdrop-filter: blur(0) saturate(...)` 语义），
     所以不能按旧规则短路（注释 `:113-115`）；
4. 主流程 `drawCurrentUiBackdropFilter(...)`（`:136-236`）：
   - 采样区域 `UiMainLayerSnapshotService.resolveSampleRegion(...)` 为 null ⇒ 返回 `"texture-copy-unavailable"`（`:143-147`）；
   - `acquireSnapshot(...)` 为 null ⇒ 返回 `"snapshot-unavailable: <detail>"`（`:150-155`）；
   - 圆角由 shader 的连续覆盖率裁（`:157-159`，非 stencil）；
   - **光源方向** `resolveLightDirection(context, l, t, r, b)`（`:181`，见 §5.6）；
   - shader 分支 `drawBackdropTextureWithShader(...)`（`:238-297`）成功 ⇒ `recordPath(SHADER, "blur=…, saturation=…, family=…, material=…, lens=…, snapshot=…")`（`:292-295`）；
   - shader 不可用（被配置关 `:247-250` / 程序初始化失败 `:251-255`）⇒ 试固定管线；
   - 固定管线关闭 ⇒ 返回 `"fixed-pipeline-disabled"`（`:193-195`）；`blurRadius<=0` ⇒ `"shader-and-blur-unavailable"`（`:196-198`）；
   - 固定管线成功 ⇒ `recordPath(FIXED_PIPELINE, "shader-unavailable, samples=<4..16>, effect-degraded(no-vibrancy), snapshot=…")`（`:218-221`）
     —— **材质档在此降级为"仅模糊"**（`:217` 注释）；
5. 主流程返回非 null 的 fallback 串 ⇒ `drawTintFallback(...)`（`:393-422`）：
   - tint 降级被关 ⇒ `recordPath(NONE, "tint-fallback-disabled: <detail>")`（`:399-402`）；
   - 带材质档 ⇒ alpha `clamp(round(tintAlpha*255) + blurRadius/2, 16, 200)`、RGB 取材质 tint RGB、
     高光 alpha `clamp(alpha+26, 32, 230)`，`drawSurface(...)`（`:405-414`）⇒ `recordPath(TINT_FALLBACK, detail + ", family=…, material=…, lens=…")`（`:403-404`）；
   - 无材质档 ⇒ `tintAlpha = clamp(18 + blurRadius*2 + round((sat-1)*16), 18, 72)`、高光 `clamp(+22, 32, 96)`（`:416-421`）。

### 2.3 三态判据（建立在 §1.6 两个 API 上）

| 态 | `getLastBackdropFilterRenderPath()` | 可能的 `getLastBackdropFilterDetail()` 前缀 | 用户可见结果 |
| --- | --- | --- | --- |
| **真渲染** | `SHADER` | `blur=<n>, saturation=<f>, family=…, material=…, lens=…, snapshot=…` | 完整液态玻璃（折射缘带 / 随动缘光 / 材质 tint / 噪点） |
| **tint 降级** | `TINT_FALLBACK` | `<上游失败原因>, family=…, material=…`（上游串形如 `texture-copy-unavailable` / `snapshot-unavailable: …` / `fixed-pipeline-disabled` / `shader-and-blur-unavailable`） | 纯色玻璃底 + 高光（模糊没了，材质色与亮边还在，`:405-414`） |
| **降级（固定管线模糊）** | `FIXED_PIPELINE` | `shader-unavailable, samples=<n>, effect-degraded(no-vibrancy), snapshot=…` 或 `shader disabled by config` | 有模糊、**无 vibrancy/亮边/噪点**（`:217-221`）；材质档降级为"仅模糊" |
| **静默不绘** | `NONE` | `disabled by page policy` / `skipped` / `tint-fallback-disabled: <detail>` | 什么都没画（玻璃缺失），**无异常、无日志** |
| **静默不绘（门面短路，诊断不更新！）** | 保持上一帧的值（不是"一定是 NONE"） | 同上 | 什么都没画，且诊断**停在旧值** ⇒ 见 §3.2 |

**枚举四值归类（契约口径）**：
- `SHADER` = **真渲染**；
- `FIXED_PIPELINE` + `TINT_FALLBACK` = **降级**（两者都要按"降级"记账，`FIXED_PIPELINE` 仍有模糊，
  `TINT_FALLBACK` 连模糊都没有）；
- `NONE` = **静默不绘**（含"策略关闭""几何/参数跳过""tint 降级被关"三种成因，靠 detail 区分）；
- 初始值：`lastRenderPath = NONE`、`lastDetail = "not-run"`（`UiBackdropFilterRenderer.java:36-37`）。

---

## 3. 诊断口径（静默失败的可判定化）

### 3.1 唯一入口与最小日志契约

```java
/* 推荐的 mcphone 侧采样点（示意；实现归 t2+） */
BackdropFilterRenderPath path = UiRenderContext.getLastBackdropFilterRenderPath();
String detail = UiRenderContext.getLastBackdropFilterDetail();
```

- 二者均 `public static`（`UiRenderContext.java:340` / `:349`），**无需 context 实例即可读**。
- 值是**进程级全局静态**（`UiBackdropFilterRenderer.java:36-37` 的两个 `volatile`），
  语义 = "**最近一次**真的走到渲染器的 backdrop 请求的结果"，**不是**"当前帧/当前节点"。

### 3.2 四条判定纪律（避免误诊）

1. **不写诊断的路径**：`UiRenderBackends.backdropFilter(...)` 在 `resolveContext(backend) == null` 时
   **直接 return**（`UiRenderBackends.java:73-75` / `:104-106`），**不调用 `recordPath`** ⇒ 诊断值
   保持上一帧/上一块玻璃的结果。所以「`path==SHADER` 但屏幕上就是没有玻璃」是**可能的**，
   必须先排查 §5.1 的装饰器穿透。
2. **无法区分"哪个节点"**：诊断不含节点身份/矩形。要在 mcphone 里定位，靠
   **一次只挂一块玻璃 + 读 detail 的 `snapshot=WxH @x,y fbo=… rev=… region=… tile=…`**
   （`formatSnapshotState`，`:454-463`）反推是哪个矩形。
3. **`NONE` 三种成因要分开**：`disabled by page policy`（页面策略关了）/
   `skipped`（几何非法或参数无产出）/ `tint-fallback-disabled: <detail>`（连兜底都被关）。
   只有第三种说明"曾经尝试过真渲染但失败"。
4. **批次语义会改变 `reused`**：`beginBackdropBatch` 冻结主层版本号 ⇒ 同批兄弟玻璃的
   `snapshot=reused …` 才是**预期**（性能特征），不是复用 bug（`UiRenderContext.java:279-289`）。

### 3.3 Qz 自家怎么用（照抄即可）

`QzSrc:internal/devtools/glass/GlassLabHost.java:279-280`：

```java
pathSignal.set("backdrop 路径: " + UiRenderContext.getLastBackdropFilterRenderPath().getLabel()
        + " | 诊断: " + UiRenderContext.getLastBackdropFilterDetail());
```

⇒ mcphone 侧诊断 UI（About/设置页或 F3 覆写）**直接照这个格式**；日志侧建议**状态变化才打印**
（`path` 或 `detail` 变才写一行），避免每帧刷屏（与 `UiHudRenderListener.java:111-115` 的"值变才写"同一纪律）。

> **落地状态（t8 repair-round-2 更新，review F8/F9 对账用）**
> - **已落地（日志侧）**：`PhoneGlass.apply(...)` 成功后调 `logDiagnosticsIfChanged()`，比较
>   `renderPathLabel() + '\u0001' + diagDetail()` 与上次值，变化时 `System.out.println("[mcphone] glass: " + diagSummary())`
>   —— 即本节要求的"值变才打印"，游戏内可在 `fml-client-latest.log` 读到（含 SHADER / FIXED_PIPELINE /
>   TINT_FALLBACK / NONE 与 detail 成因）。同一路径也暴露 `PhoneGlass.diagnosticSeen()` 供只读展示。
> - **本轮未提供游戏内 UI 出口**：设置页那一行只读诊断文本**未落地**（t8 的 inScope 不含
>   `client/apps/ScenePages.java`）⇒ **帧代价只能靠 F3 目测/看日志，无量化出口**；
>   该项已在 §8「下一轮项」登记。

---

## 4. 发布兼容风险（含玻璃的最小已发布版本）

### 4.1 `git ls-tree 4.8.0` 实证据（逐字命令 + 输出）

```
$ git -C <Qz-UILib> ls-tree -r --name-only 4.8.0 | grep -E 'UiBackdrop|UiGlassMaterial'
src/main/java/club/heiqi/uilib/ui/render/UiBackdropFilterRenderer.java
src/main/java/club/heiqi/uilib/ui/render/UiBackdropShaderProgram.java
src/test/java/club/heiqi/uilib/ui/render/UiBackdropShaderProgramTest.java

$ git -C <Qz-UILib> ls-tree -r --name-only 4.8.0 \
      | grep -cE '(^|/)(UiBackdrop|UiGlassMaterial)\.java$'
0
```

> **口径修正（重要）**：`grep -E 'UiBackdrop|UiGlassMaterial'` 在 4.8.0 下**不是 0 而是 3 行**，
> 因为 `UiBackdropFilterRenderer.java` / `UiBackdropShaderProgram.java`（+1 个测试类）也匹配 `UiBackdrop`。
> 判"玻璃是否在 4.8.0 内"必须用**全名精确计数**（第二条命令，结果 **0**），或直接问
> `UiBackdrop.java` / `UiGlassMaterial.java` 是否存在。二手转述里的"计数 0"是前者写错的结果。

4.8.0 的 backdrop 全家福（同一 tag，`grep -i backdrop`）只有：
`BackdropBlurConfig` `BackdropBlurController` `BackdropBlurPolicy` `BackdropBlurPreset`
`BackdropFilterRenderPath` `DefaultBackdropBlurController` `UiBackdropFilterRenderer`
`UiBackdropShaderProgram` + `shader/uiBackdropF.frag` + `shader/uiBackdropV.vert`（+2 个测试类）。
**没有** `UiBackdrop` / `UiGlassMaterial` / `UiBackdropEffect`。

引入提交（本机 `git log --diff-filter=A` 实查）：

| 文件 | 首次加入提交 | 日期 | 提交标题 |
| --- | --- | --- | --- |
| `UiGlassMaterial.java` | `c42252df` | 2026-09-02 | `[build]: 磨玻璃材质升级：Poisson 盘 + iOS 材质档（vibrancy/tint/亮边/抖噪）` |
| `UiBackdrop.java` | `bcdb8148` | 2026-09-02 | `[build]: 聊天框与聊天 HUD 上 Liquid Glass（50% 强度 / 模糊 8px），走声明式 scene 通道` |

两者均**晚于** 4.8.0（4.8.0 发布 `2026-08-17T04:49:45Z`，见下）。

> **不要用本地 tag 判断发布状态**：本地克隆 `<Qz-UILib>` 已过期
> （`HEAD=7fd570ab`，本地 tag 最高只到 4.8.0）。发布状态一律查 GitHub releases API。

### 4.2 GitHub releases API（发布事实）

```
$ curl -s --noproxy '*' "https://api.github.com/repos/QuanhuZeYu/Qz-UILib/releases?per_page=100"
4.9.1 | published_at= 2026-09-11T10:49:48Z | prerelease= False | draft= False
4.9.0 | published_at= 2026-09-11T03:27:34Z | prerelease= False | draft= False
4.8.0 | published_at= 2026-08-17T04:49:45Z | prerelease= False | draft= False
4.7.0 | published_at= 2026-08-14T12:38:43Z | prerelease= False | draft= False
（更早略；完整列表见附录 A 命令 A5）
```

⇒ 4.9.0 与 4.9.1 是 2026-09-11 同一天的两个发布；**含玻璃的最小已发布版本 = 4.9.0**；
当前最新已发布 = 4.9.1。tags 之间隔着 4.8.0（08-17）→ 4.9.0（09-11），
玻璃（09-02 引入）**落在 4.8.0 之后、4.9.0 之前** ⇒ 4.8.0 里必然没有。

### 4.3 4.9.0 → 4.9.1 的差量（自证"玻璃签名两版一致"）

两个官方 jar 的条目 diff（本机 python3 zipfile）：

```
4.9.0 entries: 1835   4.9.1 entries: 1853
ADDED in 4.9.1 (18):
  club/heiqi/uilib/internal/chat3/input/ChatHudEditPreviews$Preview.class
  club/heiqi/uilib/internal/chat3/input/ChatHudEditPreviews.class
  club/heiqi/uilib/ui/hud/api/HudEditService$1.class
  club/heiqi/uilib/ui/hud/api/HudEditService$2.class
  club/heiqi/uilib/ui/hud/api/HudEditService$Host.class
  club/heiqi/uilib/ui/hud/api/HudEditService$Registration.class
  club/heiqi/uilib/ui/hud/api/HudEditService.class
  club/heiqi/uilib/ui/hud/api/HudEditTarget$1.class
  club/heiqi/uilib/ui/hud/api/HudEditTarget$Builder.class
  club/heiqi/uilib/ui/hud/api/HudEditTarget.class
  club/heiqi/uilib/ui/hud/api/HudLayoutData$1.class
  club/heiqi/uilib/ui/hud/api/HudLayoutData$Builder.class
  club/heiqi/uilib/ui/hud/api/HudLayoutData.class
  club/heiqi/uilib/ui/hud/api/HudLayoutMetrics.class
  club/heiqi/uilib/ui/hud/api/HudLayoutPersistence.class
  club/heiqi/uilib/ui/hud/api/HudLayoutPreference.class
  club/heiqi/uilib/ui/hud/api/HudLayoutStore.class
  club/heiqi/uilib/ui/hud/api/HudScaleRegistry.class
REMOVED in 4.9.1 (0):
```

⇒ **+18 / -0**，全是 HUD 编辑态 / 布局持久化 / 缩放注册表；无删除 ⇒ 4.9.0 上的 mcphone 代码
在 4.9.1 上不会被"删类"打脸。

玻璃与 HUD 契约源文件的**逐字节一致性**（`diff -q`，4.9.0-sources vs 4.9.1-sources）：

```
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/render/UiBackdrop.java
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/render/UiGlassMaterial.java
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/render/UiBackdropEffect.java
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/render/BackdropFilterRenderPath.java
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/hud/api/ClientHudService.java
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/hud/api/HudWindowFactory.java
IDENTICAL  4.9.0 vs 4.9.1 : club/heiqi/uilib/ui/hud/api/HudSpec.java
```

4.9.1 三份玻璃源码 md5（供后续对账）：
`UiBackdrop.java = 1fef539276668cf82336d34b62d9699d`、
`UiGlassMaterial.java = bf003dc32a35dbf729ebb079ab5a2642`、
`UiBackdropEffect.java = 722612e645b39ece83d5a1e4b3a6429b`。

⇒ **四参收口：依赖下限写 4.9.0 即可（不必写 4.9.1）**，因为玻璃 API 在两版之间逐字未变；
而 4.9.1 提供的 `HudEditService` / `HudLayoutStore` / `HudScaleRegistry` 只影响 **HUD 迁移可选能力**
（§7），不影响主壳玻璃。

### 4.4 FML 依赖判定机制（为什么必须升下限）

链条（逐段可定位）：

| 步 | 事实 | 证据 |
| --- | --- | --- |
| 1 | Qz 用 `version = Tags.VERSION` 声明版本（编译期内联成字符串常量） | `QzSrc:MyMod.java:15` |
| 2 | FML 取该字符串作内部版本 | `FML:cpw/mods/fml/common/FMLModContainer.java:183` `internalVersion = (String) descriptor.get("version");` |
| 3 | 无 `version.properties` 才回落（官方 jar 里**没有**该文件） | `FMLModContainer.java:184-192`（`searchForVersionProperties`，`:218-249`）；jar 内 `version.properties` 条目数 = 0 |
| 4 | 组 `ArtifactVersion` | `FMLModContainer.java:546` `processedVersion = new DefaultArtifactVersion(getModId(), getVersion());`（`getVersion()` 在 `:133`） |
| 5 | 版本表 = `modId -> processedVersion` | `FML:Loader.java:218-222` `modVersions.put(mod.getModId(), mod.getProcessedVersion());` |
| 6 | **范围判定只比对版本串** | `FML:Loader.java:247-256` `if (!v.containsVersion(modVersions.get(v.getLabel()))) versionMissingMods.add(v);` |
| 7 | 不满足 ⇒ 抛异常、加载失败 | `FML:Loader.java:257-261` `throw new MissingModsException(versionMissingMods);`（在 `sortModList()` 内，`:213`） |

⇒ 1.7.10 的 `@Mod(dependencies="required-after:qz_uilib@[4.9.0,)")` **只在该版本串上做范围判定，
与类是否存在无关**。

各制品的版本串实测（`mcmod.info` + `MyMod.class` 内联常量）：

| 制品 | `mcmod.info` version | `MyMod.class` 内联 | 满足 `[4.9.0,)`？ |
| --- | --- | --- | --- |
| 官方 `<qz-artifacts>/qz_uilib-4.9.1.jar` | `4.9.1` | `4.9.1`（`acceptableRemoteVersions` 常量 = `[4.9.0,4.10.0)`） | **是** |
| 仓库 `libs/qz_uilib-dev.jar`（官方 4.9.1-dev） | `4.9.1` | `4.9.1` | **是** |
| 旧实例 jar | `4.8.0-4-0.308+7fd570ab83` | 同左 | **否** |
| 旧仓库 dev jar（`qz491/backup/qz_uilib-dev-4.8.0-4-0.308.jar`） | `4.8.0-4-0.308+7fd570ab83-dirty` | 同左（另含常量 `[4.8.0,4.9.0)`） | **否** |

> **旁证**：4.8.0 那版 `MyMod.class` 里的 `acceptableRemoteVersions` 常量是 **`[4.8.0,4.9.0)`**，
> 官方 4.9.1 是 **`[4.9.0,4.10.0)`** —— 上游自己就声明"4.8.0 与 4.9.x 不互通"，
> 这就是 4.9.0 作为**断代线**的独立佐证（与我们按玻璃可用性得出的结论一致）。

### 4.5 结论：确切的 FML 版本范围字符串

```
required-after:qz_uilib@[4.9.0,)
```

- **下限 = `4.9.0` = 含液态玻璃 API 的最小已发布版本**（§4.2）；
- **上界开放**（`,` 右侧空 = 无上界）：因为 4.9.0→4.9.1 只增不删、玻璃签名逐字一致（§4.3），
  且上游自己的 `acceptableRemoteVersions` 是 `[4.9.0,4.10.0)` 这种"小版本内兼容"口径；
  写死 `[4.9.0,4.10.0)` 会在上游发 4.10 时无故拒绝（不必要的收紧）。
- 落地位置：`MC:src/main/java/com/november/mcphone/MCphone.java:28`（现为 `[4.8,)`），**由 t2 改**；
  本文只裁定字符串。当前源码实态（本轮未改）：
  ```java
  @Mod(modid = "mcphone", name = "MCphone", version = Tags.VERSION,
       acceptedMinecraftVersions = "[1.7.10]",
       dependencies = "required-after:qz_uilib@[…4.8,)")   // ← :28，须改 [4.9.0,)
  ```

> **写法约定（与本设计的门禁命令强相关）**：本文档中所有**旧范围串**一律写作
> `qz_uilib@[…4.8,)`（`…` 处原本就是 `[`）。原因：门禁命令
> `grep -rn 'qz_uilib@\[4.8' <repo>/docs/` 会扫描本文档自身，
> 若逐字保留旧串则该门禁永远非空。**新串不受该约定影响，逐字为** `required-after:qz_uilib@[4.9.0,)`。

**反向风险（必须写进手测清单）**：改成 `[4.9.0,)` 后，**任何 4.8.x 的 `qz_uilib` 都会让 mcphone
在 FML 加载期直接失败、进不去游戏**（`MissingModsException`，`Loader.java:260`）。
表现为启动日志 `The mod mcphone (MCphone) requires mod versions [...] to be available` + 崩溃，
**不是**运行期缺类 NoClassDefFoundError。

### 4.6 `PhoneGlass` 探测桥的定位：**崩溃安全网**，不是兼容旧版的手段

- **定位**：可选的极薄反射探测（`Class.forName("club.heiqi.uilib.ui.render.UiBackdrop")` 之类），
  用途是**在被误装 4.8.x 的环境里把"缺类"变成"可读的日志 + 优雅降级"**，
  避免出现 `NoClassDefFoundError` 这类无法定位的崩溃。
- **它不能替代版本下限**：FML 的判定发生在 **mod 排序阶段**（`Loader.sortModList`），
  **早于**任何 mcphone 代码执行 ⇒ **依赖串不满足时 mcphone 的类根本不会被加载**，
  探测桥没有机会运行。两者是**不同层**的防护：依赖串管"能不能加载"，探测桥管
  "在依赖串被绕过/被手改/半升级的环境里不要崩得莫名其妙"。
- **不要**用探测桥来做"4.8 也能跑"的兼容：玻璃 API 在 4.8.0 **根本不存在**，
  没有可降级的旧实现（旧实现只有 `BackdropBlur*` 家族，语义不同），
  且用户已裁定"旧配色直接淘汰、玻璃默认开启"，不存在"关掉玻璃回 4.8 观感"的诉求。

### 4.7 本机环境既成事实（勿重复做）

- 实例 `290b3test` 的 `mods/` 已是官方 `qz_uilib-4.9.1.jar`（版本串 `4.9.1`，玻璃条目齐备）；
- 仓库 `libs/qz_uilib-dev.jar` 已是官方 **4.9.1-dev**，`./gradlew compileJava` 退出码 0；
- 服务端 `<test-server>/mods` 同为官方 4.9.1；
- 旧件备份：实例 `mods-backup-qz481/`、`<qz-artifacts>/backup/`；官方制品目录 `<qz-artifacts>/`；
- **禁止用本地源码自建替代官方制品**：`Tags.VERSION` 在 dirty 工作区会产出 `-dirty` 脏串
  （实测旧 dev jar = `4.8.0-4-0.308+7fd570ab83-dirty`），会直接改变 §4.4 的版本判定结果。

---

## 5. Qz 踩坑映射（6 类 → mcphone 受影响文件/行号）

### 5.1 装饰器穿透：`backend instanceof UiRenderContext == false` ⇒ 整块不渲染（无异常、无日志）

**Qz 侧机制**：
- 门面穿透只解 **`ScaledRenderBackend`** 一种壳（`UiRenderBackends.java:149-162`），
  非 `ScaledRenderBackend` 的装饰器 ⇒ `resolveContext()` 返回 `null` ⇒ `backdropFilter` **直接 return**（`:73-75`）；
- Qz 自家注释直白承认这是"反复踩的静默降级"（`UiRenderBackends.java:144-147`：
  「HUD 宿主在 GUI scale != 1 时（MC 常态）把后端包成 ScaledRenderBackend，它不是 UiRenderContext，
  早期版本在此静默返回、玻璃整块不渲染且无任何报错——正是本仓反复踩的"能力探测静默降级"」）；
- **Qz 自家 HUD 宿主确实包**：`QzSrc:client/hud/SceneHudHost.java:213`
  `window.frame(backend.scaled(item.scale), x, y, width, height, frameTimeNanos);`
  （`scaled()` 是 `UiRenderBackend` 的 default 方法，`:38-40`，scale≠1 才包）；
  另一处：`QzSrc:ui/scene/host/SceneFramePipeline.java:338` `replayer.replay(plan, state.ctx.scaled(relativeScale), …)`。

**mcphone 受影响点（精确行号）**：

| 位置 | 现状 | 风险/要求 |
| --- | --- | --- |
| `MC:client/hud/PhoneHud.java:12` | `import club.heiqi.uilib.ui.render.PaintContextCompositor;` | HUD 裸渲染链路自建 compositor |
| `MC:client/hud/PhoneHud.java:50-52` | `compositor` / `snapshotService` / `adapters` 三个自持实例 | 迁移后应由 Qz HUD 宿主统一持有（§7.2） |
| `MC:client/hud/PhoneHud.java:220-228` | `prepareMainUiRenderState()` → `compositor.beginFrame()` → `snapshotService.beginFrame()` → `createRenderContext(...)` → `hudUi.render(size[0], size[1], context, origin[0], origin[1])` | **当前传的是裸 `UiRenderContext`（`:224`），所以今天"能画"**；一旦容器/宿主换成 `ScaledRenderBackend`（例如把这份渲染搬到带 GUI scale 的宿主里，或按 §7 迁到 `ClientHudService`，宿主按 `SceneHudHost.java:213` 包 scaled），**必须让节点玻璃走 `SceneNode.setBackdrop` + 回放器门面**，绝不能自己 `instanceof UiRenderContext` 硬判 |
| `MC:client/hud/PhoneHud.java:224-226` | `createRenderContext(screenW, screenH, pointerX, pointerY, partialTicks, compositor, snapshotService, adapters)` | 这里把真实指针传进了 context（对 §5.6 有利），迁移后会变成 `UiHudRenderListener.java:123` 的 `0, 0` |
| `MC:client/scene/PhoneUi.java:798` | `public void render(int w, int h, UiRenderBackend ctx, int absX, int absY)` | 签名收的是抽象 `UiRenderBackend` **正是为了兼容 scaled 壳**；`super.render(...)` 内部走回放器 ⇒ 只要玻璃挂在节点上、由回放器发命令，就自动获得穿透能力 |

**实现纪律**：mcphone 侧**任何**新代码都**不得**写
`if (ctx instanceof UiRenderContext) { ... }` 来决定是否上玻璃。玻璃只走
`node.setBackdrop(...)`（声明式），穿透/换算交给 `UiRenderBackends`。

### 5.2 `translatedBy` 一类"平移漏传字段" ⇒ backdrop 变 null（玻璃静默消失）

**Qz 侧机制**：`QzSrc:ui/scene/paint/PaintCommand.java:778-799`
`translatedBy(int dx, int dy)` 要把 fragment 相对坐标叠加节点绝对偏移；
`:791-792` 注释写死：
> 「backdrop 必须随命令平移透传：BACKDROP 与 BACKGROUND 同属节点局部坐标通路，走本方法叠加
> fragment 偏移。**漏传会让玻璃声明在平移后静默消失（不报错、只是不画）**。」

实现上 `backdrop` 是构造器末段参数之一（`:793-798` 的重建调用里显式带上 `backdrop, roundedBand`），
另外 8 个边界命令（PUSH/POP_OPACITY、CLIP_PUSH/POP、PUSH/POP_TRANSFORM、PUSH/POP_TRANSFORM_LAYER）
**防御性返回自身**（`:784-789`）。

**mcphone 受影响点**：

| 位置 | 关系 |
| --- | --- |
| `MC:client/scene/PhoneUi.java:798-802` | `render(...)` 覆写（`super.render` 后 `checkScissorLeak()`）——**这是 mcphone 侧唯一的渲染入口覆写**，不影响命令构造；但任何"自己在渲染里二次平移/自绘玻璃"的想法都会绕开 `translatedBy`，属于禁止项 |
| `MC:client/hud/PhoneHud.java:105-115` | `panelOrigin(...)` 计算的 `origin[0]/origin[1]` 是**面板绝对原点**，由 `PhoneUi.render(..., absX, absY)` 传入（`:228`）⇒ 玻璃矩形随 fragment 平移由 Qz 负责，**mcphone 不要再自己减偏移** |
| `MC:client/hud/PhoneHud.java:168-173` | 拖拽写 `PhoneCanvas.setHudOffsetX/Y` ⇒ 面板原点每帧变化 ⇒ **每帧都在走 `translatedBy`**，正是这条踩坑的高频路径（玻璃必须跟着动，不然拖动后玻璃留在原地/消失） |
| `MC:api/PhoneWidgets.java:122-144` | 自绘按钮是 `SceneNode.row()` + 子 `SceneNode` 文本（`:131-136`），挂玻璃时按钮玻璃矩形同样走 fragment 平移 |

**验收观察点**：拖动 HUD 时玻璃必须跟着面板走；若"拖动后玻璃消失/留在原处"，即本题。

### 5.3 底色 alpha 衰减（把玻璃盖死）

**Qz 侧机制**：见 §2.1（`ChatMarkdownSettings.java:207-211` 的 `(1-a)` 衰减实测数据）。

**mcphone 受影响点（旧配色全不透明/≈95% 就是"盖死"的元凶）**：

| 文件:行 | 现常量/值 | 现状 alpha | 在玻璃上的后果 |
| --- | --- | --- | --- |
| `MC:client/scene/PhoneUi.java:71` | `COL_BG = 0xF20E1116` | **242 ≈ 95%** | 主面板玻璃被盖死（≈95% 遮罩）⇒ 必须重定为玻璃档 alpha（§9） |
| `MC:client/scene/PhoneUi.java:73` | `COL_STATUS_BG = 0x99000000` | 153 = 60% 纯黑 | 状态栏玻璃上再叠 60% 纯黑 ⇒ 双重遮罩，须按"纯黑=0x18 基色"重定（§5.5） |
| `MC:client/scene/PhoneUi.java:75` | `COL_PAGE_BG = 0x900E1116` | 144 = 56% | 内容底板玻璃（若有）会被压暗，且它是**主壳文字对比度的真正承担者**（`:330` 注释），重定时要同时验文字 |
| `MC:client/apps/ScenePages.java:50` | `COL_PANEL = 0x33FFFFFF` | 51 = 20% 白 | **已经是玻璃友好值**（与 `UiGlassMaterial.REGULAR` 的 `0x33FFFFFF` 同值）⇒ 可直接沿用/微调，不必大改 |
| `MC:api/PhoneWidgets.java:103` | `BTN_BG = 0xFF3A414D` | **255 = 100%** | 全不透明 ⇒ 按钮玻璃全废，必须重定 |
| `MC:api/PhoneWidgets.java:104` | `BTN_BG_HOVER = 0xFF4A5462` | 100% | hover 态同样必须重定（且 hover 是 `bindComputed` 写同一个属性，`:139-141`） |
| `MC:api/PhoneWidgets.java:105` | `BTN_PRIMARY_BG = 0xFF2F5FA8` | 100% | 主操作按钮：**要么保留实心（accent 面）要么换 accent 玻璃**，需裁定（§9.4） |
| `MC:api/PhoneWidgets.java:106` | `BTN_PRIMARY_BG_HOVER = 0xFF3A72C4` | 100% | 同上 |
| `MC:api/PhoneWidgets.java:24-25` | `PANEL = 0x33FFFFFF` / `BORDER = 0x55FFFFFF` | 20% / 33% | 与 ScenePages 同值，玻璃友好 |
| `MC:feature/chat/client/ChatUi.java:36` | `COL_PANEL = 0x33FFFFFF` | 20% | **本轮不动**（见 §8），但同一 token 在三个文件重复 = 观感割裂的直接来源 |

**实现纪律**：玻璃面上的底色 alpha 必须是**显式裁定值**（§9 表），且**与"文字色"成对定义**；
不允许"先上玻璃、底色沿用旧值"。

### 5.4 圆角是倒角载体（半径太小 ⇒ 缘带没有弧度可挂）

**Qz 侧机制**：
- 圆角不属于 `UiBackdrop`，属于**节点自身**（`UiBackdrop.java:12`、`SceneNode.java:1256-1258`）；
- 缘带（缘光/厚度 tint）宽度按短边比例、峰值内移 ⇒ **直角处这两项都退化**。
  `QzSrc:internal/chat3/ChatMarkdownSettings.java:132-135` 原文：
  > 「2026-09-02 真机观感定稿时从 12 提到 20（用户：「把聊天框的圆角调大就OK了」）。上玻璃后轮廓
  > 第一次可见，12px 在 320px 宽的面板上读作"几乎直角"——**圆角是 Liquid Glass 立体倒角的载体**，
  > 半径太小则缘带没有弧度可挂（缘带宽度按短边比例、峰值内移 0.35·band，直角处这两项都退化）。
  > 改半径不影响玻璃本身，只改轮廓曲率。」
- 液态折射位移上限也按面板短边收敛：`refractionPx = min(6.0 + 40.0*lensStrength, panelShortHalfPx * 0.8)`，
  再除降采样因子（`UiBackdropFilterRenderer.java:331-334`）；注释 `:328-330` 解释小面板（如 28px 短边气泡）
  若照吃 26px 位移会把轮廓外内容拽进来、边缘糊成脏带。
- 渲染侧：shader 用**连续覆盖率**裁圆角（`:157-159`），固定管线降级路径另加圆角裁剪（`:200-201`）。

**mcphone 受影响点**：

| 位置 | 现有半径 | 影响 |
| --- | --- | --- |
| `MC:client/scene/PhoneUi.java:282` | `panel.setCornerRadius(22)` | 主面板 22px ⇒ **玻璃友好**（>20 已足够挂缘带）；HUD 面板物理尺寸 200–620 宽，逻辑尺寸更小 ⇒ 迁移后要按逻辑尺寸复核（见下） |
| `MC:api/PhoneWidgets.java:127` | `btn.setCornerRadius(8)` | **偏小**：8px 圆角 + 按钮高约 20–30px 逻辑像素 ⇒ 缘带几乎无处可挂；建议 10–12 或按短边比例 |
| `MC:client/scene/PhoneUi.java:485` | `iconBox.setCornerRadius(box / 3)` | 图标盒圆角自适应，通常够；图标盒玻璃化收益低（小面积） |
| `MC:client/scene/PhoneUi.java:279-286` | `panel` 是 `column` + `clipChildren(true)`（`:286`） | **裁剪作用域**与玻璃矩形关系：`CLIP_PUSH` 由绘制引擎在外层产出（`PaintCommandType.java:96-97`），玻璃在 clip 内被裁是对的；但**面板圆角裁剪 + 玻璃圆角必须同值**，否则出现"直角玻璃被裁成圆角面板"的错位 |
| `MC:client/hud/PhoneHud.java:95-102` | `panelSize()` 算的是**物理像素**尺寸（`screenH * 0.62 * hudScalePercent/100`，钳 `[320,1100]` 高、`[200,620]` 宽） | 迁移到 Qz HUD 后 `HudSpec` 的长度单位是 **UILib logical px**，且宿主会按 `item.scale` 走 `backend.scaled()` ⇒ **半径/尺寸的口径要跟着换域**，否则玻璃缘带宽度与观感一起变 |

### 5.5 `systemBlack = 0x18` 非纯黑（深色玻璃需要"厚度"）

**Qz 侧机制**：`UiGlassMaterial.DARK_TINT_BASE_RGB = 0x181818`（`UiGlassMaterial.java:59`），
类 javadoc `:16-17` 说明取值依据：「深色材质基色 systemBlack **0.096（约 0x18）而非纯黑**，
黑蒙层吃掉亮度故需正向补偿而非继续压暗」；八档 DARK 的 tint RGB 全部是 `0x181818`（`:47/:50/:53/:56`），
且 `luminanceLift` 随厚度**递增**（0.012→0.018→0.026→0.034）来补偿蒙层吃掉的亮度。

**mcphone 受影响点**：

| 位置 | 现状 | 问题 |
| --- | --- | --- |
| `MC:client/scene/PhoneUi.java:73` | `COL_STATUS_BG = 0x99000000` | **纯黑**（`0x000000`）× 60% ⇒ 玻璃上叠纯黑 = 失去厚度（且与 `0x18` 基色体系不一致）；应改为 `0x18` 基色的对应 alpha（§9） |
| `MC:client/scene/PhoneUi.java:71` | `COL_BG = 0xF20E1116` | `0x0E1116` 是**近纯黑的冷蓝黑**（R14 G17 B22）⇒ 上玻璃后要改为 `0x18` 系（或明确保留"手机黑"作为 accent 底色，但不能再是 95% 遮罩） |
| `MC:client/scene/PhoneUi.java:75` | `COL_PAGE_BG = 0x900E1116` | 同上 |
| `MC:client/apps/ScenePages.java:504` / `:315` / `:317` | `0xFF101418` / `0xFF1A2028` / `0xFF101418` | **全不透明**的"内容壳"（`:504` 注释语境为长文本/编辑区）⇒ 属"保留实心"候选（§9.4），但要显式声明，不能默默留旧值 |
| `MC:feature/chat/client/ChatUi.java:38` / `:39` | `COL_BUBBLE_SELF = 0xFF2F5FA8` / `COL_ONLINE = 0xFF9CE89C` | 实色，本轮不动（§8），但气泡色与本轮主壳 accent 需在下一轮对齐 |
| `MC:client/enhance/PhoneTheme.java:25-30` | 主题色对（6 组 `{text, muted}`） | **文字色体系已存在**（`:25` 默认 `0xFFE8EDF2 / 0xFFB8C4D0`）⇒ §9 的文字色裁定应**接入它**，而不是另造一套 |

### 5.6 光源 = 指针（MC 无陀螺仪 ⇒ 宿主以鼠标指针为虚拟光源）

**Qz 侧机制**：`UiBackdropFilterRenderer.java:481-523`
- javadoc `:484-486`：「官方 Liquid Glass 的缘光"responds to device motion"；MC 1.7.10 无陀螺仪，
  **宿主以鼠标指针为虚拟光源**——指针在哪个方位，缘带就朝哪边最亮。指针不可得或恰在中心时退回静态默认光向」；
- 静态默认 `DEFAULT_LIGHT_ANGLE_DEG = -55.0F`（**右上 55°**，`:506`，取值依据 `:488-494`）；
- 实现 `:507-523`：以 `context.getMouseX()/getMouseY()` 相对面板中心算单位向量，
  `length > 1` 才采用，否则用默认；`context == null` 也用默认；
- 口径如实说明（`:496-499`）：`getMouseX/Y` 是**屏幕绝对坐标**，面板矩形若在宿主局部空间（带 absX/absY 偏移），
  偏移大时缘光方向会偏；"缘光只是装饰性方向调制，不影响采样正确性"。
- 该方向再叠两道液态增益：`edgeTint = 0.14 + 0.34*lensStrength`（`:337`）、
  `edgeHighlight × (1 + 1.4*lensStrength)`（`:365-366`）。

**mcphone 受影响点（这条是本轮最容易被忽略的差异）**：

| 位置 | 事实 | 后果 |
| --- | --- | --- |
| `MC:client/hud/PhoneHud.java:224-226` | HUD 自渲染时把**真实指针**传进 context（`:225` 的 `pointerX, pointerY`） | 现状：HUD 玻璃的缘光**会跟着鼠标转** |
| `MC:client/hud/PhoneHud.java:132-133` | `pointerX = Mouse.getX(); pointerY = mc.displayHeight - Mouse.getY() - 1;`（tick 里采样，注意 GL 原点在左下 → 已翻到左上） | 迁移后**这份采样不能删**（§7 输入/hover 仍需它） |
| `QzSrc:client/UiHudRenderListener.java:123` | Qz HUD 宿主创建 context 时传的是 **`0, 0`**（`createRenderContext(width, height, 0, 0, event.partialTicks, compositor, snapshots, UiRuntimeAdapters.empty())`） | ⇒ **一旦迁到 `ClientHudService`，HUD 玻璃的 `getMouseX/Y` 恒为 0** ⇒ 缘光**恒为静态 -55°（右上）**，不再随指针 |
| 对照：`QzSrc:ui/screen/McScreenBridge.java:178-180` | 全屏页面桥传的是**真实** `pointerX, pointerY` | ⇒ 全屏手机玻璃**保留**随动缘光；**HUD 与全屏观感会出现差异**（这是宿主行为，不是 mcphone 的 bug） |
| `MC:client/scene/PhoneUi.java:71` 等 | 面板 rect 在 HUD 下带 absX/absY 偏移 | 即使 mcphone 自己喂指针，也会命中 `:496-499` 的偏移口径问题 |

**处置裁定（写进实现）**：接受宿主口径 —— **全屏随动、HUD 静态 -55°**，
不要把"自己再实现一套光源"当作本轮任务；若后续要统一，只能改 Qz（本轮**不做**，§8）。

---

## 6. 性能与默认值（默认材质档/强度 + 降级阶梯）

### 6.1 成本模型（决定默认值的依据）

- **每块玻璃 = 一次主层快照采样 + 一次全屏 quad 模糊绘制**；快照服务用 tile 网格
  （`tileSize=128`，`BackdropBlurConfig.java:128`）与 `contentRevision` 复用
  （`snapshotPoolSize=32 :104`、`contentVersionTrackingEnabled=true :135`）；
- **同一批兄弟玻璃 = 1 次捕获**（`beginBackdropBatch` 冻结版本号，`UiRenderContext.java:291-305`）；
- **`hostBackgroundBlurEnabled` 默认 `false`**（`BackdropBlurConfig.java:47`），
  理由注释 `:44-45`：「避免每帧全屏快照与多次全屏模糊绘制拖低 FPS」；
- 半径换算：`resolveBackdropShaderRadius = clamp(blurRadius*0.75/downsampleFactor, 1, min(56, policyMax))`
  （`UiBackdropFilterRenderer.java:441-452`）；`blurRadius > downsampleThreshold(16)` 触发降采样（`:112`）；
- 固定管线降级是 `8` 次 quad 叠加（`fixedPipelineSampleCount=8 :87`，样本表 `:25-34`），
  **不含** vibrancy/亮边/噪点（`:217`）。

### 6.2 默认值裁定（用户裁定落地：默认开启 + 首选风格）

| 面 | 材质档 | blur | lens | 底色 alpha | 理由 |
| --- | --- | --- | --- | --- | --- |
| 主面板（手机壳） | `DARK_THIN` | **8** | **0.5** | 见 §9 表（约 0x59 系） | 对齐 Qz 自家定稿口径：`glassBlurRadiusPx=8` / `glassLensStrength=0.5`（`ChatMarkdownSettings.java:194/:196`），材质取 DARK_THIN（同 `ChatContainer.java:170`） |
| 内容底板（`contentSlot`） | `DARK_THIN` | 8 | 0.35 | 0x59 | 比主壳更哑，避免两层同强度叠出"糊" |
| 状态栏 | `DARK_ULTRA_THIN` | 6 | 0.3 | 0x40 系 | 条状小面，缘带无处挂 ⇒ 低强度 + 低折射 |
| 卡片/列表行（`COL_PANEL` 面） | `DARK_THIN` | 8 | 0.5 | **0x73（45%）** | 直接采用 Qz 气泡定稿值（`:213`）与其 `(1-a)` 论证（§2.1） |
| 自绘按钮 | `DARK_THIN` | 6 | 1.0 | 0x73 | 对齐 `HudToolbarSpec.java:44` 的 HUD 工具栏按钮配方 `DARK_THIN, 6, 1.0f`（小面积需要更高 lens 才看得出来） |
| 主操作按钮（primary） | **实心 accent**（不上玻璃） | — | — | 100% | accent 面要"跳"，玻璃会削弱层级（§9.4 裁定） |

**为什么不是更激进的默认**：`ULTRAR_THIN` 系（tint alpha 10%）在 MC 世界里（高对比地形 + 文本 HUD）
会让文字可读性失控；`THICK` 系（30%）则接近实心板、玻璃白上。
`DARK_*` 系是**唯一能保住浅色文字对比度**的选择（`ChatMarkdownSettings.java:200-202`：
「聊天正文是浅色（text-primary 0xFFE6E8EB），白 tint 在亮背景上会把浅色文字一起洗白；
黑 tint 压暗背景才保得住对比度——这既是可读性约束，也正是真机反馈"DARK 系列更有苹果味"的成因」）。

### 6.3 性能降级阶梯（四级，可判定）

| 级 | 触发 | 处置参数 | 用户可见 |
| --- | --- | --- | --- |
| L0 完整 | 默认 | `policy = inheritGlobal()`（= 全局默认：shader on、fixed on、tint on、maxBlur 48、hostBlur off）；核对 `path == SHADER` | 完整液态玻璃 |
| L1 降强度 | F3 帧率被 HUD 玻璃拖低（HUD 常显 = 世界画面上每帧都在采样） | `BackdropBlurPolicy.performance()`（`:61`：hostBlur 0.7、maxBlur **32**）+ 主面板 lens 降到 **0.3**、blur **6** | 模糊略弱、缘带收敛 |
| L2 关 shader | 机器 shader 不可用/驱动异常（`path == FIXED_PIPELINE`，detail 含 `shader-unavailable`） | `BackdropBlurPolicy.compatibility()`（`:93`：`shaderEnabled=false`，fixed+tint 保持开） | 有模糊、无 vibrancy/亮边/噪点 |
| L3 只剩 tint | 纹理拷贝/快照不可用（`path == TINT_FALLBACK`，detail 含 `texture-copy-unavailable` / `snapshot-unavailable`） | 保持默认（tint fallback 默认开，`BackdropBlurConfig.java:94`）；**不要关 tint fallback** | 纯色玻璃底 + 高光（材质色/亮边仍在，`:405-414`） |
| L4 关玻璃 | 页面策略显式关闭（`path == NONE`，detail `disabled by page policy`） | `BackdropBlurPolicy.disabled()`（`:52`）——**仅作"用户级逃生舱"，默认不启用**（用户裁定：玻璃默认开启、为首选风格） | 回实心底色 |

**性能预算纪律（写进实现与手测清单）**：
1. 一屏玻璃数量目标：**主壳 ≤ 4 块**（面板 / 内容底板 / 状态栏 / 主页按钮行），
   列表卡片**按可见行数**各自成块，但必须用 `beginBackdropBatch` 包成一批（N → 1 次捕获）；
2. 虚拟化/长列表**不要每行挂玻璃**（Qz 自家裁决见 `SceneVirtualGrid.java:71`「单元零 BACKDROP」、
   `SceneSimpleList.java:51`「行自身不装滤镜」）⇒ mcphone 的相册/便签列表行**不上玻璃**，
   只在容器/底板上一层；
3. HUD 与全屏**不要同时**显示玻璃（HUD 已常显时全屏页面由用户操作，二者互斥）；
4. 手测口径：F3 帧率对比（HUD 常显开/关）、以及 `getLastBackdropFilterDetail()` 里
   `snapshot=reused` 占比（同批兄弟玻璃应为 `reused`）。

---

## 7. HUD 迁移边界（`PhoneHud` → `ClientHudService`）

### 7.1 目标形态

```java
/* 示意（实现归 t2+） */
ClientHudService svc = ClientHudService.getInstance();          // 仅客户端主线程（:5 / :10）
svc.register(
    HudSpec.builder("mcphone:phone")
        .anchor(HudAnchor.TOP_RIGHT)        // 四角之一（无 CENTER，见 §7.3）
        .visibility(HudVisibility.GAMEPLAY_ONLY)   // 与现有 mc.currentScreen != null 语义相同
        .margin(24)
        .stackOrder(0)
        .chrome(false)                      // ★ 必须 false（§7.4）
        .build(),
    rt -> { /* 一次建树；内容 = 手机面板（挂玻璃） */ return panelNode; });
```

### 7.2 迁移后**可删**的 mcphone 侧字段/逻辑（逐条给行号）

| 位置 | 现值 | 迁移后 |
| --- | --- | --- |
| `MC:client/hud/PhoneHud.java:12` | `import ...PaintContextCompositor` | 删（宿主持有） |
| `MC:client/hud/PhoneHud.java:13-15` | `UiMainLayerSnapshotService` / `UiRenderContext` / `UiRuntimeAdapters` import | 删（`UiRuntimeAdapters` 宿主用 `empty()`/自建） |
| `MC:client/hud/PhoneHud.java:50` | `private final PaintContextCompositor compositor = new PaintContextCompositor();` | 删 |
| `MC:client/hud/PhoneHud.java:51` | `private final UiMainLayerSnapshotService snapshotService = ...` | 删 |
| `MC:client/hud/PhoneHud.java:52` | `private final UiRuntimeAdapters adapters = UiRuntimeAdapters.minecraftDefaults();` | 删 |
| `MC:client/hud/PhoneHud.java:194-243` | `renderHud(...)` 整段：自设 ortho/viewport、`GL11.glPushMatrix/glPopMatrix`、`GlAttribDepth.current()/popExcess`、`prepareMainUiRenderState()`、`compositor.beginFrame()/finishFrame()`、`snapshotService.beginFrame()/finishFrame()`、`createRenderContext(...)`、`hudUi.render(...)` | **整体删**（宿主在 `UiHudRenderListener.onRenderGameOverlay` 里做同一件事，`:97-137`） |
| `MC:client/hud/PhoneHud.java:208-241` | GL 状态压栈/恢复与矩阵模式切换 | 删（`UiHudRenderListener.java:116-118` + `HudGlStateGuard` 负责） |
| `MC:client/hud/PhoneHud.java:249-263` | `ensureUi(...)` 里"按屏幕/缩放变化重建 PhoneUi 实例 + 抢回 `PhoneUi.ACTIVE`"（`:254-258`） | **简化**：窗口工厂只在挂载时调用一次（`HudWindowFactory.java:11`），尺寸变化由宿主 `RetainedWindow` 重测；`PhoneUi.ACTIVE` 抢占问题随之消失 |
| `MC:client/hud/PhoneHud.java:45-48` | `init()` 注册即建实例 | 改为 `ClientProxy` 里注册 HUD（须确认调用链，见 `docs/AI-DEV-NOTES.md` 踩坑 #9） |
| `MC:client/hud/PhoneHud.java:80-92` | `disposeUi()` | 改为 `HudRegistration.close()`（幂等，`HudRegistration.java:4-9`） |
| `MC:client/scene/PhoneUi.java` 的 HUD 专用构造 `PhoneUi(phoneStack, hudPanelW, hudPanelH)`（`:104`） | HUD 显式尺寸构造 | **保留**（窗口工厂内仍需按逻辑尺寸建树；Qz HUD 尺寸单位是 logical px） |

### 7.3 **必须保留在 mcphone 侧**的行为（宿主不注入输入 ⇒ 不能删）

**契约依据（硬证据）**：`QzSrc:ui/hud/api/HudWindowFactory.java:16`
「**宿主未注入输入源，窗口不接收输入**；节点无需关心 HUD 宿主细节。」
另一处佐证：`SceneHudHost.RetainedWindow` 的装配点 `SceneHostAssembly.assemble(measurer, null)`
（`QzSrc:client/hud/SceneHudHost.java:316-319`，注释 `:316` 明写「无输入退化模式 inputSource=null」）。

| 必须保留的行为 | 现有实现位置 | 迁移后怎么做 |
| --- | --- | --- |
| **拖拽移动面板** | `MC:client/hud/PhoneHud.java:153-174`（按下命中面板 ⇒ 记 `dragStartX/Y` 与基准 offset，松开时 `<4px` 判定为点击、否则 `PhoneCanvas.setHudOffsetX/Y`） | **保留**（tick 里用 `Mouse.isButtonDown(0)` + `insidePanel`）。Qz 的拖动编辑宿主是**聊天输入屏**（`HudEditService.java:17-19`），不是常显时可用的编辑态 ⇒ **不能依赖**。若要接 Qz 布局体系：拖拽结束写 `HudLayoutService.getInstance().commit(hudId, placement)`（`:203`），但命中/预览仍须 mcphone 自己做 |
| **Ctrl + 滚轮缩放** | `MC:client/hud/PhoneHud.java:146-151`（`Mouse.getDWheel()` + `isCtrlDown()`，步进 ±10%，`PhoneCanvas` 40–150） | **保留**。已统一到 Qz 倍率：写 `HudToolbarService.getInstance().scale("mcphone:phone").setPercent(...)`（`HudToolbarService.java:102` + `HudScaleState.setPercent :42`），**已统一：下限 50 / 上限 150（mcphone 侧）**（`PhoneCanvas.java:265-267` 仍允许 40，低值由 `PhoneHud.syncScale()` 提示后按宿主下限 50 显示；review F11 兜底分支）。Qz `HudScaleState` 本身是 50–200 / 步进 10 / 默认 100（`HudScaleState.java:13-16`），mcphone 只在 50–150 内取值 |
| **G 键开关 HUD** | `MC:client/hud/PhoneHud.java:34` 注释所述 `ClientHooks` 注册的 `keyHud`；`PhoneCanvas.isHudEnabled()` 判断在 `:134` / `:196` | **保留**。Qz 侧没有"临时隐藏"状态（`HudVisibility` 只有 `GAMEPLAY_ONLY` / `IN_WORLD`，`HudVisibility.java:4-9`）⇒ G 键仍由 mcphone 的 keybinding 驱动；实现上可"关闭注册"或"内容树空尺寸整窗隐藏"（`SceneHudHost.java:300-301`：内容空尺寸时整窗隐藏） |
| **hover 视觉反馈** | `MC:api/PhoneWidgets.java:137-141`（`runtime().interactionState(btn)` + `hovered()` + `bindComputed` 写底色） | **保留**：交互状态由**页面 runtime** 提供（`SceneRuntime.interactionState`），HUD 内容树用的是**同一套 scene 控件** ⇒ 只要宿主注入了输入源到 runtime 才有 hover；而 HUD 宿主**不注入** ⇒ **HUD 内的 hover 不会触发**。裁定：HUD 内不做 hover 效果（或 mcphone 自己按指针位置写 Signal 模拟） |
| **点击打开手机** | `MC:client/hud/PhoneHud.java:160-166`（松开且位移 <4px ⇒ `openPhone(mc, phone)`，`:187-190`） | **保留**（tick 里自己命中判定）。**不要**指望 HUD 窗口内部控件接收 CLICK |
| **命中测试依据** | `MC:client/hud/PhoneHud.java:177-180` `insidePanel(px,py,origin,size)`；`panelOrigin(...) :105-115` | 迁移后**必须与宿主实际放置一致**。可用公开纯函数复刻同一数学：`SceneAnchorResolver.resolveViewport(right, bottom, …)`（`SceneAnchorResolver.java:155`，public static）+ `HudLayoutResolver.resolve(placement, vw, vh, …)`（`HudLayoutResolver.java:32`，public static）。**残余风险**：宿主的安全区（`HudInsets`）可能被其它 mod 的 `registerAvoidance` 撑大，而 mcphone **读不到** `SceneHudHost.currentSafeInsets()`（该访问器在 `SceneHudHost.java:264`，但宿主实例不可从外部取得）⇒ 命中盒可能与渲染盒有偏移。**缓解**：mcphone 自己也 `registerAvoidance` 声明占位，并把手测项列入清单（"拖动/点击命中是否与视觉一致，尤其有其它 HUD 模组时"） |

### 7.4 `chrome(true)` 会盖玻璃 ⇒ 手机 HUD 必须 `chrome(false)`

- `HudSpec.Builder.chrome` 默认 **true**（`HudSpec.java:61`）；`isChrome()` 契约（`:44-50`）：
  「`false` 时窗口内容直接浮在画面上（无外壳背景与内边距）——现代风格悬浮式 HUD 使用；默认 true 保持既有窗口观感」。
- 宿主实现（`QzSrc:client/hud/SceneHudHost.java:323-329`）：
  `shell.setPadding(...).setBackgroundColor(SceneChromeTokens.HUD_SHELL_BG)`，其中
  **`HUD_SHELL_BG = 0xA0000000`**（`QzSrc:ui/scene/paint/SceneChromeTokens.java:249`）= **63% 纯黑**。
- 后果（精确口径）：shell 是 root、content 是其子 ⇒ 命令顺序为 **shell 的 BACKGROUND 先、content 的命令后**。
  玻璃在 content 节点上采样的是**它背后已经画好的画面**，其中已包含 shell 那层 63% 纯黑
  ⇒ 玻璃采样源被先压暗 63%（观感变闷、变脏），并且 shell 的 `padding` 会把内容盒缩进去、
  使玻璃矩形比"手机面板"小一圈。**这不是"盖在玻璃之上"，而是"先把背景压暗再让玻璃采样"** ——
  成因不同（§2.1 是 alpha 乘在玻璃之上，这里是采样源被污染），结论相同：手机 HUD 不能用 `chrome(true)`。
  另外该壳色是**纯黑**（`0x000000`），违反 §5.5 的 `0x18` 基色口径。
- **裁定**：手机 HUD 用 `chrome(false)`，由 `panel` 自己承担圆角+玻璃+边框（与全屏 `PhoneUi.buildShell` 一致）。

### 7.5 锚点/缩放口径差异（迁移必须显式处理）

| 维度 | mcphone 现状 | Qz HUD | 处置 |
| --- | --- | --- | --- |
| 锚点集合 | **9 个**：`TOP_LEFT/TOP_CENTER/TOP_RIGHT/CENTER_LEFT/CENTER/CENTER_RIGHT/BOTTOM_LEFT/BOTTOM_CENTER/BOTTOM_RIGHT`（`MC:client/PhoneCanvas.java:259-262`），默认 **`CENTER_LEFT`**（`:269`） | **4 角**：`TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT`（`HudAnchor.java:4-8`） | **不能 1:1 映射**：CENTER 系必须映射到最近角 + `HudPlacement` 偏移（`HudPlacement.of(anchor,x,y) :30`）或 `HudLayoutService` 覆盖（`:203`）。**建议**：`CENTER_LEFT` → `TOP_LEFT` + 偏移（垂直居中由 mcphone 写偏移，或保留 mcphone 自算 origin 的路径） |
| 面板尺寸 | 物理像素（`PhoneHud.java:95-102`，`[320,1100]` 高 / `[200,620]` 宽） | logical px（`HudSpec` javadoc `:5`「布局数值单位均为 UILib logical px」） | 尺寸/半径/字号全部换域；`item.scale` 由宿主经 `backend.scaled()` 承担（`SceneHudHost.java:199-213`） |
| 缩放范围 | `hudScalePercent` **40–150**，步进 10，**默认 60**（`MC:client/PhoneCanvas.java:265-267` = `HUD_SCALE_MIN=40` / `HUD_SCALE_MAX=150` / `HUD_SCALE_DEF=60`；步进在 `PhoneHud.java:149`） | `HudScaleState` **50–200**，步进 10，**默认 100**（`HudScaleState.java:13-16`） | **三处差异**（下限 40→50、上限 150→200、默认 60→100）⇒ 必须显式裁定：统一到 Qz 口径（并把 60 作为迁移初值写入）或保留 mcphone 自管缩放并接受"宿主倍率恒 100" |
| 位置持久化 | `PhoneCanvas` 的 `hudAnchor/hudOffsetX/hudOffsetY/hudScalePercent` | `HudLayoutService` + `HudLayoutStore` 端口（4.9.1 新增，`HudLayoutStore.class`）+ `HudLayoutPreference`（锚点 + 行程百分比 + 缩放） | **本轮不接持久化端口**（§8）：Qz 的端口由 Qz 内部挂载，mcphone 无法挂自己的；继续用 `PhoneCanvas` 落盘 |
| 可见性 | `mc.currentScreen != null` ⇒ 不画（`PhoneHud.java:128-131` / `:197`）；`ClientHooks.isCameraMode()` | `GAMEPLAY_ONLY` = 世界内且无普通 GuiScreen（`HudVisibility.java:5`） | 语义等价，但**相机模式/HUD 总开关**仍须 mcphone 自己判断（Qz 不知道这些） |

### 7.6 客户端专属安全

`ClientHudService.getInstance()` 的加载器在服务端会抛
`IllegalStateException("ClientHudService is only available on the Minecraft client")`
（`ClientHudService.java:17-26`），且类 javadoc 明写「服务端不得调用」（`:5`）。
⇒ 注册调用必须放在 `@SideOnly(CLIENT)` 或 `ClientProxy` 路径里（与 `docs/AI-DEV-NOTES.md` 踩坑 #8
「`@SideOnly(CLIENT)` 不能标 CommonProxy 方法」互为约束）。

---

## 8. 本轮不做清单（明确排除，供评审核对）

**契约级排除（team 目标已声明）**：

1. **不改 Qz-UILib 源码**（`git -C <Qz-UILib> status --porcelain` 必须为空）；
   需要 Qz 能力时只用公开 API 或写在 mcphone 内。
2. **不改 `mcphone-gtnh/src/`**（本轮 t1 只读侦察 + 设计笔记；`git status --porcelain -- src` 必须为空）。
   `MCphone.java:28` 的依赖串修改、配色替换、玻璃接入全部由 **t2+** 落地。
3. **不发版、不 push、不打 tag、不动 README/dependencies.gradle**（后者已由队长改完，勿重复改）。
4. **不启动游戏客户端**（手测由用户执行；本轮只产出文档与设计）。
5. **不用本地源码自建替代官方制品**（`Tags.VERSION` 会产出 `-dirty` 脏串，见 §4.7）。

**技术级排除（本轮明确不做的能力）**：

**下一轮项（t8 repair-round-2 登记，review F8/F9/F11 的兜底分支）**：

0a. **游戏内玻璃诊断 UI 出口**：设置页「显示」分组那一行只读 `PhoneGlass.diagSummary()` 文本
    **本轮未落地**（t8 inScope 不含 `client/apps/ScenePages.java`）。当前可观测量只有
    **日志出口**（见 §3.3 的"落地状态"：`apply()` 成功后值变才打印 `[mcphone] glass: …`）
    ⇒ **帧代价只能靠 F3 目测或读日志，没有游戏内量化出口**。下一轮把该行加进设置页（或加 F3 覆写）。
0b. **HUD 缩放口径**：已统一为 **下限 50 / 上限 150（mcphone 侧 `PhoneCanvas` 仍写 40–150）**，
    `PhoneHud.syncScale()` 对 `<50` 的旧配置打印**一次性提示**（"已按 50% 显示"），不改盘上配置值。
    手测注意：**旧配置 40 的 HUD 会以 50% 显示**。

6. **不做"旧配色可选皮肤"**：旧配色**直接淘汰**（用户裁定），不提供切换开关、不保留
   "关闭态与改动前一致"的兼容目标。
7. **不做 `CENTER` 锚点**：不向上游提需求、不在 mcphone 内实现 9 锚点 → 4 锚点的通用映射层；
   本轮只裁定"就近角 + 偏移"（§7.5 建议）。
8. **不接 Qz 的 HUD 布局持久化端口**（`HudLayoutStore` / `HudLayoutPreference` / `HudLayoutData`）：
   这些是 Qz 内部装配的端口，mcphone 无从挂载；继续用 `PhoneCanvas` 落盘（§7.5）。
9. **不实现"精确安全区命中"**：不通过反射/内部类取 `SceneHudHost.currentSafeInsets()`；
   接受 §7.2 的残余风险并用"自己也 registerAvoidance + 手测清单"缓解。
10. **不统一 HUD 与全屏的缘光光源**：接受"全屏随动 / HUD 静态 -55°"的宿主口径（§5.6），
    不自己实现第二套光源，不改 Qz（要改就等上游）。
11. **不上玻璃的面（本轮不动）**：
    - `MC:feature/chat/client/ChatUi.java` 全文件（含 `:36 COL_PANEL`、`:38 COL_BUBBLE_SELF`）
      —— **标注为"本轮不动但与主壳观感割裂"**：它与 `ScenePages.java:50` / `PhoneWidgets.java:24`
      共用同一 token 值却各自定义，主壳换玻璃后聊天页会显得"更实、更暗"；
    - `MC:client/CameraHandler.java:82/:99/:100`（相机取景框，画在世界层，与 UI 不共面）；
    - `MC:client/apps/ScenePages.java:530` / `MC:feature/chat/client/ChatUi.java:395`
      的 `setBackgroundColor(0xFF000000)`（图片底衬，实心是对的行为）；
    - `MC:client/enhance/WallpaperPresets.java:40-45` 的壁纸渐变（是**玻璃的采样素材**，不是被淘汰的壳色）。
12. **不做 shader 自研 / 不写 Mixin**：玻璃全部走 Qz 声明式通道（`setBackdrop`）。
13. **不改 `api/`（`docs/addon-api.md` 的公开 SPI）**：`PhoneWidgets` 的公开方法签名不变
    （只改私有常量与内部实现），避免附属 App 需要同步改。

**本轮内已完成的 `docs/` 同步（非"不做"，列此备查）**：`docs/AI-DEV-NOTES.md:10/:21/:45`、
`docs/SESSION-20260909-merged.md:152` 的 `[4.8,)`/`4.8.0` 残留已同步为 `[4.9.0,)`/`4.9.1`/`4.9.0+`
（前后 grep 对照见附录 A 命令 A7）。`docs/SESSION-20260909-merged.md:188`
（历史会话记录里的旧实例 jar 名 `qz_uilib-4.8.0-4-0.308+7fd570ab83.jar`）**刻意保留**：
那是当时环境的实录，改写会篡改会话日志；且它不含 `@[4.8` 形式、不影响依赖口径。

---

## 9. 玻璃风格配色体系裁定

### 9.1 三条总则

1. **玻璃面上不放"不透明底色"**：所有玻璃面的底色 alpha **必须显式声明**（§9.4 表），
   且必须通过 `(1-a)` 衰减核算（§2.1）。
2. **文字/图标色必须与材质档成对定义**：`DARK_*` 配**浅字**，`LIGHT（无 DARK 前缀）*` 配**深字**。
   Qz 自家选择是"**全 DARK_* + 浅字**"（`ChatMarkdownSettings.java:200-202`），mcphone 沿用。
3. **accent / 语义色保留实色**：强调色（primary 按钮、在线状态、开关开态）不上玻璃 ——
   玻璃是"容器语言"，实色是"状态语言"，混用会让层级塌掉。

### 9.2 材质档 ↔ 文字色配对表（裁定值）

| 材质档 | tint alpha | 合成总遮罩 T 档（见 §9.3 T 列） | 正文色 | 次要文字 | 图标/字形 | 依据 |
| --- | --- | --- | --- | --- | --- | --- |
| `DARK_ULTRA_THIN` | 10% | 面板 0x42 / 条状 0x39 / 卡片 0x58 | `0xFFE8EDF2` | `0xFFB8C4D0` | `0xFFE8EDF2` | 沿用 `PhoneWidgets.java:22-23` 现有文字 token（`TEXT` / `MUTED`） |
| `DARK_THIN`（**默认主档**） | 15% | 面板 0x59 / 卡片 0x73 / hover 0x91 | `0xFFE8EDF2` | `0xFFB8C4D0` | `0xFFE8EDF2` | 同上；与 `ChatContainer` 定稿档一致（`ChatContainer.java:170`） |
| `DARK_REGULAR` | 20% | 面板 0x8C / 条状 0x76 | `0xFFFFFFFF`（更实的面提亮一档） | `0xFFC8D2DC` | `0xFFFFFFFF` | Qz 气泡档（`ChatMessageList.java:1709`）；底色越实、文字越要亮以维持对比 |
| `DARK_THICK` | 30% | 面板 0xAF / 条状 0xA2 | `0xFFFFFFFF` | `0xFFD5DCE4` | `0xFFFFFFFF` | 模态卡片/强隔离层；接近实心，文字按实心板标准 |

> 本列是**合成总遮罩 T 档**（= 节点底色 a 与材质 tint 的合成，`T = a + t·(1−a/255)`），
> **不是**节点自身底色 alpha（节点 a 见 §9.3「节点底色 a（渲染值）」列）；
> 每档的完整 (a, T) 逐角色表见 `docs/qz-liquid-glass-review.md` §14.3。

**对比度基线**：Qz 自家"浅字 + DARK_* + tint 0x33（20%）"的组合被认为可读且定稿
（`ChatMarkdownSettings.java:148-149` `textPrimaryArgb = 0xFFE6E8EB` 配 `:139` 的 `0xF2242B33` 气泡 + DARK 系玻璃）。
mcphone 现有 `TEXT = 0xFFE8EDF2`（`PhoneWidgets.java:22`）与其同量级 ⇒ **不需要改文字色**，
只需要**改底色 alpha** + **统一 accent**。

### 9.3 表面体系表（面 → 配方 → 底色 → 文字）

> **数值口径（review F2/F4 裁定：以实现为准，唯一真值）**：下表「节点底色 a（渲染值）」列 = `PhoneGlass.SURFACE_ALPHA`
> 的**实际取值**（`PhoneGlass.surface(role)` 产出），即叠加在玻璃之上的那层实心填充的 alpha；
> 「合成总遮罩 T」= 与材质档 tint 合成的总遮罩，按 `T = a + t·(1−a/255)` 计得
> （`t` = 材质档 `tintArgb` 的 alpha：`DARK_ULTRA_THIN 0x1A` / `DARK_THIN 0x26` / `DARK_REGULAR 0x33` / `DARK_THICK 0x4D`）。
> 本节早期版本把「裁定总遮罩」写进了名为「底色 alpha」的列 ⇒ 与实现差 5–24/255（review F2），本版已修。
> 手测/验收的参考值以本表为准（review §13 同裁定）。
> **§9.3 表注（t13 文档收尾，review G2/G3）**：① `PhoneGlass.SURFACE_ALPHA` 的 **REGULAR/THICK 行 STATUS 单元格（`0x54`/`0x7A`）运行期不可达** —— `capFor(Role.STATUS)=THIN` 经 `fullness()` 比较把更厚的档**恒向下钳**为 THIN（实测 STATUS + REGULAR/THICK ⇒ `resolveTier=THIN`、`a=0x23`、合成 `T=0x44`）；这两格是历史/预留值，**保留在实现里不删**（§9.2 的「条状 `0x76`（REGULAR）/ `0xA2`（THICK）」同理不可达，仅表材质档上限）。② F1 判据措辞更正：「`surface(STATUS, ULTRA_THIN)` 与 THIN 行不同」**在节点 a 维度不成立** —— 两档的节点底色**同为 `0x23`**（`SURFACE_ALPHA` 两行 STATUS 列相同，与 review §14.3 一致）；F1 修复（`fullness()` 替换 `ordinal()`）的实质差异在**材质档**（`DARK_ULTRA_THIN` vs `DARK_THIN`）、**blur**（6 vs 8）、**lens 系数**（0.8 vs 1.0）与**合成总遮罩 T**（`0x39` vs `0x44`）。③ 本节状态栏行原先把 ULTRA 材质与 THIN 档 T 写在一起（G1），已改为两档并列。另记：`docs/qz-liquid-glass-verification-r2.md` 内仍有本机绝对路径（该报告不在 t13 三条 finding 范围内，但按仓库规约应在发布前用 `<repo>` / `<test-server>` / `<Qz-UILib>` 等占位符脱敏）。
> **t9 修正（round 2）**：本表「合成总遮罩 T」列最初三处算错（主面板/内容底板 0x5B、状态栏 0x46、卡片/按钮 0x7D），
> 已按 `T = a + t·(1−a/255)` 重算为 **0x59 / 0x44 / 0x73**；权威全表见 `docs/qz-liquid-glass-review.md` §14.3（review F13）。

| 面 | mcphone 载体 | 材质档 / blur / lens | 节点底色 a（渲染值） | 合成总遮罩 T | 文字色 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 手机主面板 | `PhoneUi.panel`（`PhoneUi.java:349-365`，圆角 `PANEL_RADIUS=22`） | `DARK_THIN` / 8 / 0.5 | **`0x3C14181C`**（60/255 = 23.5%，替代 `:71` 的 `0xF2`） | **0x59（35%）** | — （容器，无自带文字） | 壁纸 `WALLPAPER` 作为面板图源铺满 ⇒ 玻璃采样的是**世界背景**，壁纸在其上（BACKGROUND/IMAGE 都晚于 BACKDROP）⇒ 若壁纸不透明，玻璃只在壁纸透明处可见。**裁定**：壁纸保持现状（不透明显式），玻璃主要服务"无壁纸"与"内容区" |
| 内容底板 | `PhoneUi.contentSlot`（`:426-436`） | `DARK_THIN` / 8 / 0.35 | **`0x3C14181C`**（替代 `:75` 的 `0x90`） | **0x59（35%）** | 页面文字（`PhoneTheme.text()` / `PhoneWidgets.TEXT`） | "保证文字在任何壁纸/世界背景上可读"职责**由材质 tint（≈15% 黑）+ 玻璃态底色 + 深色壁纸共同承担**；改后必须手测"浅色壁纸 + 长文本"页（H7） |
| 状态栏 | `PhoneUi` statusBar（`:399-402`） | `DARK_THIN`（AUTO 真渲染）/ `DARK_ULTRA_THIN`（降级路径） / 8 或 6 / 0.5 或 0.3 | **`0x2314181C`**（35/255 = 13.7%，替代 `:73` 的 `0x99` **纯黑**；review F3：原 `0x25` 的合成 T=0x46 比裁定 T=0x40 深 5/255） | **0x44（27%）** = THIN 档（AUTO 真渲染）/ **0x39（25%）** = ULTRA_THIN 档（降级；review §14.3 `ULTRA_THIN + STATUS` 配对） | `PhoneTheme.text()` | 条状，缘带弱；**必须换掉纯黑**（§5.5）。同档位差异体现在**材质档 / blur / lens 系数 / 合成 T**，**节点 a 两档同为 `0x23`**（非缺陷，判据见 §9.3 表注）。导航条同档（`PhoneUi.java:448-449`） |
| 页面卡片 / 信息行 | `ScenePages.cardSurface()`（`ScenePages.java:50-79`）、`PhoneWidgets.PANEL`（`:24`） | `DARK_THIN` / 8 / 0.5 | **`0x5A14181C`**（90/255 = 35.3%） | **0x73（45%）** | `PhoneTheme.text()` / `MUTED`；附属见 addon-api | 原 `0x33FFFFFF`（20% 白）已淘汰 → 改用 `DARK_*` + 玻璃令牌深底（与 Qz 定稿一致，避免亮背景洗白浅字）；圆角 12 |
| 边框 | `PhoneWidgets.BORDER`（`:25`）、`ScenePages.COL_BORDER`（`:50`） | — | 保留 `0x55FFFFFF` / `0xFF39404B` 量级 | — | — | 玻璃面轮廓（§5.4）：边框是**倒角载体**，`borderWidth>0` 才出外倒角+内缘阴影（`SceneSurfaceReliefPainter.java:62-81`） |
| 自绘按钮（普通） | `PhoneWidgets.mountButton` | `DARK_THIN` / 6 / 1.0 | **`0x5A14181C`**（35.3%，替代 `:103` 的 `0xFF`），hover **`0x7E14181C`**（126 = 49.4%） | **0x73（45%）** / hover **0x91（57%）** | `PhoneGlass.text()`（=`0xFFE8EDF2`） | hover 只小幅加实，不再换 RGB（避免"变色"破坏玻璃感）；配方对齐 `HudToolbarSpec.java:43-44` |
| 主操作按钮（primary） | `PhoneWidgets.mountButton(primary=true)` | **实心，不上玻璃** | `0xFF2F5FA8`（保留）；hover `0xFF3A72C4` | — | `0xFFFFFFFF` | **裁定：保留实心**（§9.1 总则 3）；accent 面要"跳" |
| 长文本 / 编辑区 | `ScenePages.java:343-348`（`0xFF1A2028` / `0xFF101418`） | **实心保留** | 不变（255） | — | 现有 | **裁定：TextArea / 长文本区保留实心**（理由：`docs/AI-DEV-NOTES.md` 踩坑 #5 —— Qz 的 TextArea 字形绘制固定 16px，玻璃的折射缘带会与文本边缘互相干扰；且长文本可读性优先） |
| 图片查看底衬 | `ScenePages.java:564`、`ChatUi.java:395`（`0xFF000000`） | 实心保留 | 不变 | — | — | 图片需要中性背景 |
| 图片缩略图底衬 | `ScenePages.java:537`、`ChatUi.java:332`、`:470`（`0xFF101418`） | 实心保留 | 不变 | — | — | 同上（且本轮不动 ChatUi） |

### 9.4 旧配色淘汰清单（精确行号 + 替换方向）

| # | 文件:行 | 现值 | 淘汰动作 | 替换方向 |
| --- | --- | --- | --- | --- |
| P1 | `MC:client/scene/PhoneUi.java:71` | `COL_BG = 0xF20E1116` | **删除该常量** | `DARK_THIN` / blur 8 / lens 0.5，**节点底色（反解值）`0x3C14181C`**（合成总遮罩 **T=0x59/35%**，见 §9.3；旧文档写的 `0x59` 曾是 T 口径，现已统一为节点 a + T 双列，review F4 已修） |
| P2 | `MC:client/scene/PhoneUi.java:73` | `COL_STATUS_BG = 0x99000000` | **删除该常量** | `DARK_ULTRA_THIN` / blur 6 / lens 0.3 + **节点底色 `0x2314181C`**（0x18 基色，禁纯黑，§5.5；review F3 修正：原 `0x25` 偏深 5/255） |
| P3 | `MC:client/scene/PhoneUi.java:75` | `COL_PAGE_BG = 0x900E1116` | **删除该常量** | `DARK_THIN` + **节点底色 `0x3C14181C`**（合成总遮罩 **T=0x59/35%**） |
| P4 | `MC:api/PhoneWidgets.java:103` | `BTN_BG = 0xFF3A414D` | **删除该常量** | `DARK_THIN` / 6 / 1.0 + **节点底色 `0x5A14181C`**（合成总遮罩 **T=0x73/45%**，圆角 12） |
| P5 | `MC:api/PhoneWidgets.java:104` | `BTN_BG_HOVER = 0xFF4A5462` | **删除该常量** | 同档玻璃 + **节点底色 `0x7E14181C`**（126/49.4%，T=0x91/57%；不再换 RGB） |
| P6 | `MC:api/PhoneWidgets.java:105` | `BTN_PRIMARY_BG = 0xFF2F5FA8` | **保留（实心 accent）** | 仅统一为 accent token（供 ChatUi 复用，减少三处重复定义） |
| P7 | `MC:api/PhoneWidgets.java:106` | `BTN_PRIMARY_BG_HOVER = 0xFF3A72C4` | **保留（实心 accent）** | 同上 |
| P8 | `MC:client/apps/ScenePages.java:50` | `COL_PANEL = 0x33FFFFFF` | **保留值、改来源** | 值不变（20% 白）；但玻璃下建议 `0x73` + DARK 档（§9.3） |
| P9 | `MC:api/PhoneWidgets.java:24` | `PANEL = 0x33FFFFFF` | 同上 | 与 P8 合并为**单一 token 来源**（见 P12） |
| P10 | `MC:client/scene/PhoneUi.java:72` | `COL_BORDER = 0xFF39404B` | 保留（边框是倒角载体） | 玻璃面下建议降到 `0x55FFFFFF` 量级以出亮边 |
| P11 | `MC:client/apps/ScenePages.java:51` / `MC:api/PhoneWidgets.java:25` | `COL_BORDER = 0x55FFFFFF` / `BORDER = 0x55FFFFFF` | 保留 | 同上 |
| P12 | `MC:api/PhoneWidgets.java:22-25`（`TEXT/MUTED/PANEL/BORDER`）+ `ScenePages.java:50-51` + `ChatUi.java:34-37` | **三处重复定义同名同值 token** | **不删、但须登记**（本轮不动 ChatUi） | 下一轮把主壳 token 收敛到一处（如 `PhoneTheme`），ChatUi 单独一轮对齐 |

> 说明：P1–P5 是"**必须删的旧常量**"（它们的存在就是"把玻璃盖死"的原因）；P6–P7 是"**明确保留实心**"；
> P8–P12 是"**值可留、来源要收**"。凡标注"保留"的，也必须在实现里**显式写注释说明为何不上玻璃**，
> 否则评审无法区分"刻意保留"与"漏改"。

### 9.5 与既有主题色体系的关系

- `MC:client/enhance/PhoneTheme.java:25-30` 已提供 6 组 `{文字色, 次要文字色}`，**玻璃改造不替换它**：
  它管"文字"，玻璃管"表面"；两者正交（`PhoneUi.java:301/:317` 已用它取状态栏文字色）。
- `MC:client/apps/BuiltinApps.java:46-240` 的 12 个 App 图标色（`0xFF3E6E9E` 等）**全部保留**：
  图标是 accent 语言（§9.1 总则 3），且它们在 `iconBox`（`PhoneUi.java:485-487`）里是**实色小块**，
  上玻璃反而会让小图标失去识别度。
- `MC:client/enhance/WallpaperPresets.java:40-45` 的 6 套渐变是**玻璃的采样素材**：
  玻璃效果强弱在不同壁纸下差别很大 ⇒ 手测清单必须包含"逐壁纸 × 逐材质档"的最小组合。

---

## 附录 A：证据复现命令清单（可重跑；全部只读）

> 全部在 WSL 内执行（`wsl.exe -d Ubuntu-26.04 -- bash <script>`，脚本落盘再跑）。
> `QzSrc` = `<qz-artifacts>/qz_uilib-4.9.1-sources.jar` 解包内容（命令 A4 现场生成）。

| 编号 | 命令 | 产出 |
| --- | --- | --- |
| A1 | `git -C <Qz-UILib> status --porcelain` | 空（Qz-UILib 未被修改） |
| A2 | `git -C <repo> status --porcelain -- src` | 空（未改 src） |
| A3 | `git -C <Qz-UILib> ls-tree -r --name-only 4.8.0 \| grep -E 'UiBackdrop\|UiGlassMaterial'` / `… \| grep -cE '(^|/)(UiBackdrop\|UiGlassMaterial)\.java$'` | 3 行（名字含 UiBackdrop 的渲染器）/ **0**（精确计数） |
| A4 | `python3` + `zipfile`：把 `qz_uilib-4.9.1-sources.jar` 解到 `/tmp/qz491src`；把 `4.9.0-sources.jar` 解到 `/tmp/qz490src` | 698 / 689 个 `.java` |
| A5 | `curl -s --noproxy '*' "https://api.github.com/repos/QuanhuZeYu/Qz-UILib/releases?per_page=100"` | 4.9.1 / 4.9.0 / 4.8.0 及发布时间 |
| A6 | `python3` + `zipfile` 对比 `qz_uilib-4.9.0.jar` 与 `qz_uilib-4.9.1.jar` 条目；`diff -q` 对比两者 sources 的 7 个关键文件 | +18/-0；7 个文件全部 IDENTICAL |
| A7 | `grep -rn 'qz_uilib@\[4.8' <repo>/docs/` | **空**（旧残留已清）；前后对照见 §8 末与本表下方 |
| A8 | `python3` + `zipfile` 读 3 个 jar 的 `mcmod.info` 与 `club/heiqi/uilib/MyMod.class` 内联常量 | `4.9.1` / `4.9.1` / `4.8.0-4-0.308+7fd570ab83-dirty` |
| A9 | `unzip -l` 等价：`python3` 列 `libs/qz_uilib-dev.jar` 中 `UiBackdrop\|UiGlassMaterial\|ClientHudService\|Hud*\|UiRenderContext\|uiBackdrop` 条目 | 见 §1 各表"jar 条目"列 |

**`[4.8,)` 残留：前后 grep 对照（A7）**

```
# 前（t1 开始前）
$ grep -rn 'qz_uilib@\[4.8' <repo>/docs/
docs/AI-DEV-NOTES.md:21:├── MCphone               @Mod，依赖 required-after:qz_uilib@[…4.8,)
docs/SESSION-20260909-merged.md:152:10. ... 1.7.10 下 `@Mod` 注解 `required-after:qz_uilib@[…4.8,)` 才是有效声明。

# 后（t1 完成）
$ grep -rn 'qz_uilib@\[4.8' <repo>/docs/
(no match)

$ grep -rn '4\.8\|4\.9' <repo>/docs/
docs/AI-DEV-NOTES.md:10:...（4.9.0+，LGPL-3.0）场景栈上...            ← 由「4.8+」同步
docs/AI-DEV-NOTES.md:21:... required-after:qz_uilib@[4.9.0,)（下限=含液态玻璃的最小已发布版本...）
docs/AI-DEV-NOTES.md:45:- 运行依赖：qz_uilib-4.9.1.jar（实例 mods 里）...   ← 由「qz_uilib-4.8.0.jar」同步
docs/SESSION-20260909-merged.md:152:... `required-after:qz_uilib@[4.9.0,)` 才是有效声明...  ← 同步
docs/SESSION-20260909-merged.md:188:  - qz_uilib-4.8.0-4-0.308+7fd570ab83.jar / ...     ← 刻意保留（历史实录，§8）
```

**准确交代两处与任务书字面不一致的地方（不掩盖）**：

1. 任务清单把 `docs/AI-DEV-NOTES.md:45` 描述为 `[4.8,)` 残留；**实测该行不含 `@[4.8` 形式**，
   而是版本号残留 `qz_uilib-4.8.0.jar`（`grep -rn 'qz_uilib@\[4.8'` 只命中 `:21` 与
   `SESSION:152`）。已按同一意图同步为 `qz_uilib-4.9.1.jar`。
2. 另发现 `docs/AI-DEV-NOTES.md:10` 的「4.8+」与新的「4.9.0+」口径冲突（超出行号清单），
   一并同步为「4.9.0+」，理由与范围登记在此。

---

## 附录 B：文档自证

- 文件名：`docs/qz-liquid-glass-design.md`
- 节数：**9 个正文节（§1–§9）** + §0 摘要 + 附录 A/B（§1–§9 的标题与任务书要求的
  「玻璃 API 契约／渲染与降级口径／诊断口径／发布兼容风险／Qz 踩坑映射／性能与默认值／
  HUD 迁移边界／本轮不做清单／玻璃风格配色体系裁定」逐条同序对应）。
- 字节数/行数：见文件尾部自动生成的一行（由产出脚本 `wc -c` / `wc -l` 写入，避免"文档声称的
  存在性证据"与真实产物漂移）。
- 本轮改动文件：`docs/qz-liquid-glass-design.md`（新增）、`docs/AI-DEV-NOTES.md`（3 行）、
  `docs/SESSION-20260909-merged.md`（1 行）。**未改 `src/`、未改 `Qz-UILib`、未改 `libs/`。**

> **产物自证（自动生成，勿手改）**：本文件 `88561` 字节 / `1014` 行；正文 §1–§9 共 **9** 节（`grep -c "^## [1-9]\."`）。
> 数字为定稿后由产出脚本 `wc -c` / `wc -l` 生成并迭代到不动点，避免与真实产物漂移。
