# t5 独立验证报告：编译 / 构建 / 服务端冒烟 / 能力降级负控 / 玻璃渲染路径取证

> 任务 **t5**（kind=verification，attempt 1，attempt_id `e642e9b2-71be-46f3-95b7-c58dcd0bcd03`）· 执行者 **verify**（独立于实现者 t2/t3/t4）
> 仓库 `<repo>` · `git rev-parse HEAD` = `a328673ccddd5cd814c56be94119d18131f455a4` · `git describe` = `v1.0.3-beta.2-10-ga328673-dirty`
> inScope：`mcphone-gtnh/docs/`（本文件）。**未修改任何实现代码**（`src/` 零改动，见 §11 自证）。
> 依据：`docs/qz-liquid-glass-design.md`（t1，88561 字节）§2/§3/§6/§7 + 附录 A。
> 本报告中的所有"实现者自证"均**未被引用**；负控与字节码取证由本任务**独立重写/独立复现**。

---

## 0. 结论摘要

| 项 | 结论 |
| --- | --- |
| 完整构建（含强制真编译） | **通过**，`exit=0`，产物 `build/libs/mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar` |
| jar 内容检查 | **通过**，新类在内（`PhoneGlass` + 3 枚举 + `$1`）；`club/heiqi/` 条目 **0**（未把 Qz 打进 mcphone jar） |
| 服务端冒烟（新制品） | **通过**，`Done (1.860s)`；mcphone 相关 `NoClassDefFoundError/NoSuchMethodError/ExceptionInInitializerError` = **0**；`kill -TERM` 正常停止（rc=143） |
| 静态取证（grep + 字节码两层） | **通过**，四玻璃模式唯一命中 `PhoneGlass`（grep 1 文件 / 字节码 148 与 147 个 class 各 1 文件） |
| 降级负控（能力不可用 / 开关关闭） | **通过**，两条路径均不抛 LinkageError，且回落色/文字色符合裁定 |
| 三态判据期望取值表 | 已产出（§8），含 **"诊断可能过期"** 判读说明与取样纪律 |
| 必须用户手测清单 | 已产出（§10） |
| 独立发现的缺陷 | **F1 medium（capFor 档位钳制反向）**、F2 low、F3 low/medium（文档-实现漂移）、F4 low（三态诊断无 UI 出口） |
| 是否阻断下游（t6/t7） | **不阻断**：F1–F4 都不是崩溃/加载失败类；但 F1 会使手测"降级时更薄"的预期落空，F3 会让 t6/t7 拿错参考值，建议在 t7 手测说明里先修正参考值 |

---

## 1. 验收逐条对照

| # | 验收标准 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 记录命令/退出码/产物路径；发布 jar 存在且给版本号（来自 git describe） | passed | §2 |
| 2 | jar 内类检查（python3 zipfile；新类在内、Qz 玻璃类未被打进 jar） | passed | §3 |
| 3 | 服务端冒烟启动至 Done、无 mcphone 异常、`kill -TERM` 正常停止（给关键日志行） | passed | §6 |
| 4 | 静态取证：除 PhoneGlass 外零字面引用（给 grep 命令与输出） | passed | §4 |
| 5 | 降级负控：能力不可用/开关关闭时不抛 LinkageError（给证据） | passed | §5 |
| 6 | 三态判据期望取值表（NONE/SHADER/FIXED_PIPELINE/TINT_FALLBACK） | passed | §8 |
| 7 | 报告明确列出「自动化无法验证、必须用户手测」的项目 | passed | §10 |
| 8 | 未修改任何实现代码（`git status --porcelain -- src` 为空） | passed | §11 |

---

## 2. 完整构建（验收 1）

```bash
# 契约命令（原样执行）
$ cd <repo> && ./gradlew build --build-cache -x test
BUILD SUCCESSFUL in 5s
17 actionable tasks: 6 executed, 11 up-to-date
# exit = 0   （:compileJava UP-TO-DATE —— 因 t4 已编译过同一份 src）
```

```bash
# 补充：强制真编译（清掉 classes 并禁缓存），排除 UP-TO-DATE 造成的"假通过"
$ rm -rf build/classes/java/main && ./gradlew build -x test --no-build-cache
> Task :compileJava
Jabel: initialized                       <-- 编译器真跑（含 Mixin AP note）
BUILD SUCCESSFUL in 5s
17 actionable tasks: 1 executed, 16 up-to-date
# exit = 0
```

