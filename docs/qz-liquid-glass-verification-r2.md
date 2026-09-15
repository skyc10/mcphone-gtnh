# t10 修复后重新验证报告（t8 repair-round-2 之后的独立复验 · t7 装 jar 前门禁）

> 任务 **t10**（kind=verification，attempt 1，attempt_id `61934e31-e17b-4558-9fd2-fbbd7598415d`）· 执行者 **verify**（独立于 t2/t4/t5/t8/t9）
> 仓库 `<repo>` · HEAD `a328673ccddd5cd814c56be94119d18131f455a4` · `git describe` = `v1.0.3-beta.2-10-ga328673-dirty`
> inScope：`mcphone-gtnh/docs/`（本文件）。**未修改任何实现代码**（`src/` 零改动，见 §11）。
> 本轮背景：t5 的证据是在 t8 修复**之前**取的；t8 改了行为代码（`fullness()` 替换 ordinal 比较、`SURFACE_ALPHA` 的 THIN.STATUS 与 ULTRA 行、新增 `logDiagnosticsIfChanged()` 诊断出口、`PhoneHud.onGlassSettingsChanged()`），却只跑了 `compileJava`。本报告把全部证据在**修复后的代码**上重做一遍。
> **独立原则**：本报告不复用 t2/t5/t8/t9 的自证；行为结论全部由本任务自写 harness（`GlassProbe10/10b/10d`）现场产出。

---

## 0. 结论摘要

| 项 | 结论 |
| --- | --- |
| 完整构建（契约命令 + 强制真编译） | **通过**，`exit=0` |
| 最终发布制品 | `build/libs/mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar` · 363620 B · mtime **2026-09-15 02:23:04** · md5 **`eb1f175777ce9cdbca69f9011c89a387`** |
| jar 内容 | **通过**（entries=191 / .class=148；PhoneGlass 族 5 个类在内；`club/heiqi/` = **0**） |
| **F1 行为取证** | **通过**：`resolveTier(STATUS,ULTRA_THIN)=ULTRA_THIN`、`surface=0x2314181C`、recipe 真换成 `DARK_ULTRA_THIN/blur6/lens×0.8`；`fullness(ULTRA)=0 < fullness(THIN)=1` 且 `capFor` 只降不升 → 反向钳制已消除 |
| **单一真值对账** | **通过**：`compositeAlpha()` 8 格（PANEL/STATUS/CARD/BUTTON × THIN/ULTRA）与文档 T 列 **Δ=0，8/8**；另 4 格（PAGE、HOVER）亦 Δ=0 |
| **新诊断出口** | **通过（运行态取证）**：捕获到 2 行真实日志；4 次连续 `apply` 只打印 1 行（非每帧刷屏）；注入诊断变化后再 `apply` 追加 1 行 |
| **降级负控（独立复现）** | **通过**：类缺失 18 PASS/0 FAIL（+1 条 harness 自证 CNFE）、开关关闭 11 PASS/0 FAIL、正控 17 PASS/0 FAIL；三路径 LinkageError 0 |
| **服务端冒烟（最终制品）** | **通过**：`Done (1.798s)`、mcphone 相关 3 类 LinkageError = **0**、全局 3 类异常扫描为空、`kill -TERM` 干净退出 |
| 静态两层 | **通过**：grep 唯一命中 `PhoneGlass.java`（11 命中/1 文件）；字节码层 148(jar)/147(classes) 唯一命中 `PhoneGlass.class` |
| 独立发现 | **G1 low**（§9.3 状态栏行 material 与 T 不配对）、**G2 low**（REGULAR/THICK 的 STATUS 单元格与 §9.2「条状」值运行期不可达）、**G3 说明**（契约第 3 条"与 THIN 行不同"在节点 a 上不成立，已在材质档/合成 T 上成立）—— 均非阻断 |
| 是否放行 t7 | **放行**（9/9 验收 passed；G1/G2 是 docs 口径残留，建议 t11/t9 顺手修，不影响制品行为） |

---

## 1. 被验证的制品与修订指纹（含并发写入声明）

> ⚠️ 本轮与 **t11 repair-round-3** 并发：t11 在验证期间改了 `PhoneGlass.java` 的**注释**与 `docs/*.md` 文本（队长已预警）。因此本报告**钉住**被验证的修订，并给出**两次构建**的指纹：

