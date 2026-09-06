package com.november.mcphone.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.november.mcphone.client.PhoneCanvas;

/**
 * 退出看门狗：JVM 关闭流程（shutdown hook 阶段）若 15 秒仍未结束，
 * 先把全部线程栈转储到 .minecraft/mcphone/shutdown-dump.txt（定位卡住者，
 * 例如 MCEF/JCEF 的关闭挂起），再强制 halt 结束进程，避免用户手动结束进程。
 *
 * <p>正常退出远快于 15 秒，看门狗不会触发（触发即说明有钩子挂起）。</p>
 */
public final class ForceExitWatchdog {

    private ForceExitWatchdog() {}

    public static void register() {
        Runtime.getRuntime().addShutdownHook(new Thread(ForceExitWatchdog::run, "mcphone-exit-watchdog"));
    }

    private static void run() {
        Thread watchdog = new Thread(() -> {
            sleep(8000);
            dumpThreads("8s");
            sleep(7000);
            System.err.println("[mcphone] JVM shutdown not finished 15s after quit — forcing halt "
                + "(see mcphone/shutdown-dump.txt for the blocking thread)");
            dumpThreads("15s");
            Runtime.getRuntime().halt(0);
        }, "mcphone-exit-watchdog-inner");
        watchdog.setDaemon(true);
        watchdog.start();
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
            File out = new File(PhoneCanvas.baseDir(), "shutdown-dump.txt");
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