- `git describe --tags --always --dirty` = **`v1.0.3-beta.2-10-ga328673-dirty`**（HEAD `a328673`）
- 发布 jar（不带 `-dev/-api/-sources` 后缀者）：
  **`build/libs/mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`**（362718 B，2026-09-15 01:47）
  同批产物：`-dev.jar` 360927 B、`-api.jar` 25003 B、`-sources.jar` 249438 B
- jar 内 `mcmod.info` 的 `version` = `v1.0.3-beta.2-master.10+a328673ccd-dirty`（与文件名一致）
- ⚠️ 同 HEAD 还存在 01:02 生成的旧串 `mcphone-v1.0.2-master.43+a328673ccd-dirty.jar`（同为 dirty、同 commit，仅 describe 的 tag 基线不同）⇒ **t7 装 jar 时请认准 `v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`**，勿按文件名新旧直觉挑。

---

## 3. jar 内容检查（验收 2，python3 zipfile；WSL 无 unzip）

```
[jar] entries=191   .class=148
PhoneGlass 相关条目：
    com/november/mcphone/client/enhance/PhoneGlass$1.class        1488
    com/november/mcphone/client/enhance/PhoneGlass$Path.class     1452
    com/november/mcphone/client/enhance/PhoneGlass$Role.class     1489
    com/november/mcphone/client/enhance/PhoneGlass$Tier.class     1783
    com/november/mcphone/client/enhance/PhoneGlass.class         14119
其它关键类：ClientHooks.class / PhoneCanvas.class / apps/ScenePages.class / hud/PhoneHud.class /
            hud/PhoneHud$HudPhoneUi.class / scene/PhoneUi.class
club/heiqi/ 前缀条目 count = 0        <-- Qz 玻璃类未被 shade 进 mcphone jar
```

---

## 4. 静态取证（验收 4）

### 4.1 grep 层（契约命令 + 实际输出）

```bash
$ grep -rn 'UiBackdrop\|UiBackdropEffect\|UiGlassMaterial\|BackdropBlur' src/main/java/
```
命中 **9 行，全部在 `client/enhance/PhoneGlass.java`**（`grep -rl … | wc -l` = **1**）：

| 行 | 内容性质 |
| --- | --- |
| 11 / 12 / 17 | javadoc 里的契约文字 |
| 197–199 | 4 个探测用**字符串常量**（`CLASS_MATERIAL/CLASS_BACKDROP/CLASS_EFFECT`） |
| 208 | 注释 |
| 316 | `node.setBackdrop((club.heiqi.uilib.ui.render.UiBackdrop) backdrop);`（**唯一类字面引用**） |
| 381 / 467 / 499 | 注释 |

### 4.2 字节码层（关键：grep 可能被字符串拼接/反射绕过，故独立扫常量池）

自写 python3 扫描，对每个 `.class` 的原始字节做四模式匹配（`UiBackdrop` / `UiGlassMaterial` / `BackdropBlur` / `UiBackdropEffect`）：

```
[BYTECODE/jar]          扫描 .class = 148，命中文件数 = 1
    HIT com/november/mcphone/client/enhance/PhoneGlass.class   patterns=[UiBackdrop, UiGlassMaterial, UiBackdropEffect]
[BYTECODE/classes-dir]  扫描 .class = 147，命中文件数 = 1
    HIT com/november/mcphone/client/enhance/PhoneGlass.class
```

- 另用更宽的 `club/heiqi/uilib/ui/render` 前缀扫描时，`PhoneUi.class` 也命中 —— 逐一核对后确认是 **`UiRenderBackend`**（`PhoneUi.java:955 public void render(..., club.heiqi.uilib.ui.render.UiRenderBackend ctx, ...)`），**不是玻璃类型**，属预存在代码。
- 附带更正：t2 自述"扫 146 个 `.class` 唯一命中" —— 命中**唯一性**成立，但计数已漂移（现为 jar 148 / classes 目录 147，因 t3/t4 新增了 `PhoneHud$HudPhoneUi`、`PhoneGlass$1` 等类）。不影响结论。
- `setBackdrop` / `getBackdrop` / `liquidGlass` 的引用面：**仅 `PhoneGlass.java`**（`src/` 内其余命中都是注释），即"玻璃只从门面进出"的隔离契约成立。

