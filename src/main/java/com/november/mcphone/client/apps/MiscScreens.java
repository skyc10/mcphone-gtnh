package com.november.mcphone.client.apps;

import java.util.List;

import net.minecraft.client.gui.GuiTextField;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.PhoneGui;
import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.UiHelper;
import com.november.mcphone.core.ItemPhone;
import com.november.mcphone.net.NetworkHandler;

/**
 * 设置 / 相机 / 应用管理。
 */
public final class MiscScreens {

    private MiscScreens() {}

    // ===================== 设置 =====================

    public static class SettingsScreen extends AppScreen {

        private GuiTextField nameField;
        private boolean made;
        private String toast;
        private long toastUntil;

        SettingsScreen(PhoneGui gui) {
            super(gui);
        }

        private void ensureField(int sx, int sy, int sw) {
            if (!made) {
                nameField = new GuiTextField(font(), sx + 10, sy + 18, sw - 20, 12);
                String cur = ItemPhone.getDeviceName(gui.phoneStack);
                nameField.setText(cur == null ? "" : cur);
                nameField.setMaxStringLength(24);
                made = true;
            }
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            ensureField(sx, sy, sw);
            font().drawStringWithShadow(PhoneGui.tr("label.mcphone.device_name"), sx + 10, sy + 6, 0xFFBFD4E6);
            UiHelper.outline(sx + 8, sy + 16, sw - 16, 16, 1, 0x55FFFFFF);
            nameField.drawTextBox();
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.save"), sx + 10, sy + 38, sw - 20, 14, mx, my);

            font().drawStringWithShadow(PhoneGui.tr("label.mcphone.wallpaper"), sx + 10, sy + 60, 0xFFBFD4E6);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.reset_wallpaper"), sx + 10, sy + 72, sw - 20, 14, mx, my);
            font().drawStringWithShadow(PhoneGui.tr("msg.mcphone.wallpaper_hint"), sx + 10, sy + 92, 0xFF8899AA);

            font().drawStringWithShadow(PhoneGui.tr("msg.mcphone.about"), sx + 10, sy + sh - 22, 0xFF667788);
            drawToast(sx, sy, sw, sh);
        }

        private void drawToast(int sx, int sy, int sw, int sh) {
            if (toast != null && System.currentTimeMillis() < toastUntil) {
                font().drawStringWithShadow(toast, sx + 10, sy + sh - 34, 0xFF9CE89C);
            }
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            ensureField(sx, sy, sw);
            nameField.mouseClicked(mx, my, button);
            if (Widgets.hit(mx, my, sx + 10, sy + 38, sw - 20, 14)) {
                NetworkHandler.sendToServer(new NetworkHandler.SetDeviceName(nameField.getText()));
                toast = PhoneGui.tr("msg.mcphone.saved");
                toastUntil = System.currentTimeMillis() + 1500;
            } else if (Widgets.hit(mx, my, sx + 10, sy + 72, sw - 20, 14)) {
                PhotoStore.clearWallpaper();
                toast = PhoneGui.tr("msg.mcphone.wallpaper_reset");
                toastUntil = System.currentTimeMillis() + 1500;
            }
        }

        @Override
        public boolean keyTyped(char c, int code) {
            return nameField != null && nameField.textboxKeyTyped(c, code);
        }

        @Override
        public void updateScreen() {
            if (nameField != null) nameField.updateCursorCounter();
        }
    }

    // ===================== 相机 =====================

    public static class CameraScreen extends AppScreen {

        CameraScreen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            UiHelper.scaledText(
                font(),
                PhoneGui.tr("msg.mcphone.camera_desc"),
                sx + sw / 2F,
                sy + 16,
                1F,
                0xFFBFD4E6,
                false);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.enter_camera"), sx + 15, sy + 40, sw - 30, 16, mx, my);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.gallery"), sx + 15, sy + 62, sw - 30, 14, mx, my);
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            if (Widgets.hit(mx, my, sx + 15, sy + 40, sw - 30, 16)) {
                net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(null);
                ClientHooks.setCameraMode(true);
            } else if (Widgets.hit(mx, my, sx + 15, sy + 62, sw - 30, 14)) {
                gui.openAppById("gallery");
            }
        }
    }

    // ===================== 应用管理 =====================

    public static class AppManagerScreen extends AppScreen {

        private java.util.Collection<IPhoneApp> apps;

        AppManagerScreen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void onOpened() {
            apps = PhoneApi.apps();
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            int y = sy + 4;
            for (IPhoneApp app : apps) {
                if (y + 14 > sy + sh) break;
                boolean enabled = PhoneCanvas.isAppEnabled(app.id());
                boolean hover = Widgets.hit(mx, my, sx + 8, y, sw - 16, 13);
                UiHelper.roundedRect(sx + 8, y, sw - 16, 13, 2, hover ? 0x446FB2E8 : 0x33000000);
                font().drawStringWithShadow(
                    UiHelper.ellipsize(font(), app.displayName(), sw - 46),
                    sx + 12,
                    y + 3,
                    0xFFEAEAEA);
                boolean boxHover = Widgets.hit(mx, my, sx + sw - 20, y + 2, 9, 9);
                UiHelper.outline(sx + sw - 20, y + 2, 9, 9, 1, boxHover ? 0xFF6FB2E8 : 0x88FFFFFF);
                if (enabled) UiHelper.rect(sx + sw - 18, y + 4, 5, 5, 0xFF6FB2E8);
                y += 15;
            }
            font().drawStringWithShadow(PhoneGui.tr("msg.mcphone.addon_hint"), sx + 10, sy + sh - 10, 0xFF667788);
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            int y = sy + 4;
            for (IPhoneApp app : apps) {
                if (y + 14 > sy + sh) break;
                if (Widgets.hit(mx, my, sx + 8, y, sw - 16, 13)) {
                    boolean enabled = PhoneCanvas.isAppEnabled(app.id());
                    PhoneCanvas.setAppEnabled(app.id(), !enabled);
                    return;
                }
                y += 15;
            }
        }
    }
}
