package com.november.mcphone.net;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import com.november.mcphone.core.StargateConfig;

/**
 * 服务端集成动作。全部运行时反射/类名识别，无编译期依赖 AE2 与 ae2fc。
 *
 * <p>传送：内置多传送点，数量/冷却/跨维度由星门配置（[teleport]）在服务端强制。</p>
 * <p>AE2：手机是否注册为无线终端（IWirelessTermHandler 动态代理）由星门开关
 * registerWirelessTerminal 决定（默认不注册）；ME App 打开顺序：背包里的真终端
 * （通用无线终端优先，自动换手 + 关闭后换回）→ 手机内置基础终端（受 builtinTerminal
 * 控制，默认关）。绑定：潜行 + 持手机右击 ME 安全站（注册开启时才可绑定）。</p>
 *
 * <p><b>星门合规（GTNH 规则）</b>：所有玩法入口默认收敛为「禁用/受限」，行为全部
 * 由 {@link StargateConfig}（config/mcphone-stargate.cfg）在服务端强制：</p>
 * <ul>
 *   <li>[ae2] registerWirelessTerminal=false：手机<b>不</b>注册为 AE2 无线终端，
 *       不再「电力免费、无限电力、无限范围」——若服主放开注册，电力语义改为跟随
 *       所绑定 ME 网络的真实供电（isNetworkPowered + extractAEPower 真实抽取）；</li>
 *   <li>[ae2] builtinTerminal=false：手机内置兜底物品终端关闭，未注册/无终端时
 *       ME App 明确提示并拒绝；</li>
 *   <li>[teleport] maxWaypoints / cooldownSeconds / crossDimension：传送点数量、
 *       冷却、跨维度限制全部服务端校验（伪造包拦在服务端，客户端提示只是兜底）；</li>
 *   <li>[enderchest] enabled=false：末影箱玩法默认关闭，服务端拒绝并提示。</li>
 * </ul>
 */
public final class AppIntegrations {

    private AppIntegrations() {}

    // ===================== 末影箱 =====================

    /**
     * 星门合规：末影箱玩法默认关闭。入口仍在（客户端置灰兜底在 UI 侧），
     * 但服务端一律校验 {@link StargateConfig#enderchestEnabled()}，伪造包也打不开。
     */
    public static void openEnderChest(EntityPlayer player) {
        if (!StargateConfig.enderchestEnabled()) {
            if (player instanceof EntityPlayerMP) {
                ((EntityPlayerMP) player).addChatMessage(new ChatComponentText(
                    "§7[MCphone] §c末影箱功能已被服务器规则（星门）关闭"
                        + "（config/mcphone-stargate.cfg [enderchest] enabled）。"));
            }
            return;
        }
        player.displayGUIChest(player.getInventoryEnderChest());
    }

    // ===================== AE2 无线终端 =====================

    private static final String AE2_SECURITY_TILE = "appeng.tile.misc.TileSecurity";
    private static final String UWT_ITEM_CLASS =
        "com.glodblock.github.common.item.ItemWirelessUltraTerminal";
    private static volatile Object ae2WirelessRegistry;

    /** postInit 调用：AE2 在场且 [ae2] registerWirelessTerminal=true 时把手机注册为无线终端。 */
    public static void registerAe2WirelessHandler() {
        // 星门合规：默认 false——手机不注册为 AE2 无线终端，不参与 ME 网络玩法。
        if (!StargateConfig.registerWirelessTerminal()) {
            System.out.println("[mcphone] stargate: AE2 wireless registration disabled"
                + " (config/mcphone-stargate.cfg [ae2] registerWirelessTerminal=false)");
            return;
        }
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
                // 星门版真实电力语义：尝试从绑定的 ME 网络真实抽取耗电（MODULATE）。
                return checkAe2Power(args, true);
            case "hasPower":
                // 星门版真实电力语义：只查询绑定 ME 网络的供电状态，不扣电。
                return checkAe2Power(args, false);
            case "hasInfinityPower":
            case "hasInfinityRange":
                // 星门合规：不再宣称无限电力/无限范围，跟随真实 ME 网络语义。
                return Boolean.FALSE;
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

    private static final long NO_SERIAL = Long.MIN_VALUE;

    /** 解析手机 AE2 绑定密钥（ME 安全站 locatable serial）；未绑定/非法返回 {@link #NO_SERIAL}。 */
    private static long parseSerial(String key) {
        if (key == null) return NO_SERIAL;
        String t = key.trim();
        if (t.isEmpty()) return NO_SERIAL;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return NO_SERIAL;
        }
    }

