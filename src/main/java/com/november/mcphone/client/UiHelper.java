package com.november.mcphone.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.GL11;

/**
 * 2D 绘制小工具：矩形、渐变、圆角矩形、缩放文字、动态贴图。
 * 全部走 GL11 立即模式，1.7.10 无 GlStateManager。
 */
public final class UiHelper {

    private static final Map<String, TexEntry> texCache = new HashMap<>();

    private static final class TexEntry {

        int id;
        long stamp;
        int w;
        int h;
    }

    private UiHelper() {}

    public static void color(int argb) {
        float a = (argb >>> 24) & 0xFF;
        float r = (argb >>> 16) & 0xFF;
        float g = (argb >>> 8) & 0xFF;
        float b = argb & 0xFF;
        GL11.glColor4f(r / 255F, g / 255F, b / 255F, a / 255F);
    }

    public static void beginOverlay(boolean texture) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        if (!texture) GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    public static void endOverlay(boolean texture) {
        if (!texture) GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glPopAttrib();
    }

    /** 纯色矩形（ARGB，支持半透明）。 */
    public static void rect(float x, float y, float w, float h, int argb) {
        if ((argb >>> 24) == 0) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        color(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** 垂直渐变矩形。 */
    public static void gradient(float x, float y, float w, float h, int top, int bottom) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glBegin(GL11.GL_QUADS);
        color(top);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        color(bottom);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** 近似圆角矩形：矩形 + 四角阶梯。 */
    public static void roundedRect(float x, float y, float w, float h, float r, int argb) {
        rect(x + r, y, w - 2 * r, h, argb);
        rect(x, y + r, w, h - 2 * r, argb);
        float r2 = r * 0.5F;
        for (int i = 0; i < 2; i++) {
            float cx = (i == 0) ? x + r2 : x + w - r - r2;
            float cy = (i == 0) ? y + r2 : y + h - r - r2;
            rect(cx, cy, r + r2, r + r2, argb);
        }
    }

    /** 简单边框（四条边，适合小控件）。 */
    public static void outline(float x, float y, float w, float h, float t, int argb) {
        rect(x, y, w, t, argb);
        rect(x, y + h - t, w, t, argb);
        rect(x, y + t, t, h - 2 * t, argb);
        rect(x + w - t, y + t, t, h - 2 * t, argb);
    }

    /** 缩放文字（s>1 放大）。 */
    public static void scaledText(FontRenderer fr, String s, float x, float y, float scale, int color, boolean centered) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        if (centered) {
            int w = fr.getStringWidth(s);
            fr.drawStringWithShadow(s, (int) (-w / 2F), 0, color);
        } else {
            fr.drawStringWithShadow(s, 0, 0, color);
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** 省略号截断。 */
    public static String ellipsize(FontRenderer fr, String s, int maxW) {
        if (fr.getStringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fr.getStringWidth(s + "...") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    // ===================== 动态贴图（文件图片） =====================

    /**
     * 加载/缓存一张图片贴图；stamp 用于文件变更后自动重载。返回贴图 id，-1 表示失败。
     */
    public static int loadImageTexture(File f) {
        if (f == null || !f.isFile()) return -1;
        String key = f.getAbsolutePath();
        TexEntry e = texCache.get(key);
        long stamp = f.lastModified();
        if (e == null || e.stamp != stamp) {
            try {
                BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) return -1;
                if (e == null) {
                    e = new TexEntry();
                    texCache.put(key, e);
                } else {
                    TextureUtil.deleteTexture(e.id);
                }
                e.id = TextureUtil.uploadTextureImageAllocate(GL11.glGenTextures(), img, true, true);
                e.stamp = stamp;
                e.w = img.getWidth();
                e.h = img.getHeight();
            } catch (Exception ex) {
                return -1;
            }
        }
        return e.id;
    }

    public static int texWidth(File f) {
        TexEntry e = texCache.get(f.getAbsolutePath());
        return e == null ? 1 : e.w;
    }

    public static int texHeight(File f) {
        TexEntry e = texCache.get(f.getAbsolutePath());
        return e == null ? 1 : e.h;
    }

    /** 等比 cover 绘制：填满 (x,y,w,h)，超出部分裁剪。 */
    public static void drawImageCover(int texId, float x, float y, float w, float h, int texW, int texH) {
        if (texId <= 0 || texW <= 0 || texH <= 0) return;
        float scale = Math.max(w / texW, h / texH);
        float uw = w / scale / texW;
        float vh = h / scale / texH;
        float u0 = (1F - uw) / 2F;
        float v0 = (1F - vh) / 2F;
        drawTexQuad(texId, x, y, w, h, u0, v0, u0 + uw, v0 + vh);
    }

    /** 等比 contain 绘制：完整显示，不足处留空。返回实际绘制区域。 */
    public static float[] drawImageContain(int texId, float x, float y, float w, float h, int texW, int texH) {
        if (texId <= 0 || texW <= 0 || texH <= 0) return null;
        float scale = Math.min(w / texW, h / texH);
        float dw = texW * scale;
        float dh = texH * scale;
        float dx = x + (w - dw) / 2F;
        float dy = y + (h - dh) / 2F;
        drawTexQuad(texId, dx, dy, dw, dh, 0, 0, 1, 1);
        return new float[] { dx, dy, dw, dh };
    }

    private static void drawTexQuad(int texId, float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** 清空贴图缓存（关掉手机界面时调用）。 */
    public static void freeTextures() {
        Iterator<Entry<String, TexEntry>> it = texCache.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, TexEntry> e = it.next();
            TextureUtil.deleteTexture(e.getValue().id);
            it.remove();
        }
    }
}
