package com.november.mcphone.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 退出保底看门狗 v3：无论附属 mod 行为如何，保证「点退出游戏」后进程一定结束。
 *
 * <p><b>v2 的实测缺陷（2026-09-09）</b>：看门狗正常 armed，玩家退出后
 * fml-client-latest.log 在 SoundSystem 关闭后戛然而止，但主看门狗 armed 之后
 * <b>零输出</b>——没有退出检测、没有线程摘要、没有 25s dump（附属看门狗用同一个
 * running 字段却正确检测到了退出）。同时它只往 stderr 打日志，log4j 一死其输出
 * 全部丢失，死后无法诊断卡在哪一步；强杀完全依赖 JVM 内部线程，而退出卡死场景
 * （MCEF native 冻结 / safepoint 挂起）下 JVM 内线程本身就不可靠。</p>
 *
 * <p><b>v3 三层设计</b>：</p>
 * <ol>
 * <li><b>独立文件日志</b>：{@code <mcDataDir>/mcphone/exit-watchdog.log}（append，
 *     超 256KB 轮转为 .1）。三条内部线程各自每 60s 打一行 alive 心跳，游戏死后
 *     也能精确诊断哪条线程活到几点。</li>
 * <li><b>最小化探测器</b>：daemon 线程只做 volatile 字段读（{@code Minecraft.running}，
 *     绝不碰 {@link Thread#getAllStackTraces()}），连续 2 次读到 running=false 就
 *     发出退出请求。字段读不可能阻塞，这是整条链里最可靠的一环。</li>
 * <li><b>JVM 外部杀手</b>（Windows）：注册时生成 PowerShell 脚本并用 wscript
 *     隐藏启动（无窗口闪现）。循环检测：flag 文件出现 → 等
 *     {@link #PROP_EXT_GRACE} 秒 → {@code taskkill /F /T} 杀整棵进程树；flag
 *     没出现但心跳文件 {@code .exit-hb-<pid>} 断更超 {@link #PROP_HB_STALL} 秒
 *     （三条内部线程全死/全冻 = v2 式失效）→ 同样强杀；目标进程消失 → 自己退出。
 *     JVM 内部无论冻结成什么样都杀得掉。</li>
 * </ol>
 *
 * <p><b>内部时间线</b>（v3.3 起由「保存门控」驱动）：daemon 线程探测退出请求
 * （running=false 或 Client thread 消亡，请求出现前绝不干预）。请求出现后
 * <b>先等世界保存结束</b>（见 v3.3 第 1 条），保存结束后 0–25s 宽限（每 5s 一行
 * 存活非守护线程摘要）；25s Client thread 已亡且有非守护线程存活 → halt 策略链；
 * Client thread 仍活 → 再宽限 10s，35s 无条件 halt。
 * halt 策略链：直调 Runtime.halt → 反射 halt → FML exitJava → System.exit，
 * 每策略独立 daemon 线程 + 200ms 判定窗，全部失败每 30s 重试。</p>
 *
 * <p><b>三线程互为看门狗</b>：任一线程发现同伴死掉就拉起新线程顶替（beat 超过
 * {@link #STALE_BEAT_MS} 判死）。三条线程全部死亡/冻结时由外部杀手的心跳断更
 * 兜底——这就是 v2 失效场景（内部线程没走完时间线）的解。</p>
 *
 * <p><b>v3.1 修正</b>（dummy 端到端复现 + 2026-09-09 两次卡死取证）：模板链的
 * flag / 心跳两分支本身都能杀，卡死的真正缺口是 (1) 退出清理阶段 JVM 整体冻结
 * 时 flag 没人写（内部线程与主线程一起冻死）；(2) 杀手侧零观测，是否运行过全靠
 * 猜。修正：最早注册的 shutdown hook 一进退出流程就写 flag（抢在冻结点前立起
 * 外部杀手的触发器）；杀手 ps1 自带 {@code ext-watchdog.log} 记录启动/触发/杀完；
 * spawn 后轮询确认杀手真的起来了，失败退回直启 powershell；{@code touchHb}/
 * {@code writeFlag} 去掉 synchronized（v3 持锁做文件 I/O，FS 卡顿会拖死三条内部
 * 线程）。</p>
 *
 * <p><b>v3.3 修正（2026-09-15，本轮）——两个坏档/误杀缺陷</b></p>
 *
 * <p><b>(a) 强杀时钟不再早于世界保存。</b>1.7.10 的退出顺序是
 * {@code runGameLoop → Minecraft.shutdown()（running=false）→ run() 退出循环 →
 * finally shutdownMinecraftApplet() → loadWorld(null)（此处 initiateShutdown 并
 * 等待集成服务端停止 = 世界落盘）→ Display.destroy() → System.exit(0)}。v3.x 让
 * 探测器一读到 {@code running==false} 就写 flag（≈0.5–1s），而 25s/35s 内部时间线
 * 与外部 flag+35s 都从这一刻起算 ⇒ <b>整段强杀窗口覆盖世界保存</b>，慢存档会被
 * {@code Runtime.halt}/taskkill 打断在写盘中途（坏档窗口）。
 * v3.3 的门控：探测器只登记「退出请求」，内部时间线必须等
 * <i>世界保存结束</i> 才起跑，判据（任一成立）：
 * <ul>
 * <li>{@code shutdownStarted}：注册时挂的 shutdown hook 已进入 JVM 关闭流程
 *     （hook 只在 {@code System.exit(0)} 时跑，而该调用位于 loadWorld(null) 之后）；</li>
 * <li>集成服务端对象已消失：结构扫描 {@code Minecraft} 中类型名以
 *     {@code IntegratedServer} 结尾的字段，其值由 {@code loadWorld(null)} 在
 *     「等到 {@code isServerStopped()}」之后清空——字段非空期间即「可能仍在保存」；</li>
 * <li>退出请求来自「Client thread 已消亡」（崩溃路径/保存阶段已结束）；</li>
 * <li><b>上界兜底</b>：{@link #PROP_SAVE_WAIT}（默认 180s）到期仍判定为正在保存，
 *     则照旧武装强杀——真卡死时绝不无限等待。</li>
 * </ul>
 * 保存期间三条内部线程继续 touch 心跳（外部杀手的心跳断更分支因此不会在合法慢存档
 * 中途开火）。探测不可用时（找不到字段）一律 <b>fail-safe</b> 当作「可能仍在保存」，
 * 由上面的上界收口，绝不因为探测不到就退回「保存前就开杀」。</p>
 *
 * <p><b>(b) 心跳断更判定不再依赖墙钟。</b>v3.2 生成的 ps1 用
 * {@code ((Get-Date) - $hb.LastWriteTime).TotalSeconds > 90} 判心跳断更：OS 休眠/
 * 挂起期间游戏进程与心跳文件一起停摆，唤醒瞬间墙钟直接跳过 90s ⇒ <b>立刻杀掉一个
 * 完全健康的游戏</b>。v3.3 改为「执行保护的多次确认」：判定疑似断更后不立即开火，
 * 而是做 {@link #PROP_HB_CONFIRM}（默认 10s，拆成每轮 2s）轮确认，每轮
 * (1) 真正执行一段自旋（挂起期间不执行，故「完成轮次」本身就是系统在跑的凭据）、
 * (2) 复核心跳文件的 mtime 与内容里的单调序号 {@code seq}（Java 侧每 2s 写入
 * millis/nanoTime/seq，{@code nanoTime} 是与墙钟无关的单调时钟）、
 * (3) 复核 flag 是否出现、进程是否还在。任何一轮发现心跳前进 → 判定为
 * 「休眠唤醒 / FS 卡顿」并放弃击杀、重置窗口。只有 <b>连续多轮真实执行后心跳仍
 * 毫无前进</b>（且观察者自身 CPU 时间确实增长 = 系统确实醒着）才 taskkill，
 * 同时把目标进程的 {@code TotalProcessorTime} 增量写进日志作为「进程是否在跑」的
 * 第二判据。OS 休眠/唤醒因此不再误杀，而真卡死仍能被杀。</p>
 *
 * <p>{@code -Dmcphone.exitwatchdog=false} 整体禁用；
 * {@code -Dmcphone.exitwatchdog.externalgrace=<秒>}（默认 35）flag 触发后的外部
 * 强杀延迟；{@code -Dmcphone.exitwatchdog.hbstall=<秒>}（默认 90）心跳断更判定；
 * {@code -Dmcphone.exitwatchdog.hbconfirm=<秒>}（默认 10，4–120）断更确认窗；
 * {@code -Dmcphone.exitwatchdog.savewait=<秒>}（默认 180，0 = 关闭保存门控退回
 * v3.2 行为）退出请求后等待世界保存的上界。
 * 外部脚本与 flag/心跳文件都在 mcphone 数据目录，可随时手删。</p>
 */
@SideOnly(Side.CLIENT)
public final class ForceExitWatchdog {

    /** 保存门控走完后的宽限期：覆盖退出清理（SoundSystem/JCEF）等正常收尾。 */
    private static final long GRACE_MS = 25000;
    /** 宽限期内的存活线程摘要打印间隔。 */
    private static final long SUMMARY_INTERVAL_MS = 5000;
    /** Client thread 仍存活时的追加宽限（收尾疑似死锁）。 */
    private static final long EXTRA_GRACE_MS = 10000;
    /** 每种 halt 策略之间的生效判定窗口。 */
    private static final long HALT_PROBE_MS = 200;
    /** 全部策略失败后的重试周期。 */
    private static final long RETRY_INTERVAL_MS = 30000;
    /** 内部线程轮询退出信号的间隔。 */
    private static final long POLL_INTERVAL_MS = 500;
    /** 内部线程心跳文件触碰间隔。 */
    private static final long HEARTBEAT_MS = 2000;
    /** 内部线程 alive 日志间隔（写进文件日志，死后可诊断）。 */
    private static final long ALIVE_LOG_MS = 60000;
    /** 同伴线程 beat 超过此时长视为死亡，拉新线程顶替。 */
    private static final long STALE_BEAT_MS = 10000;
    /** 文件日志轮转阈值。 */
    private static final long LOG_ROTATE_BYTES = 256 * 1024;
    /** 禁用开关：-Dmcphone.exitwatchdog=false。 */
    private static final String PROP_DISABLE = "mcphone.exitwatchdog";
    /** 外部杀手 flag 触发延迟（秒）：-Dmcphone.exitwatchdog.externalgrace。 */
    private static final String PROP_EXT_GRACE = "mcphone.exitwatchdog.externalgrace";
    /** 外部杀手心跳断更判定（秒）：-Dmcphone.exitwatchdog.hbstall。 */
    private static final String PROP_HB_STALL = "mcphone.exitwatchdog.hbstall";
    /** 外部杀手心跳断更确认窗（秒）：-Dmcphone.exitwatchdog.hbconfirm。 */
    private static final String PROP_HB_CONFIRM = "mcphone.exitwatchdog.hbconfirm";
    /**
     * 退出请求后等待世界保存结束的上界（秒）：-Dmcphone.exitwatchdog.savewait。
     * 0 = 关闭门控（退回 v3.2「保存前即开杀」行为，仅用于对照实验）。
     */
    private static final String PROP_SAVE_WAIT = "mcphone.exitwatchdog.savewait";
    /** 保存门控上界默认值（秒）。 */
    private static final int SAVE_WAIT_DEF_SEC = 180;
    /** 外部杀手心跳断更确认窗默认值（秒）。 */
    private static final int HB_CONFIRM_DEF_SEC = 10;
    /** B/H 线程在上界 + 这个余量后仍未见到 flag 时的兜底武装（A 被卡死时）。 */
    private static final long GATE_ASSIST_SLACK_MS = 60000L;
    /** 墙钟与单调时钟落差超过此值时打一行「疑似 OS 挂起」诊断（纯观测，不参与判定）。 */
    private static final long SUSPEND_DIAG_MS = 60000L;
    /** 1.7.10 客户端主线程名。 */
    private static final String MAIN_THREAD_NAME = "Client thread";
    /** 退出 flag / 心跳文件名前缀（带 pid，多实例互不干扰）。 */
    private static final String FLAG_PREFIX = ".exit-flag-";
    private static final String HB_PREFIX = ".exit-hb-";

    private ForceExitWatchdog() {}

    // ---- 共享状态（register() 早期一次性填充，之后只读或 volatile） ----
    private static File baseDir;
    private static File flagFile;
    private static File hbFile;
    private static Minecraft mcRef;
    private static Field runningField;
    /** 集成服务端字段（结构扫描；非空 = 世界保存可能仍在进行）。 */
    private static Field integratedServerField;
    private static int extGraceSec = 35;
    private static int hbStallSec = 90;
    private static int hbConfirmSec = HB_CONFIRM_DEF_SEC;
    /** 保存门控上界（毫秒）；<=0 表示关闭门控。 */
    private static long saveWaitMaxMs = SAVE_WAIT_DEF_SEC * 1000L;
    private static volatile boolean flagWritten;
    /** 退出请求（running=false 或 Client thread 消亡）确认时刻；0 = 尚未请求。 */
    private static volatile long exitRequestedAt;
    /** 退出请求来自「Client thread 已消亡」（保存阶段必然已结束）。 */
    private static volatile boolean clientDeadExit;
    /** shutdown hook（= System.exit 之后，世界保存已完成）已进入 JVM 关闭流程。 */
    private static volatile boolean shutdownStarted;
    /** 内部强杀时间线已被某条线程认领（避免 A/B 双跑）。 */
    private static volatile boolean timelineClaimed;
    /** 外部杀手 ps1 的自记日志（ext-watchdog.log），与主日志分开便于诊断杀手侧。 */
    private static File killerLogFile;
    private static volatile long beatA;
    private static volatile long beatB;
    private static volatile long beatHb;
    private static Thread threadA;
    private static Thread threadB;
    private static Thread threadHb;
    /** 心跳文件内容写入锁：只串行化三条线程之间的小写入，不牵涉其它路径。 */
    private static final Object HB_LOCK = new Object();
    /** 心跳内容里的单调序号（每 touch 一次 +1，供外部杀手判「有没有前进」）。 */
    private static long hbSeq;

    public static void register() {
        if ("false".equalsIgnoreCase(System.getProperty(PROP_DISABLE, "true"))) {
            System.err.println("[mcphone] ForceExitWatchdog: disabled via -D" + PROP_DISABLE + "=false");
            return;
        }
        long pid = currentPid();
        try {
            baseDir = PhoneCanvas.baseDir();
        } catch (Throwable t) {
            baseDir = null;
            System.err.println("[mcphone] ForceExitWatchdog: no data dir (" + t
                + "), file log & external killer unavailable");
        }
        if (baseDir != null) {
            if (pid > 0) {
                flagFile = new File(baseDir, FLAG_PREFIX + pid);
                hbFile = new File(baseDir, HB_PREFIX + pid);
                // 同 pid 复用（上次会话被外部杀手杀掉）时清掉旧文件。
                flagFile.delete();
                touchHb();
            }
            FileLog.log("=== session start: watchdog v3.3, pid=" + pid
                + ", os=" + System.getProperty("os.name")
                + ", java=" + System.getProperty("java.version"));
        }
        mcRef = safeMinecraft();
        runningField = mcRef != null ? findRunningField() : null;
        integratedServerField = mcRef != null ? findIntegratedServerField() : null;
        extGraceSec = Math.max(5, Integer.getInteger(PROP_EXT_GRACE, 35).intValue());
        hbStallSec = Math.max(5, Integer.getInteger(PROP_HB_STALL, 90).intValue());
        hbConfirmSec = clampInt(Integer.getInteger(PROP_HB_CONFIRM, HB_CONFIRM_DEF_SEC).intValue(), 4, 120);
        int saveWaitSec = clampInt(Integer.getInteger(PROP_SAVE_WAIT, SAVE_WAIT_DEF_SEC).intValue(), 0, 3600);
        saveWaitMaxMs = saveWaitSec * 1000L;
        FileLog.log("armed, watching "
            + (runningField != null ? "Minecraft." + runningField.getName() + " / Client thread liveness"
                                    : "Client thread liveness")
            + " (internal grace " + (GRACE_MS / 1000) + "s/" + ((GRACE_MS + EXTRA_GRACE_MS) / 1000)
            + "s after save gate; external kill: flag +" + extGraceSec + "s or heartbeat stall " + hbStallSec
            + "s with " + hbConfirmSec + "s guarded confirm; save gate "
            + (saveWaitMaxMs > 0
                ? (integratedServerField != null
                    ? "probe=" + integratedServerField.getType().getName() + " cap " + (saveWaitMaxMs / 1000) + "s"
                    : "probe UNAVAILABLE (fail-safe: wait max " + (saveWaitMaxMs / 1000) + "s)")
                : "DISABLED (savewait=0)"));
        long now = System.currentTimeMillis();
        beatA = beatB = beatHb = now;
        installEarlyFlagHook();
        startThread('A');
        startThread('B');
        startThread('H');
        if (baseDir != null && pid > 0) {
            if (isWindows()) {
                spawnExternalKiller(pid);
            } else {
                FileLog.log("external killer skipped (not windows)");
            }
        } else {
            FileLog.log("external killer skipped (pid/data dir unavailable)");
        }
    }

    /** 三条内部线程统一从这里拉起；ensureOthers() 重启用同一入口。 */
    private static synchronized void startThread(char which) {
        String name;
        Runnable body;
        switch (which) {
            case 'A': name = "watchdog";  body = ForceExitWatchdog::watcherBody;   break;
            case 'B': name = "detector";  body = ForceExitWatchdog::detectorBody;  break;
            default:  name = "heartbeat"; body = ForceExitWatchdog::heartbeatBody; break;
        }
        Thread t = new Thread(body, "mcphone-exit-" + name);
        t.setDaemon(true);
        t.start();
        switch (which) {
            case 'A': threadA = t; break;
            case 'B': threadB = t; break;
            default:  threadHb = t; break;
        }
    }

    /** 拉起除 self 之外所有已死/未启动的内部线程（互为看门狗）。 */
    private static synchronized void ensureOthers(Thread self) {
        if (self != threadA && (threadA == null || !threadA.isAlive())) {
            FileLog.log("watchdog thread dead, restarting");
            startThread('A');
        }
        if (self != threadB && (threadB == null || !threadB.isAlive())) {
            FileLog.log("detector thread dead, restarting");
            startThread('B');
        }
        if (self != threadHb && (threadHb == null || !threadHb.isAlive())) {
            FileLog.log("heartbeat thread dead, restarting");
            startThread('H');
        }
    }

    // ------------------------------------------------------------------
    // 线程 A：完整探测器 + 保存门控 + 25s/35s 时间线 + halt 策略链
    // ------------------------------------------------------------------

    private static void watcherBody() {
        long lastAlive = System.currentTimeMillis();
        int hits = 0;
        boolean clientDead = false;
        try {
            while (true) {
                long now = System.currentTimeMillis();
                beatA = now;
                try {
                    touchHb();
                } catch (Throwable ignored) {}
                ensureOthers(Thread.currentThread());
                boolean exiting;
                try {
                    // mainThreadState() 返回 -1（探针故障）不算退出信号；若此时连
                    // running 字段也没有，则探测能力整体失效 → 退回 v1 兜底。
                    int st = mainThreadState();
                    if (st < 0 && runningField == null) {
                        throw new IllegalStateException("thread probe failed, no running field");
                    }
                    clientDead = (st == 1);
                    exiting = (runningField != null && !runningField.getBoolean(mcRef)) || clientDead;
                } catch (Throwable t) {
                    // 探测能力意外失效 → 保守退回 v1 的 shutdown hook 方案兜底。
                    // 线程保持存活（继续 beat + 监督同伴），避免 ensureOthers
                    // 无限重启循环。
                    FileLog.log("signal probe failed, disarmed (" + t + "), shutdown hook fallback installed");
                    installLegacyHook(baseDir);
                    while (true) {
                        beatA = System.currentTimeMillis();
                        try {
                            touchHb();
                        } catch (Throwable ignored) {}
                        ensureOthers(Thread.currentThread());
                        sleep(1000);
                    }
                }
                if (exiting) {
                    // 连续两次（跨 500ms）才认定退出已开始：排除 getAllStackTraces
                    // 偶发漏报 Client thread 造成的误判（宁可漏杀不可误杀）。
                    if (++hits >= 2) {
                        break;
                    }
                } else {
                    hits = 0;
                }
                if (now - lastAlive > ALIVE_LOG_MS) {
                    lastAlive = now;
                    FileLog.log("watchdog alive (full probe)");
                }
                sleep(POLL_INTERVAL_MS);
            }
            onExitRequested("watchdog timeline probe" + (clientDead ? " (Client thread gone)" : " (running=false)"),
                clientDead);
            runExitSequence("watchdog");
        } catch (Throwable t) {
            // 退出序列意外失败时不要盲目强杀（可能仍未到保存门控）：退回 v1 的
            // 「shutdown hook 兜底」（hook 只在 System.exit 之后跑，天然不覆盖世界
            // 保存阶段），同时保留心跳 + 上界兜底武装（gateAssist）作为最终保证。
            FileLog.log("watcher crashed (" + t + "), relying on shutdown-hook + heartbeat-cap + external killer");
            installLegacyHook(baseDir);
        }
        parkForever();
    }

    /**
     * 退出请求确认（A/B 任一线程命中即调用，幂等）：只登记请求时刻，
     * <b>不写 flag、不开杀</b>——真正的武装要等世界保存结束（见
     * {@link #waitForSavePhaseEnd(String)}）。
     */
    private static void onExitRequested(String source, boolean clientDead) {
        boolean first = false;
        synchronized (ForceExitWatchdog.class) {
            if (exitRequestedAt == 0L) {
                exitRequestedAt = System.currentTimeMillis();
                clientDeadExit = clientDead;
                first = true;
            }
        }
        if (first) {
            FileLog.log("game exit detected via " + source + " -> holding all kills until the world save finishes ("
                + (clientDead ? "Client thread already gone, save phase over"
                              : "save gate cap " + (saveWaitMaxMs / 1000) + "s") + ")");
        }
    }

    /**
     * 退出序列：保存门控 → 武装（写 flag）→ 强杀时间线 → 停放。
     * 由线程 A 驱动；B/H 只做上界兜底武装，不重复跑时间线。
     */
    private static void runExitSequence(String who) {
        String reason = waitForSavePhaseEnd(who);
        writeFlag(reason);
        if (claimTimeline()) {
            runTimeline();
        }
        parkForever();
    }

    /**
     * 等待世界保存结束。返回武装 flag 的原因描述（用于日志/flag 内容）。
     *
     * <p>这是缺陷 (a) 的核心：v3.2 在 {@code running==false}（世界保存 <b>之前</b>）
     * 就武装并起算 25/35s，慢存档会被杀在写盘中途。这里改用「保存是否仍在进行」的
     * 结构探针 + shutdown hook + 上界三重判据；探针不可用时 fail-safe（当作仍在保存），
     * 由 {@code savewait} 上界收口，因此既不会杀在存档中途，也不会无限等待。</p>
     */
    private static String waitForSavePhaseEnd(String who) {
        long start = System.currentTimeMillis();
        if (saveWaitMaxMs <= 0) {
            FileLog.log("save gate disabled (savewait=0), arming immediately (v3.2 behaviour, world may still be saving)");
            return "save gate disabled (savewait=0)";
        }
        if (clientDeadExit) {
            return "Client thread finished before request (save phase over)";
        }
        long nextLog = start + SUMMARY_INTERVAL_MS;
        while (true) {
            if (shutdownStarted) {
                return "shutdown hook fired (JVM shutdown reached, world already saved)";
            }
            if (!worldSavePossiblyRunning()) {
                return "integrated server gone (world save finished)";
            }
            long waited = System.currentTimeMillis() - start;
            if (waited >= saveWaitMaxMs) {
                FileLog.log("SAVE GATE CAP EXCEEDED at " + (waited / 1000)
                    + "s (world still looks unsaved): arming kill timeline anyway - save may be deadlocked");
                return "save wait cap " + (waited / 1000) + "s exceeded (save likely deadlocked)";
            }
            if (System.currentTimeMillis() >= nextLog) {
                nextLog += SUMMARY_INTERVAL_MS;
                FileLog.log("waiting for world save to finish (" + (waited / 1000) + "s/"
                    + (saveWaitMaxMs / 1000) + "s, integrated server present, killing held off)");
            }
            // 保存期间必须保持心跳新鲜：外部杀手的「心跳断更」分支据此不会在
            // 合法慢存档中途开火（内容里的 seq 同时让杀手的确认轮次判为有前进）。
            beatA = System.currentTimeMillis();
            try {
                touchHb();
            } catch (Throwable ignored) {}
            ensureOthers(Thread.currentThread());
            sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * 世界是否可能仍在保存：结构扫描得到的集成服务端字段非空即「可能仍在保存」
     * （{@code loadWorld(null)} 在「等到 isServerStopped()」之后才把它置 null）。
     * 探针不可用一律返回 true（fail-safe：宁可晚杀，不可杀在存档中途）。
     */
    private static boolean worldSavePossiblyRunning() {
        if (integratedServerField == null || mcRef == null) {
            return true;
        }
        try {
            return integratedServerField.get(mcRef) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 上界兜底武装：A 若被卡死，B/H 在「上界 + 60s」后补写 flag，保证外部杀手最终开火。 */
    private static void gateAssist(String who) {
        long req = exitRequestedAt;
        if (req == 0L || flagWritten) {
            return;
        }
        if (System.currentTimeMillis() - req <= saveWaitMaxMs + GATE_ASSIST_SLACK_MS) {
            return;
        }
        FileLog.log("save gate cap exceeded without flag (assist from " + who
            + " thread): arming external killer, internal timeline may be stuck");
        writeFlag("save wait cap exceeded (assist:" + who + ")");
    }

    /**
     * 信号出现后的多级时间线。0–25s 只观察打日志；25s 按 Client thread 死活
     * 分流；35s 无条件强杀。各步骤自行吞异常；探针故障一律 fail-closed
     * （按「仍有线程存活」处理，绝不当作干净退出提前收工）。
     *
     * <p>进入本方法时世界保存已经结束（保存门控），因此这里的宽限期只需覆盖
     * 退出收尾（资源/GL/SoundSystem/JCEF/其它 mod 的 shutdown hook）。</p>
     */
    private static void runTimeline() {
        long deadline = System.currentTimeMillis() + GRACE_MS;
        long nextSummary = System.currentTimeMillis() + SUMMARY_INTERVAL_MS;
        while (System.currentTimeMillis() < deadline) {
            beatA = System.currentTimeMillis();
            sleep(Math.min(200, Math.max(0, deadline - System.currentTimeMillis())));
            if (System.currentTimeMillis() >= nextSummary) {
                nextSummary += SUMMARY_INTERVAL_MS;
                logAliveNonDaemonSummary();
            }
            // 宽限期内非守护线程自然清零是最好的结局，直接收工。探针故障
            // （-1=未知）不算清零：fail-closed，继续留在时间线上。
            if (aliveNonDaemons() == 0) {
                FileLog.log("all non-daemon threads finished during grace period, exiting cleanly");
                return;
            }
        }
        logAliveNonDaemonSummary();
        dumpThreads((GRACE_MS / 1000) + "s");

        int mainState = mainThreadState();
        if (aliveNonDaemons() == 0) {
            // 25s 整点复查：线程已全部清零，JVM 正在自然退出，不再插手。
            FileLog.log("no non-daemon threads left at " + (GRACE_MS / 1000) + "s, letting JVM finish naturally");
            return;
        }
        if (mainState != 0) {
            // Client thread 已消亡（或探针故障→fail-closed 保守强杀）、世界保存
            // 已完成：残留的任意非守护线程（不限 CEF）都是杀不掉进程的元凶
            // → 强制 halt。
            FileLog.log("Client thread finished but non-daemon threads remain, forcing halt");
            forceExitLoop();
        } else {
            // Client thread 仍活着：保存已结束，说明卡在退出收尾（例如别的 mod 的
            // shutdown hook / CEF native 关闭）。再宽限 10s，仍活着则 35s 无条件强杀。
            FileLog.log("Client thread still alive at " + (GRACE_MS / 1000)
                + "s (shutdown cleanup may be stalled), extra " + (EXTRA_GRACE_MS / 1000) + "s then unconditional halt");
            sleep(EXTRA_GRACE_MS);
            if (mainThreadState() != 0) {
                FileLog.log("Client thread still alive at " + ((GRACE_MS + EXTRA_GRACE_MS) / 1000)
                    + "s, forcing unconditional halt (cleanup likely deadlocked)");
                forceExitLoop();
            } else if (aliveNonDaemons() > 0) {
                FileLog.log("Client thread finished during extra grace but non-daemon threads remain, forcing halt");
                forceExitLoop();
            }
            // 其余情况：线程已清零，JVM 自然退出，不插手。
        }
    }

    /** 认领内部强杀时间线（A/B 只有一条能跑，避免双跑）。 */
    private static synchronized boolean claimTimeline() {
        if (timelineClaimed) {
            return false;
        }
        timelineClaimed = true;
        return true;
    }

    /**
     * 停放态：时间线已结束（或由别的线程负责）后，本线程继续打心跳 + 监督同伴，
     * 但不再重新进入退出序列（v3.x 低危缺陷：时间线 return 后 A 会被 ensureOthers
     * 重启并二次进入时间线）。
     */
    private static void parkForever() {
        while (true) {
            beatA = System.currentTimeMillis();
            try {
                touchHb();
            } catch (Throwable ignored) {}
            ensureOthers(Thread.currentThread());
            sleep(1000);
        }
    }

    /**
     * 强制结束进程的策略链。每个策略在独立的一次性 daemon 线程里执行（若
     * 策略被重定向成 System.exit 并挂死在钩子里，不能阻塞看门狗本身），每步
     * 之间睡 {@link #HALT_PROBE_MS}（若进程已死，本线程随之消亡，不会再走
     * 下一步）；全部失败则每 {@link #RETRY_INTERVAL_MS} 重试整条链——FML 的
     * 重定向是 per-call-site 的 ASM 改写，新线程的新调用点可能未被改写。
     * 永不返回：进程要么死了，要么一直重试。
     */
    private static void forceExitLoop() {
        dumpThreads("force");
        while (true) {
            beatA = System.currentTimeMillis();
            // (1) 直调：FML 只改写模组类里的 halt 调用点，正常环境一步即死。
            runDetached("direct-halt", new Runnable() {
                @Override
                public void run() {
                    Runtime.getRuntime().halt(0);
                }
            });
            sleep(HALT_PROBE_MS);
            // (2) 反射：即便本类调用点已被 FML 改写，反射分发不经过字节码。
            runDetached("reflective-halt", new Runnable() {
                @Override
                public void run() {
                    try {
                        Method halt = Runtime.class.getMethod("halt", int.class);
                        halt.invoke(Runtime.getRuntime(), 0);
                    } catch (Throwable t) {
                        FileLog.log("reflective halt failed (" + t + ")");
                    }
                }
            });
            sleep(HALT_PROBE_MS);
            // (3) FML 自己的退出路径。
            runDetached("fml-exitJava", new Runnable() {
                @Override
                public void run() {
                    try {
                        FMLCommonHandler.instance().exitJava(0, false);
                    } catch (Throwable t) {
                        FileLog.log("FMLCommonHandler.exitJava failed (" + t + ")");
                    }
                }
            });
            sleep(HALT_PROBE_MS);
            // (4) 最后兜底：可能被重定向为 System.exit，但若钩子恰好已跑完仍生效。
            runDetached("system-exit", new Runnable() {
                @Override
                public void run() {
                    System.exit(0);
                }
            });
            sleep(HALT_PROBE_MS);
            FileLog.log("all exit strategies failed, retrying every " + (RETRY_INTERVAL_MS / 1000) + "s");
            sleep(RETRY_INTERVAL_MS);
        }
    }

    /** 在一次性 daemon 线程里执行一个退出策略，异常全吞（策略本身永不抛出）。 */
    private static void runDetached(final String name, final Runnable strategy) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    strategy.run();
                    // 能执行到这里说明该策略没有终结进程（或被重定向）。
                    FileLog.log("strategy " + name + " returned without terminating the process");
                } catch (Throwable err) {
                    FileLog.log("strategy " + name + " threw " + err);
                }
            }
        }, "mcphone-exit-" + name);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------
    // 线程 B：最小化探测器——只读 volatile 字段，绝不碰 getAllStackTraces。
    // 职责：登记退出请求（不写 flag）+ 上界兜底武装 + 监督同伴线程。
    // ------------------------------------------------------------------

    private static void detectorBody() {
        long lastAlive = System.currentTimeMillis();
        int hits = 0;
        boolean signalled = false;
        while (true) {
            long now = System.currentTimeMillis();
            beatB = now;
            try {
                touchHb();
            } catch (Throwable ignored) {}
            ensureOthers(Thread.currentThread());
            if (!signalled) {
                boolean exiting = false;
                try {
                    if (runningField != null && mcRef != null) {
                        exiting = !runningField.getBoolean(mcRef);
                    }
                } catch (Throwable t) {
                    // 字段读几乎不可能失败；失败了退避重试（字段还在，下一次
                    // 读大概率恢复），不要让探测线程死掉。
                    if (now - lastAlive > ALIVE_LOG_MS) {
                        lastAlive = now;
                        FileLog.log("minimal detector probe error: " + t);
                    }
                    sleep(5000);
                    continue;
                }
                if (exiting) {
                    if (++hits >= 2) {
                        onExitRequested("minimal detector (running field)", false);
                        signalled = true;
                    }
                } else {
                    hits = 0;
                }
            }
            gateAssist("detector");
            if (now - lastAlive > ALIVE_LOG_MS) {
                lastAlive = now;
                FileLog.log(signalled ? "minimal detector alive (post-signal)" : "minimal detector alive");
            }
            sleep(signalled ? 1000 : POLL_INTERVAL_MS);
        }
    }

    // ------------------------------------------------------------------
    // 线程 H：心跳——每 2s 触碰心跳文件（写入 millis/单调 nanoTime/序号）。
    // 三条线程里任何一条活着都会顺手触碰心跳，只有全部死亡/冻结时心跳文件才会
    // 断更 → 外部杀手兜底强杀。另外承担「上界兜底武装」与「疑似 OS 挂起」诊断。
    // ------------------------------------------------------------------

    private static void heartbeatBody() {
        long lastAlive = System.currentTimeMillis();
        long wall = System.currentTimeMillis();
        long mono = System.nanoTime();
        while (true) {
            long now = System.currentTimeMillis();
            long monoNow = System.nanoTime();
            beatHb = now;
            try {
                touchHb();
            } catch (Throwable ignored) {}
            // 纯观测诊断：OS 挂起后墙钟跳变而单调时钟不跳（是否生效取决于
            // JVM/OS 的 nanoTime 时钟源，不参与任何击杀判定）。
            long wallDelta = now - wall;
            long monoDelta = (monoNow - mono) / 1000000L;
            if (wallDelta - monoDelta > SUSPEND_DIAG_MS) {
                FileLog.log("OS suspend/resume suspected: wall +" + (wallDelta / 1000)
                    + "s but monotonic +" + (monoDelta / 1000) + "s (informational only, no kill decision uses wall clocks)");
            }
            wall = now;
            mono = monoNow;
            ensureOthers(Thread.currentThread());
            gateAssist("heartbeat");
            if (now - lastAlive > ALIVE_LOG_MS) {
                lastAlive = now;
                FileLog.log("heartbeat alive");
            }
            sleep(HEARTBEAT_MS);
        }
    }

    // ------------------------------------------------------------------
    // 退出 flag / 心跳文件
    // ------------------------------------------------------------------

    private static synchronized void writeFlag(String source) {
        if (flagWritten) {
            return;
        }
        flagWritten = true;
        if (flagFile == null) {
            FileLog.log("FLAG NOT WRITTEN (no flag file), external killer relies on heartbeat staleness only");
            return;
        }
        try (FileOutputStream out = new FileOutputStream(flagFile)) {
            out.write(("mcphone exit signal @ " + timestamp() + " via " + source
                + "\nexternal kill in ~" + extGraceSec + "s if process still alive\n")
                .getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            FileLog.log("FLAG WRITE FAILED: " + t + " (external killer relies on heartbeat staleness)");
            return;
        }
        FileLog.log("exit flag written via " + source + " -> external killer fires in ~" + extGraceSec
            + "s if process still alive");
    }

    /**
     * 写 flag 的最早入口：注册时作为 shutdown hook 挂上（JVM 退出流程的第一批
     * 钩子）。v3.1 的取证教训：退出清理阶段（SoundSystem/JCEF native 关闭）JVM 可能
     * 整体冻结，内部轮询线程来不及写 flag；hook 在退出流程一开始就执行，抢在
     * 冻结点之前把外部杀手的触发器立起来。文件操作全部吞异常——hook 里任何抛出
     * 都会终止后续钩子。
     *
     * <p><b>v3.3 补充</b>：该 hook 只可能在 {@code System.exit(0)} 时执行，而 1.7.10
     * 的这个调用位于 {@code shutdownMinecraftApplet → loadWorld(null)}（世界保存）
     * <b>之后</b>，所以「hook 已触发」同时是「世界保存已完成」的可靠证据——保存门控
     * 用它作为第一判据。</p>
     */
    private static void installEarlyFlagHook() {
        if (flagFile == null) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        shutdownStarted = true;
                        writeFlag("shutdown hook (post-save, JVM shutdown reached)");
                    } catch (Throwable ignored) {}
                }
            }, "mcphone-exit-early-flag"));
        } catch (Throwable t) {
            FileLog.log("early flag hook install failed (" + t + ")");
        }
    }

    /**
     * 心跳触碰：写入 {@code millis=<墙钟> nano=<单调> seq=<序号>}。
     *
     * <p>v3.3 起内容有意义：外部杀手用 {@code seq}/{@code nano} 作为
     * <b>与墙钟无关的推进判据</b>——OS 休眠唤醒后墙钟会跳，但文件内容不会自己前进，
     * 杀手因此可以要求「多轮真实执行之后文件仍无推进」才开火，从根上消除休眠误杀。
     * 仍不持类锁（v3 的教训：持锁做文件 I/O 会拖死三条线程），只用一把只服务于
     * 心跳写入的细粒度锁；内容写失败时退回纯 mtime 触碰。</p>
     */
    private static void touchHb() {
        if (hbFile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (HB_LOCK) {
            hbSeq++;
            String line = "millis=" + now + " nano=" + System.nanoTime() + " seq=" + hbSeq;
            try (FileOutputStream out = new FileOutputStream(hbFile)) {
                out.write(line.getBytes(StandardCharsets.US_ASCII));
            } catch (Throwable t) {
                if (!hbFile.setLastModified(now)) {
                    FileLog.log("heartbeat touch failed: " + t);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // JVM 外部杀手（Windows）：PowerShell 循环，wscript 隐藏启动。
    // flag 出现 → 等 extGrace 秒 → taskkill /F /T；
    // 心跳断更 hbStall 秒 + 执行保护的多次确认 → taskkill /F /T；
    // 目标进程消失 → 自己退出。
    // ------------------------------------------------------------------

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** 从 Java 9+ ProcessHandle 取 pid，退回 RuntimeMXBean 解析；失败 -1。 */
    private static long currentPid() {
        try {
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Object current = ph.getMethod("current").invoke(null);
            return ((Long) ph.getMethod("pid").invoke(current)).longValue();
        } catch (Throwable ignored) {}
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) {
                return Long.parseLong(name.substring(0, at));
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    /**
     * 生成并启动外部杀手。v3.3：
     * <ul>
     * <li>心跳断更分支不再用墙钟直接判死，改为「执行保护的多次确认」——每轮真正
     *     执行一小段自旋（挂起期间不执行）+ 复核心跳 mtime 与内容序号 + 复核 flag
     *     与进程存在性；任何一轮发现前进即放弃击杀。这样 OS 休眠/挂起唤醒不会
     *     误杀健康游戏，而真卡死（心跳彻底停摆且系统醒着）仍会被杀。</li>
     * <li>ps1 自带日志 {@code ext-watchdog.log}（杀手侧观测：启动/循环心跳/触发/
     *     杀完的 rc 全落盘）。</li>
     * <li>spawn 后轮询等待杀手日志里出现 started 行（wscript 链完全异步，失败
     *     无声）；确认失败则退回直启 powershell，再不行才放弃。</li>
     * </ul>
     */
    private static void spawnExternalKiller(long pid) {
        File ps1 = new File(baseDir, "ext-watchdog.ps1");
        File vbs = new File(baseDir, "ext-watchdog-launch.vbs");
        killerLogFile = new File(baseDir, "ext-watchdog.log");
        // UTF-16LE（带 BOM）保证含中文/空格的路径在 Windows PowerShell 5 下可读。
        try (FileOutputStream out = new FileOutputStream(ps1)) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xFE});
            out.write(buildKillerScript(pid, flagFile, hbFile, killerLogFile).getBytes(StandardCharsets.UTF_16LE));
        } catch (Throwable t) {
            FileLog.log("external killer script write failed: " + t);
            return;
        }
        StringBuilder vb = new StringBuilder();
        vb.append("' MCphone external exit watchdog launcher v3.3 (generated, safe to delete)\r\n");
        // v3.1 生产实锤：wscript 分支没起来且死因被吞（ps1 的 $ErrorActionPreference
        // 也救不了 vbs 自身）。这里 vbs 自己写 launcher 日志：活着/Run 返回/Err 描述，
        // 下次失效可直接分辨"wscript 没执行"vs"vbs 里 Run 失败"。
        vb.append("On Error Resume Next\r\n");
        vb.append("Set fso = CreateObject(\"Scripting.FileSystemObject\")\r\n");
        vb.append("Set sh = CreateObject(\"WScript.Shell\")\r\n");
        vb.append("logPath = WScript.Arguments(1)\r\n");
        vb.append("Set logf = fso.OpenTextFile(logPath, 8, True)\r\n");
        vb.append("logf.WriteLine Now & \" vbs launcher alive, spawning powershell\"\r\n");
        vb.append("rc = sh.Run(\"powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"\"\" & WScript.Arguments(0) & \"\"\"\", 0, False)\r\n");
        vb.append("logf.WriteLine Now & \" vbs Run rc=\" & rc & \" Err=\" & Err.Description\r\n");
        vb.append("logf.Close\r\n");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(vbs), StandardCharsets.US_ASCII)) {
            w.write(vb.toString());
        } catch (Throwable t) {
            FileLog.log("external killer launcher write failed: " + t);
            return;
        }
        // 就绪确认：杀手 ps1 的第一件事是写 started 行。wscript 链完全异步
        // （v3 的坑：spawn 成功 ≠ 杀手真跑起来了，且失败无声），轮询等日志
        // 出现；等不到就直启 powershell 兜底再试一轮。确认窗 20s：实测
        // wscript→PowerShell 冷启动 11.3s，10s 窗必超时 → 每次都误判失败
        // 转直启造成双 spawn。
        long before = killerLogFile.exists() ? killerLogFile.length() : -1L;
        File vbsLog = new File(baseDir, "ext-watchdog.vbs.log");
        vbsLog.delete(); // 每会话重开，避免旧会话日志混淆判读
        spawnViaWscript(vbs, ps1, vbsLog);
        if (waitForKillerStart(before, 20000)) {
            return;
        }
        FileLog.log("killer start NOT confirmed via wscript within 20s, falling back to direct powershell");
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", ps1.getAbsolutePath())
                .redirectErrorStream(true).start();
        } catch (Throwable t2) {
            FileLog.log("external killer spawn failed entirely: " + t2);
            return;
        }
        if (!waitForKillerStart(before, 20000)) {
            FileLog.log("external killer failed to start via both paths (check ext-watchdog.log / AV)");
        }
    }

    /**
     * 生成外部杀手脚本正文（v3.3）。抽成独立方法便于离线复现/端到端验证脚本逻辑
     * （dummy 进程 + 手工造 flag/心跳文件），避免「改了 Java 字符串却没人跑过 ps1」。
     *
     * <p>注意 Java 字符串转义：正文里的 {@code \d} 必须写成 {@code \\d}；
     * PowerShell 侧统一用单引号字面量，脚本里不做变量插值以免路径被拆坏。</p>
     */
    private static String buildKillerScript(long pid, File flag, File hb, File log) {
        String flagPath = psQuote(flag.getAbsolutePath());
        String hbPath = psQuote(hb.getAbsolutePath());
        String logPath = psQuote(log.getAbsolutePath());
        // 确认轮数：每轮 2s；最少 3 轮（保证「连续多轮真实执行」而不是一次误判）。
        int rounds = Math.max(3, (hbConfirmSec + 1) / 2);
        StringBuilder sb = new StringBuilder();
        sb.append("# MCphone external exit watchdog v3.3 (generated at runtime, safe to delete)\r\n");
        sb.append("$ErrorActionPreference = 'SilentlyContinue'\r\n");
        sb.append("$targetPid = ").append(pid).append("\r\n");
        sb.append("$flagFile = ").append(flagPath).append("\r\n");
        sb.append("$hbFile = ").append(hbPath).append("\r\n");
        sb.append("$logFile = ").append(logPath).append("\r\n");
        sb.append("$flagGraceSec = ").append(extGraceSec).append("\r\n");
        sb.append("$hbStallSec = ").append(hbStallSec).append("\r\n");
        sb.append("$hbConfirmSec = ").append(hbConfirmSec).append("\r\n");
        sb.append("$confirmRounds = ").append(rounds).append("\r\n");
        sb.append("function KLog([string]$msg) {\r\n");
        sb.append("    try { Add-Content -LiteralPath $logFile -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff') + ' ' + $msg) -Encoding UTF8 } catch {}\r\n");
        sb.append("}\r\n");
        // 心跳内容里的单调序号（Java 侧每 2s 写一次 millis/nano/seq）。读不到返回 -1。
        sb.append("function HbSeq([string]$path) {\r\n");
        sb.append("    try {\r\n");
        sb.append("        $raw = Get-Content -LiteralPath $path -Raw\r\n");
        sb.append("        if ($raw -match 'seq=(\\d+)') { return [long]$matches[1] }\r\n");
        sb.append("    } catch {}\r\n");
        sb.append("    return [long]-1\r\n");
        sb.append("}\r\n");
        sb.append("KLog ('=== ext-killer start pid=' + $targetPid + ' pid$=' + $PID + ' flagGrace=' + $flagGraceSec + 's hbStall=' + $hbStallSec + 's hbConfirm=' + $hbConfirmSec + 's rounds=' + $confirmRounds)\r\n");
        // 寿命与会话时长解耦（v3.1 教训：固定 5 分钟 TTL 在长会话退出前自毁，
        // flag 写下时已无杀手读它）。deadline 只由心跳新鲜度顺延：hb mtime 比
        // 上次记录的 stamp 新（=游戏仍在 touch 心跳）→ deadline 推到
        // now + hbStallSec + 60s 并更新 stamp；hb 停跳超 hbStallSec 本就触发
        // stall 击杀，不存在"游戏活着但 deadline 到点"的空窗。
        sb.append("$deadline = (Get-Date).AddSeconds(").append(hbStallSec + 60).append(")\r\n");
        sb.append("$hbStamp = $null\r\n");
        sb.append("while ($true) {\r\n");
        sb.append("    if (Test-Path -LiteralPath $flagFile) {\r\n");
        sb.append("        KLog 'flag seen, waiting grace then kill'\r\n");
        sb.append("        Start-Sleep -Seconds $flagGraceSec\r\n");
        sb.append("        if (Get-Process -Id $targetPid) {\r\n");
        sb.append("            KLog 'target still alive after grace, taskkill'\r\n");
        sb.append("            taskkill /F /T /PID $targetPid | Out-Null\r\n");
        sb.append("            KLog ('taskkill (flag) rc=' + $LASTEXITCODE)\r\n");
        sb.append("        } else { KLog 'target gone during grace, no kill needed' }\r\n");
        sb.append("        break\r\n");
        sb.append("    }\r\n");
        sb.append("    if (-not (Get-Process -Id $targetPid)) { KLog 'target gone, exiting'; break }\r\n");
        sb.append("    $hb = Get-Item -LiteralPath $hbFile\r\n");
        sb.append("    if ($hb) {\r\n");
        sb.append("        if ($hbStamp -and $hb.LastWriteTime -gt $hbStamp) {\r\n");
        // 正常路径每 2s 都会顺延，不逐条记日志（会刷爆文件）
        sb.append("            $deadline = (Get-Date).AddSeconds($hbStallSec + 60)\r\n");
        sb.append("        }\r\n");
        sb.append("        $hbStamp = $hb.LastWriteTime\r\n");
        sb.append("        $staleSec = ((Get-Date) - $hb.LastWriteTime).TotalSeconds\r\n");
        sb.append("        if ($staleSec -gt $hbStallSec) {\r\n");
        // --- v3.3 核心：墙钟只能用来「怀疑」，不能用来判死。 ---
        sb.append("            KLog ('heartbeat looks stale ' + [math]::Round($staleSec, 1) + 's > ' + $hbStallSec + 's -> execution-guarded confirmation (' + $confirmRounds + ' rounds); wall clock alone is never a kill reason (OS suspend must not kill a healthy game)')\r\n");
        sb.append("            $mt0 = $hb.LastWriteTime\r\n");
        sb.append("            $sq0 = HbSeq $hbFile\r\n");
        sb.append("            $cpu0 = (Get-Process -Id $targetPid).TotalProcessorTime\r\n");
        sb.append("            $self0 = (Get-Process -Id $PID).TotalProcessorTime\r\n");
        sb.append("            $progress = $false\r\n");
        sb.append("            $flagSeen = $false\r\n");
        sb.append("            $gone = $false\r\n");
        sb.append("            for ($i = 1; $i -le $confirmRounds; $i++) {\r\n");
        sb.append("                Start-Sleep -Seconds 2\r\n");
        // 这一小段自旋是「本机确实醒着、本进程确实在跑」的凭据：OS 挂起期间
        // 不消耗 CPU 时间，因此轮次不会在挂起中被白白走完。
        sb.append("                $spin = 0; for ($j = 0; $j -lt 30000; $j++) { $spin = $spin + 1 }\r\n");
        sb.append("                if (Test-Path -LiteralPath $flagFile) { $flagSeen = $true; break }\r\n");
        sb.append("                if (-not (Get-Process -Id $targetPid)) { $gone = $true; break }\r\n");
        sb.append("                $hb2 = Get-Item -LiteralPath $hbFile\r\n");
        sb.append("                if ($hb2 -and $hb2.LastWriteTime -gt $mt0) { $progress = $true; break }\r\n");
        sb.append("                $sq2 = HbSeq $hbFile\r\n");
        sb.append("                if ($sq2 -ge 0 -and $sq0 -ge 0 -and $sq2 -gt $sq0) { $progress = $true; break }\r\n");
        sb.append("            }\r\n");
        sb.append("            if ($gone) { KLog 'target gone during stall confirmation, exiting'; break }\r\n");
        sb.append("            if ($flagSeen) {\r\n");
        sb.append("                KLog 'flag appeared during stall confirmation -> defer to flag grace'\r\n");
        sb.append("            } elseif ($progress) {\r\n");
        sb.append("                $hbNow = Get-Item -LiteralPath $hbFile\r\n");
        sb.append("                if ($hbNow) { $hbStamp = $hbNow.LastWriteTime }\r\n");
        sb.append("                $deadline = (Get-Date).AddSeconds($hbStallSec + 60)\r\n");
        sb.append("                KLog 'heartbeat resumed during confirmation (OS suspend/resume or FS hiccup) -> kill aborted'\r\n");
        sb.append("            } else {\r\n");
        sb.append("                $selfMs = ((Get-Process -Id $PID).TotalProcessorTime - $self0).TotalMilliseconds\r\n");
        sb.append("                $cpuMs = ((Get-Process -Id $targetPid).TotalProcessorTime - $cpu0).TotalMilliseconds\r\n");
        sb.append("                if ($selfMs -lt 20) {\r\n");
        // 观察者自己都没跑够：说明这段时间机器基本在挂起（或本进程被饿死），
        // 一律不杀，重置窗口等下一次心跳。
        sb.append("                    KLog ('observer accumulated only ' + [math]::Round($selfMs) + 'ms cpu -> system was suspended, kill aborted')\r\n");
        sb.append("                    $hbNow = Get-Item -LiteralPath $hbFile\r\n");
        sb.append("                    if ($hbNow) { $hbStamp = $hbNow.LastWriteTime }\r\n");
        sb.append("                    $deadline = (Get-Date).AddSeconds($hbStallSec + 60)\r\n");
        sb.append("                } else {\r\n");
        sb.append("                    KLog ('no heartbeat progress over ' + ($confirmRounds * 2) + 's of execution-guarded rounds (observer cpu +' + [math]::Round($selfMs) + 'ms, target cpu +' + [math]::Round($cpuMs) + 'ms, target pid alive) -> taskkill')\r\n");
        sb.append("                    taskkill /F /T /PID $targetPid | Out-Null\r\n");
        sb.append("                    KLog ('taskkill (stall) rc=' + $LASTEXITCODE)\r\n");
        sb.append("                    break\r\n");
        sb.append("                }\r\n");
        sb.append("            }\r\n");
        sb.append("        }\r\n");
        sb.append("    }\r\n");
        sb.append("    if ((Get-Date) -gt $deadline) { KLog 'deadline reached with stale/missing heartbeat, exiting without kill'; break }\r\n");
        sb.append("    Start-Sleep -Seconds 2\r\n");
        sb.append("}\r\n");
        sb.append("KLog 'ext-killer exit'\r\n");
        return sb.toString();
    }

    private static void spawnViaWscript(File vbs, File ps1, File vbsLog) {
        try {
            // wscript //B：完全无窗口（直接 powershell -WindowStyle Hidden 会闪
            // 一下黑框）。wscript 立即返回，PowerShell 留在后台盯 flag/心跳。
            // 第二个参数 = vbs 自记日志路径；用绝对路径调 wscript 不依赖 PATH。
            new ProcessBuilder("wscript.exe", "//B", vbs.getAbsolutePath(), vbsLog.getAbsolutePath())
                .redirectErrorStream(true).start();
        } catch (Throwable t) {
            FileLog.log("wscript spawn failed (" + t + "), will try direct powershell");
        }
    }

    /** 轮询杀手日志出现新增（started 行写入即有新增）直到确认或超时。 */
    private static boolean waitForKillerStart(long sizeBefore, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            sleep(500);
            try {
                if (killerLogFile != null && killerLogFile.exists()
                        && killerLogFile.length() > sizeBefore) {
                    FileLog.log("external killer confirmed running (ext-watchdog.log grew)");
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** PowerShell 单引号字符串转义（路径里的 ' 翻倍）。 */
    private static String psQuote(String path) {
        return "'" + path.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------
    // 探针 / 诊断工具
    // ------------------------------------------------------------------

    /** 每 5s 一行的存活非守护线程摘要（前 5 个名字 + 首帧栈）。 */
    private static void logAliveNonDaemonSummary() {
        try {
            StringBuilder sb = new StringBuilder("alive non-daemon threads: ");
            int shown = 0;
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread th = e.getKey();
                if (!th.isDaemon() && th.isAlive()) {
                    if (shown >= 5) {
                        sb.append("...");
                        break;
                    }
                    StackTraceElement[] st = e.getValue();
                    sb.append('"').append(th.getName()).append("\"(")
                        .append(th.getState()).append(") ")
                        .append(st.length > 0 ? st[0] : "(no stack)").append("; ");
                    shown++;
                }
            }
            if (shown == 0) {
                sb.append("(none)");
            }
            FileLog.log(sb.toString());
        } catch (Throwable ignored) {}
    }

    /** 存活非守护线程数；-1 = 探测故障（未知），调用方必须按 fail-closed 处理。 */
    private static int aliveNonDaemons() {
        try {
            int n = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (!t.isDaemon() && t.isAlive()) {
                    n++;
                }
            }
            return n;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Client thread 状态探针：0=存活，1=已消亡，-1=探测故障（与「消亡」不可
     * 混淆——故障时调用方按未知处理，宁可留在强杀时间线上也不当作干净退出）。
     */
    private static int mainThreadState() {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (MAIN_THREAD_NAME.equals(t.getName()) && t.isAlive()) {
                    return 0;
                }
            }
            return 1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 与 MCEF 同款：Minecraft 里唯一的 volatile boolean 字段即 running。 */
    private static Field findRunningField() {
        try {
            for (Field f : Minecraft.class.getDeclaredFields()) {
                if (f.getType() == boolean.class && (f.getModifiers() & Modifier.VOLATILE) != 0) {
                    f.setAccessible(true);
                    return f;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 结构扫描「集成服务端」字段（运行时成员名是 SRG 名，不能按名字反射）：
     * 取 {@code Minecraft} 里声明类型名以 {@code IntegratedServer} 结尾的字段；
     * 退化时接受 {@code MinecraftServer}。该字段在
     * {@code loadWorld(null)} 等到 {@code isServerStopped()} 之后被置空，因此
     * 「非空」= 世界保存可能仍在进行。
     */
    private static Field findIntegratedServerField() {
        try {
            Field fallback = null;
            for (Field f : Minecraft.class.getDeclaredFields()) {
                String type = f.getType().getName();
                if (type.endsWith("IntegratedServer")) {
                    f.setAccessible(true);
                    return f;
                }
                if (fallback == null && type.endsWith("MinecraftServer")) {
                    fallback = f;
                }
            }
            if (fallback != null) {
                fallback.setAccessible(true);
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Minecraft safeMinecraft() {
        try {
            return Minecraft.getMinecraft();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 探测能力意外失效时的 v1 兜底：shutdown hook 阶段挂起 15s 后走策略链
     * 强杀。仅在轮询线程无法判断退出信号时安装（hook 只在 System.exit 之后跑，
     * 因此它天然不覆盖世界保存阶段）。
     */
    private static void installLegacyHook(final File legacyBaseDir) {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    sleep(15000);
                    dumpThreads("15s-legacy");
                    forceExitLoop();
                }
            }, "mcphone-exit-watchdog-legacy"));
        } catch (Throwable t) {
            FileLog.log("legacy hook install failed (" + t + ")");
        }
    }

    private static void dumpThreads(String phase) {
        if (baseDir == null) {
            FileLog.log("shutdown dump skipped (no data dir): " + phase);
            return;
        }
        try {
            // 按阶段命名：25s 的第一现场不被 force 阶段覆盖。
            File out = new File(baseDir, "shutdown-dump-" + phase + ".txt");
            try (PrintWriter w = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
                w.println("MCphone shutdown thread dump @ " + phase + " after quit signal");
                Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
                for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
                    Thread t = e.getKey();
                    w.println();
                    w.println("\"" + t.getName() + "\""
                        + " daemon=" + t.isDaemon()
                        + " state=" + t.getState()
                        + " alive=" + t.isAlive());
                    for (StackTraceElement el : e.getValue()) {
                        w.println("    at " + el);
                    }
                    if (e.getValue().length == 0) {
                        w.println("    (no stack)");
                    }
                }
                w.println("total threads: " + stacks.size());
            }
            FileLog.log("shutdown thread dump written: " + out.getAbsolutePath());
        } catch (Throwable t) {
            FileLog.log("shutdown dump failed: " + t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
    }

    // ------------------------------------------------------------------
    // 文件日志：退出阶段 log4j 可能已死，所有输出同时落盘 + stderr 镜像，
    // 游戏死后靠 alive 心跳行即可精确诊断内部线程活到几点。
    // ------------------------------------------------------------------

    private static final class FileLog {
        private static void log(String msg) {
            String line = timestamp() + " " + msg;
            // stderr 镜像：游戏活着时 log4j 会把它收进 fml-client-latest.log。
            System.err.println("[mcphone] ForceExitWatchdog: " + msg);
            if (baseDir == null) {
                return;
            }
            synchronized (FileLog.class) {
                try {
                    File f = new File(baseDir, "exit-watchdog.log");
                    if (f.length() > LOG_ROTATE_BYTES) {
                        File old = new File(baseDir, "exit-watchdog.log.1");
                        old.delete();
                        f.renameTo(old);
                    }
                    try (FileOutputStream out = new FileOutputStream(f, true)) {
                        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {
                    // 日志写不进去（磁盘满/目录没了）也不能拖垮看门狗线程。
                }
            }
        }
    }
}
