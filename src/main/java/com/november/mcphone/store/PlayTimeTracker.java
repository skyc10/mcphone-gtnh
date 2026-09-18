package com.november.mcphone.store;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.november.mcphone.net.NetworkHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端游玩时长计时（服务端权威）：
 * <ul>
 * <li>登录/退出/每 20 秒：累计现实 tick 并把增量刷入 {@link PlayTimeData}（按存档）；
 * <li>登录 + 每次周期刷入时发 {@code PlayTimeSync}(id 15, S→C) 给客户端展示；
 * <li>星门（t8）借同一节奏推送 {@code StargateSync}(id 19, S→C)：登录全发、
 *     每 20 秒周期 flush 时与上次推送名单对比，变了才重发（省流）。</li>
 * <li>客户端提示里程碑后回 {@code PlayTimeMilestone}(id 16, C→S)，这里落盘保证一次性。</li>
 * </ul>
 *
 * <p>注册在 FML 总线（1.7.10 的 TickEvent/PlayerEvent 都在这里派发），
 * 由 NetworkHandler.init() 两侧无条件调用——单人模式 init 也跑在客户端线程，
 * 若按 effectiveSide 门控会漏注册；监听器内部以 {@code EntityPlayerMP} 过滤
 * 玩家事件，ServerTick 事件只由服务端 tick 线程派发，客户端侧注册无副作用。</p>
 */
public final class PlayTimeTracker {

    /** 周期刷盘 + 推送间隔：400 tick = 20 现实秒。 */
    private static final int SYNC_INTERVAL_TICKS = 400;

    /** 每个在线玩家本会话累计的现实 tick（登录起算）。 */
    private static final Map<UUID, Long> SESSION = new HashMap<>();

    /** 已刷入 PlayTimeData 的会话 tick 基线（退出/周期刷盘用）。 */
    private static final Map<UUID, Long> FLUSHED = new HashMap<>();

    /**
     * 每个在线玩家上次推送的星门禁用名单（t8 省流对比用；名单至多 2 条，直接存副本，
     * equals 比对足够；仅服务端线程读写——登录/周期 flush 都在服务端 tick 线程）。
     */
    private static final Map<UUID, java.util.List<String>> LAST_SENT = new HashMap<>();

    /** 自维护服务端 tick 计数（不依赖 MinecraftServer.tickCounter 的映射名）。 */
    private static long serverTick;

    private PlayTimeTracker() {}

    public static void register() {
        FMLCommonHandler.instance().bus().register(new PlayTimeTracker());
    }

    // ===================== 事件 =====================

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP p = (EntityPlayerMP) event.player;
        SESSION.put(p.getUniqueID(), 0L);
        FLUSHED.put(p.getUniqueID(), 0L);
        syncTo(p);
        // 星门（t8）：登录立即推一次当前禁用名单（客户端主屏按名单隐藏）。
        syncStargate(p, true);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP p = (EntityPlayerMP) event.player;
        flush(p, true);
        SESSION.remove(p.getUniqueID());
        FLUSHED.remove(p.getUniqueID());
        LAST_SENT.remove(p.getUniqueID());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        serverTick++;
        // 本会话累计：所有在线玩家每 tick +1（现实 tick，服务端权威口径）。
        for (UUID id : SESSION.keySet()) {
            SESSION.put(id, SESSION.get(id) + 1);
        }
        // 退避到 20 秒一次：刷盘 + 全量推送。
        if (serverTick % SYNC_INTERVAL_TICKS != 0) return;
        for (Object o : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP) flush((EntityPlayerMP) o, false);
        }
    }

    // ===================== 计时与同步 =====================

    /** 把会话增量刷入按存档数据；logout=true 时即使增量为 0 也强制落盘一次。 */
    private static void flush(EntityPlayerMP p, boolean logout) {
        UUID id = p.getUniqueID();
        long session = SESSION.containsKey(id) ? SESSION.get(id) : 0L;
        long flushed = FLUSHED.containsKey(id) ? FLUSHED.get(id) : 0L;
        long delta = session - flushed;
        if (delta > 0 || logout) {
            data().addTotalTicks(id, delta);
            FLUSHED.put(id, session);
        }
        syncTo(p);
        // 星门（t8）：跟周期 flush 同节奏做名单对比推送（不变不发包）。
        syncStargate(p, false);
    }

    private static void syncTo(EntityPlayerMP p) {
        UUID id = p.getUniqueID();
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.PlayTimeSync(
            SESSION.containsKey(id) ? SESSION.get(id) : 0L,
            data().totalTicks(id),
            data().isMilestoneShown(id, 0),
            data().isMilestoneShown(id, 1),
            // 自维护服务器 tick 计数 = 本次服务器运行时长（uptime）。
            serverTick), p);
    }

    /**
     * 星门禁用名单推送（t8，{@code StargateSync} id 19, S→C）：登录 {@code force=true}
     * 全量发；之后每 20 秒周期 flush 时与 {@link #LAST_SENT} 对比，变了才重发（省流——
     * {@code mcphone-stargate.cfg} 的 mtime 不变时名单恒等，一条包都不发）。
     * 名单由 {@code StargateConfig.disabledAppIds()} 现算（懒加载 + mtime 缓存，
     * 服主热改配置最大 20 秒内生效）。
     */
    private static void syncStargate(EntityPlayerMP p, boolean force) {
        java.util.List<String> ids = com.november.mcphone.core.StargateConfig.disabledAppIds();
        UUID id = p.getUniqueID();
        java.util.List<String> last = LAST_SENT.get(id);
        if (!force && last != null && last.equals(ids)) {
            return;
        }
        LAST_SENT.put(id, new java.util.ArrayList<String>(ids));
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.StargateSync(ids), p);
    }

    /** 客户端里程碑确认包（id 16）：落盘一次性标记。 */
    public static void handleMilestone(EntityPlayerMP p, int index) {
        if (index < 0 || index > 1) return;
        data().markMilestoneShown(p.getUniqueID(), index);
    }

    private static PlayTimeData data() {
        World overworld = MinecraftServer.getServer().worldServerForDimension(0);
        return PlayTimeData.get(overworld);
    }
}
