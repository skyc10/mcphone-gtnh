package com.november.mcphone.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.november.mcphone.core.ItemPhone;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 客户端事件钩子：按键（打开手机/相机快门）、相机模式tick与HUD取景框。
 */
public final class ClientHooks {

    public static KeyBinding keyPhone;
    public static KeyBinding keyShutter;

    /** WaypointSync 包在 netty 线程落地，客户端 tick 主线程应用。 */
    public static volatile java.util.List<com.november.mcphone.core.ItemPhone.Waypoint> pendingWaypointSync;

    /** UnlockSync 包在 netty 线程落地，客户端 tick 主线程应用。 */
    public static volatile java.util.List<String> pendingUnlockSync;


    /** PlayTimeSync 包在 netty 线程落地，客户端 tick 主线程应用（游玩时长快照）。 */
    public static volatile com.november.mcphone.client.enhance.PlayTimeClient.Snapshot pendingPlayTimeSync;

    /**
     * NoteSync 分批包收齐后的完整列表队列（netty 线程入队，客户端 tick 主线程出队）。
     * 只有在累加缓冲收齐 total 条后才入队一份完整列表。
     */
    public static final java.util.Queue<java.util.List<com.november.mcphone.feature.notes.Note>>
        pendingNoteSyncs = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * NoteSync 分批累加缓冲：仅在 netty 线程读写（同一条 channel 的包在同一线程
     * 按序处理），主线程不触碰。{@code noteAccumTotal < 0} 表示当前没有进行中的序列。
     */
    private static java.util.List<com.november.mcphone.feature.notes.Note> noteAccum;
    private static int noteAccumTotal = -1;

    /**
     * netty 线程调用：把一批便签按 offset 拼进累加缓冲，收齐 total 条后整体入队。
     * 新序列（offset=0）或检测到乱序会重开缓冲，半途的旧序列被丢弃不污染新数据。
     */
    public static void accumulateNoteSync(
            java.util.List<com.november.mcphone.feature.notes.Note> batch, int offset, int total) {
        if (offset == 0 || noteAccum == null || noteAccumTotal < 0
                || noteAccum.size() != offset) {
            noteAccum = new java.util.ArrayList<>(Math.max(total, batch.size()));
            noteAccumTotal = total;
        }
        noteAccum.addAll(batch);
        if (noteAccum.size() >= noteAccumTotal) {
            pendingNoteSyncs.add(noteAccum);
            noteAccum = null;
            noteAccumTotal = -1;
        }
    }

    private static String lastAe2GuiLogged;

    private static boolean cameraMode;

    private ClientHooks() {}

