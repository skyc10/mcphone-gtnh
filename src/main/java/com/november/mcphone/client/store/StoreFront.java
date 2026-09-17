package com.november.mcphone.client.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.node.TextHorizontalAlign;
import club.heiqi.uilib.ui.scene.runtime.MountHandle;
import club.heiqi.uilib.ui.scene.runtime.SceneScrolls;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.api.PhoneWidgets;
import com.november.mcphone.client.StoreClient;
import com.november.mcphone.client.enhance.PhoneGlass;
import com.november.mcphone.client.enhance.PhoneTheme;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.net.NetworkHandler;

/**
 * 应用商店（按上游形态重做）：首页 = 4 列图标网格，详情页 = 32px 大图标 + 名称 +
 * 作者/版本 + 分隔线 + 简介 + 价格行 + 整宽按钮；list<->detail 在本 App 内部双槽切换，
 * 不动 {@link PhoneUi} 的全局导航（GTNH 导航只有一级，无栈）。
 *
 * <p><b>上游对照</b>（november521/mcphone tag <b>v1.10.2</b>：
 * {@code shared/src/main/java/com/november/mcphone/feature/store/client/}，
 * 只读 clone 在 {@code <upstream-mcphone>}，用
 * {@code git show v1.10.2:<path>} 读）：</p>
 * <ul>
 *   <li>{@code feature/store/client/AppStore.java} -> {@link #listView()}：4 列网格
 *       （上游 {@code APP_COLUMNS=4} / 图标 20 / 横距 8 / 名字在图标下）+ 标题 +
 *       1px 分隔线 + 空态一行灰字；</li>
 *   <li>{@code feature/store/client/AppDetail.java} -> {@link #detailView()}：
 *       {@code PAD=6} + 32px 图标 + 名称 + {@code 作者 · v版本} + 1px 分隔线 + 简介
 *       （按宽截断）+ 亮黄价格行 + 整宽 16px 绿色近直角按钮（底边距屏底 1）；</li>
 *   <li>{@code AppDetail.state()} -> {@link #stateOf(IPhoneApp)}：四态判定，
 *       关键不变量是"还不知道"绝不画成"没买过"。</li>
 *   <li>{@code feature/store/client/CompanionApps.java} -> {@link CompanionAppsPage}：
 *       联动页（图标 16 / 行高 21 / 名字压暗 {@code #949494} / 右侧「未装」浅灰
 *       {@code #CACBCB} / 无按钮 / 无分页）；入口挂在首页网格的<b>最后一格</b>，
 *       与上游 {@code AppStore.isCompanionCell()}（{@code cell == available.size()}）
 *       同一个位置。</li>
 * </ul>
 *
 * <p><b>刻意的平台差异</b>：上游按钮是纯色直角矩形（{@code textures/store/button.png}
 * 根本不在上游仓库，走 {@code g.fill} 兜底）；本类改走本机既有的 accent/玻璃体系与
 * 全局字号缩放（{@code PhoneUi.fs}），版式抄上游、外观跟随 GTNH 手机。</p>
 */
public final class StoreFront {

    // ===================== 上游网格/详情参数 =====================

    /** 每行格子数（上游 {@code PhoneTheme.APP_COLUMNS=4}）。 */
    public static final int COLUMNS = 4;

    /** 图标盒边长基准（上游 {@code APP_ICON_SIZE=20}；按格子宽自适应，钳在 [20,28]）。 */
    private static final int ICON_SIZE = 20;

    /** 图标与名字的间距（上游 6）。 */
    private static final int ICON_LABEL_GAP = 6;

    /** 格子横距（上游 {@code APP_GRID_SPACING_X=8}）。 */
    private static final int GRID_GAP = 8;

    /**
     * 页面左右内边距。
     *
     * <p>上游 {@code AppDetail.PAD = 6}（{@code AppDetail.java:26}）：
     * {@code w = screenW - PAD*2}，在 120 GUI 宽的屏上就是 <b>108</b>。
     * 实测（{@code _r2_shots.md} §3.3）分隔线与按钮同为 108 GUI 宽、左右各留 6，
     * 两条独立证据吻合，故取 6。</p>
     */
    private static final int PAGE_PAD = 6;

    /** 格子宽下限（面板过窄时兜底，避免除零/负宽）。 */
    private static final int MIN_CELL_W = 48;

    /** 详情页大图标（上游 {@code AppDetail.BIG_ICON=32}）。 */
    private static final int BIG_ICON = 32;

    /**
     * 详情页按钮高（上游 {@code AppDetail.BUTTON_H=16}；贴图 {@code textures/store/button.png}
     * 的原始尺寸也是 <b>100×16</b>，九宫格拉伸后纵向正好 16 格——16 就是它的原生高度）。
     */
    private static final int BUTTON_H = 16;

    /**
     * 详情页按钮底边距（上游 {@code AppDetail.java:164} 的
     * {@code btnY = bottom - BUTTON_H - 1}：底边落在导航栏上方 <b>1</b> GUI）。
     *
     * <p>这里表达成详情页 column 的<b>下内边距</b>：页面容器底边恒等于屏底（= 导航栏顶），
     * 于是这个 1 就是"按钮底边距屏底 1 GUI"，与上游同一个数。</p>
     */
    private static final int DETAIL_BOTTOM_INSET = 1;

    /** 简介最多画几行（上游按剩余高度逐行 break，Qz 用 maxLines+ellipsis 表达）。 */
    private static final int DESC_MAX_LINES = 6;

    /** 前端判定失败的宽限帧数：约 5 秒（20TPS），超时按"没买成"报。 */
    private static final int BUY_TIMEOUT_FRAMES = 100;

    /**
     * 按钮四态（对齐上游 {@code AppDetail.State}）。
     *
     * <p>上游还有 {@code INSTALLED}（已安装）；GTNH 没有"安装/卸载"两步模型
     * （购买即解锁 + 主屏开关），所以本侧用 {@link #UNLOCKED}（已解锁）承接那一态。</p>
     */
    public enum State {
        /** 未同步（有价但服务端已购列表还没到）——刻意不画成"没买过"。 */
        LOADING,
        /** 已解锁（上游 = 已安装）。 */
        UNLOCKED,
        /** 免费或已购，可"安装"（上游 DOWNLOAD）。 */
        DOWNLOAD,
        /** 有价、未购、背包里的付费物品不够（上游 CANT_AFFORD）。 */
        CANT_AFFORD,
        /** 有价、未购、付得起（上游 BUY）。 */
        BUY
    }

