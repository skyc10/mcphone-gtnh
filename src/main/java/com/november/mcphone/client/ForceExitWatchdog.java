package com.november.mcphone.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.Map;

import com.november.mcphone.client.PhoneCanvas;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * 退出看门狗：JVM 关闭流程（shutdown hook 阶段）若 15 秒仍未结束，
 * 先把全部线程栈转储到 .minecraft/mcphone/shutdown-dump.txt（定位卡住者，
 * 例如 MCEF/JCEF 的关闭挂起），再强制 halt 结束进程，避免用户手动结束进程。
 *
 * <p>注意：强制结束不能直调 {@link Runtime#halt(int)}——FML 会把模组字节码里的
 * halt 调用重定向为 System.exit，而在 shutdown hook 内部触发的 System.exit
 * 永远等不到 hooks 跑完（钩子本身正被阻塞），等于静默失效。因此改用反射调用
 * 真正的 halt：反射分发不经过本类字节码，FML 重定向不生效，进程必死。</p>
 *
 * <p>正常退出远快于 15 秒，看门狗不会触发（触发即说明有钩子挂起）。</p>
 */
public final class ForceExitWatchdog {

    private ForceExitWatchdog() {}

    private static File baseDir;

    public static void register() {
        // 游戏运行期捕获目录：关闭阶段不可再调 Minecraft API。
        baseDir = PhoneCanvas.baseDir();
        // 钩子线程自身同步执行：关闭钩子线程由 JVM 保证 Join，守护子线程会在关闭早期被杀。
        Runtime.getRuntime().addShutdownHook(new Thread(ForceExitWatchdog::run, "mcphone-exit-watchdog"));
    }

    private static void run() {
        System.err.println("[mcphone] ForceExitWatchdog: shutdown hook engaged, forcing halt in 15s if shutdown stalls");
        sleep(8000);
        dumpThreads("8s");
        sleep(7000);
        System.err.println("[mcphone] JVM shutdown not finished 15s after quit — forcing halt "
            + "(see mcphone/shutdown-dump.txt for the blocking thread)");
        dumpThreads("15s");
        forceHalt();
    }

    /**
     * 强制结束进程：先反射调用真正的 {@code Runtime.halt(0)}（跳过全部
     * shutdown hooks，绕过 FML 的 halt→System.exit 重定向）；反射失败时依次
     * 退回 FMLCommonHandler.exitJava 与直调 halt，并全程打日志。
     */
    private static void forceHalt() {
        System.err.println("[mcphone] ForceExitWatchdog: forcing halt(0) via reflection to bypass FML's Runtime.halt -> System.exit redirect");
        try {
            Method halt = Runtime.class.getMethod("halt", int.class);
            halt.invoke(Runtime.getRuntime(), 0);
            // 正常情况下上一行不会返回（进程已死）
            System.err.println("[mcphone] ForceExitWatchdog: reflective halt returned unexpectedly, process still alive");
        } catch (Throwable t) {
            System.err.println("[mcphone] ForceExitWatchdog: reflective halt failed (" + t + "), falling back to FMLCommonHandler.exitJava(0, false)");
            try {
                FMLCommonHandler.instance().exitJava(0, false);
            } catch (Throwable t2) {
                System.err.println("[mcphone] ForceExitWatchdog: FMLCommonHandler.exitJava failed (" + t2 + "), last resort: direct Runtime.halt (may be FML-redirected to System.exit)");
                Runtime.getRuntime().halt(0);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void dumpThreads(String phase) {
        try {
            File out = new File(baseDir, "shutdown-dump.txt");
            try (java.io.PrintWriter w = new java.io.PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
                w.println("MCphone shutdown thread dump @ " + phase + " after quit");
                w.println("JVM uptime hint: shutdown phase");
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                long[] ids = bean.getAllThreadIds();
                Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
                for (Thread t : stacks.keySet()) {
                    w.println();
                    w.println("\"" + t.getName() + "\""
                        + " daemon=" + t.isDaemon()
                        + " state=" + t.getState()
                        + " alive=" + t.isAlive());
                    StackTraceElement[] st = stacks.get(t);
                    for (StackTraceElement e : st) {
                        w.println("    at " + e);
                    }
                    if (st.length == 0) {
                        w.println("    (no stack)");
                    }
                }
                w.println();
                w.println("total threads: " + ids.length);
            }
            System.err.println("[mcphone] shutdown thread dump written: " + out.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[mcphone] shutdown dump failed: " + t);
        }
    }
}
