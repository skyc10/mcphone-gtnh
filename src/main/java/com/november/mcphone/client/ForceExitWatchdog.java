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
 *     写 flag 文件 {@code .exit-flag-<pid>}。字段读不可能阻塞，这是整条链里最
 *     可靠的一环。</li>
 * <li><b>JVM 外部杀手</b>（Windows）：注册时生成 PowerShell 脚本并用 wscript
 *     隐藏启动（无窗口闪现）。循环检测：flag 文件出现 → 等
 *     {@link #PROP_EXT_GRACE} 秒 → {@code taskkill /F /T} 杀整棵进程树；flag
 *     没出现但心跳文件 {@code .exit-hb-<pid>} 断更超 {@link #PROP_HB_STALL} 秒
 *     （三条内部线程全死/全冻 = v2 式失效）→ 同样强杀；目标进程消失 → 自己退出。
 *     JVM 内部无论冻结成什么样都杀得掉。</li>
 * </ol>
 *
 * <p><b>内部时间线</b>（保留 v2 全部逻辑）：daemon 轮询线程探测退出信号
 * （running=false 或 Client thread 消亡，信号出现前绝不干预）。信号后 0–25s
 * 宽限（每 5s 一行存活非守护线程摘要）；25s Client thread 已亡且有非守护线程
 * 存活 → halt 策略链；Client thread 仍活 → 再宽限 10s，35s 无条件 halt。
 * halt 策略链：直调 Runtime.halt → 反射 halt → FML exitJava → System.exit，
 * 每策略独立 daemon 线程 + 200ms 判定窗，全部失败每 30s 重试。</p>
 *
 * <p><b>三线程互为看门狗</b>：任一线程发现同伴死掉就拉起新线程顶替（beat 超过
 * {@link #STALE_BEAT_MS} 判死）。三条线程全部死亡/冻结时由外部杀手的心跳断更
 * 兜底——这就是 v2 失效场景（内部线程没走完时间线）的解。</p>
 *
 * <p>{@code -Dmcphone.exitwatchdog=false} 整体禁用；
 * {@code -Dmcphone.exitwatchdog.externalgrace=<秒>}（默认 35）flag 触发后的外部
 * 强杀延迟；{@code -Dmcphone.exitwatchdog.hbstall=<秒>}（默认 90）心跳断更判定。
 * 外部脚本与 flag/心跳文件都在 mcphone 数据目录，可随时手删。</p>
 */
@SideOnly(Side.CLIENT)
public final class ForceExitWatchdog {

    /** 信号出现后的宽限期：覆盖世界保存等正常清理。 */
    private static final long GRACE_MS = 25000;
    /** 宽限期内的存活线程摘要打印间隔。 */
    private static final long SUMMARY_INTERVAL_MS = 5000;
    /** Client thread 仍存活时的追加宽限（保存疑似死锁）。 */
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
    private static int extGraceSec = 35;
    private static int hbStallSec = 90;
    private static volatile boolean flagWritten;
    private static volatile long beatA;
    private static volatile long beatB;
    private static volatile long beatHb;
    private static Thread threadA;
    private static Thread threadB;
    private static Thread threadHb;

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
            FileLog.log("=== session start: watchdog v3, pid=" + pid
                + ", os=" + System.getProperty("os.name")
                + ", java=" + System.getProperty("java.version"));
        }
        mcRef = safeMinecraft();
        runningField = mcRef != null ? findRunningField() : null;
        extGraceSec = Math.max(5, Integer.getInteger(PROP_EXT_GRACE, 35).intValue());
        hbStallSec = Math.max(5, Integer.getInteger(PROP_HB_STALL, 90).intValue());
        FileLog.log("armed, watching "
            + (runningField != null ? "Minecraft." + runningField.getName() + " / Client thread liveness"
                                    : "Client thread liveness")
            + " (internal grace " + (GRACE_MS / 1000) + "s/" + ((GRACE_MS + EXTRA_GRACE_MS) / 1000)
            + "s, external kill: flag +" + extGraceSec + "s or heartbeat stall " + hbStallSec + "s)");
        long now = System.currentTimeMillis();
        beatA = beatB = beatHb = now;
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
    // 线程 A：完整探测器 + 25s/35s 时间线 + halt 策略链（v2 逻辑 + 文件日志）
    // ------------------------------------------------------------------

    private static void watcherBody() {
        long lastAlive = System.currentTimeMillis();
        int hits = 0;
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
                    exiting = (runningField != null && !runningField.getBoolean(mcRef)) || st == 1;
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
            onExitSignal("watchdog timeline probe");
            runTimeline();
        } catch (Throwable t) {
            // 时间线任何意外失败都不能让看门狗静默消失：flag 必须已写
            // （onExitSignal），halt 链继续重试；真到不了这里太久——外部杀手
            // 会按 flag/心跳兜底。
            FileLog.log("watcher crashed after signal (" + t + "), relying on halt retry + external killer");
            try {
                forceExitLoop();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 信号出现后的多级时间线。0–25s 只观察打日志；25s 按 Client thread 死活
     * 分流；35s 无条件强杀。各步骤自行吞异常；探针故障一律 fail-closed
     * （按「仍有线程存活」处理，绝不当作干净退出提前收工）。
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
            // Client thread 仍活着：世界保存疑似死锁。再宽限 10s，仍活着则
            // 35s 无条件强杀——halt 丢的是最后几秒存档，比进程永远杀不掉好。
            FileLog.log("Client thread still alive at " + (GRACE_MS / 1000)
                + "s (save may be stalled), extra " + (EXTRA_GRACE_MS / 1000) + "s then unconditional halt");
            sleep(EXTRA_GRACE_MS);
            if (mainThreadState() != 0) {
                FileLog.log("Client thread still alive at " + ((GRACE_MS + EXTRA_GRACE_MS) / 1000)
                    + "s, forcing unconditional halt (saving likely deadlocked)");
                forceExitLoop();
            } else if (aliveNonDaemons() > 0) {
                FileLog.log("Client thread finished during extra grace but non-daemon threads remain, forcing halt");
                forceExitLoop();
            }
            // 其余情况：线程已清零，JVM 自然退出，不插手。
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
    // 职责只有两个：第一时间写 flag 文件（外部杀手的触发器）+ 监督同伴线程。
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
                        onExitSignal("minimal detector (running field)");
                        signalled = true;
                    }
                } else {
                    hits = 0;
                }
            }
            if (now - lastAlive > ALIVE_LOG_MS) {
                lastAlive = now;
                FileLog.log(signalled ? "minimal detector alive (post-signal)" : "minimal detector alive");
            }
            sleep(signalled ? 1000 : POLL_INTERVAL_MS);
        }
    }

    // ------------------------------------------------------------------
    // 线程 H：心跳——每 2s 触碰心跳文件。三条线程里任何一条活着都会顺手
    // 触碰心跳，只有全部死亡/冻结时心跳文件才会断更 → 外部杀手兜底强杀。
    // ------------------------------------------------------------------

    private static void heartbeatBody() {
        long lastAlive = System.currentTimeMillis();
        while (true) {
            long now = System.currentTimeMillis();
            beatHb = now;
            try {
                touchHb();
            } catch (Throwable ignored) {}
            ensureOthers(Thread.currentThread());
            if (now - lastAlive > ALIVE_LOG_MS) {
                lastAlive = now;
                FileLog.log("heartbeat alive");
            }
            sleep(HEARTBEAT_MS);
        }
    }

    // ------------------------------------------------------------------
    // 退出信号 / flag / 心跳文件
    // ------------------------------------------------------------------

    /** 退出信号确认（A/B 任一线程命中即调用，幂等）：写 flag 文件。 */
    private static void onExitSignal(String source) {
        FileLog.log("game exit detected via " + source + ", grace period "
            + (GRACE_MS / 1000) + "s before internal intervention");
        writeFlag(source);
    }

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
        FileLog.log("exit flag written -> external killer fires in ~" + extGraceSec
            + "s if process still alive");
    }

    private static synchronized void touchHb() {
        if (hbFile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!hbFile.setLastModified(now)) {
            try (FileOutputStream out = new FileOutputStream(hbFile)) {
                out.write(Long.toString(now).getBytes(StandardCharsets.US_ASCII));
            } catch (Throwable t) {
                FileLog.log("heartbeat touch failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // JVM 外部杀手（Windows）：PowerShell 循环，wscript 隐藏启动。
    // flag 出现 → 等 extGrace 秒 → taskkill /F /T；
    // 心跳断更 hbStall 秒 → taskkill /F /T；
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

    private static void spawnExternalKiller(long pid) {
        File ps1 = new File(baseDir, "ext-watchdog.ps1");
        File vbs = new File(baseDir, "ext-watchdog-launch.vbs");
        String flagPath = psQuote(flagFile.getAbsolutePath());
        String hbPath = psQuote(hbFile.getAbsolutePath());
        StringBuilder sb = new StringBuilder();
        sb.append("# MCphone external exit watchdog v3 (generated at runtime, safe to delete)\r\n");
        sb.append("$ErrorActionPreference = 'SilentlyContinue'\r\n");
        sb.append("$targetPid = ").append(pid).append("\r\n");
        sb.append("$flagFile = ").append(flagPath).append("\r\n");
        sb.append("$hbFile = ").append(hbPath).append("\r\n");
        sb.append("$flagGraceSec = ").append(extGraceSec).append("\r\n");
        sb.append("$hbStallSec = ").append(hbStallSec).append("\r\n");
        sb.append("while ($true) {\r\n");
        sb.append("    if (Test-Path -LiteralPath $flagFile) {\r\n");
        sb.append("        Start-Sleep -Seconds $flagGraceSec\r\n");
        sb.append("        if (Get-Process -Id $targetPid) { taskkill /F /T /PID $targetPid | Out-Null }\r\n");
        sb.append("        break\r\n");
        sb.append("    }\r\n");
        sb.append("    if (-not (Get-Process -Id $targetPid)) { break }\r\n");
        sb.append("    $hb = Get-Item -LiteralPath $hbFile\r\n");
        sb.append("    if ($hb) {\r\n");
        sb.append("        $staleSec = ((Get-Date) - $hb.LastWriteTime).TotalSeconds\r\n");
        sb.append("        if ($staleSec -gt $hbStallSec) {\r\n");
        sb.append("            taskkill /F /T /PID $targetPid | Out-Null\r\n");
        sb.append("            break\r\n");
        sb.append("        }\r\n");
        sb.append("    }\r\n");
        sb.append("    Start-Sleep -Seconds 2\r\n");
        sb.append("}\r\n");
        // UTF-16LE（带 BOM）保证含中文/空格的路径在 Windows PowerShell 5 下可读。
        try (FileOutputStream out = new FileOutputStream(ps1)) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xFE});
            out.write(sb.toString().getBytes(StandardCharsets.UTF_16LE));
        } catch (Throwable t) {
            FileLog.log("external killer script write failed: " + t);
            return;
        }
        StringBuilder vb = new StringBuilder();
        vb.append("' MCphone external exit watchdog launcher v3 (generated, safe to delete)\r\n");
        vb.append("CreateObject(\"WScript.Shell\").Run \"powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"\"\" & WScript.Arguments(0) & \"\"\"\", 0, False\r\n");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(vbs), StandardCharsets.US_ASCII)) {
            w.write(vb.toString());
        } catch (Throwable t) {
            FileLog.log("external killer launcher write failed: " + t);
            return;
        }
        try {
            // wscript //B：完全无窗口（直接 powershell -WindowStyle Hidden 会闪
            // 一下黑框）。wscript 立即返回，PowerShell 留在后台盯 flag/心跳。
            new ProcessBuilder("wscript.exe", "//B", vbs.getAbsolutePath())
                .redirectErrorStream(true).start();
            FileLog.log("external killer spawned via wscript (pid " + pid
                + ", flagGrace " + extGraceSec + "s, hbStall " + hbStallSec + "s)");
        } catch (Throwable t) {
            try {
                new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden", "-File", ps1.getAbsolutePath())
                    .redirectErrorStream(true).start();
                FileLog.log("external killer spawned via powershell fallback (" + t + ")");
            } catch (Throwable t2) {
                FileLog.log("external killer spawn failed entirely: " + t2);
            }
        }
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

    private static Minecraft safeMinecraft() {
        try {
            return Minecraft.getMinecraft();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 探测能力意外失效时的 v1 兜底：shutdown hook 阶段挂起 15s 后走策略链
     * 强杀。仅在轮询线程无法判断退出信号时安装。
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
