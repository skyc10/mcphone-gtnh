package com.november.mcphone.store;

import java.util.ArrayList;
import java.util.List;

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

    /** 处理购买请求（服务器主线程调用）：免费/未知 App 直接忽略。 */
    public static void handlePurchase(EntityPlayerMP player, String appId) {
        ItemStack price = AppPrices.priceOf(appId);
        if (price == null || isUnlocked(player, appId)) return;

        // 创造模式照常解锁但不扣物（与上游 ICost 语义一致）。
        if (!player.capabilities.isCreativeMode) {
            if (!consumeAtomic(player, price)) {
                player.addChatMessage(new ChatComponentTranslation(
                    "msg.mcphone.store_buy_failed",
                    price.getDisplayName(), price.stackSize));
                return;
            }
        }
        StoreWorldData data = StoreWorldData.get(overworld());
        data.unlock(player.getUniqueID(), appId);
        player.addChatMessage(new ChatComponentTranslation("msg.mcphone.store_buy_success"));
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.UnlockSync(unlockedApps(player)), player);
    }

    /**
     * 末影箱 App 服务端门禁：免费（无报价）直接放行；有价且未购买则拒绝并回提示。
     * 服务端权威校验购买记录，与客户端商店模式本地开关无关——有价未购即拒正是
     * 商店模式的本意（未开启商店模式的玩家同样要先花 1 个末影箱解锁）。
     */
    public static void openEnderChestIfAllowed(EntityPlayerMP player) {
        if (AppPrices.priceOf("enderchest") == null || isUnlocked(player, "enderchest")) {
            AppIntegrations.openEnderChest(player);
            return;
        }
        // 与客户端 toastLocked 的键区分：这里是服务端开门失败的服务端提示。
        player.addChatMessage(new ChatComponentTranslation("msg.mcphone.store_locked_server"));
    }

    /** 登录时全量同步（StoreEvents 调用）。 */
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