    // ===================== 页面状态 =====================

    private final PhoneUi ui;

    /** 页面槽：一次只挂一棵子树（list 或 detail），同 {@code ScenePages.PageSlot} 手法。 */
    private final SceneNode slot;

    private MountHandle handle;

    /** 详情页看的是哪个 App；null = 首页。 */
    private String detailAppId;

    /** 联动 App 页是否打开（list / detail / companion 三态共用同一个槽）。 */
    private boolean companionOpen;

    /** 在途购买 id；null = 没有在途请求。 */
    private String pendingBuyId;

    /** 购买发出时的已购集合快照：购买只增不减，集合变大即成功。 */
    private Set<String> purchaseBaseline;

    /** 在途请求剩余宽限帧数。 */
    private int pendingFrames;

    /** 详情页反馈行文案（购买中/成功/失败/买不起），null = 不画。 */
    private String message;

    private StoreFront(PhoneUi ui) {
        this.ui = ui;
        LIVE.put(ui, this);
        this.slot = SceneNode.column();
        this.slot.setFillParentWidth(true);
        // 显式高度先验：grow 求解器在部分容器旁会早退，内容高度会塌陷（项目踩坑）。
        this.slot.setPreferredHeight(Math.max(120, ui.contentHeight()));
        this.slot.setClipChildren(true);
        // 逐帧心跳：Qz 没有"页面 tick"公开钩子，而 ClientHooks 属禁改文件；
        // 用 layoutDoneSignal（每帧 SETTLE 至少发布一次）派生 computed 当帧计数器。
        final int[] frameNo = {0};
        ui.runtime().bindComputed(
            () -> {
                ui.runtime().layoutDoneSignal().get();
                return Integer.valueOf(++frameNo[0]);
            },
            ignored -> onFrame());
    }

    /**
     * 商店 App 的页面入口（BuiltinApps.store() -> ScenePages.storePage -> 本方法）。
     *
     * <p>P4-D：进门先问一次服务端"我买过哪些"——上游 {@code RequestPurchasedAppsPacket}
     * 的同一意图（购买记录只在服务端，客户端一无所知）。{@link StoreClient#requestSync()}
     * 自带 {@code syncRequested} 闸门 + {@code SYNC_REQUEST_MIN_GAP_MS} 节流，
     * 一次会话至多一条包，重复打开商店页不会刷包。</p>
     */
    public static SceneNode page(PhoneUi ui) {
        // 幂等 + 节流（见 StoreClient.requestSync 的契约）；也是"未同步期付费 App 不在主屏"
        // 那个窗口的收口点之一。
        StoreClient.requestSync();
        StoreFront front = new StoreFront(ui);
        front.showList();
        return front.slot;
    }

