# MCphone (GTNH)

简体中文 | [English](README.en.md)

把一部能用的智能手机塞进 GTNH —— 拍照、翻相册、换壁纸、记便签、多传送点传送、直连 AE2 通用无线终端，每个 App 都能绑自定义快捷键，屏幕角落还能常驻一块迷你手机 HUD，还能给自己那一部手机起个名。**默认档位即 GTNH「星门规则」合规档**：超出官方 QoL 供给水平的玩法功能默认关闭或受限，改一个配置文件就能放开（见 [星门规则配置](#星门规则配置默认合规档)）。

> **这是什么**：由 skyc10 借助 AI 将 [november521/mcphone](https://github.com/november521/mcphone)（原版，主线为 Minecraft 1.21.1 + NeoForge）**移植并重写**适配 **GTNH 2.9** 的版本——原 mod 出自 november521，本仓库（skyc10）维护 GTNH 移植分支。
> 目标环境：**GTNH 2.9.0-beta-3**（Minecraft 1.7.10 + Forge 1614，Java 17+ 运行时，lwjgl3ify）。
> 界面层整体重写在 [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib) 场景 UI 上（原生分辨率渲染，带现代字体渲染器）。

**Minecraft 1.7.10** · **GTNH 2.9.0-beta-3** · 客户端与服务端都需安装

> **冲刺 GTNH 星门认证？直接装这一版就行**——默认档位就是星门合规档（AE2 无线终端与末影箱默认关闭，传送点 3 个 / 30 秒冷却 / 不跨维度）。
> **不想受限**：改 `config/mcphone-stargate.cfg` 即可关闭这些限制，见 **[星门规则配置](#星门规则配置默认合规档)**；逐项判定与向 GTNH staff 报备的中英文话术见 [docs/STARGATE-RULES.md](docs/STARGATE-RULES.md)。

**前置模组**：

| 模组 | 说明 |
| --- | --- |
| [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib)（**4.10.0+**） | **必需前置**。GTNH 专用的现代场景 UI 库，手机的整个界面都跑在它上面。**版本下限为 4.10.0**：4.10.0 起才与本版**成对兼容**（Qz 自述与 4.9.x 不承诺混用、含公共面删除，两者必须成对升级），且 4.9.0 起才提供液态玻璃 `UiBackdrop`/`UiGlassMaterial` 等接口。装 4.9.x / 4.8.x 会因依赖不满足导致 mcphone 加载失败。仓库里不发布构建产物时，可从本项目 Release 一起下载 |

**联动模组**：ME 终端 App 依赖 **AE2** 与 **ae2fc**（通用无线终端）——这两者 GTNH 整合包自带，无需额外安装。装了它们的 GTNH 里，手机可以潜行+右击 ME 安全站完成绑定，点图标直接打开通用无线终端的完整形态。**注意：该功能默认被星门合规档关闭**（`[ae2] registerWirelessTerminal=false`，主屏也不显示图标），要放开见 [星门规则配置](#星门规则配置默认合规档)。

---

## 怎么拿到手机

工作台合成，一次一部：

```
铁锭   玻璃   铁锭
铁锭   红石   铁锭
       铁锭
```

拿在手上右键开机；手机在背包里时按 **P** 也能开机（可在原版「选项 → 按键设置 → MCphone」改键）。

`Esc` 分层级：在 App 页面按是退回主屏，在主屏按才是关机；底部 ⌂ 按钮随时回主屏。

## 功能

| App | 说明 |
| --- | --- |
| 🕐 时钟 | 大号世界时间显示，跟着状态栏一起走 |
| ☀️ 天气 | 当前生物群系、降雨/雷暴、昼夜状态 |
| 📝 便签 | 随手记点东西，存 `.minecraft/mcphone/notes/`（跨存档），支持新建/编辑/删除 |
| 📦 末影箱 | 点图标直达：关掉手机直接打开你的末影箱，与原版完全互通（**星门合规档默认关闭**，`[enderchest] enabled`） |
| 🌀 传送 | 内置传送，**不需要背包放传送宝石**。点击图标进传送点列表：绑定当前位置（也可 Shift+点击图标快速绑定）、一键传送、重命名、删除。传送点存在手机 NBT 里。**星门合规档默认：3 个点 / 30 秒冷却 / 禁止跨维度**（`[teleport]`，可放开） |
| 📡 ME终端 | 直连 AE2。背包里有**通用无线终端**时自动换手打开它的完整形态，关界面自动换回；没有时打开手机内置的物品终端。绑定方式：潜行 + 持手机右击 ME 安全站。**星门合规档默认全关**（主屏不显示图标；放开后内置终端电力跟随 ME 网络真实供电，不再免费） |
| 📷 相机 | 取景框 + 按键截图。默认 **C** 拍照、**P** 退回手机，可改键。照片只含世界画面——HUD、小地图、热栏、准星统统不进照片 |
| 🖼 相册 | 缩略图网格、大图查看、删除、一键设为壁纸。照片存 `.minecraft/mcphone/photos/`（**跨存档共享**），你也可以手动把 PNG 丢进这个文件夹，相册直接能读 |
| ⚙️ 设置 | 设备命名、重置壁纸，以及**界面大小**（50–150%）、**字体大小**（50–500%）与**按钮字号**（50–250%）滑条——拖完松手生效，适配高分辨率大屏 |
| 🗂 应用管理 | 每 App 一行：点击开/关（重开手机生效），↑/↓ 调整主屏图标顺序，顺序持久化 |
| ⌨️ App 快捷键 | 应用管理页点"键"按钮，按任意组合键（如 CTRL+SHIFT+K）绑定；一按直达该 App，页面型先进手机再进页面，直达型直接触发。Esc 取消、重复按同键清除 |
| 📟 常显 HUD | 背包里放着手机时，屏幕角落常驻一块迷你手机（默认右下角 60%）：左键打开完整手机、拖拽移动、**Ctrl+滚轮**缩放（40–150%）、**G** 键隐藏/显示，九锚点贴边 |

## 键位

| 键 | 功能 |
| --- | --- |
| P | 开/关手机 |
| C | 相机拍照（取景时） |
| G | 常显 HUD 显隐开关 |
| 每 App 热键 | 应用管理页内自行绑定，见上表 |

## 星门规则配置（默认合规档）

本 mod 面向 **GTNH「星门规则」（Stargate Rules）** 做了合规化：手机里超出官方服务器 QoL 供给水平的玩法功能**默认全部关闭或受限**，满足规则「Custom Mods 不得实质性影响游戏进程；插件不得超过官方 QoL 供给」的要求。完整逐项判定、官方链接与向 GTNH staff 报备的话术见 **[docs/STARGATE-RULES.md](docs/STARGATE-RULES.md)**。

**默认档位（开箱即用，冲刺星门认证直接用这一档）**：

| 功能 | 默认状态 |
| --- | --- |
| 📡 ME终端（AE2 无线终端直连） | **关闭**（`[ae2] registerWirelessTerminal=false`）——主屏与常显 HUD 都不显示该图标 |
| 内置兜底物品终端 | **关闭**（`[ae2] builtinTerminal=false`） |
| 🌀 传送点数量 | **3 个**（`[teleport] maxWaypoints=3`） |
| 传送冷却 | **30 秒**（`[teleport] cooldownSeconds=30`） |
| 跨维度传送 | **禁止**（`[teleport] crossDimension=false`） |
| 📦 末影箱直开 | **关闭**（`[enderchest] enabled=false`） |
| 💬 聊天好友传送 | 保留（`[chat] friendTeleport=true`，对齐官方 `/tp` 到玩家） |
| 时钟/天气/便签/相机/相册/HUD | 不受影响——纯 QoL，规则允许 |

### 如何关闭星门规则配置（恢复自由档）

单机自用、服内规矩宽松，或你已向 GTNH staff 报备放行时，**改配置文件就能关掉这些限制**——不用换 jar、不用编译，保存后**免重启**生效（懒加载 + mtime 缓存自动重读；被禁 App 的图标显隐随服务端同步名单更新，最迟重进世界生效）。

- 单人 / 局域网：`.minecraft/config/mcphone-stargate.cfg`
- 专用服务器：`<服务器根目录>/config/mcphone-stargate.cfg`
- 首次运行自动生成带注释的默认配置；**多人服以服务器端配置为准**（客户端改自己的配置不覆盖服务端判定）。

**① 只放开 AE2 无线终端**：

```properties
ae2 {
    registerWirelessTerminal=true
    builtinTerminal=true
}
```

**② 完全关闭星门限制（等于旧的日常游玩档）**：

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

聊天好友传送若要一并关掉，把 `[chat] friendTeleport` 改成 `false`。

> 键名已定版（服务端与客户端按同一套键名读写），**改键名会让旧配置静默失效、回落到最严默认值**。
> ⚠️ 放开限制即偏离星门认证档位，**认证跑请保持默认**；是否合规最终以官方规则书与 GTNH staff 答复为准。

**为什么默认值长这样（平衡代价）**：默认档对齐官方服务器的 QoL 供给——

- 聊天好友传送保留，因为规则明文允许「对其他玩家 `/tp`」；
- 传送点限 3 个 + 30 秒冷却 + 不跨维度，只保留「传送到路径点」这一官方认可形态，且不给官方之外的收益；
- AE2 无线终端与末影箱涉及「跳过游戏内成本」（通用无线终端的合成与电力约束、末影箱的合成与随身携带），属于「实质性影响游戏进程」，因此默认关闭——想用就得在配置里放开并**自行向 GTNH staff 报备**；
- 拿不准的组合，按规则兜底条款 **Ask GTNH staff**，`docs/STARGATE-RULES.md` 第 4 节有现成的功能清单与中英文询问话术。

## 开发者 / AI 接手

架构、踩坑清单、构建发版流程与工作规约见 **[docs/AI-DEV-NOTES.md](docs/AI-DEV-NOTES.md)**；GTNH 构建/调试/依赖通用要点见 [docs/gtnh-dev-guide.md](docs/gtnh-dev-guide.md)；附属开发见 [docs/addon-api.md](docs/addon-api.md)。完整开发过程历史在 `dev-history` 分支。

## 存储位置

| 内容 | 路径 |
| --- | --- |
| 照片 / 壁纸 | `.minecraft/mcphone/photos/`（把 PNG 放进来相册就能读） |
| 便签 | `.minecraft/mcphone/notes/` |
| 手机设置（缩放/图标顺序/App 开关） | `.minecraft/mcphone/settings.properties` |
| 星门规则配置（玩法开关；多人服以服务端为准） | 单人 `.minecraft/config/mcphone-stargate.cfg`／专用服务器 `<服务器根目录>/config/mcphone-stargate.cfg` |
| 附属 App 配置 | `.minecraft/mcphone/appdata/<appId>.properties` |

以上全部**跨存档共享**（客户端本地）。

## 给附属开发者

MCphone 带一套基于场景 UI 的 App 扩展接口：继承 `PhoneAppBase`、用 `PhoneWidgets` 搭页面、配置落在 `PhoneAppConfig`，几十行就是一个能用的 App。详见 **[docs/addon-api.md](docs/addon-api.md)**。

注册方式：代码 `PhoneApi.register(...)`，或 jar 内 `META-INF/services` 自动发现。

## 附属模组

基于上述接口的附属：

| 附属 | 说明 |
| --- | --- |
| [mcphone-addon-browser](https://github.com/skyc10/mcphone-addon-browser)（正在制作中） | 🌐 **浏览器**：点击图标直接打开 16:9 全屏虚拟大屏浏览真实网页，支持书签与浏览历史 |
| [mcphone-addon-wiki](https://github.com/skyc10/mcphone-addon-wiki)（正在制作中） | 📖 **维基**：点击图标全屏浏览 GTNH 中文维基，无 URL 栏、站内直达，带后退/刷新/首页 |
| mcphone-addon-music（正在制作中，仓库筹备中） | 🎵 **音乐**：查看 FMusic 前置正在播放的歌曲——歌名/歌手、封面、进度与歌词（只读展示，播放由 FMusic 负责） |

## 致谢

- **[november521](https://github.com/november521)** —— MCphone 的原作者，一切从这里开始：[november521/mcphone](https://github.com/november521/mcphone)（本仓库为其 GTNH 移植+重写版，由 skyc10 借助 AI 完成）
- **[QuanhuZeYu](https://github.com/QuanhuZeYu)** —— [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib) 的作者，GTNH 上难得的现代场景 UI 库（LGPL-3.0，本 mod 以依赖方式使用）

- **[智谱 AI（Z.ai）](https://github.com/zai-org/GLM-5)** —— 免费 GLM-5.3 token。本 mod 的 GTNH 适配与重写工作由 GLM-5.3 驱动的 AI 编码助手完成。

没有原作者的设计与代码，就没有这个 GTNH 版本。感谢。

## 许可

沿用原项目的许可；Qz-UILib 为 LGPL-3.0，以独立模组依赖使用，未修改其源码打包。
