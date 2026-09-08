# MCphone (GTNH)

简体中文 | [English](README.en.md)

把一部能用的智能手机塞进 GTNH —— 拍照、翻相册、换壁纸、记便签、多传送点传送、直连 AE2 通用无线终端，还能给自己那一部手机起个名。

> **这是什么**：由 skyc10 借助 AI 将 [november521/mcphone](https://github.com/november521/mcphone)（原版，主线为 Minecraft 1.21.1 + NeoForge）**移植并重写**适配 **GTNH 2.9** 的版本——原 mod 出自 november521，本仓库（skyc10）维护 GTNH 移植分支。
> 目标环境：**GTNH 2.9.0-beta-3**（Minecraft 1.7.10 + Forge 1614，Java 17+ 运行时，lwjgl3ify）。
> 界面层整体重写在 [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib) 场景 UI 上（原生分辨率渲染，带现代字体渲染器）。

**Minecraft 1.7.10** · **GTNH 2.9.0-beta-3** · 客户端与服务端都需安装

**前置模组**：

| 模组 | 说明 |
| --- | --- |
| [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib)（4.8+） | **必需前置**。GTNH 专用的现代场景 UI 库，手机的整个界面都跑在它上面。仓库里不发布构建产物时，可从本项目 Release 一起下载 |

**联动模组**：ME 终端 App 依赖 **AE2** 与 **ae2fc**（通用无线终端）——这两者 GTNH 整合包自带，无需额外安装。装了它们的 GTNH 里，手机可以潜行+右击 ME 安全站完成绑定，点图标直接打开通用无线终端的完整形态。

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
| 📦 末影箱 | 点图标直达：关掉手机直接打开你的末影箱，与原版完全互通 |
| 🌀 传送 | 内置传送，**不需要背包放传送宝石**。点击图标进传送点列表：绑定当前位置（也可 Shift+点击图标快速绑定）、一键传送（支持跨维度）、重命名、删除。传送点存在手机 NBT 里 |
| 📡 ME终端 | 直连 AE2。背包里有**通用无线终端**时自动换手打开它的完整形态，关界面自动换回；没有时打开手机内置的物品终端（电力免费）。绑定方式：潜行 + 持手机右击 ME 安全站 |
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

## 开发者 / AI 接手

架构、踩坑清单、构建发版流程与工作规约见 **[docs/AI-DEV-NOTES.md](docs/AI-DEV-NOTES.md)**；GTNH 构建/调试/依赖通用要点见 [docs/gtnh-dev-guide.md](docs/gtnh-dev-guide.md)；附属开发见 [docs/addon-api.md](docs/addon-api.md)。完整开发过程历史在 `dev-history` 分支。

## 存储位置

| 内容 | 路径 |
| --- | --- |
| 照片 / 壁纸 | `.minecraft/mcphone/photos/`（把 PNG 放进来相册就能读） |
| 便签 | `.minecraft/mcphone/notes/` |
| 手机设置（缩放/图标顺序/App 开关） | `.minecraft/mcphone/settings.properties` |
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
