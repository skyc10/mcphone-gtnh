package com.november.mcphone.api.store;

import net.minecraft.item.ItemStack;

/**
 * App 报价 SPI：按 appId 返回价格物品栈（数量即所需个数），null = 免费。
 *
 * <p>通过 Java SPI 注册：在你的 jar 里放
 * {@code META-INF/services/com.november.mcphone.api.store.IAppPriceProvider}，
 * 内容为实现类全限定名。同一 App id 被多方报价时内建报价优先、其余按发现顺序取第一个。</p>
 *
 * <p>实现类在客户端（商店页画价）与服务端（购买校验扣物）两端都会加载，
 * 禁止引用任何 net.minecraft.client.* 类型——碰了的话专用服务器会在扫描时崩。</p>
 */
public interface IAppPriceProvider {

    /** @return 价格物品栈（stackSize = 所需数量）；null 表示该 App 免费/不报价。 */
    ItemStack priceOf(String appId);
}
