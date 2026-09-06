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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import com.november.mcphone.core.ItemPhone;

/**
 * 服务端集成动作。全部运行时反射/类名识别，无编译期依赖 AE2 与 ae2fc。
 *
 * <p>传送：直接内置到手机（NBT 多传送点），不需要背包里的传送宝石。</p>
 * <p>AE2：手机注册为无线终端（IWirelessTermHandler 动态代理，电力免费）；
 * ME App 打开优先级：背包里的真终端（通用无线终端优先，自动换手 + 关闭后换回）
 * → 手机内置基础终端。绑定：潜行 + 持手机右击 ME 安全站。</p>
 */
public final class AppIntegrations {

    private AppIntegrations() {}

    // ===================== 末影箱 =====================

    public static void openEnderChest(EntityPlayer player) {
        player.displayGUIChest(player.getInventoryEnderChest());
    }

    // ===================== AE2 无线终端 =====================

    private static final String AE2_SECURITY_TILE = "appeng.tile.misc.TileSecurity";
    private static final String UWT_ITEM_CLASS =
        "com.glodblock.github.common.item.ItemWirelessUltraTerminal";
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
     * 打开手机上的 ME 终端：
     * 1. 背包里有真无线终端（通用无线终端/WCT/基础终端等）→ 自动换到手上，
     *    走 AE2 官方路由打开它的完整 UI，关闭界面后自动换回原物品；
     * 2. 背包里没有终端 → 打开手机内置基础终端（物品终端）。
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
        try {
            Method isTerm = wireless.getClass().getMethod("isWirelessTerminal", ItemStack.class);
            ItemStack terminal = findPreferredTerminal(player, isTerm, wireless);
            if (terminal != null) {
                int termSlot = indexOf(player, terminal);
                int heldSlot = player.inventory.currentItem;
                ItemStack original = player.inventory.mainInventory[heldSlot];
                if (termSlot != heldSlot) {
                    // 换到手上：ae2fc/AE2 的终端 GUI 从手持槽位构建宿主对象（服务端+客户端都是）。
                    player.inventory.mainInventory[heldSlot] = terminal;
                    player.inventory.mainInventory[termSlot] = original;
                    player.inventory.markDirty();
                    // 立即把两个槽位同步给客户端：客户端建 GUI 宿主用的是它自己手里的物品，
                    // 不同步的话客户端看到的还是手机 → 宿主错配 → GUI 半残（无背景面板）。
                    player.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        player.inventoryContainer.windowId, heldSlot, terminal));
                    player.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        player.inventoryContainer.windowId, termSlot, original));
                    RESTORES.add(new HandSwap(player.getCommandSenderName(), heldSlot, termSlot, original));
                    ensureSwapTickHook();
                }
                // 与手持右键完全一致：调用终端自身 onItemRightClick（ae2fc/AE2 各自的
                // GUI 打开路径，背景/页签渲染正常）。注册表路由会用错误的 GUI 配对导致缺背景。
                terminal.getItem()
                    .onItemRightClick(player.inventory.mainInventory[heldSlot], player.worldObj, player);
                return;
            }
            // 兜底：手机内置基础终端（物品终端）
            wireless.getClass()
                .getMethod("openWirelessTerminalGui", ItemStack.class, World.class, EntityPlayer.class)
                .invoke(wireless, phone, player.worldObj, player);
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §7已打开手机内置终端（物品终端）。背包放一个通用无线终端可获得完整功能。"));
        } catch (Throwable t) {
            player.addChatMessage(new ChatComponentText("§7[MCphone] §c打开 ME 终端失败: " + t));
        }
    }

    /** 优先通用无线终端（UWT 类名），其次任意 isWirelessTerminal 的背包终端。 */
    private static ItemStack findPreferredTerminal(EntityPlayerMP player, Method isTerm, Object wireless)
            throws Exception {
        ItemStack uwt = null;
        ItemStack any = null;
        for (ItemStack st : player.inventory.mainInventory) {
            if (st == null || st.getItem() == ItemPhone.INSTANCE) continue;
            if (uwt == null && UWT_ITEM_CLASS.equals(st.getItem().getClass().getName())) uwt = st;
            if (any == null && (Boolean) isTerm.invoke(wireless, st)) any = st;
        }
        return uwt != null ? uwt : any;
    }

    private static int indexOf(EntityPlayerMP player, ItemStack target) {
        ItemStack[] inv = player.inventory.mainInventory;
        for (int i = 0; i < inv.length; i++) {
            if (inv[i] == target) return i;
        }
        return player.inventory.currentItem;
    }

    // ===================== 手持槽位换回 =====================

    private static final CopyOnWriteArrayList<HandSwap> RESTORES = new CopyOnWriteArrayList<>();
    private static boolean swapTickHookRegistered;

    private static final class HandSwap {

        final String player;
        final int heldSlot;
        final int termSlot;
        final ItemStack original;

        HandSwap(String player, int heldSlot, int termSlot, ItemStack original) {
            this.player = player;
            this.heldSlot = heldSlot;
            this.termSlot = termSlot;
            this.original = original;
        }
    }

    private static void ensureSwapTickHook() {
        if (swapTickHookRegistered) return;
        swapTickHookRegistered = true;
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new HandSwapTickHook());
    }

    /** 必须是 public 具名静态类：FML ASM 事件代理跨类加载器调用，匿名/包私有类会抛 IllegalAccessError。 */
    public static final class HandSwapTickHook {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
            for (HandSwap swap : RESTORES) {
                EntityPlayerMP pl = findOnlinePlayer(swap.player);
                if (pl == null) continue;
                // 终端 GUI 已关闭（容器回到玩家背包容器）→ 换回原物品。
                if (pl.openContainer == pl.inventoryContainer) {
                    RESTORES.remove(swap);
                    ItemStack inHand = pl.inventory.mainInventory[swap.heldSlot];
                    if (inHand != null && inHand.getItem() == ItemPhone.INSTANCE) {
                        return; // 已经换回过
                    }
                    ItemStack term = pl.inventory.mainInventory[swap.heldSlot];
                    pl.inventory.mainInventory[swap.heldSlot] = swap.original;
                    pl.inventory.mainInventory[swap.termSlot] = term;
                    pl.inventory.markDirty();
                }
            }
        }
    }

    private static EntityPlayerMP findOnlinePlayer(String name) {
        for (Object o : cpw.mods.fml.common.FMLCommonHandler.instance()
            .getMinecraftServerInstance().getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP && ((EntityPlayerMP) o).getCommandSenderName().equals(name)) {
                return (EntityPlayerMP) o;
            }
        }
        return null;
    }

    // ===================== 绑定 ME 安全站 =====================

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
        // 命中方块优先，其次搜索命中点周围 3x3x3（准星打在安全站旁的线缆/面板上也能绑定）。
        TileEntity te = findSecurityTile(player, hit.blockX, hit.blockY, hit.blockZ);
        if (te == null) {
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §c对准的方块不是 ME 安全站（需潜行 + 右击安全站本体）。"));
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

    /** 在命中方块及其周围 3x3x3 范围内找 ME 安全站 Tile。 */
    private static TileEntity findSecurityTile(EntityPlayerMP player, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    TileEntity te = player.worldObj.getTileEntity(x + dx, y + dy, z + dz);
                    if (te != null && AE2_SECURITY_TILE.equals(te.getClass().getName())) {
                        return te;
                    }
                }
            }
        }
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
            cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new CrossDimTeleportHook());
        }
    }

    /** 必须是 public 具名静态类：FML ASM 事件代理跨类加载器调用，匿名/包私有类会抛 IllegalAccessError。 */
    public static final class CrossDimTeleportHook {

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
