package com.november.mcphone.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import com.november.mcphone.net.AppIntegrations;
import com.november.mcphone.net.NetworkHandler;

/**
 * 服务端购买/解锁逻辑：购买请求一律在服务端校验（查背包并扣物）后才解锁；
 * 解锁状态经 StoreWorldData 按存档持久化，并全量同步给客户端。
 *
 * <p>提示文案用 ChatComponentTranslation（键 + 参数）而不是硬编码中文：
 * 服务端没有客户端 lang，本地化由客户端 StatCollector 完成。</p>
 *
 * <p><b>P4 新增（商店默认开之后的体验补齐）</b>：</p>
 * <ul>
 *   <li>{@link #handleUnlockRequest}：接住客户端主动拉取（包 17），语义就是现成的
 *       {@link #syncTo}，只多一道抗刷包节流；</li>
 *   <li>{@link #handlePurchase} 的每个分支都回一条 {@code PurchaseResult}（包 18），
 *       让客户端能精确区分 ok / cannot_afford / purchase_failed，不再靠倒计时猜。</li>
 * </ul>
 */
public final class StoreManager {

    private StoreManager() {}

    public static boolean isUnlocked(EntityPlayerMP player, String appId) {
        return StoreWorldData.get(overworld()).isUnlocked(player.getUniqueID(), appId);
    }

    /** 该玩家已购 App 列表（登录/购买后同步给客户端）。 */
    public static List<String> unlockedApps(EntityPlayerMP player) {
        return StoreWorldData.get(overworld()).unlockedFor(player.getUniqueID());
    }

