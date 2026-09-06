package com.november.mcphone.client.scene;

import club.heiqi.uilib.ui.screen.McScreenBridge;

/**
 * 手机 GuiScreen 桥接壳。
 *
 * <p>ESC 语义：App 页面打开时先回主屏，主屏时才关闭手机（交由 McScreenBridge 处理）。</p>
 */
public class PhoneScreen extends McScreenBridge {

    private static final int KEY_ESCAPE = 1;

    public PhoneScreen(PhoneUi host) {
        super(null, host);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        PhoneUi ui = (PhoneUi) getSurface();
        if (keyCode == KEY_ESCAPE && !ui.isHome()) {
            ui.backHome();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }
}
