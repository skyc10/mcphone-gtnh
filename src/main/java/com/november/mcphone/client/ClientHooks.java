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

    /** NoteSync 包在 netty 线程落地，客户端 tick 主线程应用。 */
    public static volatile java.util.List<com.november.mcphone.feature.notes.Note> pendingNoteSync;

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
        // 聊天 App：会话/消息/图片同步包在 netty 线程入队，这里主线程应用并刷新页面。
        com.november.mcphone.feature.chat.client.ChatClient.applyPending();
        // 服务端→客户端便签同步（netty 线程缓存，主线程应用；含旧本地便签一次性导入）。
        java.util.List<com.november.mcphone.feature.notes.Note> noteSync = pendingNoteSync;
        if (noteSync != null) {
            pendingNoteSync = null;
            com.november.mcphone.feature.notes.NotesClientCache.onSync(noteSync);
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
