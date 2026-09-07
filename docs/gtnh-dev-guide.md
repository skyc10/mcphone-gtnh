# GTNH 模组开发指南（本项目适用要点）

> 整理自 GTNH 官方维基「开发」页 <https://gtnh.huijiwiki.com/wiki/开发>（2026-09-07 抓取），
> 合并了本机（WSL2）构建实测经验与本项目族（mcphone / Qz-UILib / FMusic）的通用约定。
> 只保留与本项目直接相关、可操作的内容；本机代理端口等私密配置不入库，以本机实际配置为准。

## 构建环境

- WSL2 · Java 25 · Gradle 9.3.1（wrapper 自动下载）。GTNH RFG 模板用 Daemon JVM
  criteria（`gradle/gradle-daemon-jvm.properties`，toolchainVersion=25）+ Jabel
  （`enableModernJavaSyntax=jabel`，Java 21+ 语法 → Java 8 字节码），**无需另装 JDK 21**。
- **网络坑**：TUN / fake-ip 代理环境下，maven 中央仓库 / plugins.gradle.org 的 TLS 握手可能
  被间歇重置（域名解析到 fake-ip 段）。解决：在**用户级** `~/.gradle/gradle.properties`
  配置本地代理（`systemProp.http(s).proxy*` + `org.gradle.jvmargs` 双保险），端口等细节
  不写入任何仓库。备选镜像：maven.aliyun.com（依赖下载）、
  `https\://mirrors.cloud.tencent.com/gradle/`（gradle 分发，改
  `gradle/wrapper/gradle-wrapper.properties` 的 distributionUrl，但会与上游产生 diff，非必要不动）。
- **注意：改任何 gradle.properties 后必须 `./gradlew --stop` 杀掉 daemon 才生效**，
  旧 daemon 会带着旧配置复用。
- mcphone-gtnh 全量构建 `./gradlew build --build-cache` ≈ 2 分钟；二次构建可加 `--offline`
  跳过依赖更新检查（时间跨度大时先在线完整更新一次）。

## 构建命令速查

| 命令 | 用途 |
| --- | --- |
| `./gradlew build --build-cache` | 全量构建（--build-cache 加速） |
| `./gradlew build --offline` | 二次构建，跳过依赖检查 |
| `./gradlew spotlessApply` | 格式检查报错时一键修复（GTNH 模板启用 Spotless） |
| `./gradlew updateDependencies` | 依赖过期导致构建失败时更新依赖 |
| `./gradlew updateBuildScript` | 更新构建脚本（本项目 `autoUpdateBuildScript=false`，需手动跑） |

- 产物：`build/libs/` 下**不带 `-dev`/`-api` 后缀**的 jar 是发布用 jar；`-dev` jar 用于
  跨项目编译（现有约定：附属 mod 编译依赖 mcphone dev jar，mcphone-gtnh 编译依赖
  `libs/qz_uilib-dev.jar`，运行时统一由实例 mods 目录里的正式 jar 提供）。
- IDE 提示缺东西但 gradlew 能构建 → 先刷新 Gradle；需 64 位 Java，`JAVA_HOME` 指向正确版本。

## 调试手段

- **Mixin 调试**（GTNH 模板 `usesMixins=true` 时）JVM flags：
  - `-Dmixin.debug=true -Dmixin.debug.verbose=true`
  - `-Dmixin.debug.export=true`（应用 mixin 后的类导出到 `.mixin.out`，配合 fernflower 可反编译）
  - `-Dmixin.debug.countInjections=true`（注入计数不符时报错）
  - 快捷方式：gradle.properties 里 `usesMixinDebug=true` 一键开。
  - 注：mcphone 本体不写 Mixin（`mixin` 包只是占位，为 qz_uilib 运行时提供 Mixin 库），
    调试 Qz-UILib 的 mixin 相关问题时才需要开。
- **类加载调试**（排查 lwjgl3ify / MCEF 类转换问题）：
  `-Dlegacy.debugClassLoading=true`，加 `-Dlegacy.debugClassLoadingFiner=true
  -Dlegacy.debugClassLoadingSave=true` 会把转换前后类转储到 `./CLASSLOADER_TEMP`。
- **远程调试整个整合包**：JVM args 加
  `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005`，IDEA 里
  Run → Attach to Process。行号断点基本不可用（混淆后行号乱），**用方法断点**。
