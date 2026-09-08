package com.november.mcphone.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 退出保底看门狗 v2：无论附属 mod 行为如何，保证「点退出游戏」后进程一定结束。
 *
 * <p><b>根因</b>：JVM 只在全部非守护线程结束后才自然退出。点退出后 Minecraft
 * 主循环停止、世界保存完成，但若某个附属（如 MCEF/JCEF 的关闭线程）阻塞在
 * native 调用上，进程就永远挂在后台。FML 还会把模组字节码里的
 * {@link Runtime#halt(int)} 调用点重写为 System.exit（ASM per-call-site 改写），
 * 而 System.exit 要等 shutdown hooks 全部结束才生效——挂死的钩子让退出永不
 * 完成。反射调用真正的 halt 不经过本类字节码的调用点，FML 改不到它。</p>
 *
 * <p><b>v1（shutdown hook + 固定 15s）的不足</b>：若卡死发生在更早阶段（主循环
 * 退出、世界保存、GL 上下文销毁），shutdown hook 根本没机会执行；15s 也可能
 * 不够世界保存。</p>
 *
 * <p><b>v2 设计</b>：注册时即启动 daemon 轮询线程（不依赖 shutdown hook），
 * 反射探测「游戏退出已开始」信号——Minecraft.running 翻为 false（MCEF 同款
 * volatile boolean 探测），或 Client thread 已消亡。信号出现前绝不干预
 * （宁可漏杀不可误杀）。信号出现后进入时间线：</p>
 * <ul>
 * <li>0–25s：宽限期（世界保存等），只打日志不动手，每 5s 一行存活非守护线程
 *     摘要（前 5 个名字+首帧栈）便于诊断；期间若非守护线程全部自然结束则收工；</li>
 * <li>25s：Client thread 已消亡但仍有<b>任意</b>非守护线程存活（不限 CEF）
 *     → 强制 halt；</li>
 * <li>25s：Client thread 仍活着（保存卡死）→ 再宽限 10s，35s 无条件强制
 *     halt。此时保存流程八成已死锁——halt 丢的是最后几秒存档，比进程永远
 *     杀不掉好。</li>
 * </ul>
 *
 * <p><b>halt 策略链</b>（每个策略都在独立的一次性 daemon 线程里执行——若某策略
 * 被重定向成 System.exit 并挂死在钩子里，绝不能阻塞看门狗本身；每步后睡 200ms
 * 再走下一步，全程 try-catch 永不抛出）：直调 {@code Runtime.halt(0)}（FML 只
 * 改写模组类字节码里的符号引用，正常环境一步即死）→ 反射 {@code Runtime.halt}
 * （分发不经过字节码调用点）→ FMLCommonHandler.exitJava → System.exit(0)。
 * 全部失败则打印诊断并每 30s 无限重试整条链——FML 的重定向是 per-call-site 的，
 * 新线程的新调用点可能未被改写。</p>
 *
 * <p>{@code -Dmcphone.exitwatchdog=false} 可整体禁用（诊断用），默认开启。</p>
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
    /** 轮询退出信号的间隔。 */
    private static final long POLL_INTERVAL_MS = 500;
    /** 禁用开关：-Dmcphone.exitwatchdog=false。 */
    private static final String PROP_DISABLE = "mcphone.exitwatchdog";
    /** 1.7.10 客户端主线程名。 */
    private static final String MAIN_THREAD_NAME = "Client thread";

    private ForceExitWatchdog() {}

    public static void register() {
        if ("false".equalsIgnoreCase(System.getProperty(PROP_DISABLE, "true"))) {
            System.err.println("[mcphone] ForceExitWatchdog: disabled via -D" + PROP_DISABLE + "=false");
            return;
        }
        // 注册即启动 daemon 轮询线程：不依赖 shutdown hook，避免「卡在 hook
        // 之前的阶段」时看门狗根本没有机会执行。退出阶段 log4j 可能已停，
        // 一律走 System.err（stderr 不经过 log4j 管道）。
        Thread t = new Thread(ForceExitWatchdog::watch, "mcphone-exit-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 轮询「游戏退出已开始」信号；信号出现前绝不进入时间线（防误杀）。 */
    private static void watch() {
        try {
            watch0();
        } catch (Throwable t) {
            // 轮询线程任何早期失败（如数据目录创建失败）都不能让看门狗静默
            // 消失 → 退回 v1 shutdown hook 兜底（baseDir 不可用时跳过 dump）。
            System.err.println("[mcphone] ForceExitWatchdog: watcher crashed (" + t + "), shutdown hook fallback installed");
            installLegacyHook(null);
        }
    }

    private static void watch0() {
        // 游戏运行期捕获目录：退出阶段不可再调 Minecraft API。
        final File baseDir = PhoneCanvas.baseDir();
        Minecraft mc = safeMinecraft();
        Field runningField = mc != null ? findRunningField() : null;
        String src = runningField != null
            ? "Minecraft." + runningField.getName() + " / Client thread liveness"
            : "Client thread liveness";
        System.err.println("[mcphone] ForceExitWatchdog: armed, watching " + src
            + " (grace " + (GRACE_MS / 1000) + "s, force at "
            + (GRACE_MS / 1000) + "s/" + ((GRACE_MS + EXTRA_GRACE_MS) / 1000) + "s)");

        int hits = 0;
        while (true) {
            boolean exiting;
            try {
                // mainThreadState() 返回 -1（探针故障）不算退出信号；若此时连
                // running 字段也没有，则探测能力整体失效 → 退回 v1 兜底。
                int st = mainThreadState();
                if (st < 0 && runningField == null) {
                    throw new IllegalStateException("thread probe failed, no running field");
                }
                exiting = (runningField != null && !runningField.getBoolean(mc)) || st == 1;
            } catch (Throwable t) {
                // 探测能力意外失效 → 保守退回 v1 的 shutdown hook 方案兜底。
                System.err.println("[mcphone] ForceExitWatchdog: signal probe failed, disarmed (" + t + "), shutdown hook fallback installed");
                installLegacyHook(baseDir);
                return;
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
            sleep(POLL_INTERVAL_MS);
        }
        System.err.println("[mcphone] ForceExitWatchdog: game exit detected, grace period "
            + (GRACE_MS / 1000) + "s before any intervention");
        runTimeline(baseDir);
    }

    /**
     * 信号出现后的多级时间线。0–25s 只观察打日志；25s 按 Client thread 死活
     * 分流；35s 无条件强杀。各步骤自行吞异常；探针故障一律 fail-closed
     * （按「仍有线程存活」处理，绝不当作干净退出提前收工）。
     */
    private static void runTimeline(File baseDir) {
        long deadline = System.currentTimeMillis() + GRACE_MS;
        long nextSummary = System.currentTimeMillis() + SUMMARY_INTERVAL_MS;
        while (System.currentTimeMillis() < deadline) {
            sleep(Math.min(200, Math.max(0, deadline - System.currentTimeMillis())));
            if (System.currentTimeMillis() >= nextSummary) {
                nextSummary += SUMMARY_INTERVAL_MS;
                logAliveNonDaemonSummary();
            }
            // 宽限期内非守护线程自然清零是最好的结局，直接收工。探针故障
            // （-1=未知）不算清零：fail-closed，继续留在时间线上。
            if (aliveNonDaemons() == 0) {
                System.err.println("[mcphone] ForceExitWatchdog: all non-daemon threads finished during grace period, exiting cleanly");
                return;
            }
        }
        logAliveNonDaemonSummary();
        dumpThreads(baseDir, (GRACE_MS / 1000) + "s");

        int mainState = mainThreadState();
        if (aliveNonDaemons() == 0) {
            // 25s 整点复查：线程已全部清零，JVM 正在自然退出，不再插手。
            System.err.println("[mcphone] ForceExitWatchdog: no non-daemon threads left at "
                + (GRACE_MS / 1000) + "s, letting JVM finish naturally");
            return;
        }
        if (mainState != 0) {
            // Client thread 已消亡（或探针故障→fail-closed 保守强杀）、世界保存
            // 已完成：残留的任意非守护线程（不限 CEF）都是杀不掉进程的元凶
            // → 强制 halt。
            System.err.println("[mcphone] ForceExitWatchdog: Client thread finished but non-daemon threads remain, forcing halt");
            forceExitLoop(baseDir);
        } else {
            // Client thread 仍活着：世界保存疑似死锁。再宽限 10s，仍活着则
            // 35s 无条件强杀——halt 丢的是最后几秒存档，比进程永远杀不掉好。
            System.err.println("[mcphone] ForceExitWatchdog: Client thread still alive at "
                + (GRACE_MS / 1000) + "s (save may be stalled), extra "
                + (EXTRA_GRACE_MS / 1000) + "s then unconditional halt");
            sleep(EXTRA_GRACE_MS);
            if (mainThreadState() != 0) {
                System.err.println("[mcphone] ForceExitWatchdog: Client thread still alive at "
                    + ((GRACE_MS + EXTRA_GRACE_MS) / 1000) + "s, forcing unconditional halt (saving likely deadlocked)");
                forceExitLoop(baseDir);
            } else if (aliveNonDaemons() > 0) {
                System.err.println("[mcphone] ForceExitWatchdog: Client thread finished during extra grace but non-daemon threads remain, forcing halt");
                forceExitLoop(baseDir);
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
    private static void forceExitLoop(File baseDir) {
        dumpThreads(baseDir, "force");
        while (true) {
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
                        System.err.println("[mcphone] ForceExitWatchdog: reflective halt failed (" + t + ")");
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
                        System.err.println("[mcphone] ForceExitWatchdog: FMLCommonHandler.exitJava failed (" + t + ")");
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
            System.err.println("[mcphone] ForceExitWatchdog: all exit strategies failed, retrying every "
                + (RETRY_INTERVAL_MS / 1000) + "s");
            sleep(RETRY_INTERVAL_MS);
        }
    }

    /** 在一次性 daemon 线程里执行一个退出策略，异常全吞（策略本身永不抛出）。 */
    private static void runDetached(String name, Runnable strategy) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    strategy.run();
                    // 能执行到这里说明该策略没有终结进程（或被重定向）。
                    System.err.println("[mcphone] ForceExitWatchdog: strategy " + name
                        + " returned without terminating the process");
                } catch (Throwable err) {
                    System.err.println("[mcphone] ForceExitWatchdog: strategy " + name
                        + " threw " + err);
                }
            }
        }, "mcphone-exit-" + name);
        t.setDaemon(true);
        t.start();
    }

    /** 每 5s 一行的存活非守护线程摘要（前 5 个名字 + 首帧栈）。 */
    private static void logAliveNonDaemonSummary() {
        try {
            StringBuilder sb = new StringBuilder("[mcphone] ForceExitWatchdog: alive non-daemon threads: ");
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
            System.err.println(sb);
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
    private static void installLegacyHook(final File baseDir) {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    sleep(15000);
                    dumpThreads(baseDir, "15s-legacy");
                    forceExitLoop(baseDir);
                }
            }, "mcphone-exit-watchdog-legacy"));
        } catch (Throwable t) {
            System.err.println("[mcphone] ForceExitWatchdog: legacy hook install failed (" + t + ")");
        }
    }

    private static void dumpThreads(File baseDir, String phase) {
        if (baseDir == null) {
            System.err.println("[mcphone] shutdown dump skipped (no data dir): " + phase);
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
            System.err.println("[mcphone] shutdown thread dump written: " + out.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[mcphone] shutdown dump failed: " + t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
