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
        net.minecraft.client.gui.Gui.drawRect(m, m, m + len, m + 2, c);
        net.minecraft.client.gui.Gui.drawRect(m, m, m + 2, m + len, c);
        net.minecraft.client.gui.Gui.drawRect(w - m - len, m, w - m, m + 2, c);
        net.minecraft.client.gui.Gui.drawRect(w - m - 2, m, w - m, m + len, c);
        net.minecraft.client.gui.Gui.drawRect(m, h - m - 2, m + len, h - m, c);
        net.minecraft.client.gui.Gui.drawRect(m, h - m - len, m + 2, h - m, c);
        net.minecraft.client.gui.Gui.drawRect(w - m - len, h - m - 2, w - m, h - m, c);
        net.minecraft.client.gui.Gui.drawRect(w - m - 2, h - m - len, w - m, h - m, c);
        // 中心点
        net.minecraft.client.gui.Gui.drawRect(w / 2 - 1, h / 2 - 1, w / 2 + 1, h / 2 + 1, c);
        // 提示条：热栏（底部 22px）上方，带半透明底避免被场景吃掉可读性。
        String hint = net.minecraft.util.StatCollector.translateToLocal("msg.mcphone.camera_hint");
        int tw = mc.fontRenderer.getStringWidth(hint);
        int stripH = 12;
        int stripY = h - 22 - stripH - 4;
        net.minecraft.client.gui.Gui.drawRect(w / 2 - tw / 2 - 5, stripY, w / 2 + tw / 2 + 5, stripY + stripH, 0x88000000);
        mc.fontRenderer.drawStringWithShadow(hint, (int) (w / 2F - tw / 2F), stripY + 2, 0xFFFFFFCC);
    }
}
