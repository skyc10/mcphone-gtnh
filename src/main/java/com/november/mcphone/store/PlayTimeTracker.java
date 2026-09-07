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
 * <li>客户端提示里程碑后回 {@code PlayTimeMilestone}(id 16, C→S)，这里落盘保证一次性。</li>
 * </ul>
 *
 * <p>注册在 FML 总线（1.7.10 的 TickEvent/PlayerEvent 都在这里派发），
 * 由 NetworkHandler.init() 两侧调用（客户端侧注册无效但无害）。</p>
 */
public final class PlayTimeTracker {

    /** 周期刷盘 + 推送间隔：400 tick = 20 现实秒。 */
    private static final int SYNC_INTERVAL_TICKS = 400;

    /** 每个在线玩家本会话累计的现实 tick（登录起算）。 */
    private static final Map<UUID, Long> SESSION = new HashMap<>();

    /** 已刷入 PlayTimeData 的会话 tick 基线（退出/周期刷盘用）。 */
    private static final Map<UUID, Long> FLUSHED = new HashMap<>();

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
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP p = (EntityPlayerMP) event.player;
        flush(p, true);
        SESSION.remove(p.getUniqueID());
        FLUSHED.remove(p.getUniqueID());
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
    }

    private static void syncTo(EntityPlayerMP p) {
        UUID id = p.getUniqueID();
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.PlayTimeSync(
            SESSION.containsKey(id) ? SESSION.get(id) : 0L,
            data().totalTicks(id),
            data().isMilestoneShown(id, 0),
            data().isMilestoneShown(id, 1)), p);
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
