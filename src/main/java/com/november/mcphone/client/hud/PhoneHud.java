package com.november.mcphone.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import club.heiqi.uilib.ui.hud.api.ClientHudService;
import club.heiqi.uilib.ui.hud.api.HudAnchor;
import club.heiqi.uilib.ui.hud.api.HudInsets;
import club.heiqi.uilib.ui.hud.api.HudLayoutResolver;
import club.heiqi.uilib.ui.hud.api.HudLayoutService;
import club.heiqi.uilib.ui.hud.api.HudPlacement;
import club.heiqi.uilib.ui.hud.api.HudRegistration;
import club.heiqi.uilib.ui.hud.api.HudScaleState;
import club.heiqi.uilib.ui.hud.api.HudSpec;
import club.heiqi.uilib.ui.hud.api.HudToolbarService;
import club.heiqi.uilib.ui.hud.api.HudVisibility;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.overlay.SceneAnchorResolver;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 常显手机 HUD：背包里有手机且无 GUI 打开时，把手机面板常驻画在游戏画面上。
 *
 * <p>渲染宿主 = Qz 通用 HUD（{@code ClientHudService.register(HudSpec, HudWindowFactory)}，
 * Qz 4.9.1 {@code client/hud/SceneHudHost}）。宿主接管了：视口与 GL 状态、四角锚定 + 安全区 +
 * 视口夹取、HUD 独立缩放（{@code HudScaleState}）、窗口外壳、以及窗口宽度收缩
 * （shell {@code WidthSizing.SHRINK} + {@code HudSpec.minWidth/maxWidth} 夹取）。
 * mcphone 只提供：内容树（{@link PhoneUi} 按目标尺寸建树）与「玩家选择的位置」（{@code PhoneCanvas}
 * 的锚点/偏移 → {@code HudLayoutService} 放置真值）。</p>
 *
 * <p><b>输入仍归 mcphone</b>：{@code HudWindowFactory} 的契约明写「宿主未注入输入源，窗口不接收输入」
 * （{@code SceneHudHost} 以 {@code SceneHostAssembly.assemble(measurer, null)} 装配，inputSource=null），
 * 所以拖拽 / Ctrl+滚轮缩放 / G 键开关 / 点击打开手机全部留在本类（客户端 tick）。
 * 命中判定用「宿主放置的只读镜像」（同一个公开纯函数 {@code HudLayoutResolver.resolve} /
 * {@code SceneAnchorResolver.resolveViewport} + 宿主 framePlaced 的取整），不自己另立一套锚点数学。</p>
 *
 * <p>交互：左键点击 HUD = 打开手机；拖拽 = 移动位置；Ctrl+滚轮 = 缩放；G 键 = 开关 HUD
 * （ClientHooks 注册的 keyHud）。</p>
 */
public final class PhoneHud {

    /** HUD 注册 id（Qz 全局唯一；缩放与放置真值都按它索引）。 */
    public static final String HUD_ID = "mcphone:phone";

    /** 窗口边距（沿用旧 panelOrigin 的 24 物理像素）。 */
    private static final int HUD_MARGIN = 24;

    /**
     * HudSpec 的宽度口径：minWidth=0 表示用宿主默认值（{@code HudTokens.NORMAL.minWidth}），
     * maxWidth=Integer.MAX_VALUE 表示不额外限制（最终仍会被视口夹取）。两处都必须与传给
     * {@link HudSpec.Builder} 的值一致，{@link #hitBox} 的镜像要用同一口径。
     */
    private static final int SPEC_MIN_WIDTH = 0;
    private static final int SPEC_MAX_WIDTH = Integer.MAX_VALUE;
    /** {@code HudTokens.NORMAL.minWidth}（包内可见性，这里按同口径硬编码）。 */
    private static final int HOST_DEFAULT_MIN_WIDTH = 32;

    private static PhoneHud instance;
    private static boolean handlerRegistered;

