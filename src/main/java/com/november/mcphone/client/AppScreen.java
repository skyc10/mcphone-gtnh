package com.november.mcphone.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/**
 * App 界面基类。渲染与交互发生在手机屏幕区域 (sx, sy, sw, sh) 内。
 * 鼠标坐标已换算为屏幕绝对坐标。
 */
public abstract class AppScreen {

    protected final PhoneGui gui;

    protected AppScreen(PhoneGui gui) {
        this.gui = gui;
    }

    public void onOpened() {}

    public void onClosed() {}

    public abstract void render(int sx, int sy, int sw, int sh, int mx, int my, float partialTicks);

    public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {}

    public boolean keyTyped(char typedChar, int keyCode) {
        return false;
    }

    public void updateScreen() {}

    protected static Minecraft mc() {
        return net.minecraft.client.Minecraft.getMinecraft();
    }

    public static FontRenderer font() {
        return net.minecraft.client.Minecraft.getMinecraft().fontRenderer;
    }
}