| 阶段 | jar md5 / 大小 / mtime | 对应源码 | 说明 |
| --- | --- | --- | --- |
| 首轮构建（t11 之前） | `2574cb00c8b773cdf3edc8e5a6709731` / 363613 B / 02:16:25 | `PhoneGlass.java` md5 `d61b0666586bc42e1783b460b41c8224` | 用于 02:18 的服务端冒烟（当时最新） |
| **最终构建（门禁对象）** | **`eb1f175777ce9cdbca69f9011c89a387`** / **363620 B** / **02:23:04** | **`PhoneGlass.java` md5 `f84622150f18ff8f7bd42157c543dd4c`**（792 行） | t11 注释修正后重建；**本报告 §4–§9 的行为与冒烟证据全部对应此制品** |

- 冻结校验：最终行为复跑**前后** jar md5 与 `PhoneGlass.java` md5 **完全一致**（§4 起所有取证均在冻结窗口内完成）；第二次服务端冒烟结束后复查仍为 `eb1f1757…`。
- 被对账文档的修订（两次读取，§5 的 8 格目标值在两次修订中**逐字未变**）：
  - `docs/qz-liquid-glass-design.md`：`8e621b9b…`(02:16) → **`f71812a2a8649bf53c19176af2e1fb24`**(取证时)
  - `docs/qz-liquid-glass-review.md`：`1560375a…`(02:16) → **`cefb1195ffe612bfda2cd5ac4ec9249b`**(取证时；§14.3 权威表 476–479 行未变)
- 关于「注释数值」：本报告的所有行为判据都来自 **`SURFACE_ALPHA` 表 + `fullness()` + `compositeAlpha()` 的实际调用**（反射读私有表 + 公开 API 复算），**不采信任何注释数字**（队长预警 2）。

---

## 2. 完整构建与制品指纹（验收 1）

```bash
$ cd <repo> && ./gradlew build --build-cache -x test
BUILD SUCCESSFUL in 5s
17 actionable tasks: 4 executed, 13 up-to-date
# exit = 0
$ rm -rf build/classes/java/main && ./gradlew build -x test --no-build-cache   # 强制真编译
> Task :compileJava     (Jabel: initialized —— 编译器真跑)
BUILD SUCCESSFUL in 4s
```
- 最终发布 jar（不带 `-dev/-api/-sources`）：
  `mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`，**363620 B**，**mtime 2026-09-15 02:23:04**，md5 `eb1f175777ce9cdbca69f9011c89a387`；jar 内 `mcmod.info version` 与文件名一致。
- **不是 01:02 那份旧串制品**：`mcphone-v1.0.2-master.43+a328673ccd-dirty.jar`（342212 B，mtime 01:02:35，md5 `eafe73c2e0718c8186b34b677a8ae38b`）—— 两者 tag 基线不同（旧串 `v1.0.2-master.43`，新串 `v1.0.3-beta.2-master.10`），且大小差 21 KB。`ls -lt` 排序证明新串为最新发布制品。
- ⚠️ **同版本串陷阱**：`v1.0.3-beta.2-master.10+a328673ccd-dirty` 这个文件名在本轮出现了**三个不同内容**的制品（t5 的 362718 B / 首轮 363613 B / 最终 363620 B）——**t7 装 jar 必须用 md5 认制品**（最终 = `eb1f1757…`）。

---

## 3. jar 内容检查（验收 2，python3 zipfile；WSL 无 unzip）

```
jar = build/libs/mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar
entries=191   .class=148
PhoneGlass 族: PhoneGlass.class / PhoneGlass$1 / PhoneGlass$Path / PhoneGlass$Role / PhoneGlass$Tier
club/heiqi 前缀条目 count = 0        <-- Qz 未 shade
mcmod.info version = v1.0.3-beta.2-master.10+a328673ccd-dirty
```

---

## 4. F1 行为取证（验收 3，本轮最关键）

**手法（自写，不复用实现者脚本）**：自研 `ProbeLoader extends URLClassLoader`（parent = platform loader，工程类只从 URL 加载），加载**真实 Qz 4.9.1** 与 `build/classes/java/main` 的 `PhoneGlass`；用 `sun.misc.Unsafe.allocateInstance(Minecraft)` 造实例并写 `theMinecraft`/`mcDataDir`，使**真 `PhoneCanvas`** 参与开关判定；再对 `resolveTier / surface / compositeAlpha` 走公开反射，对 `SURFACE_ALPHA / capFor / fullness / materialName / blurRadius / lensFactor` 走 `setAccessible` 私有反射。