---

## 5. 独立降级负控（验收 5）——**独立复现，非引用实现者证据**

> 实现方式与 t2 无关：本任务自写 `GlassProbe3`（纯反射 + 自研 `ProbeLoader extends URLClassLoader`，parent 设为 **platform loader** 且工程类只从 URL 加载；`loadClass` 里对指定类名**直接抛 `ClassNotFoundException`** 以模拟"宿主没有玻璃类"）。
> 为了让**真 `PhoneCanvas`**（而非替身）参与开关路径，harness 用 `sun.misc.Unsafe.allocateInstance` 造了一个 `Minecraft` 实例并写入 `theMinecraft` / `mcDataDir`（临时目录），从而让 `PhoneCanvas.load()` 读到真实的 `settings.properties`。

### 5.1 正控（真实 Qz 4.9.1 + `libs/qz_uilib-dev.jar`，无遮蔽）：33 PASS / 0 FAIL

```
available=true  glassOn=true  renderPath=NONE  label=none  detail=not-run
PASS 正控：apply(node,PANEL,THIN) → node.getBackdrop() 非 null 且类型=UiBackdrop
     | before=null  after=club.heiqi.uilib.ui.render.UiBackdrop
PASS 正控：clear(node) → getBackdrop()==null
PASS materialTintAlpha(THIN/ULTRA_THIN/REGULAR/THICK) = 0.14901961 / 0.101960786 / 0.2 / 0.3019608
PASS surface(role,tier) 6 角色 × 4 档 = 24/24 与 PhoneGlass.SURFACE_ALPHA 反解表逐值一致
PASS compositeAlpha(PANEL,THIN)=0.3493(0x59) / (CARD)=0.4494(0x73) / (STATUS)=0.2725(0x45)
```
⇒ 玻璃配方确实被**真实构造**出来并挂到节点上（不是"只返回了非 null 的占位"）。

### 5.2 负控 A：玻璃类整体缺失（遮蔽 5 个类）：38 PASS / 0 实现缺陷

```
available=false  glassOn=false  renderPath=UNAVAILABLE  label=unavailable  detail=""
PhoneCanvas 被触碰=false     <-- glassOn() 里 available() 短路，根本没走到配置读取
resolveTier(PANEL,{AUTO,THIN,ULTRA_THIN,REGULAR,THICK}) = null ×5
surface(6 角色,THIN) = PANEL/STATUS/CARD/BUTTON/HOVER 0x1FF2F5F8，PAGE 0x4C14181C
compositeAlpha = 中性 alpha（0.1216 / PAGE 0.2980）；text()=0xFF232A33；muted()=0xFF4A5460
materialTintAlpha(THIN)=0.0
apply(node,role,tier) / apply(node,role) / apply(null,…) / clear(node) / refreshDiagnostics() / diagSummary() 全部不抛
LinkageError 计数（除下述 harness 自证外）= 0
```
唯一一条 FAIL 是 **harness 自身的取证调用**，如实记录：断言里为了检查"没挂上玻璃"而调用了 `SceneNode.getBackdrop()`，而该 getter 的**返回类型正是被遮蔽的 `UiBackdrop`** ⇒ 抛 `NoClassDefFoundError: club/heiqi/uilib/ui/render/UiBackdrop`。同一次调用中 `PhoneGlass.apply(...)` 本身**正常返回、无异常**。mcphone 侧不会发生这种情况（§4.2 已证 `getBackdrop` 零引用）。

### 5.3 负控 B：开关关闭（玻璃类在场 + `glassEnabled=false`，真 PhoneCanvas）：15 PASS / 0 FAIL

```
available=true  glassOn=false
resolveTier(PANEL,THIN)=null
surface(6 角色,THIN) = 0x1FF2F5F8 ×5 + PAGE 0x4C14181C
text()=0xFF232A33   muted()=0xFF4A5460
apply(node) 不抛且 getBackdrop()==null；apply(node,role) 不抛；refreshDiagnostics()/diagSummary() 不抛
回写 glassEnabled=true 后：glassOn()=true 且 surface(PANEL,THIN)=0x3C14181C   <-- 开关双向生效
```
两条路径全程 `NoClassDefFoundError / NoSuchMethodError / ExceptionInInitializerError` 计数 **0**。