    /**
     * 反射取手机所绑定 ME 安全站所在网络的能源缓存（IEnergyGrid）。
     * 未绑定 / 安全站已拆除 / 网络不可达时返回 null。
     */
    private static Object boundEnergyGrid(ItemStack phone) {
        try {
            long serial = parseSerial(ItemPhone.getAe2Key(phone));
            if (serial == NO_SERIAL) return null;
            Class<?> aeApi = Class.forName("appeng.api.AEApi");
            Object api = aeApi.getMethod("instance").invoke(null);
            Object registries = api.getClass().getMethod("registries").invoke(api);
            Object locatable = registries.getClass().getMethod("locatable").invoke(registries);
            Object station = locatable.getClass()
                .getMethod("getLocatableBy", long.class).invoke(locatable, serial);
            if (station == null || !AE2_SECURITY_TILE.equals(station.getClass().getName())) {
                return null;
            }
            Object proxy = station.getClass().getMethod("getProxy").invoke(station);
            Object grid = proxy.getClass().getMethod("getGrid").invoke(proxy);
            Class<?> gridCache = Class.forName("appeng.api.networking.IGridCache");
            Class<?> energyGrid = Class.forName("appeng.api.networking.energy.IEnergyGrid");
            return grid.getClass().getMethod("getCache", gridCache).invoke(grid, energyGrid);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 星门版电力语义：手机终端的可用性跟随所绑定 ME 网络的真实供电。
     *
     * @param consume false=查询（hasPower：isNetworkPowered）；true=扣电（usePower：
     *                以 MODULATE 从网络真实抽取 1 AE，抽取不足即失败）
     */
    private static Boolean checkAe2Power(Object[] args, boolean consume) {
        try {
            if (args == null || args.length < 3 || !(args[2] instanceof ItemStack)) {
                return Boolean.FALSE;
            }
            Object energy = boundEnergyGrid((ItemStack) args[2]);
            if (energy == null) return Boolean.FALSE;
            Method powered = energy.getClass().getMethod("isNetworkPowered");
            if (!(Boolean) powered.invoke(energy)) return Boolean.FALSE;
            if (!consume) return Boolean.TRUE;
            Class<?> actionable = Class.forName("appeng.api.config.Actionable");
            Object modulate = actionable.getField("MODULATE").get(null);
            Class<?> multiplier = Class.forName("appeng.api.config.PowerMultiplier");
            Object one = multiplier.getField("ONE").get(null);
            Method extract = energy.getClass().getMethod("extractAEPower",
                double.class, actionable, multiplier);
            Object got = extract.invoke(energy, 1.0D, modulate, one);
            return ((Number) got).doubleValue() >= 1.0D ? Boolean.TRUE : Boolean.FALSE;
        } catch (Throwable t) {
            // 任何反射链路异常都按「无电力」处理：星门版宁可拒绝也不免费放行。
            return Boolean.FALSE;
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
     * 0. 星门规则关闭（registerWirelessTerminal 与 builtinTerminal 均为 false）→ 明确提示并拒绝；
     * 1. 背包里有真无线终端（通用无线终端/WCT/基础终端等）→ 自动换到手上，
     *    走 AE2 官方路由打开它的完整 UI，关闭界面后自动换回原物品；
     * 2. 背包里没有终端 → 打开手机内置基础终端（物品终端，受 builtinTerminal 控制）。
     */
    public static void openAe2Terminal(EntityPlayerMP player) {
        if (!StargateConfig.registerWirelessTerminal() && !StargateConfig.builtinTerminal()) {
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §cAE2 终端功能已被服务器规则（星门）关闭"
                    + "（config/mcphone-stargate.cfg [ae2] registerWirelessTerminal / builtinTerminal）。"));
            return;
        }
        Object wireless = ae2WirelessRegistry;
        if (wireless == null) {
            if (!StargateConfig.registerWirelessTerminal()) {
                // 星门开关关掉了注册：此时 ME App 不可用是配置使然，不是 AE2 缺席。
                player.addChatMessage(new ChatComponentText(
                    "§7[MCphone] §e星门规则已关闭「手机注册为 AE2 无线终端」"
                        + "（config/mcphone-stargate.cfg [ae2] registerWirelessTerminal=false），"
                        + "ME App 暂不可用；请服主按需放开该开关后再试。"));
            } else {
                player.addChatMessage(new ChatComponentText("§7[MCphone] §c未检测到 AE2，无法打开 ME 终端。"));
            }
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
                    // 不同步的话客户端看到的还是手机 → 宿主错配 → 客户端解析成 GuiNull（半残）。
                    // 注意 S2F 用的是容器槽位号（护甲/合成区占 0-8），必须经 inventoryContainer 映射。
                    player.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        player.inventoryContainer.windowId, containerSlotFor(player, heldSlot), terminal));
                    player.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        player.inventoryContainer.windowId, containerSlotFor(player, termSlot), original));
                    RESTORES.add(new HandSwap(player.getCommandSenderName(), heldSlot, termSlot, original));
                    ensureSwapTickHook();
                }
                // 与手持右键完全一致：调用终端自身 onItemRightClick（ae2fc/AE2 各自的
                // GUI 打开路径，背景/页签渲染正常）。注册表路由会用错误的 GUI 配对导致缺背景。
                terminal.getItem()
                    .onItemRightClick(player.inventory.mainInventory[heldSlot], player.worldObj, player);
                System.out.println("[mcphone] ME open (real terminal): item="
                    + terminal.getItem().getClass().getName()
                    + " container=" + player.openContainer.getClass().getName()
                    + " heldSlot=" + heldSlot);
                return;
            }
            // 星门合规：内置兜底终端受 [ae2] builtinTerminal 控制（默认关）。
            if (!StargateConfig.builtinTerminal()) {
                player.addChatMessage(new ChatComponentText(
                    "§7[MCphone] §e背包里没有可用的无线终端，且手机内置终端已被服务器规则（星门）关闭"
                        + "（config/mcphone-stargate.cfg [ae2] builtinTerminal）。"));
                return;
            }
            // 兜底：手机内置基础终端（物品终端）
            wireless.getClass()
                .getMethod("openWirelessTerminalGui", ItemStack.class, World.class, EntityPlayer.class)
                .invoke(wireless, phone, player.worldObj, player);
            System.out.println("[mcphone] ME open (built-in): container="
                + player.openContainer.getClass().getName());
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

    /** mainInventory 索引 → inventoryContainer 容器槽位号（按 Slot 的 inventory+index 精确匹配）。 */
    private static int containerSlotFor(EntityPlayerMP player, int invIndex) {
        int container = invIndex;
        int j = 0;
        for (Object o : player.inventoryContainer.inventorySlots) {
            net.minecraft.inventory.Slot sl = (net.minecraft.inventory.Slot) o;
            if (sl.isSlotInInventory(player.inventory, invIndex)) {
                container = j;
                break;
            }
            j++;
        }
        return container;
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
                    pl.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        pl.inventoryContainer.windowId,
                        containerSlotFor(pl, swap.heldSlot), swap.original));
                    pl.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        pl.inventoryContainer.windowId,
                        containerSlotFor(pl, swap.termSlot), term));
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
        if (!StargateConfig.registerWirelessTerminal()) {
            player.addChatMessage(new ChatComponentText(
                "§7[MCphone] §cAE2 终端功能已被服务器规则（星门）关闭，无需绑定"
                    + "（config/mcphone-stargate.cfg [ae2] registerWirelessTerminal）。"));
            return;
        }
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

    // ===================== 内置传送（多传送点，星门合规） =====================

    /** 跨维度传送的待执行队列：travelToDimension 后下一 tick 落位。 */
    private static final CopyOnWriteArrayList<PendingTeleport> PENDING = new CopyOnWriteArrayList<>();
    private static boolean tickHookRegistered;

    /**
     * 星门合规：每玩家上次传送时间戳（服务端内存记录，专用服务器重启即清零）。
     * 冷却判定在服务端强制，客户端伪造包绕不过。
     */
    private static final Map<String, Long> LAST_TELEPORT_MS = new ConcurrentHashMap<>();

    /** 距离可再次传送还差的毫秒数（<=0 表示可以传送）。 */
    private static long teleportCooldownRemainingMs(EntityPlayerMP player) {
        int cd = StargateConfig.teleportCooldownSeconds();
        if (cd <= 0) return 0L;
        Long last = LAST_TELEPORT_MS.get(player.getCommandSenderName());
        if (last == null) return 0L;
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0L, cd * 1000L - elapsed);
    }

    private static void markTeleported(EntityPlayerMP player) {
        LAST_TELEPORT_MS.put(player.getCommandSenderName(), System.currentTimeMillis());
    }

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
                // 星门合规：绑定数量上限在服务端强制（伪造包也绑不进第 max+1 个）。
                int max = StargateConfig.teleportMaxWaypoints();
                if (wps.size() >= max) {
                    player.addChatMessage(new ChatComponentText(
                        "§7[MCphone] §c传送点已达上限 " + max + " 个，服务器规则限制了绑定传送点数量"
                            + "（config/mcphone-stargate.cfg [teleport] maxWaypoints）。请先删除一个。"));
                    return;
                }
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
                // 星门合规：传送冷却（服务端计时，客户端提示只是兜底）。
                long waitMs = teleportCooldownRemainingMs(player);
                if (waitMs > 0) {
                    player.addChatMessage(new ChatComponentText(String.format(
                        "§7[MCphone] §c传送冷却中，还剩 %d 秒（config/mcphone-stargate.cfg [teleport] cooldownSeconds）。",
                        (waitMs + 999) / 1000)));
                    return;
                }
                ItemPhone.Waypoint w = wps.get(index);
                // 星门合规：跨维度限制（crossDimension 默认 false）。
                boolean crossDim = w.dim != player.dimension;
                if (crossDim && !StargateConfig.teleportCrossDimension()) {
                    player.addChatMessage(new ChatComponentText(
                        "§7[MCphone] §c该传送点在另一个维度（" + w.dim + "），服务器规则（星门）已禁止跨维度传送"
                            + "（config/mcphone-stargate.cfg [teleport] crossDimension）。"));
                    return;
                }
                // 全部校验通过才记冷却：本次请求成功发出即占用冷却窗口。
                markTeleported(player);
                try {
                    if (crossDim) {
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
