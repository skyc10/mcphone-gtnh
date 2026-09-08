package com.november.mcphone.store;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.november.mcphone.api.store.IAppPriceProvider;

/**
 * 内建 App 报价：付费 App 卖它替代的那个实物，其余免费。
 * 将来接 EMC 只需提供新的 IAppPriceProvider（SPI 先注册者生效，内建报价兜底）。
 */
public final class BuiltinAppPrices implements IAppPriceProvider {

    @Override
    public ItemStack priceOf(String appId) {
        if ("enderchest".equals(appId)) {
            // 按注册名取物品（id 130 硬编码依赖注册顺序，附属一多就漂移）。
            Item it = Item.getItemFromBlock(Blocks.ender_chest);
            if (it == null) {
                // 理论不可达（Blocks.enderChest 恒注册）；真发生时报错而非静默按免费处理。
                System.err.println("[mcphone] Builtin price item for 'enderchest' missing "
                    + "(Blocks.enderChest Item not registered?) — falling back to no-quote.");
                return null;
            }
            return new ItemStack(it, 1);
        }
        return null;
    }
}