---

## 6. 服务端冒烟（验收 3，含队长修订的 3b）

### 6.1 3b 换件（先换**本次构建**的制品）

| 项 | 值 |
| --- | --- |
| 旧 jar（换出前在服务端 mods 里） | `mcphone-v1.0.2-master.29+5da5132e36.jar` |
| 备份名 | `mcphone-v1.0.2-master.29+5da5132e36.jar.bak-20260915-015112` |
| 换入的新 jar | `mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`（362718 B） |
| 完整性 | md5 双端一致：`ae064e9a90d0ea455a238c7af360578c` |

### 6.2 启动（契约命令，前台 java 挂在 harness 后台任务下，PID 落盘供 TERM）

```bash
cd <test-server>
java -Xms3G -Xmx3G -Dfml.readTimeout=180 -Duser.language=en @java9args.txt -jar lwjgl3ify-forgePatches.jar nogui
```
关键日志行（`/tmp/t5_server.log`，3347 行）：

```
371 : [01:51:40] [main/INFO] [FML]: Examining mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar to load
1334: [01:52:00] [Server thread/INFO] [qz_uilib]: I am Qz-UILib at version 4.9.1
3138: [01:52:15] [Server thread/INFO] [fml.ModTracker]: This world was saved with mod mcphone version
      v1.0.2-master.29+5da5132e36 and it is now at version v1.0.3-beta.2-master.10+a328673ccd-dirty
3162: [01:52:17] [Server thread/INFO]: Done (1.860s)! For help, type "help" or "?"
```

- `mcphone` 相关 LinkageError 扫描：**0**（`grep -n 'mcphone' … | grep -cE 'NoClassDefFoundError|NoSuchMethodError|ExceptionInInitializerError'` = 0）。
- 日志里出现的 `ClassNotFoundException` 全部来自**无关 mod 的 mixin 预扫描**（`galaxyspace.*`、`MoreFunQuicksandMod.main.MFQM`）与 `gregtech…BlockOres` 的 STDERR，**无一与 mcphone / PhoneGlass 相关**（这些在改造前同样存在）。
- **3b 的验证意义（如实口径）**：服务端不加载 `PhoneGlass`（它只被客户端包引用），所以本次冒烟证明的是「**整个新 jar 在服务端加载/初始化不炸**」；「玻璃类缺失时探测桥不炸」由 §5.2 的负控 harness 直接、严格地证明。

### 6.3 停止

`kill -TERM <java pid>` → 进程在 60s 内退出，harness 记录 `T5_SRV_JAVA_EXIT rc=143`（128+15 = SIGTERM，属预期优雅停止）；停止后 `pgrep -af lwjgl3ify-forgePatches` 为空（无残留）。

> 备注（预期噪声）：备份文件因扩展名是 `.bak-…`，被 FalsePatternLib DepLoader 记为
> `WARN Skipping non-directory, nor jar source: …/mcphone-v1.0.2-master.29+5da5132e36.jar.bak-20260915-015112`（第 7 行）。
> 这是备份动作的自然结果，**不是缺陷**；如需消除噪声可在手测完成后把备份移到 mods 目录之外。

---

## 7. 依赖下限复验（验收附带项）

```
$ grep -n 'qz_uilib@' src/main/java/com/november/mcphone/MCphone.java
33:     dependencies = "required-after:qz_uilib@[4.9.0,)")
```
- 实例 `290b3test/.minecraft/mods/qz_uilib-4.9.1.jar` 的 `mcmod.info` → `"version": "4.9.1"`（≥ 4.9.0 ✓）
- 服务端 `mods/qz_uilib-4.9.1.jar` 同上（4.9.1 ✓）
- ⇒ **不会出现"mcphone 加载失败 / 玻璃静默回落"**；且 §6 冒烟在真 Qz 4.9.1 下通过。

---

## 8. 三态判据期望取值表（验收 6，用户手测对照用）

> 取值来源 = `UiRenderContext.getLastBackdropFilterRenderPath()` + `getLastBackdropFilterDetail()`（design doc §1.6/§2.3），mcphone 侧镜像为 `PhoneGlass.renderPath()` / `renderPathLabel()` / `diagDetail()` / `diagSummary()`。
> 实测初始值（本任务 harness 正控，未渲染过的会话）：`NONE` / label `none` / detail **`not-run`**。

