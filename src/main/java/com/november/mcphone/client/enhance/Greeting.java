package com.november.mcphone.client.enhance;

/**
 * 问候语挑选逻辑（参考上游 mcphone feature/clock/Greeting，按 GTNH 场景裁剪）。
 *
 * <p>纯算术与分支，不 import 任何 Minecraft 类型，便于单独核对。
 * 每项对应语言键 {@code msg.mcphone.greet.<suffix>}，带 %s 的只有
 * LONG_SESSION / MILESTONE（小时数）。</p>
 */
public final class Greeting {

    private Greeting() {}

    /** 连续游玩多少小时提示一次。 */
    public static final long LONG_SESSION_HOURS = 3;

    /** 世界总时长里程碑小时数（一次性）。 */
    public static final long MILESTONE_HOURS = 100;

    /** 深夜的现实钟点区间（跨零点）。 */
    public static final int LATE_NIGHT_FROM = 23;
    public static final int LATE_NIGHT_TO = 5;

    public enum Kind {
        WELCOME_BACK("welcome_back", false),
        LATE_NIGHT("late_night", false),
        LONG_SESSION("long_session", true),
        MILESTONE("milestone", true),
        MORNING("morning", false),
        NOON("noon", false),
        AFTERNOON("afternoon", false),
        EVENING("evening", false);

        private final String suffix;
        private final boolean hasArg;

        Kind(String suffix, boolean hasArg) {
            this.suffix = suffix;
            this.hasArg = hasArg;
        }

        public String key() {
            return "msg.mcphone.greet." + suffix;
        }

        public boolean hasArg() {
            return hasArg;
        }
    }

    /** 挑选结果：kind + 可选参数（小时数）。 */
    public static final class Choice {

        public final Kind kind;
        public final long arg;

        Choice(Kind kind, long arg) {
            this.kind = kind;
            this.arg = arg;
        }
    }

    /**
     * 按优先级从高到低挑一句问候。
     *
     * @param realHour       现实钟点（0-23）
     * @param sessionTicks   服务端权威的本局现实 tick（-1 = 未同步）
     * @param totalTicks     服务端权威的世界总现实 tick（-1 = 未同步）
     * @param shown3h        3 小时里程碑是否已提示过（服务端持久化）
     * @param shown100h      100 小时里程碑是否已提示过（服务端持久化）
     */
    public static Choice choose(int realHour, long sessionTicks, long totalTicks,
                                boolean shown3h, boolean shown100h) {
        // 刚进世界 1 分钟内：欢迎回来（用服务端本局 tick 而不是本地墙钟，跨存档重进不误报）。
        if (sessionTicks >= 0 && sessionTicks < 20L * 60) {
            return new Choice(Kind.WELCOME_BACK, -1);
        }
        if (isLateNight(realHour)) {
            return new Choice(Kind.LATE_NIGHT, -1);
        }
        if (sessionTicks >= 0 && !shown3h && sessionHours(sessionTicks) >= LONG_SESSION_HOURS) {
            return new Choice(Kind.LONG_SESSION, sessionHours(sessionTicks));
        }
        if (totalTicks >= 0 && !shown100h && totalHours(totalTicks) >= MILESTONE_HOURS) {
            return new Choice(Kind.MILESTONE, totalHours(totalTicks));
        }
        return new Choice(bandOf(realHour), -1);
    }

    /** 服务端现实 tick → 小时（20 tick/秒）。 */
    public static long sessionHours(long realTicks) {
        return Math.max(0, realTicks) / (20L * 3600);
    }

    public static long totalHours(long realTicks) {
        return Math.max(0, realTicks) / (20L * 3600);
    }

    /** 深夜跨零点，是"或"不是"与"。 */
    public static boolean isLateNight(int realHour) {
        return realHour >= LATE_NIGHT_FROM || realHour < LATE_NIGHT_TO;
    }

    /** 时段问候（深夜单独接管，不在此重复）。 */
    public static Kind bandOf(int realHour) {
        if (realHour < 11) return Kind.MORNING;      // 5-10
        if (realHour < 13) return Kind.NOON;         // 11-12
        if (realHour < 18) return Kind.AFTERNOON;    // 13-17
        return Kind.EVENING;                          // 18-22
    }
}
