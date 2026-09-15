package com.november.mcphone.client.scene;

import club.heiqi.uilib.ui.screen.McScreenBridge;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.november.mcphone.client.AppHotkey;
import com.november.mcphone.client.AppHotkeys;
import com.november.mcphone.client.PhoneCanvas;

/**
 * 手机 GuiScreen 桥接壳。
 *
 * <p>ESC 语义：App 页面打开时先回主屏，主屏时才关闭手机（交由 McScreenBridge 处理）。</p>
 *
 * <p>快捷键捕获：App 管理页进入捕获态后，本壳拦截下一次按键作为绑定（Esc 取消，
 * 重复按同主键清除），不再透传给场景树。</p>
 */
public class PhoneScreen extends McScreenBridge {

    private static final int KEY_ESCAPE = 1;

    public PhoneScreen(PhoneUi host) {
        super(null, host);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        PhoneUi ui = (PhoneUi) getSurface();
        if (PhoneUi.hotkeyCaptureTarget != null) {
            captureHotkey(ui, keyCode);
            return;
        }
        if (keyCode == KEY_ESCAPE && !ui.isHome()) {
            ui.backHome();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** 捕获态按键：Esc=取消；同主键重复=清除；其余=写入绑定并警告冲突。 */
    private void captureHotkey(PhoneUi ui, int keyCode) {
        String appId = PhoneUi.hotkeyCaptureTarget;
        PhoneUi.hotkeyCaptureTarget = null;
        if (keyCode == KEY_ESCAPE) {
            ui.rebuildPage();
            return;
        }
        AppHotkey current = AppHotkey.forApp(appId);
        boolean shift = AppHotkeys.shift();
        boolean ctrl = AppHotkeys.ctrl();
        boolean alt = AppHotkeys.alt();
        if (current != null && current.keyCode == keyCode
                && !shift && !ctrl && !alt) {
            // 重复按同一主键（无修饰键）：清除绑定。
            PhoneCanvas.setHotkey(appId, null);
        } else {
            AppHotkey binding = AppHotkey.capture(keyCode, shift, ctrl, alt);
            PhoneCanvas.setHotkey(appId, binding.serialize());
            AppHotkeys.warnIfConflicts(Minecraft.getMinecraft(), binding, appId);
        }
        ui.rebuildPage();
    }
}