| 态 | 枚举值（label） | `detail` 前缀 | 用户看到什么 | 判读要点 |
| --- | --- | --- | --- | --- |
| **真渲染** | `SHADER`（`shader`） | `blur=<n>, saturation=<f>, family=…, material=…, lens=…, snapshot=…` | 完整液态玻璃：背景被模糊折射 + 折射缘带 + 镜面高光 + 材质 tint | 唯一"完全体"态 |
| **降级（固定管线）** | `FIXED_PIPELINE`（`fixed-pipeline`） | `shader-unavailable, samples=<4..16>, effect-degraded(no-vibrancy), snapshot=…` 或 `shader disabled by config` | **有模糊、无 vibrancy/亮边/噪点**（材质档降级为"仅模糊"） | 属"降级"，记账为降级而非失败 |
| **降级（纯 tint）** | `TINT_FALLBACK`（`tint-fallback`） | `<上游失败原因>, family=…, material=…`（上游串如 `texture-copy-unavailable` / `snapshot-unavailable: …` / `fixed-pipeline-disabled` / `shader-and-blur-unavailable`） | **连模糊都没有**：纯色玻璃底 + 亮边（材质色/高光仍在） | 不要把 tint fallback 关掉（L3 处置） |
| **静默不绘** | `NONE`（`none`） | `disabled by page policy` / `skipped` / `tint-fallback-disabled: <detail>` | 什么都没画（无玻璃），**无异常、无日志** | 三种成因靠 detail 区分；只有第三种说明"曾尝试真渲染但失败" |
| **静默不绘（门面短路）** | **保持上一帧的值**（不一定非是 `NONE`） | 同上 | 什么都没画，但诊断**停在旧值** | ⚠️ 见下方"诊断可能过期" |

### 8.1 ⚠️「诊断可能过期」——必须写进手测判读（t1 新结论 + 本任务确证）

1. `UiRenderBackends.backdropFilter(...)` 在 `resolveContext(backend) == null` 时**直接 return、不调用 `recordPath`**（`UiRenderBackends.java:73-75 / :104-106`）⇒ 诊断值**保持上一帧/上一块玻璃的结果**。
2. 因此**存在「读到 `SHADER` 但屏幕上根本没有玻璃」的合法情形**（成因通常是 §5.1 装饰器穿透 `backend instanceof UiRenderContext == false`，整块静默不绘）。
3. 诊断是**进程级全局静态**（`UiBackdropFilterRenderer` 的两个 `volatile`），语义 = **"最近一次真的走到渲染器的 backdrop 请求"**，**不是"当前帧/当前节点"**；也无法区分是哪个节点/矩形（只能靠一次性只挂一块玻璃 + detail 里的 `snapshot=WxH @x,y fbo=…` 反推）。
4. mcphone 侧 `PhoneGlass.renderPath()` **额外还有一层会话内首次缓存**（幂等读，需 `refreshDiagnostics()` 才重读）⇒ 读到旧值是**双重可能**。
5. **取样纪律（建议写进手测说明）**：
   - 单一结论不要只读一次 —— **连续两帧各读一次**，两次一致才作数；
   - **切换开关/档位/页面「前」和「后」各读一次**做对照（值不变 = 说明这段路径没写诊断，而不是"没生效"）；
   - 看到"有玻璃但 path 非 SHADER"或"path 是 SHADER 但没玻璃"时，**先怀疑诊断过期/门面短路**，再怀疑实现；
   - 同批兄弟玻璃出现 `snapshot=reused …` 是**预期**（`beginBackdropBatch` 冻结主层版本号），不是复用 bug。
6. 口径补充（本任务实测）：`AUTO` 档在**会话首次**会因 `path==NONE` 解析成 `ULTRA_THIN`（详见 §9 O1），因此"默认档看起来是极薄"属**预期**。

---

## 9. 独立发现（findings）与观察项

### F1（medium）`capFor` 用枚举 ordinal 比较档位，而 `Tier` 序号**非单调**，导致 STATUS 的"更薄"请求被**反向加厚**

