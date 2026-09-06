package com.november.mcphone.client;

import net.minecraft.client.Minecraft;

/**
 * 世界时间 → 手机时钟。
 */
public final class TimeUtil {

    private TimeUtil() {}

    /** MC 一天 24000 tick，0 tick = 06:00。 */
    public static String worldClock() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return "--:--";
        long t = mc.theWorld.getWorldTime() % 24000L;
        int hour = (int) ((t / 1000L + 6) % 24);
        int min = (int) (t % 1000L * 60 / 1000);
        return String.format("%02d:%02d", hour, min);
    }

    public static int worldDay() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld == null ? 0 : (int) (mc.theWorld.getWorldTime() / 24000L);
    }
}
