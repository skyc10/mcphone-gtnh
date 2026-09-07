package com.november.mcphone.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.store.AppPrices;

/**
 * 商店模式客户端状态：开关（settings.properties 持久化）+ 服务端 UnlockSync
 * 同步来的已购 App 集合。
 *
 * <p>兼容红线：附属 App（isBuiltin()==false）一律视为已安装、免费——商店模式只是
 * 内建付费 App 的解锁框架，不改变附属 App 的可用性。价格表本身按 id 查询，
 * 附属未报价即免费，双保险。</p>
 */
public final class StoreClient {

    /** 服务端 UnlockSync 全量覆盖；未收到过同步视为全解锁（兼容旧服务端/离线场景）。 */
    private static volatile Set<String> unlocked = null;

    private StoreClient() {}

    /** 服务端解锁状态落地（客户端 tick 主线程调用）。 */
    public static void onUnlockSync(List<String> ids) {
        unlocked = new HashSet<>(ids);
        com.november.mcphone.client.scene.PhoneUi.onStoreSync();
    }

    /** 商店模式是否开启（客户端本地设置，默认关闭）。 */
    public static boolean isEnabled() {
        return PhoneCanvas.isStoreMode();
    }

    /** 该 App 当前是否可用（免费恒真；商店模式关闭恒真；未同步过视为解锁）。 */
    public static boolean isUnlocked(IPhoneApp app) {
        if (app == null || !app.isBuiltin()) return true;
        if (!isEnabled()) return true;
        if (AppPrices.priceOf(app.id()) == null) return true;
        Set<String> owned = unlocked;
        return owned == null || owned.contains(app.id());
    }

    /** 商店页用：是否需要展示购买按钮（付费且未购的内建 App）。 */
    public static boolean needsPurchase(IPhoneApp app) {
        if (app == null || !app.isBuiltin() || !isEnabled()) return false;
        if (AppPrices.priceOf(app.id()) == null) return false;
        Set<String> owned = unlocked;
        return owned != null && !owned.contains(app.id());
    }

    /** 价格展示文案（如 "末影箱 x1"）；免费返回 null。 */
    public static String priceText(IPhoneApp app) {
        ItemStack price = app == null ? null : AppPrices.priceOf(app.id());
        return price == null ? null : price.getDisplayName() + " x" + price.stackSize;
    }
}