    /**
     * 处理购买请求（服务端主线程调用）。免费/未知 App 不扣物、不加聊天栏（与改动前逐字一致），
     * 但每个分支都会回一条 {@code PurchaseResult}（P4）——客户端据此画确切结论，不再靠 100 帧猜。
     *
     * <p><b>校验顺序保持原样</b>：报价表 → 是否已购 → 创造模式 → 扣费。只在"扣费"前多插一道
     * <b>只读</b>预检 {@link #canAfford}（纯数背包，一个物品都不动），目的是把
     * "买不起"和"扣费失败"分成两种结论；真正的扣费仍是 {@link #consumeAtomic} 一次性原子完成，
     * 顺序、原子性与"整单要么全扣要么不扣"的语义都没变。上游同样是 canAfford 之后才 consume
     * （{@code StoreNetworking.handlePurchase}）。</p>
     */
    public static void handlePurchase(EntityPlayerMP player, String appId) {
        ItemStack price = AppPrices.priceOf(appId);
        if (price == null) {
            // 免费/未知 App：客户端按价格表判断，正常不会走到这里（伪造包除外）。不扣物、
            // 不加聊天栏（口径不变），只回一条"没成功"，免得客户端挂到倒计时超时。
            sendPurchaseResult(player, appId, NetworkHandler.PurchaseResult.RESULT_PURCHASE_FAILED);
            return;
        }
        if (isUnlocked(player, appId)) {
            // 已拥有 = 幂等成功：不重复扣费（与改动前一致），回 ok 让 UI 直接画"已解锁"。
            sendPurchaseResult(player, appId, NetworkHandler.PurchaseResult.RESULT_OK);
            return;
        }

        // 创造模式照常解锁但不扣物（与上游 ICost 语义一致）。
        if (!player.capabilities.isCreativeMode) {
            if (!canAfford(player, price)) {
                player.addChatMessage(new ChatComponentTranslation(
                    "msg.mcphone.store_buy_failed",
                    price.getDisplayName(), price.stackSize));
                sendPurchaseResult(player, appId, NetworkHandler.PurchaseResult.RESULT_CANNOT_AFFORD);
                return;
            }
            if (!consumeAtomic(player, price)) {
                player.addChatMessage(new ChatComponentTranslation(
                    "msg.mcphone.store_buy_failed",
                    price.getDisplayName(), price.stackSize));
                sendPurchaseResult(player, appId, NetworkHandler.PurchaseResult.RESULT_PURCHASE_FAILED);
                return;
            }
        }
        StoreWorldData data = StoreWorldData.get(overworld());
        data.unlock(player.getUniqueID(), appId);
        player.addChatMessage(new ChatComponentTranslation("msg.mcphone.store_buy_success"));
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.UnlockSync(unlockedApps(player)), player);
        sendPurchaseResult(player, appId, NetworkHandler.PurchaseResult.RESULT_OK);
    }

    /** 回一条购买结果（S→C）。纯通知，无副作用：客户端只是把结论画出来。 */
    private static void sendPurchaseResult(EntityPlayerMP player, String appId, byte result) {
        if (player == null || NetworkHandler.INSTANCE == null) return;
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.PurchaseResult(appId, result), player);
    }

    /**
     * 只读预检：背包（主 + 护甲）里凑不凑得齐标价，与 {@link #consumeAtomic} 同口径
     * （同一个 {@link #matches}），但<b>一个物品都不扣</b>。够不够的最终裁决仍是
     * {@link #consumeAtomic}（预检与扣费之间背包可能被别人动，那时按"扣费失败"回）。
     */
    private static boolean canAfford(EntityPlayerMP player, ItemStack price) {
        ItemStack[][] stashes = {player.inventory.mainInventory, player.inventory.armorInventory};
        int found = 0;
        for (ItemStack[] stash : stashes) {
            for (ItemStack s : stash) {
                if (!matches(s, price)) continue;
                found += s.stackSize;
                if (found >= price.stackSize) return true;
            }
        }
        return false;
    }

    /** 同一玩家两次"拉取式同步"的最小间隔（ms）：C→S 包不可信，必须能抗刷。 */
    private static final long REQUEST_SYNC_COOLDOWN_MS = 500L;

    /**
     * 玩家 UUID → 上次"拉取式同步"的时刻。只被主动拉取路径读写；
     * 登录推送与购买推送走 {@link #syncTo} 直连，不经过这里。条目随玩家数增长
     * （每个 UUID 一个 Long，量级可忽略）。
     */
    private static final Map<UUID, Long> lastRequestSyncMs = new ConcurrentHashMap<>();

    /**
     * 处理客户端的"主动拉取已购列表"请求（包 17，服务端主线程）：
     * 语义就是现成的 {@link #syncTo(EntityPlayerMP)}，只多一道节流。
     *
     * <p>C→S 包由不受信客户端驱动，没有节流就能被刷成 UnlockSync 放大器
     * （上游同位置有 {@code RequestThrottle.allow(player, Kind.PURCHASED)}）。
     * 节流口径：每玩家每 {@link #REQUEST_SYNC_COOLDOWN_MS} 毫秒最多回一次；
     * 被节流时只是不回包，客户端已有登录推送兜底，不会因此永久卡在未同步。</p>
     */
    public static void handleUnlockRequest(EntityPlayerMP player) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        Long last = lastRequestSyncMs.put(player.getUniqueID(), now);
        if (last != null && now - last < REQUEST_SYNC_COOLDOWN_MS) return;
        syncTo(player);
    }

    /** 客户端「商店模式」开关的反射句柄（缓存；不能直接引用客户端类，见 clientStoreMode）。 */
    private static volatile java.lang.reflect.Method clientStoreModeGetter;

    /**
     * 末影箱 App 服务端门禁：免费（无报价）直接放行；拒绝只发生在
     * 「本地客户端已开启商店模式 + 有价 + 未购买」三者同时成立时。
     *
     * <p><b>为什么门禁必须看客户端开关</b>：商店模式是客户端本地开关
     * （{@code PhoneCanvas.isStoreMode()} ← settings.properties 的 {@code storeMode}，
     * 现默认<b>开启</b>＝产品决定，上游没有这个开关）。关闭时客户端整条链路都按
     * v1.0.2 语义放行
     * （{@code StoreClient.isUnlocked} 恒真、主屏不过滤、商店页直接早退
     * ⇒ 没有任何购买入口）。若服务端无视该开关一律「有价未购即拒」，
     * 默认配置下末影箱就既打不开也买不到（死锁）——门禁与开关必须同源。</p>
     *
     * <p><b>判定范围</b>：只有内存连接（单人 / 局域网主机的本地客户端）才有可读的
     * 客户端开关；远程玩家与专用服务器的 storeMode 在别人机器上，服务端读不到
     * ⇒ 放行，由该玩家自己客户端的 StoreClient 拦截（开关的权威在那里）。
     * 商店模式开启时的门禁与扣费路径与改动前逐字一致。见 {@link #purchaseGateActive}。</p>
     */
    public static void openEnderChestIfAllowed(EntityPlayerMP player) {
        if (!purchaseGateActive(player) || AppPrices.priceOf("enderchest") == null
            || isUnlocked(player, "enderchest")) {
            AppIntegrations.openEnderChest(player);
            return;
        }
        // 与客户端 toastLocked 的键区分：这里是服务端开门失败的服务端提示。
        player.addChatMessage(new ChatComponentTranslation("msg.mcphone.store_locked_server"));
        // 顺手把权威已购列表推回客户端：客户端若还没收到过登录同步（unlocked == null），
        // 它既不过滤主屏、商店页也判不出「未购」⇒ 玩家看不到任何购买入口。
        // 一次 UnlockSync 就能让商店页列出该 App，红字之后仍留补救路径（不会扣物）。
        syncTo(player);
    }

    /** 本次请求是否要走购买门禁 = 本地客户端（同进程）+ 其「商店模式」已开启。 */
    private static boolean purchaseGateActive(EntityPlayerMP player) {
        final boolean localClient;
        try {
            // 内存连接 = 单人 / 局域网主机的本地客户端；socket = 远程玩家或专用服务器。
            localClient = player != null && player.playerNetServerHandler != null
                && player.playerNetServerHandler.netManager.isLocalChannel();
        } catch (Throwable t) {
            // 链路异常时按「无门禁」处理：绝不允许门禁把默认行为弄成打不开。
            return false;
        }
        return localClient && clientStoreMode();
    }

    /**
     * 反射读客户端「商店模式」开关（客户端设置即权威，默认 true＝产品决定）。
     *
     * <p><b>为什么反射</b>：本类会加载在专用服务器上，而客户端类
     * {@code com.november.mcphone.client.PhoneCanvas} 的常量池里含
     * {@code net.minecraft.client.Minecraft}（{@code baseDir()} 用 mcDataDir）；
     * 直接引用会让专用服务器在解析/链接时抛 NoClassDefFoundError。
     * 反射只在真正需要门禁的集成服务器（客户端就在同进程）里才会执行。</p>
     *
     * <p>任何异常一律按「商店模式关闭」= 放行处理：宁可少一道服务端门禁，
     * 也不允许把默认配置的末影箱锁死。</p>
     */
    private static boolean clientStoreMode() {
        try {
            java.lang.reflect.Method m = clientStoreModeGetter;
            if (m == null) {
                m = Class.forName("com.november.mcphone.client.PhoneCanvas")
                    .getMethod("isStoreMode");
                clientStoreModeGetter = m;
            }
            return Boolean.TRUE.equals(m.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 全量同步已购列表给客户端（S→C）。调用者：登录事件（{@code StoreEvents}）、
     * 购买成功后、以及客户端的主动拉取（{@link #handleUnlockRequest}）。
     */
    public static void syncTo(EntityPlayerMP player) {
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.UnlockSync(unlockedApps(player)), player);
    }

    private static net.minecraft.world.World overworld() {
        return MinecraftServer.getServer().getEntityWorld();
    }

    /**
     * 原子扣费：一次遍历内凑齐可扣槽位（积攒-扣除模式），数量不够则整单拒绝、
     * 一个物品都不扣；够则按记录一次性扣完。中途没有「数完再重扫」的窗口。
     */
    private static boolean consumeAtomic(EntityPlayerMP player, ItemStack price) {
        ItemStack[][] stashes = {player.inventory.mainInventory, player.inventory.armorInventory};
        List<int[]> plan = new ArrayList<>(); // 每项 = [stash 下标, 槽位, 扣取数]
        int found = 0;
        outer:
        for (int st = 0; st < stashes.length; st++) {
            for (int i = 0; i < stashes[st].length; i++) {
                ItemStack s = stashes[st][i];
                if (!matches(s, price)) continue;
                int take = Math.min(price.stackSize - found, s.stackSize);
                plan.add(new int[] {st, i, take});
                found += take;
                if (found >= price.stackSize) break outer;
            }
        }
        if (found < price.stackSize) return false;
        for (int[] slot : plan) {
            ItemStack s = stashes[slot[0]][slot[1]];
            s.stackSize -= slot[2];
            if (s.stackSize <= 0) stashes[slot[0]][slot[1]] = null;
        }
        return true;
    }

    private static boolean matches(ItemStack stack, ItemStack price) {
        return stack != null && stack.getItem() == price.getItem()
            && (price.getItemDamage() == Short.MAX_VALUE
                || stack.getItemDamage() == price.getItemDamage());
    }
}