**实际输出（最终制品 / 冻结窗口）**：
```
### INFO STATUS + AUTO       -> resolveTier=ULTRA_THIN  surface=0x2314181C
### INFO STATUS + THIN       -> resolveTier=THIN        surface=0x2314181C
### INFO STATUS + ULTRA_THIN -> resolveTier=ULTRA_THIN  surface=0x2314181C
### INFO STATUS + REGULAR    -> resolveTier=THIN        surface=0x2314181C
### INFO STATUS + THICK      -> resolveTier=THIN        surface=0x2314181C
### INFO recipe THIN:       material=DARK_THIN       blur=8  lensFactor=1.0  fullness=1  ordinal=1
### INFO recipe ULTRA_THIN: material=DARK_ULTRA_THIN blur=6  lensFactor=0.8  fullness=0  ordinal=2
### INFO SURFACE_ALPHA(row0=AUTO..row4=THICK) = [3C,3C,23,5A,5A,7E] [3C,3C,23,5A,5A,7E] [2D,2D,23,45,45,80] [6F,6F,54,6F,6F,87] [8D,8D,7A,8D,7A,8D]
PASS F1-a resolveTier(STATUS,ULTRA_THIN)==ULTRA_THIN（不再被钳成 THIN）
PASS F1-b surface(STATUS,ULTRA_THIN)==0x2314181C（= ULTRA 行 STATUS 列反解值）
PASS capFor(STATUS,*) 只降不升：ULTRA_THIN→ULTRA_THIN / THIN→THIN / REGULAR→THIN
PASS fullness(ULTRA_THIN)=0 < fullness(THIN)=1（旧 ordinal 比较 2>1 会反钳，故修复有效）
```

**判读**：修复前 `capFor` 用 `ordinal()`（`ULTRA_THIN.ordinal()=2 > THIN.ordinal()=1`）会把"更薄"的请求判成"更厚"并反向上钳；修复后改用 `fullness()`（0/1/2/3 显式厚度序），STATUS 的 `ULTRA_THIN` 请求**原样保留**，且 Qz 配方真的换成 `DARK_ULTRA_THIN` + blur 6 + lens 系数 0.8（不是"看起来一样"）。**反向钳制已消除**。

**一处必须如实说明的偏差（见 G3）**：契约第 3 条的复合期望含"**且与 AUTO/THIN 行不同**"；实测 **节点底色 a 相同**（THIN 与 ULTRA_THIN 的 STATUS 列都是 `0x23`，这是 t8 明确落地、review §14.3 认可的取值，"不同"体现在**材质档与合成总遮罩**：`compositeAlpha(STATUS,THIN)=0x44` vs `(STATUS,ULTRA_THIN)=0x39`，见 §5 表）。因此该复合期望的字面第二半在节点 a 维度不成立、在材质/合成维度成立；F1 的**实质主张（不再反向上钳）已证**，本报告判该验收项 **passed** 并把偏差登记为观察/低危 finding，供队长与 t9 裁定是否要改文案或改表。

---

## 5. F2/F4/F5 单一真值对账（验收 4）

### 5.1 口径检查（文档结构）

`docs/qz-liquid-glass-design.md` §9.3（行 926-949，修订 `f71812a2…`）：
- 表头 = **「节点底色 a（渲染值）」** + **「合成总遮罩 T」** 两列 ✓（口径段在 928-936 行，明写 `T = a + t·(1−a/255)`、`t` 取自 `DARK_ULTRA_THIN 0x1A / DARK_THIN 0x26 / DARK_REGULAR 0x33 / DARK_THICK 0x4D`）
- 表内有 **t9 修正（round 2）** 标注：历史三处算错（0x5B/0x46/0x7D）已改为 **0x59 / 0x44 / 0x73**，并指向权威表 `docs/qz-liquid-glass-review.md §14.3`
- §9.2 的列名也已从"底色 alpha"改为「合成总遮罩 T 档（见 §9.3 T 列）」，并行内声明"不是节点自身底色 alpha"

### 5.2 `compositeAlpha()` 复算 vs 文档 T 列（8 格，实测 Δ 全为 0）

