package com.november.mcphone.core;

import java.io.File;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.config.Configuration;

/**
 * 星门合规版中央配置（config/mcphone-stargate.cfg）。
 *
 * <p>GTNH「星门规则」对 Custom Mods 的要求：不得实质影响游戏进程。手机里
 * AE2 无线终端、传送、末影箱这类玩法功能默认全部收敛为「禁用」，
 * 服主可在配置里按需逐项放开。所有玩法功能实现必须读取本配置：</p>
 *
 * <ul>
 *   <li>[ae2] registerWirelessTerminal —— 手机注册为 AE2 无线终端的总开关（默认 false）；</li>
 *   <li>[ae2] builtinTerminal —— 手机内置兜底物品终端（默认 false）；</li>
 *   <li>[teleport] maxWaypoints / cooldownSeconds / crossDimension / requireItem；</li>
 *   <li>[enderchest] enabled —— 末影箱玩法开关（默认 false）；</li>
 *   <li>[chat] friendTeleport —— 聊天 App 好友传送（默认 true，属常规玩法）。</li>
 * </ul>
 *
 * <p>文件位置走 {@link MinecraftServer#getFile(String)}：专用服务器解析到服务器
 * 根目录，单人集成服务器解析到 .minecraft。沿用 {@code feature/chat/ChatConfig}
 * 的懒加载 + mtime 缓存模式，供主 mod 各玩法功能在运行时按需查询。</p>
 */
public final class StargateConfig {

    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static long lastModified;

    // [ae2] 默认全关：星门规则下手机不注册为 AE2 无线终端、不提供内置终端。
    private static boolean registerWirelessTerminal = false;
    private static boolean builtinTerminal = false;

    // [teleport] 默认 3 个传送点、30 秒冷却、不跨维度、不消耗传送宝石（预留键）。
    private static int teleportMaxWaypoints = 3;
    private static int teleportCooldownSeconds = 30;
    private static boolean teleportCrossDimension = false;
    private static boolean teleportRequireItem = false;

    // [enderchest] 默认关闭末影箱玩法。
    private static boolean enderchestEnabled = false;

    // [chat] 好友传送属常规玩法，默认保持开启。
    private static boolean chatFriendTeleport = true;

    private StargateConfig() {}

    /** [ae2] 手机注册为 AE2 无线终端的总开关。 */
    public static boolean registerWirelessTerminal() {
        load();
        return registerWirelessTerminal;
    }

    /** [ae2] 手机内置兜底物品终端。 */
    public static boolean builtinTerminal() {
        load();
        return builtinTerminal;
    }

    /** [teleport] 允许保存的传送点上限。 */
    public static int teleportMaxWaypoints() {
        load();
        return teleportMaxWaypoints;
    }

    /** [teleport] 传送冷却秒数。 */
    public static int teleportCooldownSeconds() {
        load();
        return teleportCooldownSeconds;
    }

    /** [teleport] 是否允许跨维度传送。 */
    public static boolean teleportCrossDimension() {
        load();
        return teleportCrossDimension;
    }

    /** [teleport] 是否消耗传送宝石（预留键）。 */
    public static boolean teleportRequireItem() {
        load();
        return teleportRequireItem;
    }

    /** [enderchest] 末影箱玩法开关。 */
    public static boolean enderchestEnabled() {
        load();
        return enderchestEnabled;
    }

    /** [chat] 聊天 App 好友传送开关。 */
    public static boolean chatFriendTeleport() {
        load();
        return chatFriendTeleport;
    }

    /**
     * 【仅服务端调用】星门规则当前禁用的手机 App id 名单（t8，字面对应客户端
     * BuiltinApps 注册的 id）。随 {@code StargateSync}(id 19, S→C) 推给客户端，
     * 主屏 {@code PhoneUi.orderedForHome()} 按名单直接不显示：
     * <ul>
     *   <li>"enderchest" —— [enderchest] enabled=false 时禁；</li>
     *   <li>"ae2" —— [ae2] registerWirelessTerminal 与 builtinTerminal <b>全关才禁</b>
     *       （留任一条终端路径 ME App 都可用，不该从主屏藏掉）。</li>
     * </ul>
     * 传送/聊天没有总开关（表现为数量/冷却约束，属受限而非禁用），不隐藏。
     * 注意：{@link #load()} 在本 JVM 无服务端实例时保持默认值（星门默认全禁），
     * 客户端误调会得到「全禁」名单——调用点必须约束在服务端（PlayTimeTracker）。
     */
    public static java.util.List<String> disabledAppIds() {
        java.util.List<String> out = new java.util.ArrayList<String>(2);
        if (!enderchestEnabled()) {
            out.add("enderchest");
        }
        if (!registerWirelessTerminal() && !builtinTerminal()) {
            out.add("ae2");
        }
        return out;
    }

    private static void load() {
        synchronized (LOCK) {
            try {
                MinecraftServer server = MinecraftServer.getServer();
                if (server == null) return; // 未开服（主菜单等）：保持默认值。
                File file = server.getFile("config/mcphone-stargate.cfg");
                long stamp = file.isFile() ? file.lastModified() : -1L;
                if (loaded && stamp == lastModified) return;
                loaded = true;
                lastModified = stamp;
                Configuration cfg = new Configuration(file, true);

                registerWirelessTerminal = cfg.getBoolean(
                    "registerWirelessTerminal", "ae2", false,
                    "Master switch: register the phone as an AE2 wireless terminal. "
                        + "Default false per GTNH Stargate rules (no substantial gameplay impact).");
                builtinTerminal = cfg.getBoolean(
                    "builtinTerminal", "ae2", false,
                    "Allow the phone's built-in fallback item terminal. Default false.");

                teleportMaxWaypoints = cfg.getInt(
                    "maxWaypoints", "teleport", 3, 0, Integer.MAX_VALUE,
                    "Max saved teleport waypoints. Default 3 (vanilla-like, no gameplay impact).");
                teleportCooldownSeconds = cfg.getInt(
                    "cooldownSeconds", "teleport", 30, 0, Integer.MAX_VALUE,
                    "Teleport cooldown in seconds. Default 30.");
                teleportCrossDimension = cfg.getBoolean(
                    "crossDimension", "teleport", false,
                    "Allow cross-dimension teleport. Default false.");
                teleportRequireItem = cfg.getBoolean(
                    "requireItem", "teleport", false,
                    "Whether teleporting consumes a teleport gem item (reserved key). Default false.");

                enderchestEnabled = cfg.getBoolean(
                    "enabled", "enderchest", false,
                    "Enable the ender chest gameplay feature. Default false per Stargate rules.");

                chatFriendTeleport = cfg.getBoolean(
                    "friendTeleport", "chat", true,
                    "Allow players to teleport to an online friend from the chat app. Default true.");

                if (cfg.hasChanged()) cfg.save();
            } catch (Throwable t) {
                // 配置读失败不放大默认值：星门默认即「禁用」，安全。
                System.err.println("[mcphone] stargate config load failed: " + t);
            }
        }
    }
}
