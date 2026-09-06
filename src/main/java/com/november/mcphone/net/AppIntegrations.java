package com.november.mcphone.net;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

/**
 * 服务端集成动作。全部运行时反射/类名识别，无编译期依赖 AE2 与龙研。
 */
public final class AppIntegrations {

    private AppIntegrations() {}

    // ===================== 末影箱 =====================

    public static void openEnderChest(EntityPlayer player) {
        player.displayGUIChest(player.getInventoryEnderChest());
    }

    // ===================== AE2 无线终端 =====================

    /** 通过 AEApi 注册表打开无线终端。返回 null 表示成功，否则为错误消息。 */
    public static String openAe2Terminal(EntityPlayer player) {
        ItemStack found = findTerminal(player);
        if (found == null) {
            return "§7[MCphone] §c背包里没有 AE2 无线终端（或同类注册终端）。";
        }
        try {
            Class<?> aeApi = Class.forName("appeng.api.AEApi");
            Object api = aeApi.getMethod("instance").invoke(null);
            Object registries = api.getClass().getMethod("registries").invoke(api);
            Object wireless = registries.getClass().getMethod("wireless").invoke(registries);
            wireless.getClass()
                .getMethod("openWirelessTerminalGui", ItemStack.class, World.class, EntityPlayer.class)
                .invoke(wireless, found, player.worldObj, player);
            return null;
        } catch (ClassNotFoundException nf) {
            return "§7[MCphone] §c未检测到 AE2。";
        } catch (Throwable t) {
            return "§7[MCphone] §c打开失败: " + t;
        }
    }

    private static ItemStack findTerminal(EntityPlayer player) {
        try {
            Class<?> aeApi = Class.forName("appeng.api.AEApi");
            Object api = aeApi.getMethod("instance").invoke(null);
            Object registries = api.getClass().getMethod("registries").invoke(api);
            Object wireless = registries.getClass().getMethod("wireless").invoke(registries);
            java.lang.reflect.Method isTerm = wireless.getClass()
                .getMethod("isWirelessTerminal", ItemStack.class);
            for (ItemStack s : player.inventory.mainInventory) {
                if (s != null && (Boolean) isTerm.invoke(wireless, s)) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ===================== 龙研传送宝石 =====================

    private static final String CHARM_MK2 = "com.brandon3055.draconicevolution.common.items.tools.TeleporterMKII";
    private static final String CHARM_MK1 = "com.brandon3055.draconicevolution.common.items.tools.TeleporterMKI";

    /** 用背包里的传送宝石（Enhanced Charm of Dislocation 优先）执行一次传送。 */
    public static void teleportViaCharm(EntityPlayer player) {
        ItemStack mk1 = null;
        for (ItemStack s : player.inventory.mainInventory) {
            if (s == null || s.getItem() == null) continue;
            String cls = s.getItem().getClass().getName();
            if (cls.equals(CHARM_MK2)) {
                useCharm(player, s);
                return;
            }
            if (cls.equals(CHARM_MK1) && mk1 == null) mk1 = s;
        }
        if (mk1 != null) {
            useCharm(player, mk1);
            return;
        }
        player.addChatMessage(new ChatComponentText("§7[MCphone] §c背包里没有传送宝石（Charm of Dislocation）。"));
    }

    private static void useCharm(EntityPlayer player, ItemStack charm) {
        try {
            charm.getItem()
                .onItemRightClick(charm, player.worldObj, (EntityPlayerMP) player);
        } catch (Throwable t) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c传送失败: " + t));
        }
    }
}