| 角色 \ 档 | THIN 实测 | 文档 | Δ | ULTRA_THIN 实测 | 文档 | Δ |
| --- | --- | --- | --- | --- | --- | --- |
| PANEL | `0x59`(89) | 0x59 | **0** | `0x42`(66) | 0x42 | **0** |
| STATUS | `0x44`(68) | 0x44 | **0** | `0x39`(57) | 0x39 | **0** |
| CARD | `0x73`(115) | 0x73 | **0** | `0x58`(88) | 0x58 | **0** |
| BUTTON | `0x73`(115) | 0x73 | **0** | `0x58`(88) | 0x58 | **0** |
| （附加）PAGE | `0x59` | 0x59 | 0 | `0x42` | 0x42 | 0 |
| （附加）BUTTON_HOVER | `0x91`(145) | 0x91 | 0 | `0x8D`(141) | 0x8D | 0 |

对账目标取自 `docs/qz-liquid-glass-review.md` **§14.3 权威数值表**（476-479 行，修订 `cefb1195…`）+ `design §9.3`（THIN 面行）。**8/8 落在 ±1/255（实际全为 0）**，附加 4 格同样 Δ=0。另抽样 `surface(CARD,THIN)==0x5A14181C`（节点 a 列与实现一致）✓。

### 5.3 谁是"真值"、谁是"推导"

- **真值 = `PhoneGlass.SURFACE_ALPHA`（节点底色 a）**（代码即真值；`surface()` 返回 `(a<<24)|0x14181C`）。
- **推导 = 文档「合成总遮罩 T」列**：由 `t`（官方 Qz `UiGlassMaterial.getTintAlpha()`，本任务实测 `0.14901961 / 0.101960786 / 0.2 / 0.3019608` = `0x26/0x1A/0x33/0x4D`）与 a 按 `T = a + t·(1−a/255)` 计得，本次复算与文档逐格相符。
- 残余注意（G1/G2，均 low）：见 §12。

---

## 6. 新诊断出口取证（验收 5）——**运行态实测，不是静态核对**

`PhoneGlass.apply()` 成功后调用 `logDiagnosticsIfChanged()`（`PhoneGlass.java:333` 调用点、`:732-741` 定义），比较的是 `renderPathLabel() + '\u0001' + diagDetail()`，只在变化时 `System.out.println("[mcphone] glass: " + diagSummary())`。本任务用 harness **捕获 stdout** 实测：

```
[mcphone] glass: backdrop 路径: none | 诊断: not-run              <-- 第 1 行（会话真实初始态）
[mcphone] glass: backdrop 路径: shader | 诊断: injected-by-harness <-- 第 2 行（注入变化后）
PASS 首次 apply 后打印 1 行（值变才打印的第一半）            | n0=0 n1=1
PASS 连续 4 次 apply 未重复打印（不是每帧刷屏）              | 共 1 行
PASS 注入诊断变化 + refreshDiagnostics 后再 apply 追加 1 行  | n1=1 n2=2 mutated=true
PASS diagnosticSeen() 由 false 变 true                       | false -> true
PASS 注入的 detail 出现在第二行日志里（证明比较的是 label+detail）
PASS 第一行是会话真实初始态（none / not-run）
（mode=diag 合计 6 PASS / 0 FAIL）
```
- 「值变才打印」是**双向**取证的：不变时不重复（4 次 apply 只 1 行）、变化时必打印（注入后 +1 行）。
- 手工注入方式是 harness 侧对 Qz `UiBackdropFilterRenderer.lastRenderPath/lastDetail` 做反射写入 + `refreshDiagnostics()`，仅作用于该 harness 进程，不改任何文件。
- 局限（如实）：游戏内**没有**把这三态暴露到 UI（t9 review 已把此项登记为"日志出口已落地 / 游戏内 UI 出口本轮未落地"）；因此手测仍只能靠观感 + 日志。

---

## 7. 降级负控（验收 6）——**独立复现，非引用实现者自证**

**手法**：`ProbeLoader.loadClass` 对 5 个玻璃类名直接抛 `ClassNotFoundException`（模拟"宿主没有玻璃类"）；开关路径用 `Unsafe` 造 `Minecraft` + 临时 `mcDataDir`，让**真 `PhoneCanvas`** 读 `settings.properties`。

