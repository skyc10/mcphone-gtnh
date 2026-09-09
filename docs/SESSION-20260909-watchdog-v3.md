# 会话归档：退出看门狗 v3（2026-09-09）

> 本文是 2026-09-09「主 mod 看门狗失效调查 → v3 重写 → 实测 → 交付」会话的完整交接。
> 下个会话若要继续验证/诊断退出问题，从这里开始即可，无需翻旧会话。
> 设计细节已同步进 `docs/AI-DEV-NOTES.md` 踩坑 #10；本文偏操作与状态。

## 1. 背景与取证结论（为什么重写）

用户报告：点退出游戏后进程依然挂着（v2 看门狗没起作用）。取证（fml-client-latest.log，2026-09-09 会话）：

- 19:31:42 看门狗 armed（日志行 25315），19:39:45 用户退出，FMLServerStopping/Stopped 走完，19:39:46 MCEF "Shutting down JCEF..."，19:39:47 SoundSystem 关闭后**日志戛然而止**（文件 mtime 19:39:47，用户 19:56 手动杀进程）。
- **主看门狗 armed 后零输出**：没有退出检测、没有 5s 线程摘要、没有 25s dump（`mcphone/shutdown-dump-25s.txt` 不存在）。而附属 mod 自己的看门狗（`logs/mcphone_browser_watchdog.log` / `mcphone_wiki_watchdog.log`）用**同一个** `Minecraft.running` 字段正确检测到了退出——探测手段本身没问题，是 v2 时间线没走完。
- v2 只打 stderr，log4j 一死输出全丢，死后无法诊断；强杀完全依赖 JVM 内部线程，在退出冻结期（MCEF native 卡死/safepoint）本身不可靠。
- 无 crash-report、无 hs_err。

## 2. v3 设计（已实现，commit 39745e7）

`src/main/java/com/november/mcphone/client/ForceExitWatchdog.java` 全文重写（v2 422 行 → v3 ~670 行），三层：

1. **独立文件日志** `<mcDataDir>/mcphone/exit-watchdog.log`（append，256KB 轮转 .1）。三条内部线程各 60s 一条 `xxx alive` 心跳行，游戏死后可精确诊断哪条线程活到几点。
2. **最小化探测器（线程 B）**：只读 volatile `Minecraft.running`（反射找 Minecraft 里唯一 volatile boolean 字段，与 MCEF 同款），**绝不碰 `Thread.getAllStackTraces()`**（v2 信号判定用它，疑似退出冻结期阻塞）。连续 2 次读到 false → 写 flag `.exit-flag-<pid>`。
3. **JVM 外部杀手（仅 Windows）**：运行时生成 `mcphone/ext-watchdog.ps1`（UTF-16LE 带 BOM——含中文路径 PowerShell 5 必须如此）+ `ext-watchdog-launch.vbs`，`wscript //B` 隐藏启动（直接 powershell -WindowStyle Hidden 会闪黑框）。循环：flag 出现 → 等 35s → `taskkill /F /T /PID`；心跳 `.exit-hb-<pid>` 断更 90s（=三条内部线程全冻，v2 式失效）→ 同样强杀；目标进程消失 → 脚本自退。

内部时间线与 v2 相同：daemon 轮询（running=false 或 Client thread 消亡，连续 2 次）→ 25s 宽限（每 5s 线程摘要）→ halt 策略链（直调 halt → 反射 halt → FML exitJava → System.exit，各独立 daemon 线程+200ms 窗，全失败 30s 重试）→ Client thread 仍活再宽 10s，35s 无条件。三线程（watchdog/detector/heartbeat）互为看门狗，beat 超 10s 判死并重启同伴。

系统属性：`-Dmcphone.exitwatchdog=false` 禁用；`-Dmcphone.exitwatchdog.externalgrace=<秒>`（默认 35）；`-Dmcphone.exitwatchdog.hbstall=<秒>`（默认 90）。

## 3. 实测（全部真实 Windows 进程验证，2026-09-09）

在 `C:\temp\wdtest\`（已清理）用 ping.exe 当靶：

- flag 链：grace 8s，9s 后靶死 ✓
- 心跳断更链：hb 文件作旧 30s + stall 6s，2s 内靶死 ✓
- wscript+vbs 启动链：立即返回、无窗口、杀手正常工作 ✓（教训：vbs 的 `WScript.Arguments(0)` 只收 ps1 路径一个参数，**参数必须烤进脚本内容**——首次测试把参数拼进 Arguments 才失败）
- `./gradlew build` 通过。

## 4. 交付状态（有一条待办）

- 主 mod jar：`build/libs/mcphone-v1.0.2-master.33+6d21800d55-dirty.jar`（v3）已复制到实例 mods/。
- 实例：`/mnt/c/Users/陈/AppData/Roaming/PrismLauncher/instances/290b3test/.minecraft`
- **待办（需要用户手动）**：交付时游戏正开着（pid 65180 javaw.exe），旧 jar `mcphone-v1.0.2-master.29+5da5132e36.jar` 被占用删不掉。**关游戏后必须删掉旧 jar**，mods 里只留 33+dirty 一个主 mod jar（三个 mcphone-addon-* 附属保留）。实例 `mcphone/UPDATE-NOTE-v3.txt` 里也写了。
- 当前跑着的这局还是 v2；**下次启动才加载 v3**。
- git：master commit `39745e7`（fix: 退出看门狗 v3…），dirty 后缀来自构建时未提交，提交后下次构建即为干净版本号。

## 5. 下次验证方法

1. 启动游戏 → `mcphone/exit-watchdog.log` 应出现 `=== session start: watchdog v3, pid=...` + `armed` + `external killer spawned via wscript`，且 `mcphone/` 下出现 `ext-watchdog.ps1`、`ext-watchdog-launch.vbs`、`.exit-hb-<pid>`（隐藏文件）。
2. 正常退出：进程应自然结束（若 JVM 内收尾成功）或最迟 flag 写出后 ~35s 被外部击杀。日志里应能看到 `game exit detected via ...`、`exit flag written`。
3. 若再挂死：收 `mcphone/exit-watchdog.log` + `shutdown-dump-*.txt`，alive 心跳行能定位卡死的内部线程；flag/心跳时间戳能定位外部杀手是否接手。
4. 若误杀（不该杀时杀了）：检查 `extGraceSec`/`hbStallSec`，必要时调大或 `-Dmcphone.exitwatchdog=false` 关闭。

## 6. 相关路径速查

- 看门狗源码：`src/main/java/com/november/mcphone/client/ForceExitWatchdog.java`
- 注册链：`MCphone.preInit` → `ClientProxy.initClientHooks()`（`src/main/java/com/november/mcphone/ClientProxy.java:23` 调 `ForceExitWatchdog.register()`）
- 设计文档：`docs/AI-DEV-NOTES.md` 踩坑 #10（v3 段落）
- 运行时产物（实例 `mcphone/` 下）：`exit-watchdog.log`、`ext-watchdog.ps1`、`ext-watchdog-launch.vbs`、`.exit-flag-<pid>`、`.exit-hb-<pid>`、`shutdown-dump-<phase>.txt`
- 旧版取证日志：实例 `logs/fml-client-latest.log`（2026-09-09 19:31–19:39 段）、`logs/mcphone_browser_watchdog.log`
