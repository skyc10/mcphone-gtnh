# 星门规则合规说明（stargate-rules 分支）

本文档说明 mcphone（GTNH 移植版）针对 GTNH「星门规则」（Stargate Rules）做的合规化设计与配置方法，供玩家、服主与 GTNH staff 审阅。

- 适用分支：`stargate-rules`（自 master 分出，额外提交 `d73a082`「feat: 星门版基础配置类」及后续合规化提交）
- 中央配置类：`com.november.mcphone.core.StargateConfig`
- 配置文件：`config/mcphone-stargate.cfg`（服务端与单人世界各自解析到自己的根目录，**服务端配置优先决定多人服行为**）

---

## 1. GTNH「星门规则」摘要

星门规则是 GTNH 官方为「通关星门（Stargate）」这一终极成就设定的认证规则：玩家/团队只有在不借助「实质性影响游戏进程」的手段下通关，才能在官方 Discord 获得星门身份组。核心条款摘述如下（以官方规则书原文为准）：

1. **团队与个人**只需合成 **1 座星门**即可获得认证资格，团队人数不限。
2. **插件（Plugins）**：允许使用，但**不得超过官方服务器的 QoL（生活质量）供给水平**——即允许 `/home`、对其他玩家 `/tp`，或用类似方式达到同一目的（例如传送到路径点/waypoint）。
3. **回档（Rollbacks）**：仅限事故、测试或存档损坏情形；**不得**用回档重摇战利品袋。
4. **自制模组（Custom Mods）**：如 WorldEdit、Optifine、Fastcraft 等**不实质性影响游戏进程（substantially impact gameplay）**的模组允许使用；其中 WorldEdit **只能用于装饰目的**。
5. **污染**可以关闭。
6. **利用 Bug** 谋取实质优势（复制物品、无限 EU 等）即取消资格。
7. **EV 之后**允许关闭敌对生物刷怪（省流），mobGriefing 与火蔓延也可关闭。
8. **作弊获取物品**仅限因 Bug（配方损坏）无法正常获取的物品，且应尽量把前置材料丢弃。
9. 特殊玩法（空岛、无火箭等）可申请例外。

**关键兜底条款**：以上列表并非穷尽，管理员保留认定与撤销资格的权利；规则可能随时变更。**不在白名单内的模组或玩法，须『Ask GTNH staff』（询问 GTNH staff）**。可在官方 Discord 使用 `!stargaterules` 命令查看，或直接阅读官方规则书。

### 官方与权威链接

| 来源 | 链接 |
| --- | --- |
| 官方星门规则书（Rulebook，Google Docs） | <https://docs.google.com/document/d/1Iww1FNLkCuun6s6LW7Q6_1Z1aldtxYeRxYOeq_P-vK8/edit> |
| GTNH 英文维基·Stargate（含 Discord Role Requirements 条款镜像） | <https://wiki.gtnewhorizons.com/wiki/Stargate> |
| GTNH 中文维基·星门规则 | <https://gtnh.huijiwiki.com/wiki/星门规则> |
| GTNH 中文维基·可添加MOD | <https://gtnh.huijiwiki.com/wiki/可添加MOD> |

> 提示：中文维基页面访问可能受反爬限制，若打不开请从首页 <https://gtnh.huijiwiki.com> 站内搜索「星门规则」「可添加MOD」。本文档写作时以英文维基与官方规则书为基准；规则可能更新，**最终判定以官方规则书与 GTNH staff 答复为准**。

---

## 2. 本 mod 逐功能合规判定

mcphone 的功能按星门规则第 2、4 条逐项判定如下：