| 模式 | 结果 | 关键断言 |
| --- | --- | --- |
| 正控（真实 Qz 4.9.1，未遮蔽） | **17 PASS / 0 FAIL** | `resolveTier/surface/compositeAlpha` 全部正常；`capFor`/`fullness` 行为如 §4 |
| 负控 A：类缺失（遮蔽 5 类） | **18 PASS / 0 FAIL** | `available()=false`、`glassOn()=false` 且 **PhoneCanvas 未被触碰**（短路）、`renderPath()=UNAVAILABLE`、`resolveTier` 5 档全 `null`、6 角色 `surface` 中性色（`0x1FF2F5F8`/PAGE `0x4C14181C`）、`text/muted` 中性、`materialTintAlpha=0.0`、`apply/clear/refreshDiagnostics/diagSummary/diagnosticSeen` 全 no-throw |
| 负控 B：开关关闭（真 PhoneCanvas + `glassEnabled=false`） | **11 PASS / 0 FAIL** | `glassOn()=false`、6 角色 `resolveTier=null` + 中性色、`text/muted` 回中性字、`apply()` 不抛且 `getBackdrop()==null` |

- 三条路径 **`NoClassDefFoundError` / `NoSuchMethodError` / `ExceptionInInitializerError` 计数 = 0**。
- 唯一被计数的 `LinkageError`（missing 模式 1 次）来自 **harness 自身的取证调用** `SceneNode.getBackdrop()` —— 该 getter 的返回类型正是被故意遮蔽的 `UiBackdrop`，故抛 CNFE；同一次断言里 `PhoneGlass.apply(...)` 本身**正常返回无异常**，而 mcphone 侧不存在 `getBackdrop` 调用（§9 静态取证）。**不判为实现缺陷**。
- 方法论自纠（O1）：首轮 final-tree 复跑时 `f1/diag` 曾整片 FAIL，经查是 **harness 自身的 `gamedir/mcphone/settings.properties` 残留 `glassEnabled=false`**（上一轮 switchoff 模式写入），表现为 `resolveTier=null`/中性色 —— 与"开关关闭"特征完全一致。清空 gamedir 后立即全绿；已在脚本里加入**每模式后隔离**，避免再次污染。

---

## 8. 服务端冒烟（验收 7）

服务端 `<test-server>`（GTNH 2.9.0-beta-3），启动命令（t5 验证过的正确姿势）：
```bash
cd <test-server>
java -Xms3G -Xmx3G -Dfml.readTimeout=180 -Duser.language=en @java9args.txt -jar lwjgl3ify-forgePatches.jar nogui
```

### 8.1 3b 换件记录（本轮两次，均记录新旧名与 md5）

| 次序 | 旧 jar（被换出） | 备份名 | 新 jar（换入） | md5 校验 |
| --- | --- | --- | --- | --- |
| 第 1 次 02:17:57 | `mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`（t5 装的 **t8 前**制品，md5 `ae064e9a90d0ea455a238c7af360578c`，362718 B） | `…jar.pret8-20260915-021757` | 首轮构建 `2574cb00c8b773cdf3edc8e5a6709731` / 363613 B | 双端 md5 一致 |
| **第 2 次 02:23:57（门禁对象）** | 首轮制品 `2574cb00…`（362718→363613 B） | `…jar.t10r1-20260915-022357` | **最终制品 `eb1f175777ce9cdbca69f9011c89a387` / 363620 B** | 双端 md5 一致（构建产物 == 服务端内） |

（另有一个更早的历史备份仍在：`mcphone-v1.0.2-master.29+5da5132e36.jar.bak-20260915-015112`）

### 8.2 最终制品的关键日志行（`/tmp/t10_server2.log`）

