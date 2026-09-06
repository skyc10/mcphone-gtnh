package com.november.mcphone.client;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import org.lwjgl.opengl.GL11;


/**
 * 相机模式：取景框由 CameraApp.Overlay 画在 HUD 上；快门按下时读一次帧缓冲存 PNG。
 */
public final class CameraHandler {

    /** 置位后在下一帧 HUD 渲染末尾抓帧（那一帧不画取景框）。 */
    public static volatile boolean pendingCapture;

    private CameraHandler() {}

    public static void tryCapture() {
        pendingCapture = false;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            int w = mc.displayWidth;
            int h = mc.displayHeight;
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 3).order(ByteOrder.nativeOrder());
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
            int[] rgb = new int[w * h];
            for (int y = 0; y < h; y++) {
                int srcY = h - 1 - y;
                buf.position(srcY * w * 3);
                for (int x = 0; x < w; x++) {
                    int r = buf.get() & 0xFF;
                    int g = buf.get() & 0xFF;
                    int b = buf.get() & 0xFF;
                    rgb[y * w + x] = (r << 16) | (g << 8) | b;
                }
            }
            File saved = com.november.mcphone.client.PhotoStore.savePhoto(rgb, w, h);
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText("§7[MCphone] §a" + saved.getName()));
            }
        } catch (Throwable t) {
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText("§7[MCphone] §c拍照失败: " + t));
            }
        }
    }

    /** 必须是 public：FML ASM 事件代理跨包调用监听类，包私有在 Java17+ 下会抛 IllegalAccessError。 */
    public static class Overlay {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onRenderHud(net.minecraftforge.client.event.RenderGameOverlayEvent.Post event) {
            if (event.type != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL) return;
            if (!ClientHooks.isCameraMode()) return;
            if (pendingCapture) {
                tryCapture();
                return;
            }
            drawViewfinder();
        }
    }

    static void drawViewfinder() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int w = sr.getScaledWidth();
        int h = sr.getScaledHeight();
        int m = 20;
        int len = 24;
        int c = 0xFFFFFFFF;
        // 四角
        UiHelper.rect(m, m, len, 2, c);
        UiHelper.rect(m, m, 2, len, c);
        UiHelper.rect(w - m - len, m, len, 2, c);
        UiHelper.rect(w - m - 2, m, 2, len, c);
        UiHelper.rect(m, h - m - 2, len, 2, c);
        UiHelper.rect(m, h - m - len, 2, len, c);
        UiHelper.rect(w - m - len, h - m - 2, len, 2, c);
        UiHelper.rect(w - m - 2, h - m - len, 2, len, c);
        // 中心点
        UiHelper.rect(w / 2 - 1, h / 2 - 1, 2, 2, c);
        String hint = PhoneGui.tr("msg.mcphone.camera_hint");
        mc.fontRenderer.drawStringWithShadow(
            hint,
            (int) (w / 2F - mc.fontRenderer.getStringWidth(hint) / 2F),
            h - m + 6,
            0xFFFFFFCC);
    }
}