| 功能 | 判定 | 依据与说明 |
| --- | --- | --- |
| 🕐 时钟 | ✅ 纯 QoL，允许 | 只显示世界时间，不影响任何游戏进程 |
| ☀️ 天气 | ✅ 纯 QoL，允许 | 只展示当前群系/降雨/雷暴/昼夜信息，无任何操控能力 |
| 📝 便签 | ✅ 纯 QoL，允许 | 本地文本记录，跨存档但不与游戏世界交互 |
| 🖼 相册 | ✅ 纯 QoL，允许 | 本地图片浏览与壁纸设置 |
| 📷 相机 | ✅ 纯 QoL，允许 | 纯截图（只含世界画面，HUD/小地图/热栏不进照片），不产生游戏内收益 |
| 📟 常显 HUD | ✅ 纯 QoL，允许 | 客户端 UI 元素，不提供额外信息或能力 |
| ⚙️ 设置 / 🗂 应用管理 / ⌨️ 快捷键 | ✅ 纯 QoL，允许 | 客户端界面配置 |
| 💬 聊天·好友传送 | ✅ 常规玩法，默认保留 | 对齐官方服务器的 QoL 供给——规则第 2 条明文允许「对其他玩家 /tp」；实现上仅允许传送到**当前在线好友**所在位置，不提供 /home、back 等官方供给之外的能力 |
| 🌀 传送（多传送点） | ⚠️ 受限玩法，默认收敛 | 规则第 2 条允许「用类似方式传送到路径点」，但 master 版的「无限传送点 + 跨维度 + 免冷却 + 不消耗传送宝石」超出官方 QoL 供给水平。本版默认 **3 个传送点、30 秒冷却、禁止跨维度**，且预留了消耗传送宝石的开关；全部可通过配置调整 |
| 📦 末影箱 | ❌ 玩法功能，默认关闭 | 免成本访问末影箱属于「实质性影响游戏进程」（原版需要合成并随身携带末影箱），不在官方 QoL 供给内。本版默认关闭 |
| 📡 ME 终端（AE2 无线终端直连） | ❌ 玩法功能，默认关闭 | 免成本远程访问 AE2 网络属于「实质性影响游戏进程」——原版需要合成通用无线终端并受电力/距离限制。本版默认：手机不注册为 AE2 无线终端、也不提供内置兜底终端 |

> 判定原则：**「官方服务器本来就给的」→ 允许；「官方不给、需要游戏内成本换的」→ 默认关闭或按官方水平受限**。任何拿不准的功能，按规则兜底条款询问 GTNH staff（见第 4 节）。

---

## 3. 配置开关表（config/mcphone-stargate.cfg）

所有玩法功能由中央配置类 `StargateConfig` 统一读取。文件路径：

- 专用服务器：`<服务器根目录>/config/mcphone-stargate.cfg`
- 单人游戏 / 集成服务器：`.minecraft/config/mcphone-stargate.cfg`

首次运行会自动生成带注释的默认配置；改动保存后**无需重启**（懒加载 + mtime 缓存，自动重读）。

| 分类 | 键名 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `[ae2]` | `registerWirelessTerminal` | `false` | 手机注册为 AE2 无线终端的总开关。开启后手机可绑定 ME 安全站、直连通用无线终端 |
| `[ae2]` | `builtinTerminal` | `false` | 手机内置兜底物品终端（背包无通用无线终端时的替代形态）。**需同时放开 `registerWirelessTerminal` 才能打开**；电力语义与注册态一致，跟随绑定 ME 网络的真实供电，不免费 |
| `[teleport]` | `maxWaypoints` | `3` | 允许保存的传送点上限 |
| `[teleport]` | `cooldownSeconds` | `30` | 传送冷却秒数 |
| `[teleport]` | `crossDimension` | `false` | 是否允许跨维度传送 |
| `[teleport]` | `requireItem` | `false` | 传送是否消耗传送宝石（预留键，配合平衡用） |
| `[enderchest]` | `enabled` | `false` | 末影箱玩法开关 |
| `[chat]` | `friendTeleport` | `true` | 聊天 App 内传送到在线好友（对齐官方 /tp，属常规玩法） |

**键名已定版，请勿改动**——服务端与客户端按这些键名读写同一份语义；改键名会导致旧配置静默失效回退默认值（即全部收紧）。

### 推荐配置档位

| 档位 | 适用场景 | 配置 |
| --- | --- | --- |
| 星门认证跑（默认） | 冲刺官方星门身份组 | 保持默认：AE2 全关、传送 3 点/30s/不跨维、末影箱关 |
| 休闲存档 | 不打星门认证，纯自用 | 随意；建议末影箱与 AE2 维持关闭以保留原版成本结构 |
| 服务器管理 | 服主为整个服定调 | **以服务器端的 `config/mcphone-stargate.cfg` 为准**下发给玩家；客户端配置在多人服不覆盖服务端判定 |

---