```
372 : [02:24:02] [main/INFO] [FML]: Examining mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar to load
1335: [02:24:22] [Server thread/INFO] [qz_uilib]: I am Qz-UILib at version 4.9.1
3158: [02:24:38] [Server thread/INFO]: Done (1.798s)! For help, type "help" or "?"
```
- `mcphone_linkage = 0`（`grep -i mcphone | grep -cE 'NoClassDefFoundError|NoSuchMethodError|ExceptionInInitializerError'`）
- **全局** 3 类异常扫描：**空**（连无关 mod 的 CLF 噪声本轮都不命中这 3 类）
- 停止：`kill -TERM <pid>` → 进程干净退出（`T10_SRV2_JAVA_EXIT rc=143` = 128+15 SIGTERM），`pgrep -af lwjgl3ify-forgePatches` 为空
- 指纹复查（冒烟后）：构建 jar 与服务端内 jar md5 **仍为 `eb1f1757…`**，`PhoneGlass.java` md5 未变 ⇒ 冒烟对象就是最终制品
- 观测（O2）：第二次冒烟**没有** `fml.ModTracker` 行，因为世界已由同版本串保存过（首次冒烟时出现过 `v1.0.2-master.29 → v1.0.3-beta.2-master.10` 的提示）；制品身份由 `Examining …` 行 + md5 双端一致证明。
- 预期噪声：`.pret8-* / .t10r1-* / .bak-*` 三个备份文件被 FalsePatternLib DepLoader 记为 `Skipping non-directory, nor jar source`（扩展名非 `.jar` 的自然结果），非缺陷。
- **口径如实**：服务端不加载 `PhoneGlass`（客户端包），本步证明的是「整个新 jar 在服务端加载/初始化不炸」；"玻璃类缺失不炸"由 §7 负控 harness 严格证明。

---

## 9. 静态两层取证（验收 8）

```bash
$ grep -rn 'UiBackdrop\|UiBackdropEffect\|UiGlassMaterial\|BackdropBlur' src/main/java/
```
- 命中 **11 行，全部位于 `client/enhance/PhoneGlass.java`**（`grep -rl … | wc -l` = **1**）：`213-215` 三个探测字符串常量、`332` 唯一类字面 cast（`setBackdrop((UiBackdrop) backdrop)`）、其余为 javadoc/注释。
- **字节码层**（自写 python 扫全部 `.class` 原字节，四模式）：
  `[jar] class=148 命中=1 → PhoneGlass.class`；`[classes] class=147 命中=1 → PhoneGlass.class`。
  （若把匹配放宽到 `club/heiqi/uilib/ui/render` 前缀，`PhoneUi.class` 会因 `UiRenderBackend` 命中 —— 该类型非玻璃类，属预存在代码。）
- 相关引用面：`setBackdrop` / `getBackdrop` / `liquidGlass` 在 `src/` 内的**代码级**引用只在 `PhoneGlass.java`（其余命中是注释）⇒ 隔离契约成立，也解释了 §7 中 `getBackdrop` 不构成实例缺陷。

---

## 10. 「自动化无法验证、必须用户手测」清单（验收 9）

> 前置：本任务**未启动游戏客户端**（AI 硬约束）；服务端冒烟已做（§8）。以下须由用户在本机实例 `290b3test` 手测。

| # | 项目 | 判读口径 / 预期 |
| --- | --- | --- |
| H1 | 玻璃观感是否真成立（主面板/状态栏/内容底板/卡片/按钮是否透出并模糊**世界背景**） | 自动化只能证明"挂上了 `UiBackdrop` 且配方正确"，不能证明"好看/可见" |
| H2 | 档位差异与强度滑条：自动/薄/极薄/常规/厚 + 强度 0–100% 的观感单调性 | 极薄档（ULTRA）在 PANEL/CARD 上应比薄档更透（`T=0x42 < 0x59`、`0x58 < 0x73`）；状态栏两档差别很小（`0x39` vs `0x44`） |
| H3 | **默认档可能是"极薄"** | **预期，不是缺陷**（R2：会话首次 `renderPath()==NONE` ⇒ AUTO 解析为 ULTRA_THIN）。后续 `refreshDiagnostics()` 或真渲染后会按 THIN |
| H4 | **HUD 缘光恒为静态 -55°（右上）** | **预期**：Qz 宿主 `UiHudRenderListener.java:123` 给 HUD 帧传指针 `(0,0)`；**全屏页面仍随指针动** |
| H5 | 帧率 / 性能（HUD 常显开/关、主面板 3 层 backdrop：panel/contentSlot/grid） | 自动化无法测帧时间 |
| H6 | 拖拽手感与命中盒（主屏图标长按排序 10px 阈值、HUD 拖动、Ctrl+滚轮、G 键、点击打开） | 尤其装了其它 HUD mod（`registerAvoidance` 撑大安全区）时命中盒可能偏移 |
| H7 | 亮色壁纸 + 极薄档 + 小字号（状态栏 fs14/16、卡片 fs13）的可读性 | 设计 §9.2 裁定"只改底色 alpha、不改文字色"；最坏情况可能 <4.5:1。属设计层风险，不是本任务缺陷 |
| H8 | 实心保留面（TextArea / 长文本 / 缩略图 / 图片底衬）应**仍是实心** | 三处带【刻意保留实心】注释 |
| H9 | 设置页三项（开关/档位/强度）改动后**立即**生效（含 HUD 侧） | t8 新增 `PhoneHud.onGlassSettingsChanged()`；逻辑上已接，观感需人测 |
| H10 | 三联动的观感一致性：HUD 玻璃 vs 全屏玻璃 | 两者缘光行为不同（H4），且 HUD 用宿主 logical px 口径 |
| H11 | 分辨率 × 倍率边界（1920×1080@60% / 3840×2160@50–60% / 854×480@50%） | review §14.7 / t5 §10 已列，仍属人测 |
| H12 | 三态诊断在游戏内的可判定性 | 本轮**只有日志出口**（§6），无 UI 出口；如需现场判定 SHADER/FIXED/TINT/NONE，需看日志或后续补出口 |