- 位置：`src/main/java/com/november/mcphone/client/enhance/PhoneGlass.java:362-368`（`capFor(Role,Tier)`）、`:183-190`（`SURFACE_ALPHA` 表）
- 事实：`Tier = AUTO(0), THIN(1), ULTRA_THIN(2), REGULAR(3), THICK(4)`；`capFor(STATUS) = THIN`；`capFor` 判据是 `tier.ordinal() > cap.ordinal() ? cap : tier`。⇒ 对 STATUS 请求 `ULTRA_THIN(2) > THIN(1)`，被**钳成 THIN**（即"更薄"变成了"更厚"）。
- 实测证据（真 Qz 4.9.1）：`surface(STATUS,ULTRA_THIN)` = **`0x2514181C`**(alpha 37)，而 `SURFACE_ALPHA` 表里 ULTRA 行的 STATUS 值 `0x2E14181C`(46) **不可达**；`resolveTier(STATUS,ULTRA_THIN)` 返回 **`THIN`**（连带材质档 `DARK_THIN`/blur 8/lens×1.0，而非设计 §6.2 的 `DARK_ULTRA_THIN`/blur 6/lens×0.8）。
- 连带后果：STATUS 无论请求哪一档最终都落到 THIN（AUTO/THIN/ULTRA/REGULAR/THICK → THIN），故 `SURFACE_ALPHA` 第 3 行（ULTRA）的 STATUS 列是**死值**；`AUTO` 在降级路径（`FIXED_PIPELINE`/`TINT_FALLBACK` ⇒ `ULTRA_THIN`）对状态栏不会变薄，与 §6.3 的 L1/L2「降级更薄」意图相反。
- 同时**证伪 t4 证据表**的一行：`_t4_evidence.md:42`「AUTO 降级路径 … status `0x2E`(46)」**不可达**。
- requiredFix（任选其一，属 t2 文件、不在本任务 inScope）：①`capFor` 改为按"厚度"语义比较（给 `Tier` 加显式 thickness 权重/序，而非 ordinal）；②把 `capFor(STATUS)` 设为 `ULTRA_THIN`；③显式禁止 STATUS 上升档但允许下降档。修完需同步 `SURFACE_ALPHA` 注释与 t4 证据表。

### F2（low）`compositeAlpha(BUTTON_HOVER,THIN)` 超出 §9.3 裁定总遮罩

- 实测 `0.5695`（= `0x91`），裁定目标 §9.3 = `0x8C`（0.549），偏差 **+5/255**（本任务容差取 ±2/255）。
- 成因：悬停行节点底色取 `0x7E`(126)；若按 `T=0x8C` 反解（`a=(140-38)/217*255`）应得 ≈`0x78`(120)。即悬停值与其余角色不同源。
- 影响：悬停态比裁定略实（观感层面极小）。requiredFix：重算悬停行反解值，或把 §9.3 的 `0x8C` 更正为 `0x91`。

### F3（low/medium，文档-实现漂移；t2 文件）

1. **R3 独立确证**：`neutralSurface(role)` 只对 `Role.PAGE` 返回 `0x4C14181C`，`Role.CARD` 实测 `0x1FF2F5F8`；而 `PhoneGlass.java:38-41` 的对照表 CARD 列写 `0x33FFFFFF`。
2. **另一处同类漂移（本任务新发现）**：`PhoneGlass.java:44` 写「按钮悬停 `0x8C14181C` 140 = 55%」，但实现 `SURFACE_ALPHA[THIN][HOVER] = 0x7E`，`surface(BUTTON_HOVER,THIN)` 实测 = **`0x7E14181C`**。t4 证据表沿用了错的 `0x8C`。
3. 影响：t6/t7 若照 javadoc/t4 表核对色值会误判。requiredFix：修正 `PhoneGlass` javadoc 表两行 + 同步 t4 证据表。

### F4（low，可判定性缺口）三态诊断在 mcphone 侧**没有任何出口**

- 证据（`grep -rn 'diagSummary\|renderPathLabel\|diagDetail\|refreshDiagnostics\|PhoneGlass.renderPath' src/main/java/`）：命中 6 行**全部是 `PhoneGlass.java` 内的定义/注释**，`src/` 内**零调用点**。
- 后果：design doc §3.3 建议的"F3 覆写 / 设置页诊断行 + 状态变化才打印一行"未落地 ⇒ §8 的三态取值表在游戏内**读不到**，用户手测只能靠观感与本文档比对。
- requiredFix（建议 t6/t7 裁定，非本任务 inScope）：至少加一处出口（F3 覆写或设置页一行 `PhoneGlass.diagSummary()`，值变才打印），否则"静默失败可判定化"这一设计目标在本轮无法被用户验证。