    /** 宿主下发的缩放下限（Qz {@code HudScaleState.MIN_PERCENT}）；PhoneCanvas 侧允许 40。 */
    private static final int HUD_SCALE_MIN_HOST = 50;
    /** 已就"低于宿主下限"提示过的值（0 = 未提示；同一值不重复提示，值变才再提示）。 */
    private static int warnedLowScalePercent;

    public static PhoneHud get() {
        if (instance == null) instance = new PhoneHud();
        return instance;
    }

    /**
     * 玻璃设置（开关 / 档位 / 强度）变化后重建常显 HUD 的实例（review F10）。
     *
     * <p>HUD 用的是自建 {@code HudPhoneUi} 实例，构造后 {@code PhoneUi.ACTIVE} 被还原为全屏实例，
     * 故它<b>不会</b>被 {@code PhoneUi.refreshGlassShell()} 的 ACTIVE 分支重建 ⇒ 只开 HUD 时改玻璃
     * 设置，HUD 面板会停在旧档/旧底，直到 G 键关 HUD / 切世界 / 打开 GUI 触发宿主重建。本入口补齐。</p>
     *
     * <p>先取出实例与 {@code hudUi} 引用，再 post；执行时若已被关掉/重建（引用变了）则跳过。
     * 通过 {@code rebuildShellTree()} + {@code rebuildPage()} 重建（与全屏实例同一路径），
     * 不改观感、不改默认开启，也不触碰 PhoneCanvas。</p>
     */
    public static void onGlassSettingsChanged() {
        final PhoneHud hud = instance;
        if (hud == null) return;
        final HudPhoneUi ui = hud.hudUi;
        if (ui == null) return;
        PhoneUi.postAction(() -> {
            if (hud.hudUi != ui) return;   // 期间被关掉/重建 ⇒ 跳过
            ui.rebuildShellTree();
            ui.rebuildPage();
        });
    }

    /** 注册时创建实例（ClientHooks.preInit）：事件监听器必须注册即存在。 */
    public static void init() {
        get();
        if (!handlerRegistered) {
            handlerRegistered = true;
            // 踩坑 #7：FML ASM 事件监听器必须是 public 具名静态类（禁止匿名内部类）。
            FMLCommonHandler.instance().bus().register(new TickHandler());
        }
    }