---

## 11. 未修改实现代码自证（验收 9 后半）

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
- 与 t10 开工时**逐字一致**（该改动面全部来自 t2/t3/t4/t8/t11）；本任务唯一新增物 = `docs/qz-liquid-glass-verification-r2.md`。
- `git -C <Qz-UILib> status --porcelain` = 空（Qz-UILib 只读，硬约束①）；未 push / 未打 tag / 未启动游戏客户端；harness 与脚本全部落在 `/tmp/t10/` 与 `<workspace>\_t10*.sh`（用后即删），仓库内无临时文件。

---

## 12. 独立发现（均非阻断）

### G1（low，文档口径残留）§9.3 状态栏行的「材质档」与「合成 T」不配对

- 事实：`docs/qz-liquid-glass-design.md:946` 该行 `材质档 = DARK_ULTRA_THIN（t=0x1A）`、`节点底色 a = 0x2314181C`、`合成总遮罩 T = 0x44(27%)`。
- 实测（本任务）：`compositeAlpha(STATUS,ULTRA_THIN)=0x39(57)`、`compositeAlpha(STATUS,THIN)=0x44(68)`。⇒ 该行把 **ULTRA 材质**与 **THIN 档的 T** 写在一起。
- 权威表 `review §14.3:477` 给出的 `ULTRA_THIN + STATUS = 0x23 / T=0x39` 才是正确配对（本轮 8 格对账即以它为目标，Δ=0）。
- requiredFix（docs，非本任务 inScope 的"修"）：把该行材质列写成 `DARK_THIN（AUTO 真渲染）/ DARK_ULTRA_THIN（降级）`，或把 T 列写成 `0x39（ULTRA）/ 0x44（THIN）`；同行的历史括号（"原 0x25 的合成 T=0x46"）建议移到 §9.3 的 t9 修正注里，避免与现值混淆。

### G2（low，不可达单元格）REGULAR/THICK 的 STATUS 单元格与 §9.2「条状」值运行期不可达

- 事实：`SURFACE_ALPHA` 的 `[REGULAR][STATUS]=0x54`、`[THICK][STATUS]=0x7A`；而 `capFor(STATUS)=THIN` 会把 REGULAR/THICK **向下钳**（`fullness` 比较）⇒ 这两格永远不可能是渲染结果。
- 实测：`STATUS + REGULAR → resolveTier=THIN, a=0x23, T=0x44`；`STATUS + THICK → 同上`。
- 连带：`docs/qz-liquid-glass-design.md:918/:919` §9.2 的「条状 `0x76`（REGULAR）」「条状 `0xA2`（THICK）」是按 SURFACE_ALPHA 死格算出的值，**运行期不可达**（review §14.3:481 已声明「STATUS 最高 THIN」，但 §9.2 仍按档列出这两个数）。
- requiredFix：在 §9.2 的"条状"后缀标注「（上限档 THIN，这两个值不可达）」或直接改列 `STATUS 恒为 THIN 行 T=0x44`；`SURFACE_ALPHA` 该两格可加注释"unreachable (capFor(STATUS)=THIN)"。

### G3（说明，非缺陷）契约第 3 条"且与 AUTO/THIN 行不再相同"在节点 a 维度不成立