### 观察项（OBS，均非缺陷）

| 编号 | 观察 | 说明 |
| --- | --- | --- |
| O1 | 正控下 `AUTO` 首帧解析 = `ULTRA_THIN` | 因 `renderPath()==NONE`（首次读取）。**R2 确证**，属预期；`refreshDiagnostics()` 后若真渲染过则变 `THIN` |
| O2 | §2.3 初始值实测 = `NONE` / label `none` / detail **`not-run`** | 与 design doc §2.3「初始值 NONE / not-run」一致 |
| O3 | 服务端 `.bak` 备份文件产生 DepLoader WARN | 备份动作的自然结果（§6.3），非缺陷 |
| O4 | STATUS 的 `REGULAR`/`THICK` 请求同样落到 `THIN` | 与 F1 同源（钳制方向问题只影响"往下要更薄"的方向） |
| O5 | 字节码扫描计数 148/147 ≠ t2 自述的 146 | 仅计数漂移（t3/t4 新增类），唯一命中结论不变 |

---

## 10. 「自动化无法验证、必须用户手测」清单（验收 7）

> 前置：本轮 AI **未启动游戏客户端**（硬约束）；服务端冒烟已做（§6）。以下项目只能由用户在本机实例 `290b3test` 里手测。

| # | 手测项 | 判读口径 / 预期 |
| --- | --- | --- |
| H1 | **玻璃观感是否真的成立**：主面板/状态栏/内容底板/卡片/按钮是否透出并模糊**世界背景**（不是"看起来像深色板"） | 自动化只能证明"挂上了 UiBackdrop"，不能证明"合成结果好看" |
| H2 | **模糊清晰度与档位差异**：设置页档位 chip（自动/薄/极薄/常规/厚）切换后观感差异是否可辨、强度滑条（0–100%）是否单调 | 关掉强度=0 应等价"有玻璃但无折射缘带" |
| H3 | **默认档可能是"极薄"** | **预期，不是缺陷**（R2：`AUTO` 首次解析成 `ULTRA_THIN`）。若想确认真渲染，请先让页面渲染几帧再切换一次开关/档位（会触发 `refreshGlassShell()` 重建） |
| H4 | **HUD 缘光恒为静态 -55°（右上）** | **预期，不是缺陷**：Qz 宿主 `UiHudRenderListener.java:123` 给 HUD 帧传指针 `(0,0)`，`UiRenderBacklight` 退回静态默认光向；**全屏页面仍随鼠标指针动**（`McScreenBridge.java:178-180` 传真指针）⇒ HUD 与全屏观感有差异是宿主行为 |
| H5 | **性能 / 帧率**：F3 帧率对比（HUD 常显开/关）、主面板叠加 3 层 backdrop（panel/contentSlot/grid，R5）时的 FPS | 自动化无法测帧时间；如掉帧明显，按 §6.3 的 L1（`BackdropBlurPolicy.performance()` + 主面板 lens↓blur↓）处理 |
| H6 | **拖拽手感与命中盒**：主屏图标长按拖动排序（阈值 10px）、手机 HUD 拖拽移动、Ctrl+滚轮缩放、G 键开关、点击打开手机 | 尤其**装了其它 HUD mod**时：mcphone 读不到宿主被 `registerAvoidance` 撑大的安全区，命中盒可能与渲染盒有偏移 |
| H7 | **亮色壁纸下小字号可读性（R1）**：雪原/亮壁纸 × 设置页小字/状态栏时间/长文本页 | 设计 §9.2 裁定「只改底色 alpha、不改文字色」；§5.2 已算最坏情况可能 <2:1。若确认不可读，属**跨文件后续裁定**（动 `PhoneTheme`/`PhoneGlass.text()`），不是本任务缺陷 |
| H8 | **实心保留面**：便签编辑页 TextArea、相册缩略图/大图、长文本节点应**保持实心**（无玻璃折射干扰文字） | 三处带 `【刻意保留实心】` 注释 |
| H9 | **设置项即时生效且不崩**：玻璃开关 / 档位 chip / 强度滑条（含拖动中、松手后整壳重建 `refreshGlassShell()`） | 延迟到渲染帧开头执行；不应出现闪退/手势中断 |
| H10 | **三态取值本身无法在游戏内读取**（F4）：如需确认当前处于 SHADER/FIXED/TINT/NONE，本轮**没有 UI 出口** | 需要时按 §8 的取样纪律在调试环境读，或等 F4 裁定后补出口 |
| H11 | 关玻璃 → 全 UI 中性浅底（`0x1FF2F5F8` 系 / PAGE `0x4C14181C`），文字仍可读；再开 → 恢复玻璃 | 开关双向已在 harness 证过（§5.3），观感需人眼确认 |
| H12 | 联机/服务端环境正常（本轮服务端冒烟已过，客户端进服仍需人测） | — |

