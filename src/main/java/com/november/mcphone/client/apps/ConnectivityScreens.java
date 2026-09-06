package com.november.mcphone.client.apps;

import net.minecraft.item.ItemStack;

import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.PhoneGui;
import com.november.mcphone.client.UiHelper;
import com.november.mcphone.net.NetworkHandler;

/**
 * 传送（龙研传送宝石）与 AE2 终端两个 App。
 */
public final class ConnectivityScreens {

    private ConnectivityScreens() {}

    // ===================== 传送 =====================

    private static final String CHARM_MK1 = "com.brandon3055.draconicevolution.common.items.tools.TeleporterMKI";
    private static final String CHARM_MK2 = "com.brandon3055.draconicevolution.common.items.tools.TeleporterMKII";

    public static class TeleportScreen extends AppScreen {

        TeleportScreen(PhoneGui gui) {
            super(gui);
        }

        private ItemStack findCharm() {
            if (mc().thePlayer == null) return null;
            ItemStack mk1 = null;
            for (ItemStack s : mc().thePlayer.inventory.mainInventory) {
                if (s == null || s.getItem() == null) continue;
                String cls = s.getItem().getClass().getName();
                if (cls.equals(CHARM_MK2)) return s;
                if (cls.equals(CHARM_MK1) && mk1 == null) mk1 = s;
            }
            return mk1;
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            ItemStack charm = findCharm();
            String status;
            int color;
            if (charm == null) {
                status = PhoneGui.tr("msg.mcphone.no_charm");
                color = 0xFFFF9C9C;
            } else {
                status = PhoneGui.tr("msg.mcphone.charm_ready");
                color = 0xFF9CE89C;
                if (charm.hasTagCompound() && charm.getTagCompound().hasKey("Locations")) {
                    try {
                        String loc = charm.getTagCompound().getString("Locations");
                        int count = loc.split("\"name\"").length - 1;
                        status += " (" + count + ")";
                    } catch (Throwable ignored) {}
                }
            }
            UiHelper.roundedRect(sx + 8, sy + 8, sw - 16, 22, 4, 0x55000000);
            UiHelper.scaledText(font(), status, sx + sw / 2F, sy + 17, 1F, color, false);
            UiHelper.scaledText(
                font(),
                PhoneGui.tr("msg.mcphone.charm_hint"),
                sx + sw / 2F,
                sy + 36,
                1F,
                0xFFBFD4E6,
                false);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.teleport"), sx + 20, sy + 52, sw - 40, 16, mx, my);
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            if (Widgets.hit(mx, my, sx + 20, sy + 52, sw - 40, 16)) {
                NetworkHandler.sendToServer(new NetworkHandler.Teleport());
            }
        }
    }

    // ===================== AE2 终端 =====================

    public static class Ae2Screen extends AppScreen {

        Ae2Screen(PhoneGui gui) {
            super(gui);
        }

        private static boolean ae2Loaded() {
            try {
                Class.forName("appeng.api.AEApi");
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private ItemStack findTerminal() {
            if (!ae2Loaded() || mc().thePlayer == null) return null;
            try {
                Object api = Class.forName("appeng.api.AEApi").getMethod("instance").invoke(null);
                Object registries = api.getClass().getMethod("registries").invoke(api);
                Object wireless = registries.getClass().getMethod("wireless").invoke(registries);
                java.lang.reflect.Method isTerm = wireless.getClass()
                    .getMethod("isWirelessTerminal", ItemStack.class);
                for (ItemStack s : mc().thePlayer.inventory.mainInventory) {
                    if (s != null && (Boolean) isTerm.invoke(wireless, s)) return s;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            String status;
            int color;
            if (!ae2Loaded()) {
                status = PhoneGui.tr("msg.mcphone.no_ae2");
                color = 0xFFFF9C9C;
            } else if (findTerminal() == null) {
                status = PhoneGui.tr("msg.mcphone.no_wireless");
                color = 0xFFFFC860;
            } else {
                status = PhoneGui.tr("msg.mcphone.wireless_ready");
                color = 0xFF9CE89C;
            }
            UiHelper.roundedRect(sx + 8, sy + 8, sw - 16, 22, 4, 0x55000000);
            UiHelper.scaledText(font(), status, sx + sw / 2F, sy + 17, 1F, color, false);
            UiHelper.scaledText(
                font(),
                PhoneGui.tr("msg.mcphone.ae2_hint"),
                sx + sw / 2F,
                sy + 36,
                1F,
                0xFFBFD4E6,
                false);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.open_terminal"), sx + 15, sy + 52, sw - 30, 16, mx, my);
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            if (Widgets.hit(mx, my, sx + 15, sy + 52, sw - 30, 16)) {
                NetworkHandler.sendToServer(new NetworkHandler.OpenAe2());
            }
        }
    }
}
