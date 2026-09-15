package com.november.mcphone.store;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import net.minecraft.item.ItemStack;

import com.november.mcphone.api.store.IAppPriceProvider;

/**
 * App 价格表唯一权威（客户端画价与服务端扣物共用）：没报价 = 免费。
 *
 * <p>惰性扫描 SPI（首次查询时物品注册表已就绪）；附属报价按 ServiceLoader 发现
 * 顺序优先（先到先得，允许附属覆盖内建价），全部返回 null 才用内建报价兜底。</p>
 */
public final class AppPrices {

    private static volatile List<IAppPriceProvider> providers;

    private AppPrices() {}

    /** @return 价格物品栈；null = 免费。 */
    public static ItemStack priceOf(String appId) {
        if (appId == null) return null;
        for (IAppPriceProvider p : ensureLoaded()) {
            try {
                ItemStack price = p.priceOf(appId);
                if (price != null) return price;
            } catch (Throwable t) {
                System.err.println("[mcphone] Price provider " + p.getClass().getName() + " failed: " + t);
            }
        }
        return null;
    }

    /** 该 App 是否付费。 */
    public static boolean isPaid(String appId) {
        return priceOf(appId) != null;
    }

    private static List<IAppPriceProvider> ensureLoaded() {
        List<IAppPriceProvider> list = providers;
        if (list != null) return list;
        synchronized (AppPrices.class) {
            if (providers != null) return providers;
            list = new ArrayList<>();
            try {
                // SPI 优先（可覆盖内建价），内建报价兜底。
                for (IAppPriceProvider p : ServiceLoader.load(
                    IAppPriceProvider.class, AppPrices.class.getClassLoader())) {
                    list.add(p);
                }
            } catch (Throwable t) {
                System.err.println("[mcphone] Price SPI scan failed: " + t);
            }
            list.add(new BuiltinAppPrices());
            providers = list;
            return list;
        }
    }
}
