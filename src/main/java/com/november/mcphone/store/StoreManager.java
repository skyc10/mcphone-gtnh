package com.november.mcphone.store;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.november.mcphone.net.NetworkHandler;

/**
 * 服务端购买/解锁逻辑：购买请求一律在服务端校验（查背包并扣物）后才解锁；
 * 解锁状态经 StoreWorldData 按存档持久化，并全量同步给客户端。
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

    /** 处理购买请求（包处理器主线程调用）：免费/未知 App 直接忽略。 */
    public static void handlePurchase(EntityPlayerMP player, String appId) {
        ItemStack price = AppPrices.priceOf(appId);
        if (price == null || isUnlocked(player, appId)) return;

        // 创造模式照常解锁但不扣物（与上游 ICost 语义一致）。
        if (!player.capabilities.isCreativeMode) {
            if (countItem(player, price) < price.stackSize) {
                player.addChatMessage(new ChatComponentText(
                    "§7[MC手机] §c购买失败：背包里没有 " + price.getDisplayName()
                        + " x" + price.stackSize + "。"));
                return;
            }
            consume(player, price);
        }
        StoreWorldData data = StoreWorldData.get(overworld());
        data.unlock(player.getUniqueID(), appId);
        player.addChatMessage(new ChatComponentText("§7[MC手机] §a购买成功，App 已解锁。"));
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.UnlockSync(unlockedApps(player)), player);
    }

    /** 登录时全量同步（StoreEvents 调用）。 */
    public static void syncTo(EntityPlayerMP player) {
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.UnlockSync(unlockedApps(player)), player);
    }

    private static net.minecraft.world.World overworld() {
        return MinecraftServer.getServer().getEntityWorld();
    }

    /** 主背包 + 盔甲栏内匹配物品总数（按物品与耐久匹配）。 */
    private static int countItem(EntityPlayerMP player, ItemStack price) {
        int found = 0;
        for (ItemStack s : player.inventory.mainInventory) {
            if (matches(s, price)) found += s.stackSize;
        }
        for (ItemStack s : player.inventory.armorInventory) {
            if (matches(s, price)) found += s.stackSize;
        }
        return found;
    }

    /** 先数够再扣，扣到一半发现不够会让玩家白损失前半截。 */
    private static void consume(EntityPlayerMP player, ItemStack price) {
        int remaining = price.stackSize;
        ItemStack[][] stashes = {player.inventory.mainInventory, player.inventory.armorInventory};
        for (ItemStack[] stash : stashes) {
            for (int i = 0; i < stash.length && remaining > 0; i++) {
                ItemStack s = stash[i];
                if (!matches(s, price)) continue;
                int take = Math.min(remaining, s.stackSize);
                s.stackSize -= take;
                remaining -= take;
                if (s.stackSize <= 0) stash[i] = null;
            }
        }
    }

    private static boolean matches(ItemStack stack, ItemStack price) {
        return stack != null && stack.getItem() == price.getItem()
            && (price.getItemDamage() == Short.MAX_VALUE
                || stack.getItemDamage() == price.getItemDamage());
    }
}