- **Hotswap**：RFG 生成的 "Run Client (Java 17/25, Hotswap)" 运行配置 + IDEA
  Build → Reload Changed Classes，改 UI/逻辑无需重启游戏（对 Qz-UILib 场景式 UI 迭代
  很有用）。进阶热重载可上 DCEVM（Trava JDK；注意 DCEVM 下要去掉自定义 GC 参数）。

## 依赖类型速查（dependencies.gradle）

| 配置 | 自己编译 | 自己运行 | 下游编译 | 下游运行 |
| --- | --- | --- | --- | --- |
| api | ✓ | ✓ | ✓ | ✓ |
| implementation | ✓ | ✓ | ✗ | ✓ |
| compileOnly | ✓ | ✗ | ✗ | ✗ |
| compileOnlyApi | ✓ | ✗ | ✓ | ✗ |
| runtimeOnly | ✗ | ✓ | ✗ | ✓ |

现有 `compileOnly(files("libs/…-dev.jar"))` 即"仅编译期可见、运行时由正式 jar 提供"的形态，
与附属 mod 的定位一致。若其他 mod 需要基于 mcphone 的 API 开发，把 API 包改走 `api`。

## 跨 mod 联调的官方姿势（Maven Local）

比手动拷 libs/ jar 更规范的做法（但 GitHub CI 不生效）：

1. 依赖方（如 Qz-UILib）：`VERSION=99.99.99 ./gradlew publishToMavenLocal`
2. 使用方（如 mcphone-gtnh）：`repositories.gradle` 加 `mavenLocal()`，`dependencies.gradle`
   指向对应版本。
3. CI 需要正式发布 tag，非 GTNH 团队需请人发布。

当前 libs/ dev-jar 方案可保留，本地频繁联调时可切换 mavenLocal。

## 加依赖时的 Maven 仓库

GTNH 维基原文有完整仓库表，常用的有：

- <https://cursemaven.com/>（任意 CurseForge 文件）
- <https://modmaven.dev/>
- <https://chickenbones.net/maven/>
- <https://dvs1.progwml6.com/>
- <https://jitpack.io/>（任意 GitHub 项目）

## 贡献上游（给 GTNH / 原仓库提 PR 时）

- 一个 PR 只做一个功能/修复；改动要彻底测试（了解被改类/方法的调用面）。
- **即使获批也不要自行合并 PR**（GTNewHorizons/admin 的活）；代码改动请求
  GTNewHorizons/developers review，NEI 相关找 mitchej123，任务/配方找 chochom 或
  DreamMasterXXL，原 mod 作者仍活跃的优先找原作者。
- fork → clone → `./gradlew updateBuildScript` → 开发，参考
  ExampleMod1.7.10 的 docs/migration.md。

## 资源精选（1.7.10 优先）

- **源码参考**：GTNH 全家桶都在 github.com/GTNewHorizons（Ctrl+F 找类似实现）；
  ExampleMod1.7.10 是模板。
- **1.7.10 Javadoc**：skmedix ForgeJavaDocs、DocsMC、makamys MCJavaDocs。
- **Mapping 查询**：mcp.thiakil.com、Linkie（linkie.shedaniel.dev）、MCP Mapping Viewer。
- **Mixin**：2xsaiko/mixin-cheatsheet、Mixin Extras wiki、SpongePowered Mixin wiki。
- **反编译/字节码**：BON2、FernFlower、JD-GUI（看 MCEF、Qz-UILib 依赖源码时用）。
- **建模/贴图**：Blockbench（手机 3D 模型）、LibreSprite/Aseprite（像素图）、
  lospec.com/palette-list（调色板）。
- **1.7.10 教程**：coolAlias Forge Tutorials、EMX Tutorials、
  Awesome Minecraft 1.7.10（github.com/LegacyModdingMC/awesome-minecraft-1.7.10）。
- **GTNH 专属**：StructureLib 文档（多方块结构）、维基「化学平衡」「代码风格」页、
  任务开发指南（modpack 仓库 config/betterquesting）。
- **求助**：GTNH Discord #mod-dev、StackOverflow。

## IDEA 插件注意

- **Minecraft Development** 插件需使用 GTNH 维护的分支
  <https://github.com/eigenraven/MinecraftDev>（插件市场原版与 RetroFuturaGradle 不兼容）。
- **ASM Bytecode Viewer**（插件 ID 10302）可正确显示 ASM 化代码（JetBrains 捆绑的那个不行）。