    /**
     * 活着的商店页（通常 0~1 个：主屏手机与常显 HUD 各可能有一个）。
     *
     * <p>用 WeakHashMap 是为了不拦住已废弃的手机实例：键是 {@link PhoneUi}，
     * 手机销毁后条目自然消失。</p>
     */
    private static final Map<PhoneUi, StoreFront> LIVE =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<PhoneUi, StoreFront>());

    /** 服务端已购同步到达（{@link StoreClient#onUnlockSync} 转发；主线程）。 */
    public static void onStoreSync() {
        List<StoreFront> fronts;
        synchronized (LIVE) {
            fronts = new ArrayList<>(LIVE.values());
        }
        for (StoreFront front : fronts) {
            front.onSync();
        }
    }

    // ===================== 槽位切换 =====================

    private void swap(SceneNode page) {
        if (handle != null) {
            handle.dispose();
            handle = null;
        }
        if (page != null) {
            handle = ui.runtime().mount(slot, () -> page);
        }
    }

    /** 回商店首页并刷新列表（购买成功后也走这里）。 */
    public void showList() {
        detailAppId = null;
        companionOpen = false;
        swap(listView());
    }

    /**
     * 进联动 App 页（上游 {@code CompanionApps}）。
     *
     * <p>与详情页共用同一个槽（{@link #slot}），不动 {@link PhoneUi} 的全局导航。
     * 这一页<b>整页没有按钮</b>（上游也是：行不可点、只有翻页可点；本侧改成滚动，
     * 于是连翻页也没有），所以页内不提供返回——回列表走手机底部导航的「⌂」，
     * 或重新点开商店 App（那时 {@link #page(PhoneUi)} 会重新 {@link #showList()}）。</p>
     */
    public void showCompanions() {
        detailAppId = null;
        companionOpen = true;
        swap(CompanionAppsPage.page(ui));
    }

    /** 进详情页。 */
    public void openDetail(String appId) {
        IPhoneApp app = PhoneApi.byId(appId);
        if (app == null) return;
        detailAppId = appId;
        companionOpen = false;
        // 换页清掉上一条反馈：它不是当前这个 App 的结论。
        message = null;
        // P4-C：上一条购买结果也一并清掉，防"上一个 App 的结论串到这一个"
        // （StoreClient 只保留最近一条结果，不清就会在换页瞬间画出别人的结论）。
        StoreClient.clearPurchaseResult();
        swap(detailView());
    }

    /** 重建当前槽（保持 list/detail 与选中 App 不变）。 */
    private void rebind() {
        if (companionOpen) {
            // 联动页的数据只与"装了哪些模组"有关，重建一次纯属重画；代价可忽略。
            swap(CompanionAppsPage.page(ui));
            return;
        }
        if (detailAppId == null) {
            swap(listView());
            return;
        }
        if (PhoneApi.byId(detailAppId) == null) {
            showList();
            return;
        }
        swap(detailView());
    }

    /**
     * 服务端已购同步到达（由 {@link StoreClient#onUnlockSync} 转发）。
     *
     * <p>这是 B-1（失败在手机里零反馈）的正向路径：服务端只在<b>成功</b>后回
     * {@code UnlockSync}，所以"集合变大了"即成功；"同步到了但快照没变"说明服务端
     * 拒绝了这次购买（失败路径只发聊天栏，见报告"未做到的上游特性"）。</p>
     */
    public void onSync() {
        if (pendingBuyId != null) {
            IPhoneApp app = PhoneApi.byId(pendingBuyId);
            if (purchasedNow(pendingBuyId)) {
                message = tr("msg.mcphone.store_bought_detail", nameOf(app));
                clearPending();
                rebind();
                return;
            }
            if (StoreClient.isSynced()) {
                message = tr("msg.mcphone.store_buy_no_effect", nameOf(app));
                clearPending();
                rebind();
                return;
            }
        }
        rebind();
    }

    private void clearPending() {
        pendingBuyId = null;
        purchaseBaseline = null;
        pendingFrames = 0;
    }

    /**
     * 逐帧：在途购买的三条收口路径（按优先级）。
     *
     * <p><b>1) P4-B 精确结果优先</b>：服务端新增的 {@code PurchaseResult} 是权威结论
     * （成功/买不起/失败三码），一到就按它写文案并重绘。为什么必须<b>逐帧</b>读：Qz 的
     * 文本是建树时写死的，失败路径服务端<b>不发</b> {@code UnlockSync}，只接显示逻辑
     * （{@code detailView} 里的 P4-A 分支）就没有任何重绘触发点，失败文案永远不会出现——
     * 本方法的心跳（{@code layoutDoneSignal} 派生 computed）正是那个触发点。</p>
     *
     * <p><b>2) 已购集合快照变化</b>：{@link #purchasedNow} 是旧路径的兜底，
     * {@code UnlockSync} 到达时会经 {@link #onSync} 走这里，两条路不冲突
     * （先到的那条清 pending，后到的看不到 pending）。</p>
     *
     * <p><b>3) 100 帧倒计时（{@link #BUY_TIMEOUT_FRAMES}）保留为最后兜底</b>：
     * 结果包在 UDP 类通道上理论上可能丢（本侧走 Forge 简单网络通道，丢包即永久丢），
     * 删掉它会让按钮永远停在"正在购买…"。精确结果只在"该来的没来"之后才轮到它。</p>
     */
    private void onFrame() {
        if (pendingBuyId == null) return;
        // 1) 服务端精确结果（P4-B）。
        if (StoreClient.isLastPurchaseResultFor(pendingBuyId)) {
            applyPurchaseResult(StoreClient.lastPurchaseResultCode(), pendingBuyId);
            return;
        }
        // 2) 已购集合快照变化（旧路径，仍有效）。
        if (purchasedNow(pendingBuyId)) {
            onSync();
            return;
        }
        // 3) 兜底倒计时。
        if (--pendingFrames > 0) return;
        IPhoneApp app = PhoneApi.byId(pendingBuyId);
        message = tr("msg.mcphone.store_buy_no_effect", nameOf(app));
        clearPending();
        rebind();
    }

    /**
     * 把服务端 {@code PurchaseResult} 结果码翻成手机里的一行字（P4-B 的落点）。
     *
     * <p>三码与服务端 {@code NetworkHandler.PurchaseResult.RESULT_*} 同源
     * （{@link StoreClient#RESULT_OK} 等常量就是它们的别名）。文案表：</p>
     *
     * <ul>
     *   <li>{@code RESULT_OK} → {@code msg.mcphone.store_bought_detail}（已解锁：%s）</li>
     *   <li>{@code RESULT_CANNOT_AFFORD} → {@code msg.mcphone.store_cant_afford_hint}（背包不够）</li>
     *   <li>{@code RESULT_PURCHASE_FAILED} → {@code msg.mcphone.store_buy_rejected}（服务端拒绝，未扣物品）</li>
     * </ul>
     *
     * <p><b>不猜</b>：{@code RESULT_NONE} / 未知码一律退回旧的"购买没有生效"文案，
     * 绝不把未知码画成成功。</p>
     */
    private void applyPurchaseResult(int code, String appId) {
        IPhoneApp app = PhoneApi.byId(appId);
        // 用 if/else 而不是 switch：StoreClient.RESULT_* 是 `int = NetworkHandler.PurchaseResult.RESULT_*`
        // （源头是 byte 常量，byte→int 的初始化不是常量表达式），javac 会拒绝拿它当 case 标签
        // （实测 "constant expression required"）。if/else 与之语义等价、且不依赖编译期常量。
        if (code == StoreClient.RESULT_OK) {
            message = tr("msg.mcphone.store_bought_detail", nameOf(app));
        } else if (code == StoreClient.RESULT_CANNOT_AFFORD) {
            message = tr("msg.mcphone.store_cant_afford_hint");
        } else if (code == StoreClient.RESULT_PURCHASE_FAILED) {
            message = tr("msg.mcphone.store_buy_rejected", nameOf(app));
        } else {
            message = tr("msg.mcphone.store_buy_no_effect", nameOf(app));
        }
        clearPending();
        rebind();
    }

    /** 这次购买是否已生效：快照里没有它、而现在已解锁（免费 App 视为立即生效）。 */
    private boolean purchasedNow(String appId) {
        if (appId == null) return false;
        IPhoneApp app = PhoneApi.byId(appId);
        if (app == null || !app.isBuiltin()) return true;
        if (StoreClient.price(app) == null) return true;
        if (!StoreClient.isSynced()) return false;
        Set<String> baseline = purchaseBaseline;
        return baseline != null && !baseline.contains(appId) && StoreClient.isUnlocked(app);
    }

    // ===================== 首页：4 列图标网格 =====================

    private SceneNode listView() {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setFillParentHeight(true);
        page.setPadding(PAGE_PAD);
        page.setGap(8);
        page.setScrollable(true);
        page.setClipChildren(true);
        // 滚轮必须显式 attach（setScrollable 只声明可滚动；项目踩坑 #2）。
        SceneScrolls.attach(ui.runtime(), page);

        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.store")));
        page.appendChild(separator());

        if (!StoreClient.isEnabled()) {
            // 商店模式总开关关着（GTNH 特有；上游没有这个开关）——照旧给一行灰字。
            page.appendChild(PhoneUi.muted(
                StatCollector.translateToLocal("msg.mcphone.store_closed_hint")));
            return page;
        }

        List<IPhoneApp> paid = paidApps();
        boolean synced = StoreClient.isSynced();
        // 联动入口永远排在首页网格的最后一格（上游 AppStore.isCompanionCell）。
        boolean hasCompanion = CompanionAppsPage.hasAny();

        if (!synced) {
            // B-2：未同步 = 加载中，绝不显示成"所有付费应用均已购买"。
            page.appendChild(PhoneUi.muted(
                StatCollector.translateToLocal("msg.mcphone.store_syncing")));
        }
        String failed = StoreClient.lastMessage();
        if (failed != null && !paid.isEmpty()) {
            page.appendChild(notice(failed));
        }

        // 上游 cellCount() = available.size() + (hasCompanion ? 1 : 0)：只有"一个格子
        // 都没有"时才是空态。有联动入口时即便待购列表为空也要画网格（否则玩家看不
        // 到那个入口）。
        if (paid.isEmpty() && !hasCompanion) {
            page.appendChild(PhoneUi.muted(StatCollector.translateToLocal(
                synced ? "msg.mcphone.store_empty" : "msg.mcphone.store_loading")));
            page.appendChild(PhoneUi.muted(
                StatCollector.translateToLocal("msg.mcphone.store_footnote")));
            return page;
        }

        page.appendChild(grid(paid, hasCompanion));
        page.appendChild(PhoneUi.muted(
            StatCollector.translateToLocal("msg.mcphone.store_footnote")));
        return page;
    }

    /** 首页格子 = 有价且未购的内建 App（已解锁的从商店消失、只留主屏，与上游同语义）。 */
    private static List<IPhoneApp> paidApps() {
        List<IPhoneApp> out = new ArrayList<>();
        for (IPhoneApp app : PhoneApi.orderedApps()) {
            if (StoreClient.needsPurchase(app)) out.add(app);
        }
        return out;
    }

    /**
     * 4 列网格：Qz <b>没有 flex-wrap</b>（{@code SceneNode} 无 wrap API），因此
     * <b>手动分行</b>——外层 column，每 {@link #COLUMNS} 个格子一个显式 row，
     * 行内不足 4 个补不可命中的占位格（视觉上左对齐）。
     *
     * <p>尺寸先验（不依赖 grow 求解）：row 高 = 图标 + 间距 + 名字行高；
     * 单元格宽 = (可用宽 - 3*间距)/4，图标盒钳在 [20,28] 且不超过格子宽。</p>
     */
    private SceneNode grid(List<IPhoneApp> apps, boolean hasCompanion) {
        int inner = Math.max(140, ui.panelWidth() - 2 * PAGE_PAD);
        int cellW = Math.max(MIN_CELL_W, (inner - (COLUMNS - 1) * GRID_GAP) / COLUMNS);
        int icon = Math.min(ICON_SIZE + 8, Math.max(ICON_SIZE, cellW - 2 * GRID_GAP));
        int nameH = Math.max(10, Math.round(ui.runtime().lineHeight(PhoneUi.fs(13)) * 0.85f));
        int rowH = icon + ICON_LABEL_GAP + nameH;

        SceneNode grid = SceneNode.column();
        grid.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        grid.setGap(GRID_GAP);

        // 格子总数 = 待购 App + 联动入口（末尾那一格），与上游 cellCount() 同构；
        // 行内不足 4 格照旧补不可命中的占位格（视觉左对齐）。
        int cells = apps.size() + (hasCompanion ? 1 : 0);
        for (int i = 0; i < cells; i += COLUMNS) {
            SceneNode row = SceneNode.row();
            row.setWidthSizing(SceneNode.WidthSizing.SHRINK);
            row.setGap(GRID_GAP);
            row.setCrossAxisAlign(CrossAxisAlign.START);
            row.setPreferredHeight(rowH);
            for (int j = 0; j < COLUMNS; j++) {
                int idx = i + j;
                if (idx < apps.size()) {
                    row.appendChild(cell(apps.get(idx), cellW, icon));
                } else if (idx == apps.size() && hasCompanion) {
                    row.appendChild(companionCell(cellW, icon));
                } else {
                    row.appendChild(filler(cellW, rowH));
                }
            }
            grid.appendChild(row);
        }
        return grid;
    }

    /** 一格：图标盒 + 名字在下（点整格进详情）。 */
    private SceneNode cell(IPhoneApp app, int cellW, int icon) {
        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        cell.setPreferredWidth(cellW);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(ICON_LABEL_GAP);
        cell.appendChild(iconBox(app, icon, Math.max(4, icon / 3)));
        SceneNode label = new SceneNode();
        label.setText(app.displayName());
        label.setTextColor(PhoneTheme.text());
        label.setFontSize(PhoneUi.fs(13));
        label.setMaxTextWidth(cellW);
        label.setTextHorizontalAlign(TextHorizontalAlign.CENTER);
        label.setHitTestable(false);
        cell.appendChild(label);
        final String id = app.id();
        ui.runtime().on(cell, SceneEventType.CLICK, (event, dispatch) -> ui.post(() -> openDetail(id)));
        return cell;
    }

    /**
     * 「联动App」那一格（{@link CompanionAppsPage} 的入口，排在所有待购 App 之后）。
     *
     * <p>上游 {@code AppStore.drawCompanionCell()}：入口不进 {@code available} 列表，
     * 只是网格末尾多出来的一个格子，命中判定走合并后的格子索引。本侧照同一套做：
     * 格子宽/高与普通格子逐字相同，只有图标与名字来自 {@link CompanionAppsPage}。</p>
     */
    private SceneNode companionCell(int cellW, int icon) {
        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        cell.setPreferredWidth(cellW);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(ICON_LABEL_GAP);
        cell.appendChild(CompanionAppsPage.entryIcon(icon));
        SceneNode label = new SceneNode();
        label.setText(CompanionAppsPage.entryLabel());
        label.setTextColor(PhoneTheme.text());
        label.setFontSize(PhoneUi.fs(13));
        label.setMaxTextWidth(cellW);
        label.setTextHorizontalAlign(TextHorizontalAlign.CENTER);
        label.setHitTestable(false);
        cell.appendChild(label);
        ui.runtime().on(cell, SceneEventType.CLICK, (event, dispatch) -> ui.post(this::showCompanions));
        return cell;
    }

    /** 不可命中的占位格：把最后一行撑成 4 列。 */
    private static SceneNode filler(int w, int h) {
        SceneNode n = SceneNode.column();
        n.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        n.setPreferredWidth(w);
        n.setPreferredHeight(h);
        n.setHitTestable(false);
        return n;
    }

    /**
     * 图标盒：与主屏同源（纹理 &gt; 物品 &gt; 字形）+ accent 实色底。
     *
     * <p>包内可见：{@link CompanionAppsPage} 的 16px 行内图标复用它，三页同一套画法
     * （上游 {@code GuiUtil.drawTexture} 也是主屏/商店/联动页共用）。</p>
     */
    static SceneNode iconBox(IPhoneApp app, int size, int radius) {
        SceneNode box = SceneNode.row();
        box.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        box.setPreferredWidth(size);
        box.setPreferredHeight(size);
        box.setCornerRadius(radius);
        box.setBackgroundColor(app.iconColor());
        box.setBorderWidth(1);
        box.setBorderColor(0x2EFFFFFF);
        box.setMainAxisAlign(MainAxisAlign.CENTER);
        box.setCrossAxisAlign(CrossAxisAlign.CENTER);
        box.setHitTestable(false);
        ItemStack item = app.iconItem();
        String tex = app.iconTexture();
        if (tex != null) {
            box.setImageSource(HostImageSource.texture(
                new net.minecraft.util.ResourceLocation(tex), 128, 128));
        } else if (item != null) {
            box.setImageSource(HostImageSource.itemIcon(item));
        } else {
            SceneNode glyph = new SceneNode();
            glyph.setText(app.iconGlyph());
            glyph.setTextColor(0xFFFFFFFF);
            glyph.setFontSize(PhoneUi.fs(Math.max(10, size / 2)));
            glyph.setHitTestable(false);
            box.appendChild(glyph);
        }
        return box;
    }

    // ===================== 详情页 =====================

    private SceneNode detailView() {
        IPhoneApp app = PhoneApi.byId(detailAppId);
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setFillParentHeight(true);
        // 下内边距 = DETAIL_BOTTOM_INSET（上游 btnY = bottom - BUTTON_H - 1）：
        // 中间的 spacer() 吃掉全部余量，按钮因此恒贴在页面底边之上 1 GUI，与内容多少无关。
        page.setPadding(PAGE_PAD, PAGE_PAD, DETAIL_BOTTOM_INSET, PAGE_PAD);
        page.setGap(8);
        page.setClipChildren(true);

        if (app == null) {
            page.appendChild(PhoneUi.muted(
                StatCollector.translateToLocal("msg.mcphone.store_empty")));
            return page;
        }

        // 标题行 + 返回：GTNH 导航只有一级（PhoneUi.swapPage 无栈），详情页必须有页内返回。
        SceneNode head = SceneNode.row();
        head.setFillParentWidth(true);
        head.setCrossAxisAlign(CrossAxisAlign.CENTER);
        head.setGap(6);
        head.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.store")));
        head.appendChild(spacer());
        head.appendChild(secondaryButton(tr("btn.mcphone.back"), this::showList));
        page.appendChild(head);
        page.appendChild(separator());

        // 32px 大图标 + 名称 + 「作者 · v版本」。
        SceneNode top = SceneNode.row();
        top.setFillParentWidth(true);
        top.setCrossAxisAlign(CrossAxisAlign.CENTER);
        top.setGap(8);
        top.appendChild(iconBox(app, BIG_ICON, Math.max(6, BIG_ICON / 4)));
        SceneNode texts = SceneNode.column();
        texts.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        texts.setGap(3);
        SceneNode name = text(app.displayName(), PhoneTheme.text(), 18);
        name.setMaxTextWidth(Math.max(60, ui.panelWidth() - 2 * PAGE_PAD - BIG_ICON - 8));
        texts.appendChild(name);
        texts.appendChild(text(metaOf(app), PhoneTheme.muted(), 13));
        top.appendChild(texts);
        page.appendChild(top);
        page.appendChild(separator());

        // 简介：按宽折行后截断（maxLines + ellipsis，等价上游 font.split 后 break）。
        SceneNode desc = text(descriptionOf(app), PhoneTheme.text(), 14);
        // Qz 的折行/省略号是显式声明制：不设 maxTextWidth 时整串按一行画、右侧被裁，
        // 且 setEllipsis 需要 wrapWidth>0 才生效（SceneLineClamp.java:62）。
        desc.setMaxTextWidth(Math.max(80, ui.panelWidth() - 2 * PAGE_PAD));
        desc.setMaxLines(DESC_MAX_LINES);
        desc.setEllipsis(true);
        page.appendChild(desc);

        page.appendChild(spacer());

        // 反馈行（B-1：失败也要在手机里有字，不能只发聊天栏）。
        State state = stateOf(app);
        String line = message;
        // P4-A：storefront 自己记的 message 优先；其次读一次服务端的精确结果。
        // 这一段覆盖"结果已到但这一帧还没轮到 onFrame"的窗口，两处文案同源不打架。
        if (line == null && StoreClient.isLastPurchaseResultFor(app.id())) {
            // if/else 而非 switch：理由同 applyPurchaseResult（RESULT_* 不是编译期常量）。
            int result = StoreClient.lastPurchaseResultCode();
            if (result == StoreClient.RESULT_CANNOT_AFFORD) {
                line = tr("msg.mcphone.store_cant_afford_hint");
            } else if (result == StoreClient.RESULT_PURCHASE_FAILED) {
                line = tr("msg.mcphone.store_buy_rejected", app.displayName());
            } else if (result == StoreClient.RESULT_OK) {
                line = tr("msg.mcphone.store_bought_detail", app.displayName());
            }
        }
        if (line == null && pendingBuyId != null && pendingBuyId.equals(app.id())) {
            line = tr("msg.mcphone.store_purchasing");
        }
        if (line == null && state == State.CANT_AFFORD) {
            line = tr("msg.mcphone.store_cant_afford_hint");
        }
        if (line != null) page.appendChild(notice(line));

        // 价格行（上游：免费显示"免费"，有价显示代价描述）。
        ItemStack price = StoreClient.price(app);
        page.appendChild(text(
            price == null ? tr("msg.mcphone.store_free") : priceText(price),
            price == null ? PhoneTheme.muted() : PRICE_COLOR,
            14));

        page.appendChild(button(app, state));
        return page;
    }

    /**
     * 详情页唯一的按钮（上游 {@code AppDetail.render} 的按钮区，{@code AppDetail.java:163-186}）：
     * <b>整宽 108 × 16 GUI、近直角、无描边、绿色纵向渐变</b>，底边距屏底 {@link #DETAIL_BOTTOM_INSET}。
     *
     * <p>框与字对齐上游：{@code btnX=x(PAD=6)}、{@code btnW=w(108)}、
     * {@code btnY=bottom-BUTTON_H-1}；字宽高居中（{@code (BUTTON_H - lineHeight)/2 + 1}，
     * Qz 用 {@code MainAxisAlign.CENTER} 表达）；启用字 {@code FONT_COLOR_BUTTON=0xFFFFFFFF}，
     * 禁用字 {@code FONT_COLOR_BUTTON_DISABLED=0xFF888888}。</p>
     *
     * <p><b>本机差异（刻意）</b>：上游把这个矩形交给 {@code PhoneSkin.drawOrFill} ——
     * 有贴图（{@code textures/store/button.png}，100×16 自带绿色纵向渐变）就用贴图，
     * 没贴图才退回纯色 {@code COLOR_BUTTON}。本侧<b>不引入新贴图资源</b>（只允许改本文件），
     * 因此用"多段实心色带 + 一条顶亮边"模拟同一张贴图的纵向剖面，
     * 见 {@link #buttonGradientBands} / {@link #buttonGradientHeights}。</p>
     */
    private SceneNode button(IPhoneApp app, State state) {
        boolean enabled = state == State.BUY || state == State.DOWNLOAD;
        String label = labelOf(state);

        SceneNode btn = SceneNode.column();
        btn.setWidthSizing(SceneNode.WidthSizing.FILL);
        // 关键容器用显式高度先验（不依赖 grow/内容求解）：高度就是上游 BUTTON_H=16，
        // 不再叠 14 的内边距（旧实现 16+14=30，比上游高一倍）。
        btn.setPreferredHeight(BUTTON_H);
        // 上游贴图近似直角（100×16，圆角 ≤3px）且无描边：圆角 0、边框 0。
        btn.setCornerRadius(0);
        btn.setBorderWidth(0);
        btn.setMainAxisAlign(MainAxisAlign.CENTER);
        btn.setCrossAxisAlign(CrossAxisAlign.CENTER);
        // 渐变用色带铺底；文字层叠在它上面（Qz 绘制序：BACKGROUND 先于 TEXT、父先于子）。
        paintBands(btn, enabled ? buttonGradientBands() : DISABLED_BANDS,
            enabled ? buttonGradientHeights() : DISABLED_HEIGHTS);

        SceneNode text = new SceneNode();
        text.setText(label);
        text.setTextColor(enabled ? FONT_COLOR_BUTTON : FONT_COLOR_BUTTON_DISABLED);
        text.setFontSize(PhoneWidgets.buttonFontSize(ui));
        text.setHitTestable(false);
        btn.appendChild(text);

        if (!enabled) {
            // 上游：btnEnabled = (s == BUY || s == DOWNLOAD)，其它态（加载中/已安装/买不起）
            // 一律画灰底灰字且点不动。这里只关可点性，判定逻辑一字未动。
            btn.setHitTestable(false);
            return btn;
        }
        // hover：上游给一次整张提亮（SKIN_HOVER_BRIGHTNESS=1.8）/ 兜底色换 COLOR_BUTTON_HOVER；
        // 本侧色带是"贴图等价物"，按整组色带换亮版。
        var interaction = ui.runtime().interactionState(btn);
        final int[] base = buttonGradientBands();
        final int[] lit = buttonGradientBandsHover();
        ui.runtime().bindComputed(
            () -> Boolean.TRUE.equals(interaction.hovered().get()),
            hovered -> {
                int[] colors = Boolean.TRUE.equals(hovered) ? lit : base;
                for (int i = 1; i < btn.__getChildren().size(); i++) {
                    btn.__getChildren().get(i).setBackgroundColor(colors[i - 1]);
                }
            });
        final String id = app.id();
        ui.runtime().on(btn, SceneEventType.CLICK, (event, dispatch) -> ui.post(() -> onButton(id)));
        return btn;
    }

    // ===================== 按钮纵向渐变（贴图 button.png 的剖面等价物） =====================

    /**
     * 上游按钮贴图的纵向剖面（只读参考 clone，{@code git show v1.10.2:}）：
     *
     * <pre>
     * textures/store/button.png          100×16  中心列逐行（hex）
     *   y=0        #55A459   ← 顶亮边（既有贴图常量，非 COLOR_BUTTON）
     *   y=1..14    #2D7B31 → #256729   线性变暗
     *   y=15       #246528
     * textures/store/button_disabled.png 100×16
     *   y=0        #383838   ← 顶亮边
     *   y=1..14    #1B1B1B → #151515
     *   y=15       #141414
     * </pre>
     *
     * <p>实测截图（{@code _r2_shots.md} §3.3）在 GUI 放大 + 九宫格纵向拉伸后读到的是
     * 顶 4px {@code #52B860} → 中 {@code #327E33/#2E782F} → 底 {@code #2B6E2C}，
     * 整体比贴图原值亮一档。下面的色带取"贴图剖面"的分层，颜色取<b>实测值</b>——
     * 贴图的形状 + 截图的观感，两边都不丢。</p>
     */
    private static int[] buttonGradientBands() {
        return new int[] {BAND_TOP, BAND_UPPER, BAND_MID, BAND_LOWER, BAND_BOTTOM};
    }

    /** 悬停档：整组按 {@code SKIN_HOVER_BRIGHTNESS=1.8} 的同族做法提亮（本侧给显式值，避免浮点乘色）。 */
    private static int[] buttonGradientBandsHover() {
        return new int[] {BAND_TOP_HOVER, BAND_UPPER_HOVER, BAND_MID_HOVER, BAND_LOWER_HOVER, BAND_BOTTOM_HOVER};
    }

    /**
     * 色带高度：总和恒等于 {@link #BUTTON_H}=16（上游贴图就是 16 行，一条色带对应若干行）。
     * 1 + 3 + 3 + 5 + 4 = 16。顶部那条 1 单位就是上游"顶亮边"那一行。
     */
    private static int[] buttonGradientHeights() {
        return new int[] {1, 3, 3, 5, 4};
    }

    /**
     * 禁用态色带：{@code textures/store/button_disabled.png}（100×16）的中心列剖面是
     * 顶 {@code #383838} → 中 {@code #1B1B1B/#171717} → 底 {@code #141414}，
     * 同一张贴图的通道值整体乘 3 后 ≈ 顶 {@code #A8A8A8} → 底 {@code #3C3C3C}；
     * 但上游兜底色 {@code PhoneTheme.COLOR_BUTTON_DISABLED = 0xFF3A3A3A} 落在同一族的
     * 中段，若照 3× 会明显亮于启用态的观感。这里取折中：<b>保住"顶亮边 + 纵向变暗"的
     * 形状</b>（与启用态同一几何），亮度与 {@code COLOR_BUTTON_DISABLED} 同档——
     * 截图证据里没有禁用态，此组色<b>属外推</b>，见报告 §7「未解决项」。
     */
    private static final int[] DISABLED_BANDS = {0xFF4E4E4E, 0xFF454545, 0xFF3D3D3D, 0xFF363636, 0xFF2E2E2E};
    private static final int[] DISABLED_HEIGHTS = {1, 3, 3, 5, 4};

    /** 往容器里铺一组"横向满宽、纵向固定高"的实心色带（不可命中，点击穿透到父按钮）。 */
    private static void paintBands(SceneNode host, int[] colors, int[] heights) {
        for (int i = 0; i < colors.length && i < heights.length; i++) {
            SceneNode band = SceneNode.row();
            band.setWidthSizing(SceneNode.WidthSizing.FILL);
            band.setPreferredHeight(heights[i]);
            band.setBackgroundColor(colors[i]);
            band.setHitTestable(false);
            host.appendChild(band);
        }
    }

    /** 按钮动作：BUY 发购买包并挂上结果判定；DOWNLOAD 在 GTNH 语义下只是"已经能用了"。 */
    private void onButton(String appId) {
        IPhoneApp app = PhoneApi.byId(appId);
        if (app == null) return;
        State state = stateOf(app);
        if (state == State.BUY) {
            // P4：开新一次购买前清掉上一条结果，否则 onFrame 第一帧就会拿旧结论结账。
            StoreClient.clearPurchaseResult();
            purchaseBaseline = StoreClient.snapshot();
            pendingBuyId = appId;
            pendingFrames = BUY_TIMEOUT_FRAMES;
            message = tr("msg.mcphone.store_purchasing");
            rebind();
            NetworkHandler.sendToServer(new NetworkHandler.PurchaseApp(appId));
            return;
        }
        if (state == State.DOWNLOAD) {
            // GTNH 没有"安装"这一步：解锁即出现在主屏（上游是购买≠安装两步，见报告 M-09）。
            ui.toast(tr("msg.mcphone.store_already_unlocked", app.displayName()));
            return;
        }
        if (state == State.CANT_AFFORD) {
            ItemStack price = StoreClient.price(app);
            message = price == null
                ? tr("msg.mcphone.store_cant_afford_hint")
                : tr("msg.mcphone.store_cant_afford_detail", priceText(price), app.displayName());
            rebind();
        }
    }

    // ===================== 四态判定 =====================

    /**
     * 四态判定（判定顺序照上游 {@code AppDetail.state()}）：
     *
     * <pre>
     * 免费              -> DOWNLOAD     （上游同）
     * 未同步            -> LOADING      （上游同：还不知道绝不当成没买过）
     * 已解锁            -> UNLOCKED     （上游 INSTALLED；GTNH 购买=解锁）
     * 背包里的不够      -> CANT_AFFORD  （上游同；客户端只是预判，真判在服务端）
     * 其余              -> BUY          （上游同）
     * </pre>
     *
     * <p>与上游的语义差异：上游"已购"是 DOWNLOAD（买过还要下载），GTNH 已购即已解锁、
     * 主屏直接可用，因此落 {@link State#UNLOCKED}（按钮「已解锁」）。</p>
     */
    public State stateOf(IPhoneApp app) {
        if (app == null) return State.LOADING;
        ItemStack price = StoreClient.price(app);
        if (price == null) return State.DOWNLOAD;
        if (!StoreClient.isSynced()) return State.LOADING;
        if (StoreClient.isUnlocked(app)) return State.UNLOCKED;
        if (!canAfford(price)) return State.CANT_AFFORD;
        return State.BUY;
    }

    private static String labelOf(State state) {
        switch (state) {
            case LOADING:
                return tr("msg.mcphone.store_loading");
            case UNLOCKED:
                return tr("msg.mcphone.store_installed_label");
            case DOWNLOAD:
                return tr("msg.mcphone.store_install");
            case CANT_AFFORD:
                return tr("msg.mcphone.store_cant_afford");
            case BUY:
            default:
                return StatCollector.translateToLocal("btn.mcphone.buy");
        }
    }

    /** 客户端预判"买不起"：数一遍背包（主 + 护甲），与服务端 StoreManager.matches 同口径。 */
    public static boolean canAfford(ItemStack price) {
        if (price == null) return true;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return true;
        if (player.capabilities.isCreativeMode) return true;
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

    private static boolean matches(ItemStack stack, ItemStack price) {
        return stack != null && stack.getItem() == price.getItem()
            && (price.getItemDamage() == Short.MAX_VALUE
                || stack.getItemDamage() == price.getItemDamage());
    }

    /**
     * 价格文案：对齐上游 {@code ICost.describe()}
     * （{@code ItemCost.java:41} 的 {@code Component.translatable("mcphone.cost.item", count, item)}
     * + {@code shared/src/main/resources/assets/mcphone/lang/zh_cn.json:151} 的
     * {@code "mcphone.cost.item": "%s × %s"}）——即「<b>1 × 末影箱</b>」。
     *
     * <p>数量放在名字<b>前面</b>（本侧旧形态是「末影箱 x1」）。文案走 lang，
     * 与上游"格式键 + 两个参数"同一形态；键缺失时退回同形状的字面量，不会画出 key。</p>
     */
    private static String priceText(ItemStack price) {
        String key = "label.mcphone.store_price";
        String fmt = StatCollector.translateToLocalFormatted(key, price.stackSize, price.getDisplayName());
        return key.equals(fmt)
            ? price.stackSize + " × " + price.getDisplayName()
            : fmt;
    }

    // ===================== 元数据（api/IPhoneApp 没有 version/author/description） =====================

    private static final String MOD_AUTHOR = "skyc10";
    private static final String MOD_VERSION = "1.10.2";

    /** 商店侧元数据行（作者 / 版本 / 简介文案键）。 */
    private static final class Meta {

        final String author;
        final String version;
        final String description;

        Meta(String author, String version, String description) {
            this.author = author;
            this.version = version;
            this.description = description;
        }
    }

    /** App id -> 元数据。只登记内建付费 App；其余走兜底（模组作者 + 模组版本）。 */
    private static final Map<String, Meta> META = new HashMap<>();

    static {
        META.put("enderchest", new Meta(MOD_AUTHOR, MOD_VERSION, "msg.mcphone.store_desc_enderchest"));
        META.put("teleport", new Meta(MOD_AUTHOR, MOD_VERSION, "msg.mcphone.store_desc_teleport"));
        META.put("ae2", new Meta(MOD_AUTHOR, MOD_VERSION, "msg.mcphone.store_desc_ae2"));
    }

    /** 「作者 · v版本」：api/IPhoneApp 无此字段（禁改 api/），所以商店侧自建表 + 兜底。 */
    private static String metaOf(IPhoneApp app) {
        Meta meta = META.get(app.id());
        if (meta != null) return meta.author + " · v" + meta.version;
        return MOD_AUTHOR + " · v" + MOD_VERSION;
    }

    /** 简介：元数据表优先；没有就退回一句现成文案（不编造内容）。 */
    private static String descriptionOf(IPhoneApp app) {
        Meta meta = META.get(app.id());
        if (meta != null && meta.description != null && !meta.description.isEmpty()) {
            return StatCollector.translateToLocal(meta.description);
        }
        return StatCollector.translateToLocal("msg.mcphone.store_no_description");
    }

    // ===================== 小工具 =====================

    private static SceneNode text(String value, int color, int size) {
        SceneNode n = new SceneNode();
        n.setText(value == null ? "" : value);
        n.setTextColor(color);
        n.setFontSize(PhoneUi.fs(size));
        n.setHitTestable(false);
        return n;
    }

    /**
     * 1px 分隔线（上游 {@code AppStore.java:203} / {@code AppDetail.java:135}：
     * {@code g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER)}，
     * {@code COLOR_DIVIDER = 0x44FFFFFF} = 白 27% 叠在屏底上，实测 {@code #50567C}）。
     */
    static SceneNode separator() {
        SceneNode line = SceneNode.row();
        line.setFillParentWidth(true);
        line.setPreferredHeight(1);
        line.setBackgroundColor(COLOR_DIVIDER);
        line.setHitTestable(false);
        return line;
    }

    /** 反馈行（notice 色，比正文更醒目）。 */
    private static SceneNode notice(String value) {
        return text(value, NOTICE_COLOR, 14);
    }

    private static SceneNode spacer() {
        SceneNode s = SceneNode.column();
        s.setFlexGrow(1);
        s.setHitTestable(false);
        return s;
    }

    /** 次要按钮（返回）：与 {@link PhoneWidgets#button} 同观感。 */
    private SceneNode secondaryButton(String label, Runnable action) {
        SceneNode btn = SceneNode.row();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        btn.setCrossAxisAlign(CrossAxisAlign.CENTER);
        btn.setMainAxisAlign(MainAxisAlign.CENTER);
        btn.setPadding(8, 4, 8, 4);
        btn.setCornerRadius(PhoneGlass.buttonRadius());
        btn.setBackgroundColor(SECONDARY_BG);
        btn.setBorderWidth(1);
        btn.setBorderColor(0x33FFFFFF);
        SceneNode lbl = new SceneNode();
        lbl.setText(label);
        lbl.setTextColor(PhoneTheme.text());
        lbl.setFontSize(PhoneWidgets.buttonFontSize(ui));
        lbl.setHitTestable(false);
        btn.appendChild(lbl);
        var interaction = ui.runtime().interactionState(btn);
        ui.runtime().bindComputed(
            () -> Boolean.TRUE.equals(interaction.hovered().get()) ? SECONDARY_BG_HOVER : SECONDARY_BG,
            btn::setBackgroundColor);
        ui.runtime().on(btn, SceneEventType.CLICK, (event, dispatch) -> ui.post(action));
        return btn;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private static String tr(String key, Object arg) {
        return StatCollector.translateToLocalFormatted(key, arg);
    }

    private static String tr(String key, Object a, Object b) {
        return StatCollector.translateToLocalFormatted(key, a, b);
    }

    private static String nameOf(IPhoneApp app) {
        return app == null ? "" : app.displayName();
    }

    // ===================== 配色 =====================

    /**
     * 详情页主按钮色带（上游常量名 = 值 → 本侧实现）。
     *
     * <pre>
     * PhoneTheme.COLOR_BUTTON          = 0xFF2E7D32  （贴图不存在时的兜底纯色）
     * PhoneTheme.COLOR_BUTTON_HOVER    = 0xFF43A047
     * PhoneTheme.COLOR_BUTTON_DISABLED = 0xFF3A3A3A
     * PhoneTheme.FONT_COLOR_BUTTON     = 0xFFFFFFFF
     * PhoneTheme.FONT_COLOR_BUTTON_DISABLED = 0xFF888888
     * textures/store/button.png        = 100×16 自带纵向渐变（顶亮边 #55A459）
     * </pre>
     *
     * <p>梯度值取实测（{@code _r2_shots.md} §3.3）：顶 {@code #52B860} →
     * 中 {@code #327E33/#2E782F} → 底 {@code #2B6E2C}；hover 档在其上提亮（对应上游
     * 贴图整张 ×1.8 的做法，见 {@code PhoneSkin.drawOrFill} 的 highlight 分支）。</p>
     */
    private static final int BAND_TOP = 0xFF52B860;
    private static final int BAND_UPPER = 0xFF357F35;
    private static final int BAND_MID = 0xFF2E782F;
    private static final int BAND_LOWER = 0xFF2C702D;
    private static final int BAND_BOTTOM = 0xFF2B6E2C;
    private static final int BAND_TOP_HOVER = 0xFF6BD07A;
    private static final int BAND_UPPER_HOVER = 0xFF4A9B4B;
    private static final int BAND_MID_HOVER = 0xFF439A45;
    private static final int BAND_LOWER_HOVER = 0xFF419041;
    private static final int BAND_BOTTOM_HOVER = 0xFF3F8C40;

    /** 按钮字色（上游 {@code PhoneTheme.FONT_COLOR_BUTTON} / {@code FONT_COLOR_BUTTON_DISABLED}）。 */
    private static final int FONT_COLOR_BUTTON = 0xFFFFFFFF;
    private static final int FONT_COLOR_BUTTON_DISABLED = 0xFF888888;

    /** 价格行颜色（上游 {@code FontPalette.price()}：暗底预设 {@code 0xFFFFD54F}；实测截图 {@code #FFFF18}）。 */
    private static final int PRICE_COLOR = 0xFFFFE01A;

    /** 次要按钮（返回）：沿用本机玻璃体系的深灰实心面。 */
    private static final int SECONDARY_BG = 0xFF3A414D;
    private static final int SECONDARY_BG_HOVER = 0xFF4A5462;
    /** 反馈/提示色（上游 {@code FontPalette.notice()} 的暗底预设 {0xFFFFAA44}）。 */
    private static final int NOTICE_COLOR = 0xFFFFAA44;
    /** 分隔线（上游 {@code PhoneTheme.COLOR_DIVIDER = 0x44FFFFFF}：白 27% 叠屏底，实测 {@code #50567C}）。 */
    private static final int COLOR_DIVIDER = 0x44FFFFFF;
}
