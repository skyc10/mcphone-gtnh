package com.november.mcphone.client.enhance;

import net.minecraft.util.StatCollector;

/**
 * 客户端游玩时长缓存：来自服务端 PlayTimeSync（id 15, S→C）的全量快照，
 * 由 ClientHooks 在客户端 tick 主线程落地（仿 WaypointSync/UnlockSync 模式）。
 *
 * <p>单位一律是服务端现实 tick（20/秒）。-1 表示尚未同步——把"还不知道"
 * 显示成 0 小时是撒谎，界面层必须区分。</p>
 */
public final class PlayTimeClient {

    /** 一次同步快照（不可变）。 */
    public static final class Snapshot {

        public final long sessionTicks;
        public final long totalTicks;
        public final boolean milestone3hShown;
        public final boolean milestone100hShown;

        public Snapshot(long sessionTicks, long totalTicks,
                        boolean milestone3hShown, boolean milestone100hShown) {
            this.sessionTicks = sessionTicks;
            this.totalTicks = totalTicks;
            this.milestone3hShown = milestone3hShown;
            this.milestone100hShown = milestone100hShown;
        }
    }

    private static volatile Snapshot snapshot;

    private PlayTimeClient() {}

    /** 服务端同步落地（客户端 tick 主线程调用）。 */
    public static void onSync(Snapshot s) {
        snapshot = s;
    }

    /** 离开世界时清空：换存档后未重新同步前不得展示旧值。 */
    public static void reset() {
        snapshot = null;
    }

    /** 本局游玩现实 tick；-1 = 未同步。 */
    public static long sessionTicks() {
        Snapshot s = snapshot;
        return s == null ? -1 : s.sessionTicks;
    }

    /** 世界总游玩现实 tick；-1 = 未同步。 */
    public static long totalTicks() {
        Snapshot s = snapshot;
        return s == null ? -1 : s.totalTicks;
    }

    public static boolean isMilestone3hShown() {
        Snapshot s = snapshot;
        return s != null && s.milestone3hShown;
    }

    public static boolean isMilestone100hShown() {
        Snapshot s = snapshot;
        return s != null && s.milestone100hShown;
    }

    /** 同步到达后刷新时钟页（页面打开时才需要重建；改树走 PhoneUi 延迟队列）。 */
    public static void refreshClockPage() {
        com.november.mcphone.client.scene.PhoneUi.postAction(() -> {
            com.november.mcphone.client.scene.PhoneUi ui =
                com.november.mcphone.client.scene.PhoneUi.ACTIVE;
            if (ui != null && ui.isPageOpen("clock")) ui.rebuildPage();
        });
    }

    // ===================== 展示 =====================

    /** "X 小时 Y 分"；ticks < 0 显示"同步中"。 */
    public static String format(long ticks) {
        if (ticks < 0) return StatCollector.translateToLocal("msg.mcphone.playtime_syncing");
        long hours = Math.max(0, ticks) / (20L * 3600);
        int minutes = (int) (Math.max(0, ticks) / (20L * 60) % 60);
        if (hours <= 0) return StatCollector.translateToLocalFormatted("msg.mcphone.playtime_min", minutes);
        return StatCollector.translateToLocalFormatted("msg.mcphone.playtime_hm", hours, minutes);
    }

    public static String formatSession() {
        return format(sessionTicks());
    }

    public static String formatTotal() {
        return format(totalTicks());
    }
}