    public static void preInit() {
        keyPhone = new KeyBinding("key.mcphone.phone", Keyboard.KEY_P, "MCphone");
        keyShutter = new KeyBinding("key.mcphone.shutter", Keyboard.KEY_C, "MCphone");
        ClientRegistry.registerKeyBinding(keyPhone);
        ClientRegistry.registerKeyBinding(keyShutter);
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(new ClientHooks());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new CameraHandler.Overlay());
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (keyPhone.isPressed()) {
            if (cameraMode) {
                // P 退出相机模式：回到手机界面而不是完全退出。
                setCameraMode(false);
                ItemStack phone = findPhone(mc);
                if (phone != null) {
                    mc.displayGuiScreen(new com.november.mcphone.client.scene.PhoneScreen(
                        new com.november.mcphone.client.scene.PhoneUi(phone)));
                }
                return;
            }
            if (mc.currentScreen == null && mc.thePlayer != null) {
                ItemStack phone = findPhone(mc);
                if (phone != null) {
                    mc.displayGuiScreen(new com.november.mcphone.client.scene.PhoneScreen(
                        new com.november.mcphone.client.scene.PhoneUi(phone)));
                } else {
                    mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                        StatCollector.translateToLocal("msg.mcphone.nophone")));
                }
            }
        }
        if (keyShutter.isPressed() && cameraMode && mc.currentScreen == null) {
            CameraHandler.pendingCapture = true;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (cameraMode && mc.thePlayer == null) setCameraMode(false);
        // 手机打开时驱动状态栏/时钟页的世界时钟。
        if (com.november.mcphone.client.scene.PhoneUi.ACTIVE != null) {
            com.november.mcphone.client.scene.PhoneUi.tickClock();
        }
        // 延迟关屏（点击回调里 closePhone 的落地时机）。
        com.november.mcphone.client.scene.PhoneUi.flushPendingClose();
        // 诊断：记录 ae2/ae2fc 终端 GUI 打开时的客户端界面类（排查渲染异常）。
        if (mc.currentScreen != null) {
            String cls = mc.currentScreen.getClass().getName();
            if ((cls.contains("glodblock") || cls.contains("appeng")) && !cls.equals(lastAe2GuiLogged)) {
                lastAe2GuiLogged = cls;
                System.out.println("[mcphone] client terminal GUI opened: " + cls);
            }
        } else {
            lastAe2GuiLogged = null;
        }
        // 服务端→客户端传送点同步（netty 线程缓存，主线程应用）。
        java.util.List<com.november.mcphone.core.ItemPhone.Waypoint> sync = pendingWaypointSync;
        if (sync != null) {
            pendingWaypointSync = null;
            com.november.mcphone.client.scene.PhoneUi.onWaypointSync(sync);
        }
        // 服务端→客户端已购 App 同步（netty 线程缓存，主线程应用）。
        java.util.List<String> unlock = pendingUnlockSync;
        if (unlock != null) {
            pendingUnlockSync = null;
            StoreClient.onUnlockSync(unlock);
        }
        // 服务端→客户端游玩时长快照（netty 线程缓存，主线程应用）。
        com.november.mcphone.client.enhance.PlayTimeClient.Snapshot playTime = pendingPlayTimeSync;
        if (playTime != null) {
            pendingPlayTimeSync = null;
            com.november.mcphone.client.enhance.PlayTimeClient.onSync(playTime);
            com.november.mcphone.client.enhance.PlayTimeClient.refreshClockPage();
        }
        // 聊天 App：会话/消息/图片同步包在 netty 线程入队，这里主线程应用并刷新页面。
        com.november.mcphone.feature.chat.client.ChatClient.applyPending();
        // 服务端→客户端便签同步（netty 线程分批累加收齐入队，主线程整批应用；含旧本地便签一次性导入）。
        java.util.List<com.november.mcphone.feature.notes.Note> noteSync;
        while ((noteSync = pendingNoteSyncs.poll()) != null) {
            com.november.mcphone.feature.notes.NotesClientCache.onSync(noteSync);
        }
        // 离开世界：清空时长缓存与问候状态（换存档后未重新同步前不得展示旧值）。
        if (mc.theWorld == null) {
            com.november.mcphone.client.enhance.PlayTimeClient.reset();
            com.november.mcphone.client.enhance.GreetingToast.onWorldLeave();
            // 便签同样换存档即清；重置 imported 是刻意的——导入只在"服务端为空"时
            // 触发，新存档下重新给一次导入机会是正确语义（不会产生重复导入）。
            pendingNoteSyncs.clear();
            com.november.mcphone.feature.notes.NotesClientCache.reset();
        } else if (com.november.mcphone.client.scene.PhoneUi.ACTIVE != null) {
            // 手机打开期间做一次性问候（欢迎/深夜/连续 3h/世界总 100h）。
            com.november.mcphone.client.enhance.GreetingToast.onClientTick();
        }
    }

    public static boolean isCameraMode() {
        return cameraMode;
    }

    public static void setCameraMode(boolean on) {
        cameraMode = on;
        Minecraft mc = Minecraft.getMinecraft();
        if (on) {
            if (mc.currentScreen != null) mc.displayGuiScreen(null);
        }
        CameraHandler.pendingCapture = false;
    }

    private static ItemStack findPhone(Minecraft mc) {
        for (ItemStack s : mc.thePlayer.inventory.mainInventory) {
            if (s != null && s.getItem() instanceof com.november.mcphone.core.ItemPhone) return s;
        }
        return null;
    }
}