---

## 11. 未修改实现代码自证（验收 8）

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
- 与 t5 开工前**逐字一致**（该改动面全部来自 t2/t3/t4）；本任务**只新增 `docs/` 下的本文件**。
- `git -C <Qz-UILib> status --porcelain` = 空（Qz-UILib 未被改动，硬约束①满足）。
- 本任务未 push / 未打 tag / 未发版；未启动游戏客户端（硬约束②③满足）。
- 服务端 `mods/` 的换件属环境操作（不在仓库 inScope 管辖内），已按 3b 记录新旧名（§6.1）。
- 仓库内**无临时脚本残留**（harness 与脚本全部落在 `/tmp/t5_neg/` 与 Windows 侧 `<workspace>\_t5_*.sh`，后者用完即删）。

---

## 12. 复现命令清单（全部只读 / 可重跑）

| 编号 | 命令 | 用途 |
| --- | --- | --- |
| V1 | `cd <repo> && ./gradlew build --build-cache -x test; echo $?` | 契约构建 |
| V2 | `rm -rf build/classes/java/main && ./gradlew build -x test --no-build-cache` | 强制真编译 |
| V3 | `python3 -c "import zipfile;z=zipfile.ZipFile('<jar>');print(len(z.namelist()),[n for n in z.namelist() if n.startswith('club/heiqi')])"` | jar 内容 + 无 Qz 条目 |
| V4 | `grep -rn 'UiBackdrop\|UiBackdropEffect\|UiGlassMaterial\|BackdropBlur' src/main/java/` | 静态取证（grep 层） |
| V5 | 自写 python3 原字节扫描 `build/classes/**/*.class` 与 jar 内 `.class` 的四模式 | 静态取证（字节码层） |
| V6 | `/tmp/t5_neg/GlassProbe3.java`（本任务自写）+ `java -cp … GlassProbe3 {positive\|missing\|switchoff} /tmp/t5_cp2.txt /tmp/t5_neg/gamedir` | 正控 / 类缺失负控 / 开关关闭负控 |
| V7 | `./gradlew --init-script /tmp/t5_dumpcp.gradle dumpCp -q --no-configuration-cache` | 导出 runtimeClasspath（V6 用；另需追加 LWJGL 2.9.4 两个 jar 让 MC 可初始化） |
| V8 | `kill -TERM $(cat /tmp/t5_srv_java.pid)` + `pgrep -af lwjgl3ify-forgePatches` | 服务端优雅停止与残留检查 |

---

## 13. 交付给下游（t6 评审 / t7 装 jar 手测）的要点

1. **先修参考值再手测**：F1（状态栏"降级更薄"不可达；`0x2E` 死值）与 F3（悬停 `0x8C` 实为 `0x7E`；卡片非玻璃态 `0x33FFFFFF` 实为 `0x1FF2F5F8`）会让按 t4 表/`PhoneGlass` javadoc 对照的人误判。
2. **手测必看**：H3（默认档=极薄是预期）、H4（HUD 缘光静态 -55° 是预期）、H7（亮壁纸小字对比度）、H10（三态无 UI 出口）。
3. **jar 认准**：`mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`（§2 的版本串陷阱）。
4. 服务端 `mods/` 里旧件备份名：`mcphone-v1.0.2-master.29+5da5132e36.jar.bak-20260915-015112`（如需回滚）。
