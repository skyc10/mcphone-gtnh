package com.november.mcphone.net;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import com.november.mcphone.core.ItemPhone;

/**
 * 服务端集成动作。全部运行时反射/类名识别，无编译期依赖 AE2 与龙研。
 *
 * <p>传送：直接内置到手机（NBT 绑定点），不再依赖背包里的传送宝石。</p>
 * <p>AE2：把手机注册为无线终端（{@code IWirelessTermHandler} 的动态代理），
 * 之后 {@code openWirelessTerminalGui} 与 AE2 自家无线终端走完全相同的 GUI 路径；
 * 绑定方式：潜行 + 持手机右击 ME 安全站（写入安全站的 locatable key）。</p>
 */
public final class AppIntegrations {

    private AppIntegrations() {}

    // ===================== 末影箱 =====================

    public static void openEnderChest(EntityPlayer player) {
        player.displayGUIChest(player.getInventoryEnderChest());
    }

    // ===================== AE2 无线终端 =====================

    private static final String AE2_SECURITY_TILE = "appeng.tile.misc.TileSecurity";
    private static volatile Object ae2WirelessRegistry;

    /** postInit 调用：AE2 在场时把手机注册为无线终端。 */
    public static void registerAe2WirelessHandler() {
        try {
            Class<?> aeApi = Class.forName("appeng.api.AEApi");
            Object api = aeApi.getMethod("instance").invoke(null);
            Object registries = api.getClass().getMethod("registries").invoke(api);
            Object wireless = registries.getClass().getMethod("wireless").invoke(registries);
            Class<?> handlerIface = Class.forName("appeng.api.features.IWirelessTermHandler");
            Object handler = Proxy.newProxyInstance(
                AppIntegrations.class.getClassLoader(),
                new Class<?>[] { handlerIface },
                (proxy, method, args) -> invokeHandler(method, args));
            wireless.getClass()
                .getMethod("registerWirelessHandler", handlerIface)
                .invoke(wireless, handler);
            ae2WirelessRegistry = wireless;
            System.out.println("[mcphone] AE2 detected: phone registered as wireless terminal");
        } catch (ClassNotFoundException nf) {
            // AE2 未安装，静默跳过。
        } catch (Throwable t) {
            System.err.println("[mcphone] AE2 wireless registration failed: " + t);
        }
    }

