package com.november.mcphone.client;

import java.io.File;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.core.ItemPhone;

/**
 * 手机主界面：壁纸 + 状态栏 + 应用网格；打开 App 后显示 AppScreen 并提供返回键。
 */
public class PhoneGui extends GuiScreen {

    public static final int PW = 110;
    public static final int PH = 196;
    public static final int BEZEL = 5;
    public static final int STATUS_H = 10;

    public final ItemStack phoneStack;

    private int px;
    private int py;
    private int sx;
    private int sy;
    private int sw;
    private int sh;
    private int contentY;

    private IPhoneApp currentApp;
    private AppScreen screen;

    public PhoneGui(ItemStack stack) {
        this.phoneStack = stack;
    }

    @Override
    public void initGui() {
        px = width / 2 - PW / 2;
        py = height / 2 - PH / 2;
        sx = px + BEZEL;
        sy = py + BEZEL;
        sw = PW - BEZEL * 2;
        sh = PH - BEZEL * 2;
        contentY = sy + STATUS_H + 1;
        if (screen != null) screen.onOpened();
    }

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        drawDefaultBackground();

        UiHelper.roundedRect(px, py, PW, PH, 4, 0xF21C1C22);
        UiHelper.outline(px, py, PW, PH, 1, 0xFF3A3A42);
        UiHelper.rect(px + 2, py + PH + 1, 1, 8, 0xFF3A3A42);
        UiHelper.rect(px + PW - 3, py + PH + 1, 1, 8, 0xFF3A3A42);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        float fb = (float) mc.displayHeight / (float) sr.getScaledHeight();
        GL11.glScissor((int) (sx * fb), (int) (mc.displayHeight - (sy + sh) * fb), (int) (sw * fb), (int) (sh * fb));

        drawWallpaper();
        UiHelper.rect(sx, sy, sw, STATUS_H, 0x66000000);
        String time = TimeUtil.worldClock();
        fontRendererObj.drawStringWithShadow(time, sx + 3, sy + 2, 0xFFEAEAEA);
        String dev = ItemPhone.getDeviceName(phoneStack);
        if (dev != null && !dev.isEmpty()) {
            String d = UiHelper.ellipsize(fontRendererObj, dev, sw - 40);
            fontRendererObj.drawStringWithShadow(d, sx + sw - 3 - fontRendererObj.getStringWidth(d), sy + 2, 0xFF9FB8C8);
        }

        int areaY = contentY;
        int areaH = sy + sh - areaY;
        if (screen != null && currentApp != null) {
            drawBackButton(areaY);
            UiHelper.rect(sx, areaY + 12, sw, 1, 0x33FFFFFF);
            screen.render(sx, areaY + 13, sw, areaH - 13, mx, my, partialTicks);
        } else {
            drawGrid(areaY, areaH, mx, my);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        super.drawScreen(mx, my, partialTicks);
    }

    private void drawWallpaper() {
        if (PhotoStore.hasWallpaper()) {
            File wf = PhotoStore.wallpaperFile();
            int tex = UiHelper.loadImageTexture(wf);
            if (tex > 0) {
                UiHelper.drawImageCover(tex, sx, sy, sw, sh, UiHelper.texWidth(wf), UiHelper.texHeight(wf));
                return;
            }
        }
        UiHelper.gradient(sx, sy, sw, sh, 0xFF1E3C5A, 0xFF0E1B2E);
        UiHelper.roundedRect(sx + 22, sy + 60, sw - 44, 3, 1, 0x33FFFFFF);
        UiHelper.roundedRect(sx + 30, sy + 70, sw - 60, 3, 1, 0x22FFFFFF);
    }

    private void drawBackButton(int y) {
        UiHelper.rect(sx + 2, y + 1, 12, 11, 0x44FFFFFF);
        fontRendererObj.drawStringWithShadow("<", sx + 6, y + 3, 0xFFFFFFFF);
    }

    private void drawGrid(int areaY, int areaH, int mx, int my) {
        java.util.List<IPhoneApp> apps = PhoneApi.visibleApps();
        int cols = 3;
        int cellW = sw / cols;
        int cellH = 30;
        int icon = 18;
        for (int i = 0; i < apps.size() && i < cols * 5; i++) {
            int row = i / cols;
            int col = i % cols;
            int cx = sx + col * cellW + (cellW - icon) / 2;
            int cy = areaY + 4 + row * cellH;
            IPhoneApp app = apps.get(i);
            boolean hover = mx >= cx && mx < cx + icon && my >= cy && my < cy + icon;
            UiHelper.roundedRect(cx, cy, icon, icon, 4, app.iconColor());
            if (hover) UiHelper.outline(cx, cy, icon, icon, 1, 0x88FFFFFF);
            GL11.glPushMatrix();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor((int) (cx * factor()), (int) (mc.displayHeight - (cy + icon) * factor()), (int) (icon * factor()), (int) (icon * factor()));
            app.renderIcon(cx, cy, icon);
            GL11.glPopMatrix();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            String label = UiHelper.ellipsize(fontRendererObj, app.displayName(), cellW - 4);
            fontRendererObj.drawStringWithShadow(
                label,
                (int) (sx + col * cellW + (cellW - fontRendererObj.getStringWidth(label)) / 2F),
                cy + icon + 2,
                0xFFE6E6E6);
        }
    }

    private float factor() {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        return (float) mc.displayWidth / (float) sr.getScaledWidth();
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (screen != null && currentApp != null) {
            int areaY = contentY;
            if (mx >= sx + 2 && mx < sx + 14 && my >= areaY + 1 && my < areaY + 12) {
                closeApp();
                return;
            }
            screen.mouseClicked(sx, areaY + 13, sw, sy + sh - (areaY + 13), mx, my, button);
            return;
        }
        java.util.List<IPhoneApp> apps = PhoneApi.visibleApps();
        int cols = 3;
        int cellW = sw / cols;
        int cellH = 30;
        int icon = 18;
        for (int i = 0; i < apps.size() && i < cols * 5; i++) {
            int row = i / cols;
            int col = i % cols;
            int cx = sx + col * cellW + (cellW - icon) / 2;
            int cy = contentY + 4 + row * cellH;
            if (mx >= cx && mx < cx + icon && my >= cy && my < cy + icon) {
                openApp(apps.get(i));
                return;
            }
        }
        super.mouseClicked(mx, my, button);
    }

    public void openApp(IPhoneApp app) {
        if (screen != null && currentApp != null) screen.onClosed();
        currentApp = app;
        screen = app == null ? null : app.createScreen(this);
        if (screen != null) screen.onOpened();
    }

    public void closeApp() {
        if (screen != null) screen.onClosed();
        currentApp = null;
        screen = null;
    }

    /** 供 App 之间跳转（如相册设壁纸后返回设置）。 */
    public void openAppById(String id) {
        openApp(PhoneApi.byId(id));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            if (screen != null) {
                closeApp();
            } else {
                mc.displayGuiScreen(null);
            }
            return;
        }
        if (screen != null && screen.keyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        if (screen != null) screen.updateScreen();
    }

    @Override
    public void onGuiClosed() {
        if (screen != null) screen.onClosed();
        UiHelper.freeTextures();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
