package com.november.mcphone.net;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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

    /**
     * 构造 AE2 ConfigManager 并注册终端必需的三个标准设置项。
     *
     * <p>缺失这些项时 ContainerMEMonitorable 第一帧 tick 调 getSetting(VIEW_MODE) 会抛
     * IllegalStateException 崩掉集成服务端（且 session.lock 不释放导致存档暂时进不去），
     * 与 AE2 ToolWirelessTerminal.getConfigManager 的注册集保持一致。</p>
     */
    private static Object newConfigManager() {
        try {
            Class<?> cls = Class.forName("appeng.util.ConfigManager");
            Object cm = null;
            for (java.lang.reflect.Constructor<?> c : cls.getConstructors()) {
                if (c.getParameterTypes().length == 1) {
                    cm = c.newInstance(new Object[] { null });
                    break;
                }
            }
            if (cm == null) cm = cls.newInstance();
            Class<?> settings = Class.forName("appeng.api.config.Settings");
            Method reg = cm.getClass().getMethod("registerSetting", settings, Enum.class);
            reg.invoke(cm, settings.getField("SORT_BY").get(null), cfgEnum("appeng.api.config.SortOrder", "NAME"));
            reg.invoke(cm, settings.getField("VIEW_MODE").get(null), cfgEnum("appeng.api.config.ViewItems", "ALL"));
            reg.invoke(cm, settings.getField("SORT_DIRECTION").get(null), cfgEnum("appeng.api.config.SortDir", "ASCENDING"));
            return cm;
        } catch (Throwable t) {
            System.err.println("[mcphone] AE2 ConfigManager init failed: " + t);
            return null;
        }
    }

    private static Enum<?> cfgEnum(String cls, String name) throws Exception {
        return (Enum<?>) Class.forName(cls).getField(name).get(null);
    }

    /**
     * 打开手机上的 ME 终端，三级优先：
     * 1. ae2fc 通用无线终端 UWT（虚拟栈 + AE2 官方路由，功能最全：物品/流体/样板/请求/接口）；
     * 2. WCT 无线合成终端（虚拟栈 + 自家右击路径）；
     * 3. 手机内置基础无线终端（自身 handler 注册路径）。
     */
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
        String key = ItemPhone.getAe2Key(phone);
        if (key.isEmpty()) {
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §e尚未绑定：请潜行 + 持手机右击 ME 安全站完成绑定。"));
            return;
        }
        // 1) ae2fc 通用无线终端（继承 AE2 ToolWirelessTerminal，走官方路由即可打开完整 GUI）
        Item uwt = findUltraTerminalItem();
        if (uwt != null) {
            try {
                ItemStack virtual = new ItemStack(uwt);
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("key", key);
                tag.setDouble("internalCurrentPower", 1.0E9D);
                tag.setDouble("internalMaxPower", 1.0E9D);
                tag.setInteger("infinityBoosterCard", 1);
                tag.setInteger("InfinityEnergyCard", 1);
                virtual.setTagCompound(tag);
                wireless.getClass()
                    .getMethod("openWirelessTerminalGui", ItemStack.class, World.class, EntityPlayer.class)
                    .invoke(wireless, virtual, player.worldObj, player);
                return;
            } catch (Throwable t) {
                player.addChatMessage(new ChatComponentText(
                    "§7[MCphone] §c通用无线终端打开失败，尝试 WCT: " + t));
            }
        }
        // 2) WCT 无线合成终端
        Item wct = findWctItem();
        if (wct != null) {
            try {
                ItemStack virtual = buildVirtualWctStack(key);
                wct.onItemRightClick(virtual, player.worldObj, player);
                return;
            } catch (Throwable t) {
                player.addChatMessage(new ChatComponentText(
                    "§7[MCphone] §cWCT 终端打开失败，回退基础终端: " + t));
            }
        } else {
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §7未检测到通用终端/WCT，使用基础无线终端（仅物品终端）。"));
        }
        // 3) 基础无线终端（手机自身 handler 路径）
        try {
            wireless.getClass()
                .getMethod("openWirelessTerminalGui", ItemStack.class, World.class, EntityPlayer.class)
                .invoke(wireless, phone, player.worldObj, player);
        } catch (Throwable t) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c打开 ME 终端失败: " + t));
        }
    }

    // ===================== ae2fc 通用无线终端（UWT） =====================

    private static final String UWT_ITEM_CLASS =
        "com.glodblock.github.common.item.ItemWirelessUltraTerminal";
    private static Item uwtItem;
    private static boolean uwtResolved;

    /** 懒查找 ae2fc 通用无线终端物品（未装 ae2fc 时返回 null）。 */
    private static Item findUltraTerminalItem() {
        if (uwtResolved) return uwtItem;
        uwtResolved = true;
        try {
            Class.forName(UWT_ITEM_CLASS);
            Iterator<Item> it = Item.itemRegistry.iterator();
            while (it.hasNext()) {
                Item item = it.next();
                if (item.getClass().getName().equals(UWT_ITEM_CLASS)) {
                    uwtItem = item;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        return uwtItem;
    }

    // ===================== WCT 虚拟物品栈 =====================

    private static final String WCT_ITEM_CLASS =
        "net.p455w0rd.wirelesscraftingterminal.items.ItemWirelessCraftingTerminal";
    private static Item wctItem;
    private static boolean wctResolved;

    /** 懒查找 WCT 终端物品（未装 WCT 时返回 null）。 */
    private static Item findWctItem() {
        if (wctResolved) return wctItem;
        wctResolved = true;
        try {
            Class.forName(WCT_ITEM_CLASS);
            Iterator<Item> it = Item.itemRegistry.iterator();
            while (it.hasNext()) {
                Item item = it.next();
                if (item.getClass().getName().equals(WCT_ITEM_CLASS)) {
                    wctItem = item;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        return wctItem;
    }

    /**
     * 构造虚拟 WCT 栈：拷贝手机的绑定密钥（NBT "key"）、塞满 AE 电力（internalCurrentPower/
     * internalMaxPower）、放入无限增幅卡（BoosterSlot，等效无限距离）。玩家背包无需任何终端。
     */
    private static ItemStack buildVirtualWctStack(String key) throws Exception {
        ItemStack stack = new ItemStack(findWctItem());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("key", key);
        tag.setDouble("internalCurrentPower", 1.0E9D);
        tag.setDouble("internalMaxPower", 1.0E9D);
        try {
            Object api = Class.forName("net.p455w0rd.wirelesscraftingterminal.api.WCTApi")
                .getMethod("instance").invoke(null);
            Object items = api.getClass().getMethod("items").invoke(api);
            Object boosterDesc = items.getClass().getField("InfinityBoosterCard").get(items);
            Item booster = (Item) boosterDesc.getClass().getMethod("getItem").invoke(boosterDesc);
            if (booster != null) {
                NBTTagCompound boosterNbt = new NBTTagCompound();
                new ItemStack(booster).writeToNBT(boosterNbt);
                NBTTagList list = new NBTTagList();
                list.appendTag(boosterNbt);
                tag.setTag("BoosterSlot", list);
            }
        } catch (Throwable t) {
            System.err.println("[mcphone] WCT booster card injection skipped: " + t);
        }
        stack.setTagCompound(tag);
        return stack;
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

    // ===================== 内置传送（多传送点） =====================

    /** 跨维度传送的待执行队列：travelToDimension 后下一 tick 落位。 */
    private static final CopyOnWriteArrayList<PendingTeleport> PENDING = new CopyOnWriteArrayList<>();
    private static boolean tickHookRegistered;

    /**
     * @param mode  0=传送到指定传送点 1=绑定当前位置 2=重命名 3=删除
     * @param index mode=0/2/3 的传送点下标
     * @param name  mode=1 自动命名兜底；mode=2 新名称
     */
    public static void teleportViaPhone(EntityPlayerMP player, int mode, int index, String name) {
        ItemStack phone = findPhone(player);
        if (phone == null) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c背包里没有手机。"));
            return;
        }
        List<ItemPhone.Waypoint> wps = ItemPhone.getWaypoints(phone);
        switch (mode) {
            case 1: {
                ItemPhone.Waypoint w = new ItemPhone.Waypoint();
                w.name = (name == null || name.trim().isEmpty())
                    ? "Point " + (wps.size() + 1) : name.trim();
                w.x = player.posX;
                w.y = player.posY;
                w.z = player.posZ;
                w.dim = player.dimension;
                w.yaw = player.rotationYaw;
                w.pitch = player.rotationPitch;
                wps.add(w);
                ItemPhone.setWaypoints(phone, wps);
                player.addChatMessage(new ChatComponentText("§7[MCphone] §a已绑定传送点 ["
                    + w.name + "]：" + fmt(w.x) + ", " + fmt(w.y) + ", " + fmt(w.z)
                    + "（维度 " + w.dim + "）"));
                syncWaypoints(player, phone);
                return;
            }
            case 2: {
                if (index < 0 || index >= wps.size()) return;
                if (name != null && !name.trim().isEmpty()) wps.get(index).name = name.trim();
                ItemPhone.setWaypoints(phone, wps);
                syncWaypoints(player, phone);
                return;
            }
            case 3: {
                if (index < 0 || index >= wps.size()) return;
                ItemPhone.Waypoint removed = wps.remove(index);
                ItemPhone.setWaypoints(phone, wps);
                player.addChatMessage(new ChatComponentText(
                    "§7[MCphone] §7已删除传送点 [" + removed.name + "]。"));
                syncWaypoints(player, phone);
                return;
            }
            default: {
                if (index < 0 || index >= wps.size()) {
                    player.addChatMessage(new ChatComponentText(
                        "§7[MCphone] §e传送点不存在，请在手机传送 App 里重新选择。"));
                    return;
                }
                ItemPhone.Waypoint w = wps.get(index);
                try {
                    if (w.dim != player.dimension) {
                        scheduleCrossDim(player, w);
                        player.addChatMessage(new ChatComponentText(
                            "§7[MCphone] §a正在穿越到 [" + w.name + "]（维度 " + w.dim + "）…"));
                    } else {
                        player.setPositionAndUpdate(w.x, w.y, w.z);
                        player.rotationYaw = w.yaw;
                        player.rotationPitch = w.pitch;
                        player.addChatMessage(new ChatComponentText("§7[MCphone] §a传送完成 [" + w.name + "]："
                            + fmt(w.x) + ", " + fmt(w.y) + ", " + fmt(w.z)));
                    }
                } catch (Throwable t) {
                    player.addChatMessage(new ChatComponentText("§7[MCphone] §c传送失败: " + t));
                }
            }
        }
    }

    /** 传送点全量同步给客户端（手机传送页即时刷新）。 */
    public static void syncWaypoints(EntityPlayerMP player, ItemStack phone) {
        NetworkHandler.INSTANCE.sendTo(
            new NetworkHandler.WaypointSync(ItemPhone.getWaypoints(phone)), player);
    }

    private static void scheduleCrossDim(EntityPlayerMP player, ItemPhone.Waypoint w) {
        PENDING.add(new PendingTeleport(player.getCommandSenderName(), w));
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
                        if (pl.dimension != p.wp.dim) {
                            pl.travelToDimension(p.wp.dim);
                        }
                        pl.setPositionAndUpdate(p.wp.x, p.wp.y, p.wp.z);
                        pl.rotationYaw = p.wp.yaw;
                        pl.rotationPitch = p.wp.pitch;
                        pl.addChatMessage(new ChatComponentText("§7[MCphone] §a传送完成 [" + p.wp.name + "]："
                            + fmt(p.wp.x) + ", " + fmt(p.wp.y) + ", " + fmt(p.wp.z)
                            + "（维度 " + pl.dimension + "）"));
                    }
                }
            });
        }
    }

    private static final class PendingTeleport {

        final String player;
        final ItemPhone.Waypoint wp;

        PendingTeleport(String player, ItemPhone.Waypoint wp) {
            this.player = player;
            this.wp = wp;
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