    /** 手机作为无线终端的 handler 语义（反射调用）。 */
    private static Object invokeHandler(Method method, Object[] args) throws Exception {
        switch (method.getName()) {
            case "canHandle":
                return args[0] instanceof ItemStack && ((ItemStack) args[0]).getItem() == ItemPhone.INSTANCE;
            case "usePower":
            case "hasPower":
                return Boolean.TRUE; // 电力免费
            case "hasInfinityPower":
            case "hasInfinityRange":
                return Boolean.TRUE;
            case "getEncryptionKey":
                return args[0] instanceof ItemStack ? ItemPhone.getAe2Key((ItemStack) args[0]) : "";
            case "setEncryptionKey":
                if (args[0] instanceof ItemStack) {
                    ItemPhone.setAe2Key((ItemStack) args[0], args[1] == null ? "" : (String) args[1]);
                }
                return null;
            case "getConfigManager":
                return newConfigManager();
            default:
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt.isPrimitive() && rt != void.class) return 0;
                return null;
        }
    }

    private static Object newConfigManager() {
        try {
            Class<?> cls = Class.forName("appeng.util.ConfigManager");
            for (java.lang.reflect.Constructor<?> c : cls.getConstructors()) {
                if (c.getParameterTypes().length == 1) {
                    return c.newInstance(new Object[] { null });
                }
            }
            return cls.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通过 AE2 注册表打开手机无线终端；失败/未绑定由 AE2 自己发聊天提示。 */
    public static void openAe2Terminal(EntityPlayerMP player) {
        Object wireless = ae2WirelessRegistry;
        if (wireless == null) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c未检测到 AE2，无法打开 ME 终端。"));
            return;
        }
        ItemStack phone = findPhone(player);
        if (phone == null) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c背包里没有手机。"));
            return;
        }
        try {
            wireless.getClass()
                .getMethod("openWirelessTerminalGui", ItemStack.class, World.class, EntityPlayer.class)
                .invoke(wireless, phone, player.worldObj, player);
        } catch (Throwable t) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c打开 ME 终端失败: " + t));
        }
    }

    /** 潜行 + 持手机右击 ME 安全站：把安全站 locatable key 写入手机 NBT。 */
    public static void bindAe2SecurityStation(EntityPlayerMP player, ItemStack phone) {
        if (ae2WirelessRegistry == null) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c未检测到 AE2。"));
            return;
        }
        MovingObjectPosition hit = player.rayTrace(6.0D, 1.0F);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §7潜行右击 ME 安全站即可绑定（当前未对准方块）。"));
            return;
        }
        TileEntity te = player.worldObj.getTileEntity(hit.blockX, hit.blockY, hit.blockZ);
        if (te == null || !AE2_SECURITY_TILE.equals(te.getClass().getName())) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c对准的方块不是 ME 安全站。"));
            return;
        }
        try {
            Method key = te.getClass().getMethod("getLocatableSerial");
            long serial = (Long) key.invoke(te);
            ItemPhone.setAe2Key(phone, String.valueOf(serial));
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §a已绑定 ME 安全站（密钥 " + serial + "），AE2 终端 App 现已可用。"));
        } catch (Throwable t) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c绑定失败: " + t));
        }
    }

    /** 客户端图标用：找 AE2 无线终端物品（找不到返回 null → 退回字形图标）。 */
    public static ItemStack findWirelessTerminalIcon() {
        try {
            String[] names = { "appeng.items.tools.powered.ToolWirelessTerminal" };
            Iterator<Item> it = Item.itemRegistry.iterator();
            while (it.hasNext()) {
                Item item = it.next();
                for (String n : names) {
                    if (item.getClass().getName().equals(n)) return new ItemStack(item, 1, 0);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ===================== 内置传送 =====================

    /** 跨维度传送的待执行队列：travelToDimension 后下一 tick 落位。 */
    private static final CopyOnWriteArrayList<PendingTeleport> PENDING = new CopyOnWriteArrayList<>();
    private static boolean tickHookRegistered;

    /**
     * @param mode 0 = 传送到绑定点；1 = 绑定当前位置
     */
    public static void teleportViaPhone(EntityPlayerMP player, int mode) {
        ItemStack phone = findPhone(player);
        if (phone == null) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c背包里没有手机。"));
            return;
        }
        if (mode == 1) {
            ItemPhone.setTeleportTarget(phone,
                player.posX, player.posY, player.posZ,
                player.dimension, player.rotationYaw, player.rotationPitch);
            player.addChatMessage(new ChatComponentText("§7[MCphone] §a已绑定当前位置："
                + fmt(player.posX) + ", " + fmt(player.posY) + ", " + fmt(player.posZ)
                + "（维度 " + player.dimension + "）"));
            return;
        }
        if (!ItemPhone.hasTeleportTarget(phone)) {
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §e尚未绑定传送点：Shift+点击手机主屏的传送图标以绑定当前位置。"));
            return;
        }
        double x = ItemPhone.getTeleportX(phone);
        double y = ItemPhone.getTeleportY(phone);
        double z = ItemPhone.getTeleportZ(phone);
        int dim = ItemPhone.getTeleportDim(phone);
        float yaw = ItemPhone.getTeleportYaw(phone);
        float pitch = ItemPhone.getTeleportPitch(phone);
        try {
            if (dim != player.dimension) {
                scheduleCrossDim(player, x, y, z, dim, yaw, pitch);
                player.addChatMessage(new ChatComponentText(
                    "§7[MCphone] §a正在穿越到维度 " + dim + " …"));
            } else {
                player.setPositionAndUpdate(x, y, z);
                player.rotationYaw = yaw;
                player.rotationPitch = pitch;
                player.addChatMessage(new ChatComponentText("§7[MCphone] §a传送完成："
                    + fmt(x) + ", " + fmt(y) + ", " + fmt(z)));
            }
        } catch (Throwable t) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c传送失败: " + t));
        }
    }

    private static void scheduleCrossDim(EntityPlayerMP player, double x, double y, double z,
                                         int dim, float yaw, float pitch) {
        PENDING.add(new PendingTeleport(player.getCommandSenderName(), x, y, z, dim, yaw, pitch));
        if (!tickHookRegistered) {
            tickHookRegistered = true;
            cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new Object() {

                @cpw.mods.fml.common.eventhandler.SubscribeEvent
                public void onTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
                    if (event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
                    for (PendingTeleport p : PENDING) {
                        PENDING.remove(p);
                        EntityPlayerMP pl = p.resolve();
                        if (pl == null) continue;
                        if (pl.dimension != p.dim) {
                            pl.travelToDimension(p.dim);
                        }
                        pl.setPositionAndUpdate(p.x, p.y, p.z);
                        pl.rotationYaw = p.yaw;
                        pl.rotationPitch = p.pitch;
                        pl.addChatMessage(new ChatComponentText("§7[MCphone] §a传送完成："
                            + fmt(p.x) + ", " + fmt(p.y) + ", " + fmt(p.z)
                            + "（维度 " + pl.dimension + "）"));
                    }
                }
            });
        }
    }

    private static final class PendingTeleport {

        final String player;
        final double x;
        final double y;
        final double z;
        final int dim;
        final float yaw;
        final float pitch;

        PendingTeleport(String player, double x, double y, double z, int dim, float yaw, float pitch) {
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        EntityPlayerMP resolve() {
            for (Object o : cpw.mods.fml.common.FMLCommonHandler.instance()
                .getMinecraftServerInstance().getConfigurationManager().playerEntityList) {
                if (o instanceof EntityPlayerMP && ((EntityPlayerMP) o).getCommandSenderName().equals(player)) {
                    return (EntityPlayerMP) o;
                }
            }
            return null;
        }
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private static ItemStack findPhone(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() == ItemPhone.INSTANCE) return held;
        for (ItemStack s : player.inventory.mainInventory) {
            if (s != null && s.getItem() == ItemPhone.INSTANCE) return s;
        }
        return null;
    }
}
