package com.november.mcphone.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.net.NetworkHandler;
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

    /**
     * 最近一次"真实新增"的已购 id（服务端每次 UnlockSync 与上一次比对得出）。
     *
     * <p>服务端只在<b>成功</b>解锁后才回包，所以这个信号就是"刚才买成了"的权威依据，
     * 用来给商店详情页报成功（B-1：失败/成功都要在手机里有字，不能只发聊天栏）。</p>
     */
    private static volatile List<String> justPurchased = java.util.Collections.emptyList();

    /**
     * 最近一次购买失败的人话文案（内容与服务端聊天栏同句），null = 没有失败。
     *
     * <p>服务端失败路径只 addChatMessage（{@code StoreManager}），手机里看不到；客户端
     * 在本地预判也不够时（背包里不够）记一条，商店页把它画成一行 notice 文字。</p>
     */
    private static volatile String lastMessage = null;

    // ===================== P4：主动拉取 + 购买结果回流 =====================

    /**
     * 本世界会话是否已经发过"主动拉取已购列表"。
     *
     * <p>幂等闸门，兼<b>防回环</b>：服务端收到拉取请求后回 {@code UnlockSync}，而
     * {@link #onUnlockSync} 里也挂了一处拉取触发（登录同步到达后再确认一次）；没有这道
     * 闸门就会"同步 → 拉取 → 同步 → …"无限对打。离开世界（{@link #reset()}）时复位。</p>
     */
    private static volatile boolean syncRequested;

    /** 上次发出拉取的时刻（ms）；{@link #syncRequested} 之外再兜一道时间节流。 */
    private static volatile long lastSyncRequestMs;

    /** 两次主动拉取的最小间隔（正常路径由 syncRequested 兜住，这里只防异常重复）。 */
    private static final long SYNC_REQUEST_MIN_GAP_MS = 1000L;

    /** 还没有结果时的取值；0 是"成功"，不能拿来当初始值。 */
    public static final int RESULT_NONE = -1;

    /**
     * 结果码与服务端 {@code NetworkHandler.PurchaseResult.RESULT_*} 同源。
     * 下面三个都是<b>编译期常量</b>（服务端那几个是带常量初始化的 static final byte），
     * 所以 UI 里可以直接写 {@code case StoreClient.RESULT_CANNOT_AFFORD:}。
     */
    public static final int RESULT_OK = NetworkHandler.PurchaseResult.RESULT_OK;

    /** 买不起（服务端只读预检否掉，一个物品都没扣）。 */
    public static final int RESULT_CANNOT_AFFORD = NetworkHandler.PurchaseResult.RESULT_CANNOT_AFFORD;

    /** 没成功（原子扣费未通过，或该 App 不在报价表里）。 */
    public static final int RESULT_PURCHASE_FAILED = NetworkHandler.PurchaseResult.RESULT_PURCHASE_FAILED;

    /**
     * 一次购买结果的不可变快照：{@link #onPurchaseResult} 在 netty 线程整体发布、
     * UI 线程整体读取（拆成三个 volatile 字段会出现"新 appId + 旧 result"的撕裂）。
     */
    public static final class PurchaseOutcome {

        /** 这条结论属于哪个 App。 */
        public final String appId;
        /** {@link StoreClient#RESULT_OK} / {@link StoreClient#RESULT_CANNOT_AFFORD}
         * / {@link StoreClient#RESULT_PURCHASE_FAILED}。 */
        public final int result;
        /** 客户端收到这条结论的时刻（ms）。 */
        public final long atMs;

        PurchaseOutcome(String appId, int result, long atMs) {
            this.appId = appId == null ? "" : appId;
            this.result = result;
            this.atMs = atMs;
        }
    }

    /** 最近一次购买结果；null = 还没有。 */
    private static volatile PurchaseOutcome lastPurchaseResult;

    private StoreClient() {}

    /** 服务端解锁状态落地（客户端 tick 主线程调用）。 */
    public static void onUnlockSync(List<String> ids) {
        Set<String> next = new HashSet<>(ids);
        Set<String> prev = unlocked;
        unlocked = next;
        if (prev != null) {
            List<String> added = new java.util.ArrayList<>();
            for (String id : next) {
                if (!prev.contains(id)) added.add(id);
            }
            justPurchased = added;
        } else {
            // 首次同步只是补基线，不当作"刚买成"。
            justPurchased = java.util.Collections.emptyList();
        }
        if (!added(prev, next).isEmpty()) lastMessage = null;
        // P4：本次是"进服/登录以来的首次同步"时再确认一次（幂等：已经拉过就不发）。
        // 服务端对拉取请求回的就是同一个 UnlockSync；第二次到达时 prev != null
        // ⇒ justPurchased 为空，不会被误判成"刚买成"。
        if (prev == null) requestSync();
        com.november.mcphone.client.scene.PhoneUi.onStoreSync();
        com.november.mcphone.client.store.StoreFront.onStoreSync();
    }

    /** 离开世界（换服/回主菜单）时复位：旧服已购列表不得带到新服。 */
    public static void reset() {
        unlocked = null;
        justPurchased = java.util.Collections.emptyList();
        lastMessage = null;
        // P4：拉取闸门与购买结果同样按世界会话复位（旧服的结论/节流不得带到新服）。
        syncRequested = false;
        lastSyncRequestMs = 0L;
        lastPurchaseResult = null;
    }

    private static List<String> added(Set<String> prev, Set<String> next) {
        if (prev == null) return java.util.Collections.emptyList();
        List<String> out = new java.util.ArrayList<>();
        for (String id : next) {
            if (!prev.contains(id)) out.add(id);
        }
        return out;
    }

    /** 已购列表是否已经从服务端到位（false = 未同步，UI 必须走"加载中"）。 */
    public static boolean isSynced() {
        return unlocked != null;
    }

    /** 当前已购集合的快照（可安全跨帧持有；未同步时为 null）。 */
    public static Set<String> snapshot() {
        Set<String> cur = unlocked;
        return cur == null ? null : new HashSet<>(cur);
    }

    /** 最近一次同步里新增的已购 id（空 = 没有）。 */
    public static List<String> justPurchased() {
        return justPurchased;
    }

    /** 最近一次购买失败的文案；null = 没有失败。 */
    public static String lastMessage() {
        return lastMessage;
    }

    /** 客户端记一条购买失败文案（商店页展示用；内容是服务端同句的人话）。 */
    public static void noteFailure(String message) {
        lastMessage = message;
    }

    /** 清掉失败提示（下一次成功或进入页面时）。 */
    public static void clearMessage() {
        lastMessage = null;
    }

    // ===================== P4：主动拉取已购 =====================

    /**
     * 主动向服务端要一次已购列表（幂等 + 节流）。
     *
     * <p><b>上游对照</b>：{@code RequestPurchasedAppsPacket}（"我进商店了，告诉我买过哪些"）。
     * 上游的理由是"购买记录在服务端，客户端一无所知，所以每次打开商店都要问一次"。
     * 本侧同样主动问，但触发点更早、不止商店页：</p>
     * <ul>
     *   <li><b>未同步时的任何一次 UI 查询</b>（{@link #isUnlocked} / {@link #needsPurchase}
     *       看到 {@code unlocked == null}）——这两个方法正是主屏
     *       {@code PhoneUi.orderedForHome()} 过滤付费 App、商店页
     *       {@code StoreFront.paidApps()/stateOf()} 列格子时调用的；也就是说"付费 App
     *       眼看要因为未同步被藏起来"的那一刻就顺手拉一次，把窗口压到一个 RTT；</li>
     *   <li><b>收到登录/同步状态之后</b>（{@link #onUnlockSync} 的"本次是首次同步"分支）
     *       再确认一次，防"登录推送丢了/推早了"留下的长期未同步。</li>
     * </ul>
     *
     * <p>商店模式关闭时直接不发：关闭 = v1.0.2 语义（全部直接可用），整条已购同步链路
     * 都不需要，也不该为此多发包。</p>
     *
     * <p>调用点落在查询/渲染路径上，有副作用但严格幂等：一次会话至多一条包
     * （{@link #syncRequested} 闸门 + {@link #SYNC_REQUEST_MIN_GAP_MS} 节流）。</p>
     */
    public static void requestSync() {
        if (!isEnabled()) return;
        if (syncRequested) return;
        long now = System.currentTimeMillis();
        if (now - lastSyncRequestMs < SYNC_REQUEST_MIN_GAP_MS) return;
        syncRequested = true;
        lastSyncRequestMs = now;
        NetworkHandler.sendToServer(new NetworkHandler.RequestUnlockedApps());
    }

    // ===================== P4：购买结果回流 =====================

    /** netty 线程调用：只整体发布一条不可变快照，绝不在这里碰 UI。 */
    public static void onPurchaseResult(String appId, int result) {
        lastPurchaseResult = new PurchaseOutcome(appId, result, System.currentTimeMillis());
    }

    /** 最近一次购买结果；null = 还没有（UI 读它决定画哪句话）。 */
    public static PurchaseOutcome lastPurchaseResult() {
        return lastPurchaseResult;
    }

    /** 最近一次购买结果码；{@link #RESULT_NONE} = 还没有。 */
    public static int lastPurchaseResultCode() {
        PurchaseOutcome r = lastPurchaseResult;
        return r == null ? RESULT_NONE : r.result;
    }

    /** 最近一次结果对应的 App id；没有结果时返回空串。 */
    public static String lastPurchaseResultAppId() {
        PurchaseOutcome r = lastPurchaseResult;
        return r == null ? "" : r.appId;
    }

    /** 最近一次结果的客户端接收时刻（ms）；没有结果时返回 0。 */
    public static long lastPurchaseResultAtMs() {
        PurchaseOutcome r = lastPurchaseResult;
        return r == null ? 0L : r.atMs;
    }

    /** 这条结果是否属于指定 App（UI 防"上一个 App 的结论串到这一个"）。 */
    public static boolean isLastPurchaseResultFor(String appId) {
        PurchaseOutcome r = lastPurchaseResult;
        return r != null && appId != null && r.appId.equals(appId);
    }

    /** 清掉购买结果（例如离开详情页、或开始新一次购买时）。 */
    public static void clearPurchaseResult() {
        lastPurchaseResult = null;
    }

    /** 商店模式是否开启（客户端本地设置；现默认开启＝产品决定，见 PhoneCanvas.isStoreMode）。 */
    public static boolean isEnabled() {
        return PhoneCanvas.isStoreMode();
    }

    /**
     * 该 App 当前是否可用（免费恒真；商店模式关闭恒真；<b>未同步过恒真</b>）。
     *
     * <p>未同步时返回 true 是刻意的保守值：主屏宁可在同步到达前多显示几个图标，
     * 也不能把"还不知道买没买"的 App 从主屏摘掉（错摘比多显示更糟）。</p>
     *
     * <p>P4：本方法在"未同步"时会顺手触发一次 {@link #requestSync()}（幂等、非阻塞、
     * 商店模式关闭时不发），返回值语义与改动前逐字一致。</p>
     */
    public static boolean isUnlocked(IPhoneApp app) {
        if (app == null || !app.isBuiltin()) return true;
        if (!isEnabled()) return true;
        if (AppPrices.priceOf(app.id()) == null) return true;
        Set<String> owned = unlocked;
        // P4：未同步 ⇒ 顺手拉一次（主屏 orderedForHome()/锁标渲染都会走这里）。
        if (owned == null) requestSync();
        return owned == null || owned.contains(app.id());
    }

    /**
     * 商店页/主屏过滤用：这个 App 是否还"待购"（有价的内建 App 且尚未解锁）。
     *
     * <p><b>B-2 修复点</b>：旧实现要求 {@code owned != null}，于是"还没同步"被当成了
     * "已购"⇒ 商店页显示「所有付费应用均已购买」的假空态、购买按钮根本不出现（用户报的
     * 「点购买没有反应」的直接原因之一）。现在未同步一律返回 true（= 还没确认买过），
     * 商店页据此显示"正在同步…"而不是空态；UI 的按钮态由
     * {@code StoreFront.stateOf} 给出 {@code LOADING}，刻意不画成"没买过"。</p>
     *
     * <p>P4：本方法也是"付费 App 从主屏消失"那一句判定的判定点
     * （{@code PhoneUi.orderedForHome()}），所以未同步时在这里顺手触发一次
     * {@link #requestSync()}（幂等、非阻塞）。返回值语义与改动前逐字一致。</p>
     */
    public static boolean needsPurchase(IPhoneApp app) {
        if (app == null || !app.isBuiltin() || !isEnabled()) return false;
        if (AppPrices.priceOf(app.id()) == null) return false;
        Set<String> owned = unlocked;
        // 未同步（owned == null）⇒ 尚未确认买过 ⇒ 仍算待购（旧实现这里返回 false 是 B-2 的根因）。
        if (owned == null) {
            // P4：主动拉一次，把"付费 App 被主屏藏起来"的窗口压到一个 RTT。语义不变（照样 true）。
            requestSync();
            return true;
        }
        return !owned.contains(app.id());
    }

    /** 该 App 的价格物品栈；免费返回 null（商店详情页的价格行/买不起判定用）。 */
    public static ItemStack price(IPhoneApp app) {
        return app == null ? null : AppPrices.priceOf(app.id());
    }

    /** 价格展示文案（如 "末影箱 x1"）；免费返回 null。 */
    public static String priceText(IPhoneApp app) {
        ItemStack price = app == null ? null : AppPrices.priceOf(app.id());
        return price == null ? null : price.getDisplayName() + " x" + price.stackSize;
    }
}
