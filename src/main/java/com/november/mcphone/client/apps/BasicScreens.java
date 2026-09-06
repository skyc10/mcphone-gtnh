package com.november.mcphone.client.apps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;

import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.NotesStore;
import com.november.mcphone.client.PhoneGui;
import com.november.mcphone.client.TimeUtil;
import com.november.mcphone.client.UiHelper;
import com.november.mcphone.net.NetworkHandler;

/**
 * 时钟 / 天气 / 末影箱 / 便签 四个基础 App 的界面。
 */
public final class BasicScreens {

    private BasicScreens() {}

    // ===================== 时钟 =====================

    public static class ClockScreen extends AppScreen {

        ClockScreen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            int boxH = 40;
            UiHelper.roundedRect(sx + 8, sy + 10, sw - 16, boxH, 4, 0x55000000);
            UiHelper.scaledText(font(), TimeUtil.worldClock(), sx + sw / 2F, sy + 10 + (boxH - 16) / 2F, 2F, 0xFFFFFFFF, false);
            UiHelper.scaledText(
                font(),
                StatCollector.translateToLocalFormatted("msg.mcphone.day", TimeUtil.worldDay()),
                sx + sw / 2F,
                sy + 58,
                1F,
                0xFFBFD4E6,
                false);
        }
    }

    // ===================== 天气 =====================

    public static class WeatherScreen extends AppScreen {

        WeatherScreen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            Minecraft mc = mc();
            String state;
            int color;
            if (mc.theWorld != null && mc.theWorld.isThundering()) {
                state = PhoneGui.tr("weather.mcphone.thunder");
                color = 0xFFFFC860;
            } else if (mc.theWorld != null && mc.theWorld.isRaining()) {
                state = PhoneGui.tr("weather.mcphone.rain");
                color = 0xFF7FB2E8;
            } else {
                state = PhoneGui.tr("weather.mcphone.clear");
                color = 0xFF8FE88F;
            }
            UiHelper.roundedRect(sx + 8, sy + 10, sw - 16, 34, 4, 0x55000000);
            UiHelper.scaledText(font(), state, sx + sw / 2F, sy + 22, 1.6F, color, false);

            if (mc.theWorld != null && mc.thePlayer != null) {
                net.minecraft.world.biome.BiomeGenBase biome = mc.theWorld
                    .getBiomeGenForCoords(
                        MathHelper.floor_double(mc.thePlayer.posX),
                        MathHelper.floor_double(mc.thePlayer.posZ));
                float temp = biome.getFloatTemperature(
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.posY),
                    MathHelper.floor_double(mc.thePlayer.posZ));
                String tempStr;
                if (temp < 0.15F) tempStr = PhoneGui.tr("temp.mcphone.cold");
                else if (temp > 0.95F) tempStr = PhoneGui.tr("temp.mcphone.hot");
                else tempStr = PhoneGui.tr("temp.mcphone.temperate");
                UiHelper.scaledText(
                    font(),
                    StatCollector.translateToLocal("msg.mcphone.biome") + biome.biomeName,
                    sx + 12,
                    sy + 56,
                    1F,
                    0xFFEAEAEA,
                    false);
                UiHelper.scaledText(
                    font(),
                    StatCollector.translateToLocal("msg.mcphone.temp") + tempStr,
                    sx + 12,
                    sy + 70,
                    1F,
                    0xFFEAEAEA,
                    false);
            }
        }
    }

    // ===================== 末影箱 =====================

    public static class EnderChestScreen extends AppScreen {

        EnderChestScreen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            UiHelper.scaledText(
                font(),
                PhoneGui.tr("msg.mcphone.enderchest_hint"),
                sx + sw / 2F,
                sy + 18,
                1F,
                0xFFBFD4E6,
                false);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.open"), sx + 25, sy + 40, sw - 50, 16, mx, my);
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            if (Widgets.hit(mx, my, sx + 25, sy + 40, sw - 50, 16)) {
                NetworkHandler.sendToServer(new NetworkHandler.OpenEnderChest());
            }
        }
    }

    // ===================== 便签 =====================

    public static class NotesScreen extends AppScreen {

        private enum Mode {

            LIST,
            EDIT
        }

        private Mode mode = Mode.LIST;
        private java.util.List<NotesStore.Note> notes = new java.util.ArrayList<>();
        private NotesStore.Note editing;
        private GuiTextField titleField;
        private GuiTextField bodyField;
        private int lastSx;
        private int lastSy;
        private int lastSw;
        private boolean made;

        NotesScreen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void onOpened() {
            refresh();
        }

        private void refresh() {
            notes = NotesStore.list();
        }

        private void ensureFields(int sx, int sy, int sw) {
            if (!made || lastSx != sx || lastSy != sy || lastSw != sw) {
                titleField = new GuiTextField(font(), sx + 10, sy + 16, sw - 20, 12);
                titleField.setMaxStringLength(24);
                bodyField = new GuiTextField(font(), sx + 10, sy + 40, sw - 20, 12);
                bodyField.setMaxStringLength(64);
                if (editing != null && made) {
                    titleField.setText(editing.title);
                    bodyField.setText(editing.body);
                }
                if (made && titleField != null) {
                    titleField.setFocused(true);
                }
                made = true;
                lastSx = sx;
                lastSy = sy;
                lastSw = sw;
            }
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            if (mode == Mode.EDIT) {
                ensureFields(sx, sy, sw);
                font().drawStringWithShadow(PhoneGui.tr("label.mcphone.title"), sx + 10, sy + 6, 0xFFBFD4E6);
                UiHelper.outline(sx + 8, sy + 14, sw - 16, 16, 1, 0x55FFFFFF);
                titleField.drawTextBox();
                font().drawStringWithShadow(PhoneGui.tr("label.mcphone.body"), sx + 10, sy + 32, 0xFFBFD4E6);
                UiHelper.outline(sx + 8, sy + 38, sw - 16, 16, 1, 0x55FFFFFF);
                bodyField.drawTextBox();
                Widgets.drawButton(PhoneGui.tr("btn.mcphone.save"), sx + 10, sy + sh - 20, 34, 14, mx, my);
                Widgets.drawButton(PhoneGui.tr("btn.mcphone.cancel"), sx + 50, sy + sh - 20, 34, 14, mx, my);
                return;
            }
            Widgets.drawButton("+ " + PhoneGui.tr("btn.mcphone.new_note"), sx + 10, sy + 2, sw - 20, 14, mx, my);
            int y = sy + 22;
            for (NotesStore.Note n : notes) {
                if (y + 16 > sy + sh) break;
                boolean hover = Widgets.hit(mx, my, sx + 8, y, sw - 24, 15);
                UiHelper.roundedRect(sx + 8, y, sw - 24, 15, 2, hover ? 0x556FB2E8 : 0x44000000);
                font().drawStringWithShadow(
                    UiHelper.ellipsize(font(), n.title.isEmpty() ? n.body : n.title, sw - 46),
                    sx + 12,
                    y + 4,
                    0xFFEAEAEA);
                font().drawStringWithShadow("×", sx + sw - 20, y + 4, 0xFFFF8080);
                y += 17;
            }
            if (notes.isEmpty()) {
                font().drawStringWithShadow(PhoneGui.tr("msg.mcphone.no_notes"), sx + 10, y + 4, 0xFF8899AA);
            }
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            if (mode == Mode.EDIT) {
                ensureFields(sx, sy, sw);
                titleField.mouseClicked(mx, my, button);
                bodyField.mouseClicked(mx, my, button);
                if (Widgets.hit(mx, my, sx + 10, sy + sh - 20, 34, 14)) {
                    editing.title = titleField.getText();
                    editing.body = bodyField.getText();
                    NotesStore.save(editing);
                    mode = Mode.LIST;
                    refresh();
                } else if (Widgets.hit(mx, my, sx + 50, sy + sh - 20, 34, 14)) {
                    mode = Mode.LIST;
                }
                return;
            }
            if (Widgets.hit(mx, my, sx + 10, sy + 2, sw - 20, 14)) {
                editing = new NotesStore.Note();
                editing.title = "";
                editing.body = "";
                ensureFields(sx, sy, sw);
                titleField.setText("");
                bodyField.setText("");
                titleField.setFocused(true);
                mode = Mode.EDIT;
                return;
            }
            int y = sy + 22;
            for (int i = 0; i < notes.size(); i++) {
                NotesStore.Note n = notes.get(i);
                if (y + 16 > sy + sh) break;
                if (Widgets.hit(mx, my, sx + sw - 24, y, 12, 15)) {
                    NotesStore.delete(n);
                    refresh();
                    return;
                }
                if (Widgets.hit(mx, my, sx + 8, y, sw - 24, 15)) {
                    editing = n;
                    ensureFields(sx, sy, sw);
                    titleField.setText(n.title);
                    bodyField.setText(n.body);
                    titleField.setFocused(true);
                    mode = Mode.EDIT;
                    return;
                }
                y += 17;
            }
        }

        @Override
        public boolean keyTyped(char c, int code) {
            if (mode != Mode.EDIT) return false;
            if (titleField != null && titleField.isFocused()) {
                if (titleField.textboxKeyTyped(c, code)) return true;
                if (code == 200 || code == 208) {
                    titleField.setFocused(false);
                    bodyField.setFocused(true);
                    return true;
                }
            }
            if (bodyField != null && bodyField.isFocused()) {
                if (bodyField.textboxKeyTyped(c, code)) return true;
                if (code == 200 || code == 208) {
                    bodyField.setFocused(false);
                    titleField.setFocused(true);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void updateScreen() {
            if (titleField != null) titleField.updateCursorCounter();
            if (bodyField != null) bodyField.updateCursorCounter();
        }
    }
}