## 4. 给 GTNH staff 的功能清单与询问话术

规则兜底条款要求不在白名单内的模组**『Ask GTNH staff』**。若你要在星门认证跑中使用本 mod，建议按下列清单向 staff 报备。

### 功能清单（可直接粘贴）

```text
Mod: mcphone (GTNH port of november521/mcphone, branch stargate-rules)
Client+server side UI mod ("smartphone") with these features:

Pure QoL (no gameplay impact):
- Clock / weather display
- Local notes, photo album, camera screenshots, HUD widget, UI settings

Gameplay features (stargate-compliance build):
- Chat "teleport to online friend" — mirrors official servers' /tp-to-player provision
- Teleport waypoints — default limited to 3 waypoints, 30s cooldown,
  no cross-dimension (configurable in config/mcphone-stargate.cfg)
- AE2 wireless terminal access from the phone — DISABLED by default
- Ender chest access from the phone — DISABLED by default
```

### 询问话术（英文，Discord 官方服务器使用）

```text
Hi! I'm running for the Stargate role and would like to use a client/server
UI mod called "mcphone" (GTNH port). Pure QoL features (clock, weather,
notes, camera/photo album, HUD) are always on. Its gameplay features are
gated behind config and ship disabled/limited in my setup:
- teleport to an online friend from chat (like /tp to a player)
- waypoint teleport limited to 3 points, 30s cooldown, no cross-dimension
- AE2 wireless terminal access: disabled
- ender chest access: disabled
Are these acceptable under the Stargate rules (rulebook items 2 & 4),
or should I keep any of them disabled for the run?
```

### 询问话术（中文，中文社区/服主内部沟通用）

```text
你好，我在冲刺星门认证，想使用手机 UI 模组 mcphone（星门合规版）。
纯 QoL 功能（时钟/天气/便签/相机相册/HUD）常开；玩法功能均受配置约束，
默认状态如下：
- 聊天内传送到在线好友（等同官方 /tp 到玩家）
- 路径点传送：默认 3 个点、30 秒冷却、不跨维度
- AE2 无线终端直连：默认关闭
- 末影箱直开：默认关闭
请问以上是否符合星门规则第 2、4 条？是否需要为认证跑继续保持关闭？
```

### 报备建议

- 把 `config/mcphone-stargate.cfg` 的**实际内容**一并发给 staff，证明你的档位与申报一致。
- 若 staff 要求进一步收紧，优先用配置解决（如 `maxWaypoints=0`、`cooldownSeconds` 调大、`friendTeleport=false`），无需换 mod 版本。
- staff 答复可能随规则版本变化，**每次新认证跑前重新确认**。

---

## 5. 与 master 分支的差异速览

| 项 | master | stargate-rules（本分支） |
| --- | --- | --- |
| AE2 无线终端直连 | 潜行+右击 ME 安全站即可用 | 默认关闭，`[ae2] registerWirelessTerminal` 控制 |
| 内置兜底物品终端 | 无终端时自动可用（电力免费） | 默认关闭，`[ae2] builtinTerminal` 控制 |
| 传送点数量 | 不限 | 默认 3（`[teleport] maxWaypoints`） |
| 传送冷却 | 无 | 默认 30 秒（`[teleport] cooldownSeconds`） |
| 跨维度传送 | 支持 | 默认禁止（`[teleport] crossDimension`） |
| 末影箱直开 | 可用 | 默认关闭（`[enderchest] enabled`） |
| 聊天好友传送 | 可用 | 保持可用（`[chat] friendTeleport`，对齐官方 /tp） |
| 配置文件 | 无此文件 | `config/mcphone-stargate.cfg`（自动生成，免重启热读） |
| 被禁 App 的展示 | 照常显示图标，点击后服务端拒绝并提示 | **主屏与常显 HUD 直接不显示**（`StargateSync` 名单同步，配置改动 ≤20 秒生效；旧服务端安全退化为照常显示） |

> 2026-09-18 追加：本分支另含三处 UI 修复/调整（master 暂无）——主屏标签单行化（长名不再折行导致图标错位）、联动App 页几何随面板归一化（修文字与行重叠）、时钟页时长口径改为「玩家总游玩时长 / 服务器时长」。待测项见 `docs/TESTING-STARGATE.md` §1.5。
