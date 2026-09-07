package com.november.mcphone.store;

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
            Item it = Item.getItemById(130); // 末影箱
            return it == null ? null : new ItemStack(it, 1);
        }
        return null;
    }
}