    /** 客户端 tick 交互监听器（public 具名静态类，见踩坑 #7）。 */
    public static final class TickHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            PhoneHud.get().tick(Minecraft.getMinecraft());
        }
    }

    /** 宿主注册句柄：HUD 关/世界关时 close（幂等）；重开时重新 register。 */
    private HudRegistration hudRegistration;

    /** 常驻 HUD 手机实例（工厂在建树时创建）。 */
    private HudPhoneUi hudUi;
    /** 工厂返回给宿主的内容根（外层定尺寸盒），用于读宿主的实测内容盒。 */
    private SceneNode hudContent;

    /** HUD 面板的 100% 设计尺寸（logical px）：宿主缩放只乘在它上面。 */
    private int designW = -1;
    private int designH = -1;

    /** 已下发给宿主的意图放置盒（避免每 tick 重复写 Qz 放置存储）。 */
    private AnchorRect pushedRect;

    /** 拖拽状态（tick 驱动）。 */
    private boolean dragging;
    private int dragStartX;
    private int dragStartY;
    private int dragBaseOffsetX;
    private int dragBaseOffsetY;
    /** 最近一次采样的指针位置（物理像素，左上原点）。 */
    private int pointerX = -1;
    private int pointerY = -1;

    private PhoneHud() {}

    /** HUD 是否持有手机实例（时钟 tick 用）。 */
    public boolean hasUi() {
        return hudUi != null;
    }

    /** 释放 HUD：关闭宿主注册 + 释放手机实例（关世界/关 HUD 时）。 */
    public void disposeUi() {
        closeRegistration();
    }

    // ===================== 客户端 tick：宿主同步 + 交互 =====================

    private void tick(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            closeRegistration();
            dragging = false;
            return;
        }
        // 指针位置（物理像素，左上原点；与宿主视口同坐标系 = displayWidth/Height）
        pointerX = Mouse.getX();
        pointerY = mc.displayHeight - Mouse.getY() - 1;
        // 踩坑 #3：回调改树一律走 PhoneUi.post(...)，渲染帧开头 flush。
        // HUD 不再经 PhoneUi.render（改由宿主管线绘制），所以这个 flush 落到客户端 tick。
        PhoneUi.flushPendingActions();
        if (mc.currentScreen != null) {
            // 屏幕打开：宿主已按 HudVisibility.GAMEPLAY_ONLY 隐藏窗口，mcphone 也不接管指针。
            dragging = false;
            return;
        }
        ItemStack phone = ClientHooks.findPhone(mc);
        if (!PhoneCanvas.isHudEnabled() || ClientHooks.isCameraMode() || phone == null) {
            // 停显即注销（Qz 没有「临时隐藏」状态，HudVisibility 只有 GAMEPLAY_ONLY/IN_WORLD）。
            closeRegistration();
            dragging = false;
            return;
        }
        int viewportW = Math.max(1, mc.displayWidth);
        int viewportH = Math.max(1, mc.displayHeight);
        syncHost(viewportW, viewportH);
        // 先把「玩家选择的位置」下发（首次注册、world 重连后必下发），
        // 命中盒才有宿主放置可镜像。
        pushPlacement(viewportW, viewportH, false);
        int[] hit = hitBox(viewportW, viewportH);
        interact(mc, phone, hit);
        // 拖拽/滚轮刚改过 PhoneCanvas：同 tick 回灌给宿主（下一帧起生效）。
        pushPlacement(viewportW, viewportH, false);
    }

    /** 宿主侧同步：注册（首次/重开）、设计尺寸、缩放真值。 */
    private void syncHost(int viewportW, int viewportH) {
        int[] design = designSize(viewportW, viewportH);
        if (design[0] != designW || design[1] != designH) {
            designW = design[0];
            designH = design[1];
            if (hudUi != null) {
                // PhoneUi 公开 API：重写面板 preferred 尺寸并联动内容槽/主页网格；
                // 经 post 落到本 tick 之外（踩坑 #3：不在分发/渲染中改树）。
                final HudPhoneUi ui = hudUi;
                final int w = designW;
                final int h = designH;
                PhoneUi.postAction(() -> {
                    if (hudUi == ui) ui.setPanelSize(w, h);   // 期间被关掉/重建则跳过
                });
            }
        }
        ensureRegistration();
        syncScale();
    }

    /** 缩放真值交给宿主：PhoneCanvas.hudScalePercent → Qz HudScaleState（宿主按它缩放窗口）。 */
    private void syncScale() {
        HudScaleState state = HudToolbarService.getInstance().scale(HUD_ID);
        if (state == null) return;
        int percent = PhoneCanvas.getHudScalePercent();
        // review F11：PhoneCanvas 允许 40，宿主 HudScaleState.MIN_PERCENT=50 ⇒ 40–49 会被静默夹到 50。
        // 一次性提示（值变才再提示），避免"我设了 40 怎么变大了"被当成 bug。
        if (percent < HUD_SCALE_MIN_HOST && percent != warnedLowScalePercent) {
            warnedLowScalePercent = percent;
            System.out.println("[mcphone] HUD 缩放 " + percent + "% 低于宿主下限 "
                + HUD_SCALE_MIN_HOST + "%：已按 " + HUD_SCALE_MIN_HOST + "% 显示"
                + "（mcphone 侧未改配置值，可在设置里改为 50–150%）");
        }
        if (state.percent().get() != percent) {
            // Qz 口径 50–200（PhoneCanvas 允许 40，宿主下限 50），夹取由 HudScaleState.setPercent 负责。
            state.setPercent(percent);
        }
    }

    /** 首次/重开注册：HudSpec + HudWindowFactory。 */
    private void ensureRegistration() {
        if (hudRegistration != null) return;
        if (designW <= 0 || designH <= 0) return;
        HudSpec spec = HudSpec.builder(HUD_ID)
                // 四角锚点：Placement 缺失时的兜底；真正的放置真值在 HudLayoutService（见 pushPlacement）。
                .anchor(mappedAnchor())
                // 世界内且无普通 GuiScreen：与旧 mc.currentScreen != null 判定等价。
                .visibility(HudVisibility.GAMEPLAY_ONLY)
                .margin(HUD_MARGIN)
                .stackOrder(0)
                // 必须 false：chrome(true) 的宿主外壳是 0xA0000000（63% 纯黑，先压暗玻璃采样源），
                // 且其 padding 会把内容盒缩一圈（手机面板自己带圆角+玻璃+边框）。
                .chrome(false)
                .build();
        hudRegistration = ClientHudService.getInstance().register(spec, this::buildContent);
    }

    /** 撤销注册 + 释放手机实例（幂等）。 */
    private void closeRegistration() {
        HudRegistration reg = hudRegistration;
        hudRegistration = null;
        if (reg != null) {
            try {
                reg.close();
            } catch (Throwable t) {
                System.err.println("[mcphone] hud registration close failed: " + t);
            }
        }
        releaseUi();
        pushedRect = null;
    }

    /** 释放 HUD 手机实例（不改注册表；工厂重挂载时先调一次）。 */
    private void releaseUi() {
        hudContent = null;
        if (hudUi != null) {
            try {
                hudUi.dispose();
            } catch (Throwable t) {
                System.err.println("[mcphone] hud dispose failed: " + t);
            }
            hudUi = null;
        }
    }

    // ===================== 内容树（HudWindowFactory） =====================

    /**
     * 内容工厂：宿主在挂载（含世界重连后重建）时调用一次，返回内容根。
     *
     * <p>入参 {@code runtime} 是宿主窗口自己的 scene runtime（内容树由宿主管线 layout/paint/flush）。
     * {@link PhoneUi} 实例自带的 runtime 只承担信号绑定与 effect 归属；绑定物化走全局
     * {@code ReactiveScheduler}，宿主的 flush 会一并应用。</p>
     */
    private SceneNode buildContent(SceneRuntime runtime) {
        // 宿主重挂载：上一份内容不再是渲染源，先释放（不做注册表改动，避免宿主迭代中改注册表）。
        releaseUi();
        SceneNode box = SceneNode.column();
        box.setHitTestable(false);
        ItemStack phone = ClientHooks.findPhone(Minecraft.getMinecraft());
        if (phone == null) {
            // 兜底空内容：宿主按「空窗」整窗隐藏（SceneHudHost.RetainedWindow.isEmptyContent）。
            return box;
        }
        // 按目标（100% 设计）尺寸建树：外壳/主页网格/文本宽度一次建对，避免大尺寸建树后再缩
        // 导致网格单元与文本残留被面板裁剪（7ae1357 的内容裁半根因）。
        box.setPreferredWidth(designW);
        box.setPreferredHeight(designH);
        PhoneUi prev = PhoneUi.ACTIVE;
        HudPhoneUi ui = new HudPhoneUi(phone, designW, designH);
        // PhoneUi 构造会抢占 ACTIVE（全屏实例指针/时钟语义），立即还原。
        PhoneUi.ACTIVE = prev;
        hudUi = ui;
        hudContent = box;
        box.appendChild(ui.hudRoot());
        return box;
    }

    /** HUD 专用 PhoneUi：把基类 protected 的 getRoot() 暴露给内容工厂（不新增 PhoneUi 公开 API）。 */
    private static final class HudPhoneUi extends PhoneUi {
        HudPhoneUi(ItemStack phoneStack, int panelW, int panelH) {
            super(phoneStack, panelW, panelH);
        }

        SceneNode hudRoot() {
            return getRoot();
        }
    }

    // ===================== 面板尺寸 / 放置真值 =====================

    /**
     * HUD 面板的 100% 设计尺寸（logical px；宿主视口就是帧缓冲像素，故与旧物理像素同量纲）。
     *
     * <p>缩放（{@code hudScalePercent}）不在这里乘：它下发给宿主 {@code HudScaleState}，
     * 由宿主按 {@code ceil(设计尺寸 × 倍率)} 放置与绘制。旧的绝对闸门（[320,1100] / [200,620]）
     * 随之改在 100% 设计尺寸上生效；旧代码里「宽度超过屏幕就夹到 screenW-40」的那道自算夹取删除，
     * 改由宿主在 {@code SceneAnchorResolver.resolveViewport} 里把盒子夹进视口。</p>
     */
    private static int[] designSize(int viewportW, int viewportH) {
        int h = (int) (viewportH * PhoneUi.basePanelHeight());
        h = Math.max(320, Math.min(1100, h));
        int w = (int) (h * 0.56);
        w = Math.max(200, Math.min(620, w));
        return new int[] {w, h};
    }

    /** 宿主的每窗口缩放倍率（{@code SceneHudHost.unifiedScaleFactor} = HudScaleState.factor()）。 */
    private static float hudScaleFactor() {
        HudScaleState state = HudToolbarService.getInstance().scale(HUD_ID);
        float factor = state == null ? 1.0F : state.factor();
        return factor > 0 ? factor : 1.0F;
    }

    /** 面板的物理尺寸：宿主按 {@code ceil(logical × scale)} 计（MeasuredHud 构造口径）。 */
    private static int[] physicalPanelSize(float scale) {
        int[] design = currentDesign();
        return new int[] {
            (int) Math.ceil(design[0] * scale),
            (int) Math.ceil(design[1] * scale)
        };
    }

    private static int[] currentDesign() {
        PhoneHud hud = get();
        return new int[] {hud.designW, hud.designH};
    }

    /** 宿主实测的内容盒（工厂内容根的 cachedLayout）；未测到则退回设计尺寸。 */
    private int[] measuredContentBox() {
        SceneNode content = hudContent;
        int[] fallback = currentDesign();
        if (content == null) return fallback;
        Object box = content.getCachedLayout();
        if (!(box instanceof LayoutBox)) return fallback;
        LayoutBox layout = (LayoutBox) box;
        return new int[] {
            layout.getWidth() > 0 ? layout.getWidth() : fallback[0],
            layout.getHeight() > 0 ? layout.getHeight() : fallback[1]
        };
    }

    /**
     * 把「玩家选择的位置」交给 Qz 放置真值（{@code HudLayoutService}）。
     *
     * <p>mcphone 不再自己算渲染原点（旧 {@code panelOrigin} 已删）：这里只把 PhoneCanvas 的
     * 九宫格锚点语义 + 偏移换算成四角锚点 + {@code HudPlacement} 偏移，宿主
     * （{@code SceneHudHost.placeAndFrame} → {@code HudLayoutResolver.resolve}）负责解析、夹取与绘制。</p>
     */
    private void pushPlacement(int viewportW, int viewportH, boolean force) {
        if (hudRegistration == null || designW <= 0) return;
        float scale = hudScaleFactor();
        int[] panel = physicalPanelSize(scale);
        HudPlacement want = intentPlacement(viewportW, viewportH, panel[0], panel[1]);
        AnchorRect wantRect = resolvePlacement(want, viewportW, viewportH, panel[0], panel[1]);
        HudPlacement now = HudLayoutService.getInstance().placement(HUD_ID);
        if (!force && now != null && pushedRect != null && sameRect(wantRect, pushedRect)) return;
        HudLayoutService.getInstance().commit(HUD_ID, want);
        pushedRect = wantRect;
    }

    /**
     * 旧九宫格锚点语义 → Qz 四角锚点 + 偏移。
     *
     * <p>Qz 的 offset 以「锚边」为正方向（{@code HudLayoutResolver.resolve}：right/bottom 分支做减法），
     * 所以右侧/底部锚点要取反向差值，才能得到与旧 {@code panelOrigin} 一致的像素位置。</p>
     */
    private static HudPlacement intentPlacement(int viewportW, int viewportH, int panelW, int panelH) {
        String anchorName = PhoneCanvas.getHudAnchor();
        boolean left = anchorName.endsWith("LEFT");
        boolean right = anchorName.endsWith("RIGHT");
        boolean top = anchorName.startsWith("TOP");
        boolean bottom = anchorName.startsWith("BOTTOM");
        int x = (left ? HUD_MARGIN : right ? viewportW - panelW - HUD_MARGIN : (viewportW - panelW) / 2)
                + PhoneCanvas.getHudOffsetX();
        int y = (top ? HUD_MARGIN : bottom ? viewportH - panelH - HUD_MARGIN : (viewportH - panelH) / 2)
                + PhoneCanvas.getHudOffsetY();
        HudAnchor anchor = mappedAnchor();
        int offX = isRight(anchor) ? (viewportW - panelW) - x : x;
        int offY = isBottom(anchor) ? (viewportH - panelH) - y : y;
        return HudPlacement.of(anchor, offX, offY);
    }

    private static HudAnchor mappedAnchor() {
        String anchorName = PhoneCanvas.getHudAnchor();
        boolean right = anchorName.endsWith("RIGHT");
        boolean bottom = anchorName.startsWith("BOTTOM");
        if (right) return bottom ? HudAnchor.BOTTOM_RIGHT : HudAnchor.TOP_RIGHT;
        return bottom ? HudAnchor.BOTTOM_LEFT : HudAnchor.TOP_LEFT;
    }

    private static boolean isRight(HudAnchor anchor) {
        return anchor == HudAnchor.TOP_RIGHT || anchor == HudAnchor.BOTTOM_RIGHT;
    }

    private static boolean isBottom(HudAnchor anchor) {
        return anchor == HudAnchor.BOTTOM_LEFT || anchor == HudAnchor.BOTTOM_RIGHT;
    }

    /**
     * 宿主的放置盒解析（镜像 {@code SceneHudHost.placeAndFrame} 的两条分支）：
     * 有 Placement 覆盖走 {@code HudLayoutResolver.resolve}，否则走四角锚定
     * {@code SceneAnchorResolver.resolveViewport}。
     *
     * <p>安全区（HudInsets）用 NONE：其它 mod 经 registerAvoidance 撑大的安全区在 mcphone 侧
     * 读不到（宿主实例不外露），这是已知的命中盒残余偏差，列入手测清单。</p>
     */
    private static AnchorRect resolvePlacement(HudPlacement placement,
            int viewportW, int viewportH, int boxW, int boxH) {
        if (placement != null) {
            return HudLayoutResolver.resolve(placement, viewportW, viewportH, boxW, boxH, HudInsets.NONE);
        }
        HudAnchor anchor = mappedAnchor();
        SceneAnchorResolver.ResolvedViewport placed = SceneAnchorResolver.resolveViewport(
                isRight(anchor), isBottom(anchor), viewportW, viewportH, boxW, boxH,
                HUD_MARGIN, 0, 0, 0, 0, 0);
        return new AnchorRect(placed.getX(), placed.getY(), placed.getWidth(), placed.getHeight());
    }

    /**
     * 命中盒 = 宿主实际绘制盒的只读镜像（物理像素，左上原点）。
     *
     * <p>逐步对照 Qz 4.9.1 {@code SceneHudHost}：render 的内容测量夹取
     * （minWidth/maxWidth + 逻辑视口约束）→ {@code MeasuredHud} 的
     * {@code ceil(logical×scale)} → placeAndFrame 的放置解析 → framePlaced 的
     * {@code /scale} 取整 + 回乘 scale。指针坐标（Mouse）与宿主视口同为 displayWidth/Height 系，
     * 故可直接比较。</p>
     */
    private int[] hitBox(int viewportW, int viewportH) {
        float scale = hudScaleFactor();
        int width = Math.max(1, viewportW);
        int height = Math.max(1, viewportH);
        // 1) 宿主的 measure 约束 = 逻辑视口（floor(物理 / scale)），内容盒被它夹取。
        int logicalViewportW = Math.max(1, (int) Math.floor(width / scale));
        int logicalViewportH = Math.max(1, (int) Math.floor(height / scale));
        int[] measured = measuredContentBox();
        int boxW = Math.min(measured[0] > 0 ? measured[0] : designW, logicalViewportW);
        int boxH = Math.min(measured[1] > 0 ? measured[1] : designH, logicalViewportH);
        // 2) 宿主的宽度夹取（HudSpec 口径）+ MeasuredHud 的物理尺寸口径。
        int minWidth = SPEC_MIN_WIDTH == 0 ? Math.min(HOST_DEFAULT_MIN_WIDTH, SPEC_MAX_WIDTH) : SPEC_MIN_WIDTH;
        int itemW = Math.max(minWidth, Math.min(SPEC_MAX_WIDTH, boxW));
        int itemH = Math.max(1, boxH);
        int physicalW = (int) Math.ceil(itemW * scale);
        int physicalH = (int) Math.ceil(itemH * scale);
        // 3) 放置盒（宿主：placement 覆盖分支 / 锚定回退分支）。
        AnchorRect placed = resolvePlacement(HudLayoutService.getInstance().placement(HUD_ID),
                width, height, physicalW, physicalH);
        // 4) framePlaced：/scale 取整成逻辑盒，再回乘 scale 得实际绘制盒。
        int logicalX = Math.round(placed.getX() / scale);
        int logicalY = Math.round(placed.getY() / scale);
        int logicalW = Math.max(1, Math.min(itemW, (int) Math.floor(placed.getWidth() / scale)));
        int logicalH = Math.max(1, Math.min(itemH, (int) Math.floor(placed.getHeight() / scale)));
        int physX = Math.round(logicalX * scale);
        int physY = Math.round(logicalY * scale);
        int physW = Math.round((logicalX + logicalW) * scale) - physX;
        int physH = Math.round((logicalY + logicalH) * scale) - physY;
        return new int[] {physX, physY, physW, physH};
    }

    private static boolean sameRect(AnchorRect a, AnchorRect b) {
        return a.getX() == b.getX() && a.getY() == b.getY()
                && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight();
    }

    // ===================== 交互（宿主不注入输入 ⇒ 全在 mcphone） =====================

    private void interact(Minecraft mc, ItemStack phone, int[] hit) {
        // Ctrl+滚轮缩放（步进 10%，40–150，落 PhoneCanvas；宿主 HudScaleState 负责真正缩放）。
        int wheel = Mouse.getDWheel();
        if (wheel != 0 && isCtrlDown() && (dragging || insidePanel(pointerX, pointerY, hit))) {
            PhoneCanvas.setHudScalePercent(PhoneCanvas.getHudScalePercent() + (wheel > 0 ? 10 : -10));
            return;
        }

        boolean pressed = Mouse.isButtonDown(0);
        if (pressed && !dragging && insidePanel(pointerX, pointerY, hit)) {
            dragging = true;
            dragStartX = pointerX;
            dragStartY = pointerY;
            dragBaseOffsetX = PhoneCanvas.getHudOffsetX();
            dragBaseOffsetY = PhoneCanvas.getHudOffsetY();
        } else if (!pressed && dragging) {
            // 松开：基本没动 = 点击打开手机；拖过 = 落盘位置。
            dragging = false;
            int dx = pointerX - dragStartX;
            int dy = pointerY - dragStartY;
            if (Math.abs(dx) < 4 && Math.abs(dy) < 4) {
                openPhone(mc, phone);
            } else {
                PhoneCanvas.setHudOffsetX(dragBaseOffsetX + dx);
                PhoneCanvas.setHudOffsetY(dragBaseOffsetY + dy);
            }
        } else if (dragging) {
            PhoneCanvas.setHudOffsetX(dragBaseOffsetX + (pointerX - dragStartX));
            PhoneCanvas.setHudOffsetY(dragBaseOffsetY + (pointerY - dragStartY));
        }
    }

    private static boolean insidePanel(int px, int py, int[] box) {
        return px >= box[0] && px < box[0] + box[2]
            && py >= box[1] && py < box[1] + box[3];
    }

    private static boolean isCtrlDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    private static void openPhone(Minecraft mc, ItemStack phone) {
        mc.displayGuiScreen(new com.november.mcphone.client.scene.PhoneScreen(
            new PhoneUi(phone)));
    }
}
