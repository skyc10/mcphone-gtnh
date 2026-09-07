package com.november.mcphone.feature.chat;

import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 聊天 App 服务端事件：
 * <ul>
 *   <li>登录时全量同步会话/好友数据给客户端（同 StoreEvents 模式）；</li>
 *   <li>好友跨维度传送的待执行队列（同 AppIntegrations.CrossDimTeleportHook 模式：
 *       travelToDimension 之后下一 tick 才能可靠落位）。</li>
 * </ul>
 *
 * <p>事件监听器必须是 public 具名静态类（FML ASM 跨类加载器调用的硬性要求，
 * 见 docs/AI-DEV-NOTES.md 踩坑 #7），所以拆成两个具名类注册。</p>
 */
public final class ChatEvents {

    /** 跨维度好友传送待执行队列：元素 = [传送者名字, 目标玩家名字]。 */
    private static final CopyOnWriteArrayList<String[]> PENDING_TP = new CopyOnWriteArrayList<>();
    private static boolean tickHookRegistered;

    private ChatEvents() {}

    /** NetworkHandler.init() 调用（common 侧，Forge 总线登录事件只在服务端触发）。 */
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new LoginHook());
    }

    public static void scheduleTeleport(String travelerName, String targetName) {
        PENDING_TP.add(new String[] {travelerName, targetName});
        if (!tickHookRegistered) {
            tickHookRegistered = true;
            FMLCommonHandler.instance().bus().register(new TeleportTickHook());
        }
    }

    /** 必须是 public 具名静态类（踩坑 #7）。 */
    public static final class LoginHook {

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.player instanceof EntityPlayerMP) {
                ChatService.syncTo((EntityPlayerMP) event.player);
            }
        }
    }

    /** 必须是 public 具名静态类（踩坑 #7）。 */
    public static final class TeleportTickHook {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            for (String[] p : PENDING_TP) {
                PENDING_TP.remove(p);
                EntityPlayerMP traveler = ChatService.onlinePlayerByName(p[0]);
                EntityPlayerMP target = ChatService.onlinePlayerByName(p[1]);
                if (traveler == null || target == null) continue;
                if (traveler.dimension != target.dimension) {
                    // 先跨维度，位置下一 tick 落位（元素重新入队）。
                    traveler.travelToDimension(target.dimension);
                    PENDING_TP.add(p);
                    continue;
                }
                traveler.setPositionAndUpdate(
                    target.posX + 1.0, target.posY, target.posZ);
                traveler.rotationYaw = target.rotationYaw;
                traveler.rotationPitch = target.rotationPitch;
                traveler.fallDistance = 0.0f;
                traveler.addChatMessage(new ChatComponentText(
                    "§7[MC手机] §a已传送到 " + target.getCommandSenderName() + " 身边。"));
            }
        }
    }
}
