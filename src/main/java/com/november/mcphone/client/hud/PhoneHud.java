package com.november.mcphone.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import club.heiqi.uilib.ui.host.NativeDisplaySize;
import club.heiqi.uilib.ui.host.UiHostRenderSupport;
import club.heiqi.uilib.ui.render.PaintContextCompositor;
import club.heiqi.uilib.ui.render.UiMainLayerSnapshotService;
import club.heiqi.uilib.ui.render.UiRenderContext;
import club.heiqi.uilib.ui.runtime.UiRuntimeAdapters;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.util.GlAttribDepth;

import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 常显手机 HUD：背包里有手机且无 GUI 打开时，把手机面板常驻画在游戏画面上。
 *
 * <h2>为什么渲染仍归 mcphone（本轮从 Qz 通用 HUD 宿主改回来）</h2>
 * v1.0.3-beta.3 把本 HUD 迁到 {@code ClientHudService.register(HudSpec, factory)}（Qz 4.9.1
 * {@code client/hud/SceneHudHost}）。迁移后用户可见的三个故障都能定位到「宿主绘制」这条链上：
 *
 * <ol>
 *   <li><b>App 图标完全不渲染</b>：宿主唯一的 HUD 渲染桥 {@code UiHudRenderListener} 用
 *       {@code UiRuntimeAdapters.empty()} 建渲染上下文（4.9.1 {@code UiHudRenderListener.java:123-124}，
 *       已用随包发行的 {@code qz_uilib-4.9.1.jar} 字节码复核），于是
 *       {@code UiRenderContext.drawHostImage()} 在 {@code hostImageRenderer == null} 时直接
 *       {@code return}（{@code UiRenderContext.java:786-789}），ItemStack 图标同理
 *       （{@code :807-811}）。而 mcphone 的图标格**只用** {@code HostImageSource}
 *       （{@code PhoneUi.iconCell}：{@code HostImageSource.texture(...)} / {@code .itemIcon(...)}）；
 *       {@code HostImageSource} 又是全 Qz 唯一的 {@code SceneImageSource} 实现，且
 *       {@code UiRenderContext.drawImage} 只认它。⇒ 走宿主管线时，**所有**贴图/物品图标/壁纸
 *       一律静默丢弃；面板底色、状态栏文字、导航条「⌂」是普通 paint，照常显示——
 *       与「图标全没了、⌂ 还在」的用户现象逐条吻合。宿主侧没有任何注入适配器的入口
 *       （{@code UiHostRenderSupport.createRenderContext} 的 adapters 参数只由 Qz 自己的监听器传），
 *       所以「不改 Qz 就画不出图标」，只能把绘制收回 mcphone。</li>
 *   <li><b>比例/缩放不可控</b>：宿主把倍率拆成 {@code HudScaleSetting.get() × HudScaleState.factor()}
 *       两个因子，前者是 Qz 私有全局量（mcphone 读不到，4.9.1 里也没有任何 {@code set} 调用点），
 *       后者由 {@code HudToolbarService.scale(id)} 惰性创建。mcphone 只能写下发的一半，
 *       且缩放只作用在「100% 设计盒」上，实际观感与旧版 {@code panelSize()}（把缩放乘进物理面板）
 *       不是同一套口径。收回绘制后倍率只有一个真相：{@code PhoneCanvas.hudScalePercent}。</li>
 *   <li><b>Ctrl+滚轮无反应 / 点击拖拽不可操控</b>：宿主只画不读输入（{@code inputSource=null}），
 *       命中盒只能靠 mcphone 自己「镜像」宿主那套
 *       {@code resolveViewport / HudLayoutResolver.resolve / framePlaced} 数学（含 {@code ceil}、
 *       {@code /scale} 取整），是第二事实源；镜像里还硬编码了 {@code HudInsets.NONE}
 *       （宿主用的是 {@code registry.avoidanceInsets(...)} 的真实安全区）。
 *       收回绘制后命中盒 = 实际绘制矩形，同一份变量算出来，不存在镜像偏差。
 *       滚轮另外还有 API 问题：{@code Mouse.getDWheel()} 是<b>消费式</b>读（LWJGL 2.9.4 字节码：
 *       {@code return dwheel; dwheel = 0;}），任何更早的消费者都会把它清零；本轮改用 Forge 在
 *       {@code Mouse.next()} 循环内派发的 {@code MouseEvent}（携带每事件 {@code dwheel}）。</li>
 * </ol>
 *
 * <h2>渲染契约（与全屏手机同源）</h2>
 * <pre>
 * design（100% 设计尺寸，logical px）= 屏高 × 0.62（夹取 320–1100）× 0.56（夹取 200–620）
 * physical = ceil(design × hudScalePercent / 100)
 * 绘制 = PhoneUi.render(designW, designH, context.scaled(scale), round(originX/scale), round(originY/scale))
 * 命中盒 = (originX, originY, physicalW, physicalH) —— 与绘制同一份 origin/physical，不另算
 * </pre>
 * {@code UiRuntimeAdapters.minecraftDefaults()} 是图标能画出来的关键：它与全屏手机
 * （{@code McScreenBridge} 内部同款适配器）走完全一样的宿主图片路径。
 *
 * <p><b>输入仍全在 mcphone 侧</b>：HUD 内容树整体关闭命中（{@link #disableHitTesting}），
 * 避免 Qz 自身的输入路由把点击派发给 HUD 实例（那会让 HUD 悄悄跳页）；鼠标/滚轮/键盘一律
 * 由本类的客户端 tick 与 Forge 事件处理。</p>
 */
public final class PhoneHud {

    /** 窗口边距（物理像素；沿用旧 panelOrigin 的 24）。 */
    private static final int HUD_MARGIN = 24;

    /** 松开时位移小于该值（物理像素）视为点击而不是拖拽。 */
    private static final int CLICK_SLOP_PX = 4;

    /** Ctrl+滚轮每格的缩放步进。 */
    private static final int SCALE_STEP_PERCENT = 10;

    /** 与 {@code PhoneCanvas.HUD_SCALE_MIN/MAX} 同口径（那边是 private，这里按同值硬编码）。 */
    private static final int SCALE_MIN_PERCENT = 40;
    private static final int SCALE_MAX_PERCENT = 150;

    /** 与 {@code PhoneCanvas.HUD_OFFSET_LIMIT} 同口径。 */
    private static final int OFFSET_LIMIT = 4096;

    /**
     * 配置内存缓存的最长有效期（毫秒）。
     *
     * <p>{@code PhoneCanvas.load()} 没有任何缓存（每次调用都新建 Properties + 读整文件），
     * 而旧 HUD 每 tick 要读 6–10 次、拖拽时每 tick 还要写 2 次整文件。这里把「读」压到
     * 每秒最多一轮（5 个键），「写」只在用户实际操作时发生。设置页改锚点/缩放后最多 1 秒
     * 被 HUD 看到，而设置页只在 GuiScreen 打开时可用（此时 HUD 本就不显示），无用户可见延迟。</p>
     */
    private static final long CFG_REFRESH_MS = 1000L;

    /** 逐帧诊断（{@code -Dmcphone.hud.debug=true}）：打印设计尺寸/缩放/绘制盒/指针，用于手测取证。 */
    private static final boolean DEBUG = Boolean.getBoolean("mcphone.hud.debug");

    private static PhoneHud instance;
    private static boolean handlerRegistered;

    public static PhoneHud get() {
        if (instance == null) instance = new PhoneHud();
        return instance;
    }

    /**
     * 注册时创建实例（ClientHooks.preInit）：事件监听器必须注册即存在。
     *
     * <p>三条线：客户端 tick（交互 + 延迟动作 flush）、Forge overlay（自绘）、Forge MouseEvent
     * （Ctrl+滚轮；Qz 宿主迁走后滚轮不再有别的读取者）。</p>
     */
    public static void init() {
        get();
        if (handlerRegistered) return;
        handlerRegistered = true;
        // 踩坑 #7：FML/Forge 事件监听器必须是 public 具名静态类（禁止匿名内部类）。
        FMLCommonHandler.instance().bus().register(new TickHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new OverlayHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new MouseWheelHandler());
    }

    /** 客户端 tick：采样指针（物理像素）、刷配置缓存、拖拽/点击交互。 */
    public static final class TickHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            PhoneHud.get().tick(Minecraft.getMinecraft());
        }
    }

    /** Forge overlay：HUD 自绘入口（与全屏手机同一条 scene 管线）。 */
    public static final class OverlayHandler {
        @SubscribeEvent
        public void onRenderOverlay(net.minecraftforge.client.event.RenderGameOverlayEvent.Post event) {
            if (event == null || event.type != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            PhoneHud.get().renderHud(mc, event.partialTicks);
        }
    }

    /**
     * 1.7.10 读滚轮的正确时机：Forge 在 {@code Minecraft.runTick()} 的 {@code while (Mouse.next())}
     * 循环体内派发 {@code MouseEvent}（{@code Minecraft.java:1776-1826}，每个鼠标事件一次），
     * 事件体自带该事件的 {@code dwheel = Mouse.getEventDWheel()}。
     *
     * <p>为什么不轮询 {@code Mouse.getDWheel()}：它是消费式读取，读到即清零，任何更早的消费者
     * （别的 mod 的 tick/overlay）都会让这里读到 0；而 {@code Mouse.getEventDWheel()} 只在事件
     * 循环内有效，mod 无法在循环体里插代码，{@code MouseEvent} 就是那个位置的公开钩子。</p>
     *
     * <p>Ctrl 按住时消费掉该事件（{@code setCanceled}），顺带避免原版把同一个滚轮当成切换快捷栏
     * （{@code Minecraft.java:1792-1796}）。</p>
     */
    public static final class MouseWheelHandler {
        @SubscribeEvent
        public void onMouseEvent(net.minecraftforge.client.event.MouseEvent event) {
            if (event == null || event.dwheel == 0) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
            if (!isCtrlDown()) return;
            PhoneHud hud = get();
            hud.refreshConfigIfStale();
            if (!hud.cfgEnabled || ClientHooks.isCameraMode()) return;
            hud.applyScaleStep(event.dwheel > 0 ? 1 : -1);
            event.setCanceled(true);
        }
    }

    // ===================== 配置缓存 =====================

    private boolean cfgEnabled;
    private int cfgScalePercent = 100;
    private String cfgAnchor = "CENTER_LEFT";
    private int cfgOffsetX;
    private int cfgOffsetY;
    private long cfgStampMs;

    /** 渲染资源（与全屏手机同款；图标能画出来的关键就是 minecraftDefaults() 的图片适配器）。 */
    private final PaintContextCompositor compositor = new PaintContextCompositor();
    private final UiMainLayerSnapshotService snapshotService = new UiMainLayerSnapshotService();
    private final UiRuntimeAdapters adapters = UiRuntimeAdapters.minecraftDefaults();

    /** HUD 常驻手机实例（设计尺寸变化时重建）。 */
    private PhoneUi hudUi;
    private int builtDesignW = -1;
    private int builtDesignH = -1;

    /** 拖拽状态（tick 驱动）。 */
    private boolean dragging;
    private int dragStartX;
    private int dragStartY;
    private int dragBaseOffsetX;
    private int dragBaseOffsetY;
    /** 拖拽期间的实时偏移（只在内存里，松手才落盘一次）。 */
    private int liveOffsetX;
    private int liveOffsetY;

    /** 最近一次采样的指针位置（物理像素，左上原点）。 */
    private int pointerX = -1;
    private int pointerY = -1;

    /** 诊断节流。 */
    private long debugStampMs;

    private PhoneHud() {}

    /** HUD 是否持有手机实例（时钟 tick 用）。 */
    public boolean hasUi() {
        return hudUi != null;
    }

    /** 释放 HUD 实例（关世界时）。 */
    public void disposeUi() {
        if (hudUi != null) {
            try {
                hudUi.dispose();
            } catch (Throwable t) {
                System.err.println("[mcphone] hud dispose failed: " + t);
            }
            hudUi = null;
        }
        builtDesignW = -1;
        builtDesignH = -1;
    }

    /**
     * 玻璃设置（开关 / 档位 / 强度）变化后重建常显 HUD 的实例（review F10）。
     *
     * <p>HUD 用的是自建 {@code PhoneUi} 实例，构造后 {@code PhoneUi.ACTIVE} 被还原为全屏实例，
     * 故它<b>不会</b>被 {@code PhoneUi.refreshGlassShell()} 的 ACTIVE 分支重建 ⇒ 只开 HUD 时改玻璃
     * 设置，HUD 面板会停在旧档/旧底。本入口补齐。</p>
     *
     * <p>先取出实例与引用，再 post；执行时若已被关掉/重建（引用变了）则跳过。
     * 通过 {@code rebuildShellTree()} + {@code rebuildPage()} 重建（与全屏实例同一路径），
     * 不改观感、不改默认开启，也不触碰 PhoneCanvas。</p>
     */
    public static void onGlassSettingsChanged() {
        final PhoneHud hud = instance;
        if (hud == null) return;
        final PhoneUi ui = hud.hudUi;
        if (ui == null) return;
        PhoneUi.postAction(() -> {
            if (hud.hudUi != ui) return;   // 期间被关掉/重建 ⇒ 跳过
            ui.rebuildShellTree();
            ui.rebuildPage();
        });
    }

    // ===================== 配置缓存：读盘收敛 =====================

    private void refreshConfigIfStale() {
        long now = System.currentTimeMillis();
        if (now - cfgStampMs < CFG_REFRESH_MS) return;
        cfgStampMs = now;
        cfgEnabled = PhoneCanvas.isHudEnabled();
        cfgScalePercent = PhoneCanvas.getHudScalePercent();
        cfgAnchor = PhoneCanvas.getHudAnchor();
        cfgOffsetX = PhoneCanvas.getHudOffsetX();
        cfgOffsetY = PhoneCanvas.getHudOffsetY();
    }

    /** 自己写过的键：直接更新缓存，不立刻再读盘一遍。 */
    private void applyScaleStep(int direction) {
        int next = clamp(cfgScalePercent + direction * SCALE_STEP_PERCENT,
            SCALE_MIN_PERCENT, SCALE_MAX_PERCENT);
        if (next == cfgScalePercent) return;
        cfgScalePercent = next;
        PhoneCanvas.setHudScalePercent(next);
    }

    // ===================== 客户端 tick：交互 =====================

    private void tick(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            disposeUi();
            dragging = false;
            cfgStampMs = 0L;             // 下次进世界立刻重读配置
            return;
        }
        // 踩坑 #3：回调改树一律走 PhoneUi.post(...)，这里统一 flush。
        PhoneUi.flushPendingActions();
        refreshConfigIfStale();
        if (mc.currentScreen != null) {
            dragging = false;
            pointerX = -1;
            pointerY = -1;
            return;
        }
        ItemStack phone = ClientHooks.findPhone(mc);
        if (!cfgEnabled || ClientHooks.isCameraMode() || phone == null) {
            dragging = false;
            return;
        }
        // 指针位置（物理像素，左上原点；与自绘用的 displayWidth/Height 同坐标系）。
        // 注：1.7.10 世界里鼠标被 grab，OS 光标不可见，但 LWJGL 的 Mouse.getX/getY 在
        // grabbed 态按增量推进（x += poll_x）并夹在窗口内 ⇒ 它是可用的「虚拟光标」，
        // 只是玩家看不见；按住 Ctrl 时本类会画出一个小十字标出它（见 drawEditCursor）。
        pointerX = Mouse.getX();
        pointerY = mc.displayHeight - Mouse.getY() - 1;
        Layout layout = layout(mc, phone, true);
        interact(mc, phone, layout);
        if (DEBUG) debugLog(mc, layout);
    }

    private void interact(Minecraft mc, ItemStack phone, Layout layout) {
        boolean pressed = Mouse.isButtonDown(0);
        if (pressed && !dragging && layout.contains(pointerX, pointerY)) {
            dragging = true;
            dragStartX = pointerX;
            dragStartY = pointerY;
            dragBaseOffsetX = cfgOffsetX;
            dragBaseOffsetY = cfgOffsetY;
            liveOffsetX = cfgOffsetX;
            liveOffsetY = cfgOffsetY;
        } else if (!pressed && dragging) {
            // 松开：基本没动 = 点击打开手机；拖过 = 落盘位置（一次写 X+Y，旧实现是每 tick 两次）。
            dragging = false;
            int dx = pointerX - dragStartX;
            int dy = pointerY - dragStartY;
            if (Math.abs(dx) < CLICK_SLOP_PX && Math.abs(dy) < CLICK_SLOP_PX) {
                openPhone(mc, phone);
            } else {
                commitOffsets(clamp(dragBaseOffsetX + dx, -OFFSET_LIMIT, OFFSET_LIMIT),
                    clamp(dragBaseOffsetY + dy, -OFFSET_LIMIT, OFFSET_LIMIT));
            }
        } else if (dragging) {
            liveOffsetX = dragBaseOffsetX + (pointerX - dragStartX);
            liveOffsetY = dragBaseOffsetY + (pointerY - dragStartY);
        }
    }

    private void commitOffsets(int x, int y) {
        cfgOffsetX = x;
        cfgOffsetY = y;
        liveOffsetX = x;
        liveOffsetY = y;
        PhoneCanvas.setHudOffsetX(x);
        PhoneCanvas.setHudOffsetY(y);
    }

    // ===================== 渲染 =====================

    /** 每帧自绘（Forge overlay Post(ALL)）。 */
    private void renderHud(Minecraft mc, float partialTicks) {
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        refreshConfigIfStale();
        if (!cfgEnabled || ClientHooks.isCameraMode()) return;
        ItemStack phone = ClientHooks.findPhone(mc);
        if (phone == null) return;
        Layout layout = layout(mc, phone, true);
        if (hudUi == null) return;
        drawHud(layout, partialTicks);
    }

    /**
     * 面板的几何真值：设计尺寸、倍率、绘制原点与物理盒。
     *
     * <p><b>唯一事实源</b>：绘制与命中都只读这里的结果，不再有「镜像宿主」的第二套数学。</p>
     */
    private Layout layout(Minecraft mc, ItemStack phone, boolean ensureUi) {
        int screenW = Math.max(1, mc.displayWidth > 0 ? mc.displayWidth : NativeDisplaySize.width());
        int screenH = Math.max(1, mc.displayHeight > 0 ? mc.displayHeight : NativeDisplaySize.height());
        int[] design = designSize(screenH);
        float scale = clamp(cfgScalePercent, SCALE_MIN_PERCENT, SCALE_MAX_PERCENT) / 100.0F;
        int physicalW = (int) Math.ceil(design[0] * scale);
        int physicalH = (int) Math.ceil(design[1] * scale);
        int offsetX = dragging ? liveOffsetX : cfgOffsetX;
        int offsetY = dragging ? liveOffsetY : cfgOffsetY;
        int[] origin = panelOrigin(screenW, screenH, physicalW, physicalH, offsetX, offsetY);
        if (ensureUi) ensureUi(phone, design[0], design[1]);
        return new Layout(screenW, screenH, design[0], design[1], scale,
            origin[0], origin[1], physicalW, physicalH);
    }

    /** 100% 设计尺寸（logical px，与旧 panelSize / PhoneUi.BASE_PANEL_HEIGHT 同源）。 */
    private static int[] designSize(int screenH) {
        int h = clamp((int) (screenH * PhoneUi.basePanelHeight()), 320, 1100);
        int w = clamp((int) (h * 0.56F), 200, 620);
        return new int[] {w, h};
    }

    /**
     * 面板左上角（物理像素）：九宫格锚点 + 玩家偏移，并按视口夹取（不让面板整块跑出屏幕）。
     *
     * <p>旧实现把夹取交给 Qz 放置服务，命中盒再镜像一遍；现在绘制与命中同源，夹一次即可。</p>
     */
    private int[] panelOrigin(int screenW, int screenH, int w, int h, int offsetX, int offsetY) {
        String anchor = cfgAnchor;
        boolean left = anchor.endsWith("LEFT");
        boolean right = anchor.endsWith("RIGHT");
        boolean top = anchor.startsWith("TOP");
        boolean bottom = anchor.startsWith("BOTTOM");
        int x = (left ? HUD_MARGIN : right ? screenW - w - HUD_MARGIN : (screenW - w) / 2) + offsetX;
        int y = (top ? HUD_MARGIN : bottom ? screenH - h - HUD_MARGIN : (screenH - h) / 2) + offsetY;
        x = clamp(x, 0, Math.max(0, screenW - w));
        y = clamp(y, 0, Math.max(0, screenH - h));
        return new int[] {x, y};
    }

    /**
     * 保证 HUD 手机实例与当前设计尺寸匹配（屏幕变化/缩放改设计尺寸时重建）。
     *
     * <p>按<b>设计尺寸</b>建树（100%），倍率只在绘制时施加一次
     * （{@code context.scaled(scale)}）——这样网格单元、文本宽度都是按 100% 设计算好的，
     * 均匀缩小时不会出现「大尺寸建树 + 面板裁剪」的内容裁半。</p>
     */
    private void ensureUi(ItemStack phone, int designW, int designH) {
        if (hudUi != null && builtDesignW == designW && builtDesignH == designH) return;
        PhoneUi prev = PhoneUi.ACTIVE;
        HudPhoneUi ui = new HudPhoneUi(phone, designW, designH);
        // PhoneUi 构造会抢占 ACTIVE（全屏实例指针/时钟语义），立即还原。
        PhoneUi.ACTIVE = prev;
        // HUD 内容树整体关闭命中：输入归 mcphone 的 tick，避免 Qz 场景路由把点击派发给
        // HUD 实例（那会让 HUD 自己跳页/响应悬停）。
        disableHitTesting(ui.hudRoot());
        disposeUi();
        hudUi = ui;
        builtDesignW = designW;
        builtDesignH = designH;
    }

    /** HUD 专用 PhoneUi：把基类 protected 的 getRoot() 暴露给本类（不新增 PhoneUi 公开 API）。 */
    private static final class HudPhoneUi extends PhoneUi {
        HudPhoneUi(ItemStack phoneStack, int panelW, int panelH) {
            super(phoneStack, panelW, panelH);
        }

        SceneNode hudRoot() {
            return getRoot();
        }
    }

    /** 递归关闭命中（只影响 hit test，不改布局/绘制）。 */
    private static void disableHitTesting(SceneNode node) {
        if (node == null) return;
        node.setHitTestable(false);
        for (SceneNode child : node.__getChildren()) {
            disableHitTesting(child);
        }
    }

    /**
     * 绘制一帧 HUD：自设 ortho/viewport（与 beta.2 的手搓宿主同构），
     * 用 {@code UiRuntimeAdapters.minecraftDefaults()} 建上下文 ⇒ 图标/壁纸等
     * {@code HostImageSource} 与全屏手机一样能真正画出来。
     */
    private void drawHud(Layout layout, float partialTicks) {
        int screenW = layout.screenW;
        int screenH = layout.screenH;
        int frameBaseDepth = GlAttribDepth.current();
        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        try {
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, screenW, screenH, 0.0D, -1000.0D, 1000.0D);
            GL11.glViewport(0, 0, screenW, screenH);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                GL11.glLoadIdentity();
                UiHostRenderSupport.prepareMainUiRenderState();
                compositor.beginFrame();
                snapshotService.beginFrame();
                try {
                    UiRenderContext context = UiHostRenderSupport.createRenderContext(
                        screenW, screenH, pointerX, pointerY, partialTicks,
                        compositor, snapshotService, adapters);
                    // 设计尺寸 -> 物理尺寸只施加一次；absX/absY 走「逻辑原点」（replayer 会给每条
                    // 命令加 offset，再由 scaled 后端乘倍率，故必须传物理原点 / scale）。
                    int logicalX = Math.round(layout.x / layout.scale);
                    int logicalY = Math.round(layout.y / layout.scale);
                    hudUi.render(layout.designW, layout.designH, context.scaled(layout.scale),
                        logicalX, logicalY);
                    if (isCtrlDown()) drawEditCursor(context);
                } finally {
                    snapshotService.finishFrame();
                    compositor.finishFrame();
                }
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(previousMatrixMode);
            GlAttribDepth.popExcess(frameBaseDepth);
        }
    }

    /**
     * 按住 Ctrl（= HUD 编辑态）时在指针处画一个小十字。
     *
     * <p>为什么需要：世界里鼠标被 grab，OS 光标不可见，而命中判定用的是 LWJGL 的虚拟光标位置
     * ⇒ 玩家「看着面板点」时并不知道光标在哪，表现为「点击/拖拽完全无法操控」。画出光标后，
     * 悬停命中、Ctrl+滚轮缩放、拖拽都变成可瞄准的操作。</p>
     */
    private void drawEditCursor(UiRenderContext context) {
        int x = pointerX;
        int y = pointerY;
        if (x < 0 || y < 0) return;
        int white = 0xFFFFFFFF;
        int shadow = 0x80000000;
        context.fillRect(x - 6, y - 6, x + 7, y - 5, shadow);
        context.fillRect(x - 6, y + 6, x + 7, y + 7, shadow);
        context.fillRect(x - 6, y - 6, x - 5, y + 7, shadow);
        context.fillRect(x + 6, y - 6, x + 7, y + 7, shadow);
        context.fillRect(x - 5, y - 5, x + 6, y - 4, white);
        context.fillRect(x - 5, y + 5, x + 6, y + 6, white);
        context.fillRect(x - 5, y - 5, x - 4, y + 6, white);
        context.fillRect(x + 5, y - 5, x + 6, y + 6, white);
        context.fillRect(x - 1, y - 1, x + 1, y + 1, white);
    }

    // ===================== 小工具 =====================

    private static void openPhone(Minecraft mc, ItemStack phone) {
        PhoneHud hud = instance;
        if (hud != null && hud.hudUi != null) {
            // HUD 侧回到主屏：点击 HUD 的语义是「打开手机」，不是让 HUD 自己跳页。
            hud.hudUi.backHome();
        }
        mc.displayGuiScreen(new com.november.mcphone.client.scene.PhoneScreen(
            new PhoneUi(phone)));
    }

    private void debugLog(Minecraft mc, Layout layout) {
        long now = System.currentTimeMillis();
        if (now - debugStampMs < 1000L) return;
        debugStampMs = now;
        System.out.println("[mcphone][hud] screen=" + mc.displayWidth + "x" + mc.displayHeight
            + " design=" + layout.designW + "x" + layout.designH
            + " scale=" + layout.scale
            + " rect=(" + layout.x + "," + layout.y + "," + layout.w + "," + layout.h + ")"
            + " pointer=(" + pointerX + "," + pointerY + ")"
            + " inside=" + layout.contains(pointerX, pointerY)
            + " anchor=" + cfgAnchor + " offset=(" + cfgOffsetX + "," + cfgOffsetY + ")"
            + " scale%=" + cfgScalePercent + " dragging=" + dragging);
    }

    private static boolean isCtrlDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 一帧的几何真值（绘制与命中共用）。 */
    private static final class Layout {
        final int screenW;
        final int screenH;
        final int designW;
        final int designH;
        final float scale;
        /** 绘制原点（物理像素，左上原点）。 */
        final int x;
        final int y;
        final int w;
        final int h;

        Layout(int screenW, int screenH, int designW, int designH, float scale,
               int x, int y, int w, int h) {
            this.screenW = screenW;
            this.screenH = screenH;
            this.designW = designW;
            this.designH = designH;
            this.scale = scale;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
