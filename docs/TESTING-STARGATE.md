# 星门规则版手测清单（stargate-rules 分支）

适用：mcphone 星门合规版（`stargate-rules` 分支）。本清单覆盖**默认档位**（星门认证跑）下的受限行为、**开启后**的行为，以及**恢复 master 体验**的配置示例。配置类：`com.november.mcphone.core.StargateConfig`；配置文件：`config/mcphone-stargate.cfg`。

## 0. 准备

- [ ] 客户端与服务端（或单人世界）安装**同一个**构建 jar（`build/libs/mcphone-<version>.jar`，不带 `-dev/-api/-sources` 后缀）。
- [ ] 首次进入世界后确认配置文件已自动生成：专用服务器在 `<服务器根目录>/config/mcphone-stargate.cfg`，单人在 `.minecraft/config/`。
- [ ] 多人服以**服务器端**配置为准；客户端改配置不影响服务端判定。改配置保存后免重启（懒加载 + mtime 缓存自动重读）。
- [ ] 测试循环：改配置 → 关游戏/重连（客户端 UI 提示类需重进）→ 验证。

## 1. 默认档位（不改动任何配置）

### 1.1 ME 终端（AE2，默认全关）

- [ ] **未安装 AE2**：打开手机 ME App → 提示星门规则已关闭手机注册（`[ae2] registerWirelessTerminal=false`），不崩溃。
- [ ] **安装 AE2 + 默认配置**：打开手机 ME App → 同上，明确提示被星门规则关闭，**不**出现「未检测到 AE2」的误导文案。
- [ ] 潜行 + 持手机右击 ME 安全站 → 提示「AE2 终端功能已被服务器规则（星门）关闭，无需绑定」。
- [ ] **混搭档**（仅把 `builtinTerminal=true`，`registerWirelessTerminal` 仍 false）：打开 ME App → 仍提示星门关闭注册（修复后不再误报「未检测到 AE2」），且不会打开内置终端。

### 1.2 传送（默认 3 点 / 30 秒 / 禁跨维）

- [ ] 已绑定 3 个传送点后，再绑第 4 个 → 服务端拒绝，提示「传送点已达上限 3 个…[teleport] maxWaypoints」。
- [ ] 成功传送一次后 30 秒内再次传送 → 提示剩余冷却秒数；冷却过后可正常传送。
- [ ] 在另一维度（如下界）绑定的传送点，从主世界点击 → 提示不允许跨维度（`[teleport] crossDimension=false`）。
- [ ] 同维度、冷却外、未超上限的传送 → 正常落位。
- [ ] （可选，进阶）伪造客户端包直接发 Teleport 包绕过客户端 UI → 服务端同样拦截（全部校验在服务端强制）。

### 1.3 末影箱（默认关）

- [ ] 打开手机末影箱入口 → 服务端提示「末影箱功能已被服务器规则（星门）关闭…[enderchest] enabled」，不打开界面。

### 1.4 聊天好友传送（默认保留）

- [ ] 双方互加好友且对方在线 → 从聊天 App 传送到对方位置成功（`[chat] friendTeleport=true`，对齐官方 `/tp` 到玩家）。
- [ ] 对方离线/非好友 → 拒绝。

### 1.5 纯 QoL 回归（不应受影响）

- [ ] 时钟/天气/便签/相机/相册/HUD/设置/快捷键全部照常。

## 2. 开启后行为（按需放开）

示例：放开 AE2（服务端 config/mcphone-stargate.cfg）：

```properties
ae2 {
    registerWirelessTerminal=true
    builtinTerminal=true
}
```

- [ ] 重进世界：潜行 + 持手机右击 ME 安全站 → 提示绑定成功（密钥序号）。
- [ ] 背包里有通用无线终端 → ME App 直接打开**真终端**完整 UI（自动换手、关闭后换回）。
- [ ] 背包里没有终端 → 打开手机内置终端（物品终端）；**电力跟随绑定 ME 网络的真实供电**——ME 网络断电时终端不可用（不再无限电力）。
- [ ] 只放开 `registerWirelessTerminal=true`、`builtinTerminal=false`：背包无终端时 → 明确提示内置终端被星门规则关闭。
- [ ] `registerWirelessTerminal=true` 但环境无 AE2 → 提示「未检测到 AE2」。

## 3. 恢复 master 体验（配置示例）

```properties
ae2 {
    registerWirelessTerminal=true
    builtinTerminal=true
}
teleport {
    maxWaypoints=2147483647
    cooldownSeconds=0
    crossDimension=true
}
enderchest {
    enabled=true
}
```

- [ ] 传送点不限量、无冷却、可跨维度；末影箱直开可用；AE2 行为与 master 一致。
- [ ] 注意：恢复 master 体验即偏离星门认证档位，**不得用于认证跑**；是否合规自行向 GTNH staff 报备（见 `docs/STARGATE-RULES.md` 第 4 节）。

## 4. 已知设计（非缺陷）

- 服务端重启后传送冷却时间戳清零（内存记录，见 `AppIntegrations.LAST_TELEPORT_MS` 注释）。
- `[teleport] requireItem` 为预留键（消耗传送宝石），当前实现未读取其效果，仅配置存在。
- 多人服中客户端配置不覆盖服务端判定；服务端重启前修改的配置即时生效（mtime 缓存）。