- THIN 行与 ULTRA_THIN 行的 **STATUS 列都是 `0x23`**（t8 明确按此落地；review §14.3 亦如此），因此 `surface(STATUS,ULTRA_THIN)` 与 `surface(STATUS,THIN)` 的**节点底色必然相同**；二者的区别在**材质档**（`DARK_ULTRA_THIN` vs `DARK_THIN`）、blur（6 vs 8）、lens 系数（0.8 vs 1.0）与**合成总遮罩 T**（`0x39` vs `0x44`）。
- 结论：F1 的实质主张（不再反向钳制）已证；该复合期望的第二半需按上述口径理解，或由 t9 在文档中写明"STATUS 的档位差异体现在材质与 T，而非节点 a"。

### 观察项（OBS）

| 编号 | 观察 | 说明 |
| --- | --- | --- |
| O1 | harness 首轮误报整片 FAIL（gamedir 残留 `glassEnabled=false`） | 已定位为 harness 自身状态污染，清空后全绿；脚本已加模式间隔离 |
| O2 | 第二次冒烟无 `ModTracker` 行 | 世界已由同版本串保存过；制品身份由 `Examining` 行 + md5 双端一致证明 |
| O3 | 三态诊断仍无游戏内 UI 出口 | 本轮只补日志出口（§6）；用户手测无法直接读 path/detail（H12） |
| O4 | t11 期间文档/注释仍在变 | 本报告所有数字均绑定 §1 的修订指纹；§14.3 的 8 格目标值在两次修订间逐字未变 |

---

## 13. 复现命令清单

| 编号 | 命令 | 用途 |
| --- | --- | --- |
| V1 | `cd <repo> && ./gradlew build --build-cache -x test; echo $?` | 契约构建 |
| V2 | `rm -rf build/classes/java/main && ./gradlew build -x test --no-build-cache` | 强制真编译 |
| V3 | `python3 -c "import zipfile;z=zipfile.ZipFile('<jar>');print(len(z.namelist()),[n for n in z.namelist() if n.startswith('club/heiqi')])"` | jar 内容 + 无 Qz 条目 |
| V4 | `grep -rn 'UiBackdrop\|UiBackdropEffect\|UiGlassMaterial\|BackdropBlur' src/main/java/` | 静态取证（grep 层） |
| V5 | 自写 python 扫 `build/classes/**/*.class` 与 jar 内 `.class` 原字节 | 静态取证（字节码层） |
| V6 | `java -cp /tmp/t10/classes GlassProbe10 {f1\|diag\|switchoff} /tmp/t5_cp2.txt /tmp/t10/gamedir`、`GlassProbe10b missing …`、`GlassProbe10d`（档位可达性） | 正控 / 负控 / 诊断出口 / F1 / 对账 |
| V7 | `./gradlew --init-script /tmp/t5_dumpcp.gradle dumpCp -q --no-configuration-cache` + 追加 LWJGL 2.9.4 两个 jar | 导出 runtimeClasspath（V6 依赖） |
| V8 | `cd <test-server> && java … -jar lwjgl3ify-forgePatches.jar nogui` + `kill -TERM $(cat /tmp/t10_srv2_java.pid)` | 服务端冒烟与停止 |

---

## 14. 给 t7 的移交要点

1. **装 jar 认 md5**：`mcphone-v1.0.3-beta.2-master.10+a328673ccd-dirty.jar`（文件名在三轮里重复出现）→ 本轮门禁对象是 **363620 B / md5 `eb1f175777ce9cdbca69f9011c89a387` / mtime 02:23:04**。若 t11 之后再改 `src/`，**必须重新构建并重跑本报告的 §4–§7**（注释改动也会因行号表变化而改变 jar 字节）。
2. **参考值以 `docs/qz-liquid-glass-review.md §14.3` 为准**（THIN：面板/内容 `0x59`、状态栏 `0x44`、卡片/按钮 `0x73`、hover `0x91`；ULTRA：`0x42 / 0x39 / 0x58 / 0x58 / 0x8D`）—— 与本任务实测 **逐格 Δ=0**。
3. **手测必看**：H3（默认档=极薄是预期）、H4（HUD 缘光静态 -55° 是预期）、H7（亮壁纸小字）、H12（三态无 UI 出口）。
4. 服务端回滚点：`…jar.pret8-20260915-021757`（t8 前）、`…jar.t10r1-20260915-022357`（t11 前）、`mcphone-v1.0.2-master.29+5da5132e36.jar.bak-20260915-015112`（本轮改造前）。
