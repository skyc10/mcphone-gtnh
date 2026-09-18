package com.november.mcphone.client.scene;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneToast;
import club.heiqi.uilib.ui.scene.host.AbstractSceneHostWidget;
import club.heiqi.uilib.ui.scene.host.lwjgl.LwjglInputSource;
import club.heiqi.uilib.ui.scene.host.lwjgl.LwjglStateReader;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.MountHandle;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.TimeUtil;
import com.november.mcphone.core.ItemPhone;

/**
 * 手机场景宿主：基于 Qz-UILib scene 栈渲染整部手机。
 *
 * <p>面板尺寸按宿主屏幕（原生像素）动态计算：高度约占屏幕 62%（修复旧版固定
 * 逻辑像素导致的"界面大小不正确"），宽高比约 0.56。树结构：</p>
 * <pre>
 * root (COLUMN 居中，透明)
 *   └ panel (COLUMN，圆角面板，液态玻璃 + 壁纸为背景图)
 *       ├ statusBar  (ROW：信号 + 设备名 + 时钟；上游版式 = 左信号、右时间，玻璃条)
 *       ├ contentSlot (COLUMN，显式高度，单槽页面挂载点，玻璃内容底板)
 *       └ homeBar    (ROW：主页按钮，玻璃导航条)
 * </pre>
 *
 * <p><b>液态玻璃（v3）</b>：<b>只有主面板</b>经 {@link #glassify} 取 {@code PhoneGlass} 令牌
 * （底色 + backdrop）；状态栏 / 内容底板 / 导航条 / 主屏网格经 {@link #glassifySurfaceOnly}
 * 只取底色、<b>不挂 backdrop</b>——子面挂 backdrop 会把壁纸整块盖掉（回放序：父节点自身
 * IMAGE 早于子节点 BACKDROP，而 backdrop 回放是不透明覆盖该矩形），这是「换壁纸只提示成功、
 * 界面无变化」的根因。旧常量
 * {@code COL_BG 0xF20E1116} / {@code COL_STATUS_BG 0x99000000} / {@code COL_PAGE_BG 0x900E1116}
 * 已淘汰——它们的 alpha（95% / 60% 纯黑 / 56%）会按层次契约把玻璃盖死
 * （{@code ScenePaintEngine.java:417-438} 规定 BACKDROP 先于 BACKGROUND 发出）。</p>
 */
public class PhoneUi extends AbstractSceneHostWidget implements com.november.mcphone.api.PhoneContext {

    /** 当前打开的手机实例（时钟 tick 用）；随 dispose 清空。 */
    public static volatile PhoneUi ACTIVE;

    /**
     * 快捷键捕获态：非 null 时值为待绑定 appId，下一次按键（PhoneScreen.keyTyped
     * 拦截）作为该 App 的热键。仅 appmgr 页进入/退出。
     */
    public static volatile String hotkeyCaptureTarget;

    /** 状态栏时钟（世界时间），ClientHooks 每客户端 tick 驱动。 */
    private static final Signal<String> CLOCK = Signal.create("--:--");
    private static String lastClock = "";

    /** 设备名（状态栏绑定；设置页保存后即时更新）。 */
    private static final Signal<String> DEVICE_NAME = Signal.create("");

    /** 壁纸图片源（null = 默认深色底）。相册设为壁纸后更新，立即生效。 */
    private static final Signal<SceneImageSource> WALLPAPER = Signal.create(loadWallpaper());

    // ===================== 液态玻璃表面令牌（旧配色已淘汰，§9.3/§9.4） =====================

    /**
     * 一像素分隔线 / 描边色 —— 上游常量 {@code PhoneTheme.COLOR_DIVIDER}
     * （upstream-mcphone@v1.10.2 {@code shared/src/main/java/com/november/mcphone/core/client/PhoneTheme.java:109}
     * = {@code 0x44FFFFFF}，白 27%）；上游所有分隔线都是 1px：
     * {@code g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER)}
     * （例：{@code feature/store/client/AppStore.java:207}、
     * {@code feature/settings/client/SettingsList.java:88}、
     * {@code feature/store/client/AppDetail.java:135}）。
     *
     * <p>上游另有一条更淡的同族常量 {@code COLOR_DIVIDER_FAINT}
     * （同文件 :112 = {@code 0x22FFFFFF}，只用在关于页的次级分组）：
     * 本文件没有次级分隔线可用，故不引入（未引入项记在 P1 报告里）。</p>
     *
     * <p>本文件里的 1px 线一共两处，本轮一并收口：面板外框
     * {@link #COL_BORDER}（原 {@code 0xFF39404B}）与主屏图标盒描边
     * （原 {@code 0x2EFFFFFF}，按上游图标形态<b>删除</b>，见 {@code iconCell}）。</p>
     */
    private static final int COLOR_DIVIDER = 0x44FFFFFF;

    /**
     * 面板外框（1px，改动前 {@code 0xFF39404B} 不透明板岩色）
     * —— 统一到 {@link #COLOR_DIVIDER}。
     *
     * <p><b>为什么敢把不透明外框降成半透明白</b>：① 设计文档自己就是这么建议的 ——
     * {@code docs/qz-liquid-glass-design.md:968}（P10）原文「{@code COL_BORDER = 0xFF39404B}
     * 保留（边框是倒角载体）→ 玻璃面下建议降到 {@code 0x55FFFFFF}
     * 量级以出亮边}」，{@code 0x44FFFFFF} 与之同一量级，也正是上游分隔线的取值；
     * ② 宽度一字未动（仍是 1px）⇒ 绘制路径与几何完全不变。</p>
     *
     * <p><b>Qz 4.10.0 实际口径（javap + 源码核对）</b>：{@code borderWidth>0} ⇒
     * {@code ScenePaintEngine.java:534-543} 发一条 BORDER 命令（用节点的边框色/宽/圆角）；
     * 本类不经 {@code SceneSurfaceBinder}，节点的 {@code surfaceElevation} 保持默认 {@code -1.0f}
     * （{@code ScenePaintProps.java:27}）⇒ <b>不走</b> {@code SceneSurfaceReliefPainter} 的浮雕通道
     * （那条要 {@code elevation >= 0}，{@code ScenePaintEngine.java:502}）。
     * 改色因此在 paint 层等价于「把外框从深板岩换成 27% 白」，
     * 不牵动布局、命中与玻璃挂载（glassify / glassifySurfaceOnly 一行未动）。</p>
     */
    private static final int COL_BORDER = COLOR_DIVIDER;

    /**
     * 面板圆角（改动前 22，不动）。面板是玻璃的<b>倒角载体</b>：&gt;20 才挂得住液态缘带
     * （docs/qz-liquid-glass-design.md §5.4）。
     */
    private static final int PANEL_RADIUS = 22;

    /**
     * 玻璃表面在 100% 不透明下的最大参考值：{@code 0x8D}（THICK 档面板底色，69%）。
     *
     * <p><b>为什么需要它</b>：层次契约是「BACKDROP 在节点 BACKGROUND 之前发出」
     * （{@code ScenePaintEngine.java:417-438}）⇒ 节点自身底色<b>乘在玻璃之上</b>，
     * 按 {@code (1-a)} 衰减折射缘带与镜面高光。旧值 {@code COL_BG = 0xF20E1116}（95%）
     * 与 {@code COL_STATUS_BG = 0x99000000}（60% 纯黑）就是「把玻璃盖死」的元凶，已删除。</p>
     *
     * <p>{@link #glassSurface} 是本类<b>唯一</b>产出玻璃面底色的入口，它保证产出的
     * ARGB alpha 通道 &le; 本参考值（grep 可复核：本文件里 alpha ≥ 0xE6 的<b>面底色</b>一处也没有
     * —— 唯一的 1px 描边 {@code COL_BORDER} 本轮已按上游 {@code COLOR_DIVIDER} 降到 0x44）。底色数值本身由
     * {@link com.november.mcphone.client.enhance.PhoneGlass#surface} 按档反解
     * （与材质档 tint 合成后落在裁定的总遮罩上），本文件不自行造色值。</p>
     */
    public static final int GLASS_SURFACE_ALPHA_MAX = 0x8D;

    /** 玻璃面角色与语义名的配对（诊断/评审读数用）。 */
    private static String roleName(com.november.mcphone.client.enhance.PhoneGlass.Role role) {
        if (role == null) return "?";
        switch (role) {
            case PANEL:
                return "主面板";
            case PAGE:
                return "内容底板";
            case STATUS:
                return "状态栏/导航条";
            case CARD:
                return "卡片/网格";
            case BUTTON:
                return "按钮";
            case BUTTON_HOVER:
                return "按钮悬停";
            default:
                return "?";
        }
    }

    /**
     * 玻璃面底色：一律经 {@link com.november.mcphone.client.enhance.PhoneGlass#surface} 取，
     * 并由本方法<b>显式断言</b> alpha 预算——防止后来者把一个 ≥90% 的实心底色再塞回来。
     *
     * @throws IllegalStateException 玻璃令牌越预算（几乎必然是新增硬编码色值导致）
     */
    private static int glassSurface(com.november.mcphone.client.enhance.PhoneGlass.Role role) {
        int argb = com.november.mcphone.client.enhance.PhoneGlass.surface(role);
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha > GLASS_SURFACE_ALPHA_MAX) {
            throw new IllegalStateException("玻璃面 " + roleName(role) + " 底色 alpha 0x"
                + Integer.toHexString(alpha) + " 超过预算 0x"
                + Integer.toHexString(GLASS_SURFACE_ALPHA_MAX) + "：会盖死玻璃（层次契约）");
        }
        return argb;
    }

    /**
     * 玻璃面挂载：底色 + backdrop 一次到位（建树期调用一次）。
     *
     * <p><b>只给主面板（{@link #buildPanel}）用</b>：子面（状态栏 / 内容槽 / 导航条 / 主屏网格）
     * 一律用 {@link #glassifySurfaceOnly}，否则会把壁纸整块盖掉（成因见该方法注释）。</p>
     *
     * <p>开关关闭 / Qz 玻璃类缺失时 {@code PhoneGlass.surface} 自动回中性非玻璃底色，
     * {@code apply} 静默失败 ⇒ 观感是「无玻璃的中性面板」，不保留旧深灰方案。</p>
     */
    private static void glassify(SceneNode node, com.november.mcphone.client.enhance.PhoneGlass.Role role) {
        if (node == null) return;
        node.setBackgroundColor(glassSurface(role));
        com.november.mcphone.client.enhance.PhoneGlass.apply(node, role);
    }

    /**
     * 子面玻璃降级挂载：<b>只取底色，不挂 backdrop</b>（建树期调用一次）。
     *
     * <p><b>为什么子面不能挂 backdrop</b>（「换壁纸只提示成功、界面无变化」的根因，r2_F §5.2）：
     * Qz 的绘制计划是「父节点自身 fragment 先入 plan，然后才递归子节点」
     * （{@code ScenePaintEngine.java:242/249/264-268}），而同一节点内 IMAGE 晚于 BACKDROP
     * （{@code :414-459}）⇒ 壁纸确实画在<b>面板自己那层玻璃之上</b>，但三个子面的 BACKDROP
     * 全部排在壁纸 IMAGE <b>之后</b>；backdrop 回放又是
     * {@code glColor4f(1,1,1,1)} + {@code glDisable(GL_BLEND)} 之后把「主层快照（不含壁纸）」
     * 整块盖进该节点矩形（{@code UiBackdropFilterRenderer.java:167-168/189-191}，首块 quad 在
     * glEnable(GL_BLEND) 之前）⇒ 每个子面 = 该矩形被整块替换为模糊快照。而
     * 状态栏 + 内容槽 + 导航条的高度之和 = 面板高（{@code contentHeight() = panelH - statusH - homeH}，
     * panel 无 padding）⇒ 壁纸可见面积 ≈ 0。</p>
     *
     * <p>降级后仍走 {@link #glassSurface} 的同一套「档位 ↔ 底色 ↔ alpha 预算」裁定
     * （{@code PhoneGlass.surface} 内部仍按档/角色/强度解算），所以开关、档位、强度变化照旧即时
     * 反映在底色上，只是不再有模糊与液态缘带；玻璃本体只保留 {@link #buildPanel} 一层，
     * 既符合 Qz 的层次契约（BACKDROP 只在面板层），也顺带减少每帧 backdrop 批次。</p>
     */
    private static void glassifySurfaceOnly(SceneNode node,
                                            com.november.mcphone.client.enhance.PhoneGlass.Role role) {
        if (node == null) return;
        node.setBackgroundColor(glassSurface(role));
    }

    private static final float BASE_PANEL_HEIGHT = 0.62f;

    private final ItemStack phone;

    /** 字体缩放（设置 App 调节，settings.properties 持久化）。 */
    private static volatile float fontScale = PhoneCanvas.getFontScale();
    /** 界面缩放百分比（50–150）。 */
    private static volatile int uiScalePercent = PhoneCanvas.getUiScalePercent();

    private SceneNode root;
    private SceneNode panel;
    private SceneNode contentSlot;
    private SceneNode homeBar;

    /**
     * 导航栏整格节点，索引语义 0=◁ 返回 / 1=○ 主屏（第三键 □ 任务已按用户要求删除，
     * 上游 NavButton.TASKS 本就未实现，见 NAV_GLYPHS 上方注释）。
     *
     * <p>每次整壳重建都会重新赋值（buildNavigationBar 里逐键新建）；不是 static：
     * 全屏与常显 HUD 是两个独立实例，字段必须跟实例走。</p>
     */
    private SceneNode[] navKeys;

    private MountHandle pageMount;
    /**
     * 外壳挂载句柄（{@code runtime.mount(root, …)} 的产物），整壳重建时先 dispose。
     *
     * <p><b>为什么必须有它</b>：{@link #root} 是<b>复用</b>对象（构造期只建一次，且
     * {@code PhoneHud} 只在工厂里取一次 {@code ui.hudRoot()}，见 {@code PhoneHud.java:316}），
     * 而 Qz 的 {@code SceneNode.appendChild} <b>只 add、不替换也不清理</b>
     * （已用随包发行的 {@code libs/qz_uilib-dev.jar} 字节码核对：{@code appendChild} 只有
     * {@code children.add} + 赋 parent，没有 remove）。所以「把新 panel 追加到旧 root 上」
     * 会让 root 下越积越多块等高 panel：每块各建一条状态栏（CLOCK/DEVICE_NAME 各画一遍 =
     * 双状态栏），而当前页经 {@code swapPage} 挂进<b>新</b> panel 的 contentSlot（屏外那块），
     * 屏上那块旧 panel 的槽已被 {@code pageMount.dispose()} 摘空 = 界面变空。</p>
     *
     * <p>走 mount 契约后，重建 = 先 {@code dispose()}（其 onCleanup 调
     * {@code root.removeChild(旧 panel)}）再挂新壳：root 的子节点恒为 1 块 panel，
     * 且建壳期的 bind/on 都归属外壳子 Owner，随旧壳一并退订。</p>
     */
    private MountHandle shellMount;
    private String currentPageId;
    private int panelW;
    private int panelH;

    public PhoneUi(ItemStack phoneStack) {
        this(phoneStack, 0, 0);
    }

    /**
     * 外部宿主（常显 HUD）用：显式给定面板逻辑尺寸构建，外壳/主页网格/文本
     * 宽度全部一次建对。先按全屏尺寸构建再缩小（旧 setPanelSize 路径）会让
     * 大尺寸的网格单元与文本残留，溢出被面板裁剪（HUD 只显示一半的根因）。
     */
    public PhoneUi(ItemStack phoneStack, int hudPanelW, int hudPanelH) {
        super(new LwjglInputSource(new LwjglStateReader()));
        runtime.__enableMotion();
        this.phone = phoneStack;
        clientWaypoints = new java.util.ArrayList<>(ItemPhone.getWaypoints(phoneStack));
        if (hudPanelW > 0 && hudPanelH > 0) {
            panelW = clamp(hudPanelW, 200, 900);
            panelH = clamp(hudPanelH, 320, 1400);
        } else {
            applyPanelSize();
        }
        buildShell();
        buildHomeGrid();
        ACTIVE = this;
    }

    private void applyPanelSize() {
        Minecraft mc = Minecraft.getMinecraft();
        int screenH = Math.max(400, mc.displayHeight);
        int screenW = Math.max(300, mc.displayWidth);
        float scale = uiScalePercent / 100.0f;
        int h = (int) (screenH * BASE_PANEL_HEIGHT * scale);
        panelH = clamp(h, 400, 1400);
        panelW = clamp((int) (panelH * 0.56), 260, 900);
        if (panelW > screenW - 40) panelW = Math.max(260, screenW - 40);
    }

    /** 设置页保存设备名后调用：状态栏立即刷新（服务器 NBT 稍后同步）。 */
    public static void updateDeviceName(String name) {
        String shown = (name == null || name.isEmpty())
            ? StatCollector.translateToLocal("label.mcphone.default_device") : name;
        DEVICE_NAME.set(shown);
    }

    /** 全局字号：所有手机内文本一律经此换算（设置 App 的字体缩放）。 */
    public static int fs(int size) {
        return Math.max(9, Math.round(size * fontScale));
    }

    /** 字体缩放变化后（设置页滑条）：重开当前页让新字号生效（延迟到分发结束）。 */
    public static void refreshFontScale() {
        fontScale = PhoneCanvas.getFontScale();
        postAction(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null) ui.rebuildPage();
        });
    }

    /** 按钮字号缩放变化后（设置页滑条）：重开当前页生效（延迟到分发结束）。 */
    public static void refreshButtonScale() {
        postAction(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null) ui.rebuildPage();
        });
    }

    /** 界面缩放变化后：重算面板尺寸并重排当前页（延迟到分发结束）。 */
    public static void refreshUiScale() {
        postAction(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null) {
                ui.uiScalePercent = PhoneCanvas.getUiScalePercent();
                ui.applyPanelSize();
                ui.panel.setPreferredWidth(ui.panelW);
                ui.panel.setPreferredHeight(ui.panelH);
                ui.contentSlot.setPreferredHeight(ui.contentHeight());
                ui.rebuildPage();
            }
        });
    }

    /** 重建当前页（主页或当前 App），旧 MountHandle 一并回收。 */
    public void rebuildPage() {
        String id = currentPageId;
        if (id == null) {
            swapPage(null, null);
            buildHomeGrid();
        } else {
            openApp(id);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public SceneRuntime runtime() {
        return runtime;
    }

    // ===================== PhoneContext 适配（附属 SPI） =====================

    @Override
    public int scaledFont(int size) {
        return fs(size);
    }

    @Override
    public String tr(String key) {
        return net.minecraft.util.StatCollector.translateToLocal(key);
    }

    @Override
    public Signal<String> clock() {
        return clockSignal();
    }

    @Override
    public void sendToServer(cpw.mods.fml.common.network.simpleimpl.IMessage msg) {
        com.november.mcphone.net.NetworkHandler.sendToServer(msg);
    }

    @Override
    public java.util.List<ItemPhone.Waypoint> waypoints() {
        return clientWaypoints;
    }

    /** 状态栏时钟信号（页面可绑定大号时钟显示）。 */
    public static Signal<String> clockSignal() {
        return CLOCK;
    }

    public int panelWidth() {
        return panelW;
    }

    public int panelHeight() {
        return panelH;
    }

    /** 内容区高度（面板高 - 状态栏 - 主页栏），页面/滚动容器用它做显式高度先验。 */
    public int contentHeight() {
        int statusH = measurer.lineHeight(fs(16)) + 20;
        int homeH = measurer.lineHeight(fs(16)) + 12
            + 2 * club.heiqi.uilib.ui.scene.paint.SceneChromeTokens.PAD_LG;
        return Math.max(100, panelH - statusH - homeH);
    }

    /** 面板高度占屏比基准（HUD 等外部宿主按屏换算面板尺寸用）。 */
    public static float basePanelHeight() {
        return BASE_PANEL_HEIGHT;
    }

    /**
     * 外部宿主（常显 HUD）设置面板逻辑尺寸：不改全局 uiScalePercent，
     * 重写面板 preferred 尺寸并联动内容槽与主页网格（网格单元/文本宽度
     * 均在构建时按面板尺寸定值，必须一并重建，否则溢出被面板裁剪）。
     */
    public void setPanelSize(int w, int h) {
        panelW = clamp(w, 200, 900);
        panelH = clamp(h, 320, 1400);
        panel.setPreferredWidth(panelW);
        panel.setPreferredHeight(panelH);
        contentSlot.setPreferredHeight(contentHeight());
        if (currentPageId == null) {
            rebuildPage();
        } else {
            openApp(currentPageId);
        }
    }

    public ItemStack phoneStack() {
        return phone;
    }

    // ===================== 骨架 =====================

    /** 主面板 = 液态玻璃正典面（DARK_THIN / blur 8 / lens 0.5）。 */
    private void buildPanel() {
        panel = SceneNode.column();
        panel.setPreferredWidth(panelW);
        panel.setPreferredHeight(panelH);
        panel.setCornerRadius(PANEL_RADIUS);
        // 玻璃 + 配套底色（旧 COL_BG 0xF20E1116 = 95% 会把玻璃整个盖死，已淘汰）。
        glassify(panel, com.november.mcphone.client.enhance.PhoneGlass.Role.PANEL);
        panel.setBorderWidth(1);
        panel.setBorderColor(COL_BORDER);
        panel.setClipChildren(true);
        // 壁纸作为面板背景图铺满；相册设壁纸后经 bind 即时刷新。
        // 注：BACKGROUND 与 IMAGE 都晚于 BACKDROP（ScenePaintEngine.java:417-470）⇒
        // 壁纸不透明时玻璃只在"无壁纸/壁纸透明处"可见（设计文档 §9.3 已裁定保持现状）。
        SceneImageSource wp = WALLPAPER.get();
        if (wp != null) panel.setImageSource(wp);
        runtime.bind(WALLPAPER, panel::setImageSource);
    }

    private void buildShell() {
        root = SceneNode.column();
        root.setFillParentWidth(true);
        root.setFillParentHeight(true);
        root.setCrossAxisAlign(CrossAxisAlign.CENTER);
        root.setMainAxisAlign(MainAxisAlign.CENTER);

        // 建壳：root 在构造期只建一次、身份永久保持，外壳经 runtime.mount 挂到它下面。
        // 宿主每帧经 getRoot() 取树（AbstractSceneHostWidget.java:117）；常显 HUD 也只在工厂里
        // 取一次这个 root（PhoneHud.java:316 box.appendChild(ui.hudRoot())）⇒ 绝不能换 root 对象。
        rebuildShellTree();
    }

    /**
     * 重建整棵外壳树（新建面板 + 状态栏 + 内容槽 + 导航条并挂到 {@link #root}）。
     *
     * <p><b>先 dispose 旧壳再 mount 新壳</b>（不能直接 {@code root.appendChild(panel)}）：
     * {@link #root} 是复用对象，而 Qz 的 {@code SceneNode.appendChild} 只 add 不替换
     * （jar 字节码已核对）⇒ 直接追加会让 root 下累积多块等高 panel —— 每块各建一条状态栏
     * （同一 CLOCK/DEVICE_NAME 画两遍 = 双状态栏），而 {@code swapPage} 把当前页挂进
     * <b>新</b> panel 的 contentSlot（屏外那块），屏上旧 panel 的槽已被摘空 = 界面变空。</p>
     *
     * <p>走 Qz 的挂载契约 {@link SceneRuntime#mount}：builder 在子 Owner 内执行、产物 append 到
     * parent，并登记 {@code onCleanup → parent.removeChild(旧 panel)}（jar 字节码核对：
     * {@code SceneRuntime.lambda$mount$3}）。于是 root 的子节点恒为 1 块 panel，<b>root 对象身份
     * 不变</b> ⇒ {@code PhoneHud} 的 {@code hudRoot()} 引用继续有效；建壳期的 3 个 bind
     * （WALLPAPER / CLOCK / DEVICE_NAME）也归属外壳子 Owner，随旧壳一起退订（旧写法它们落在
     * rootOwner，每次重建泄漏 3 个 effect，直到关屏才回收）。</p>
     *
     * <p>公开给常显 HUD（{@code PhoneHud}）：改玻璃档/开关/强度后 HUD 用的
     * {@code HudPhoneUi} 实例也要整壳+当前页重建（review F10）。</p>
     */
    public void rebuildShellTree() {
        if (shellMount != null) {
            shellMount.dispose();          // 旧壳出树（root.removeChild）+ 旧壳作用域 effect/事件退订
            shellMount = null;
        }
        shellMount = runtime.mount(root, this::buildShellContent);
    }

    /** 外壳内容构建（在 mount 的子 Owner 内执行一次）：返回 root 的唯一子节点 panel。 */
    private SceneNode buildShellContent() {
        buildPanel();
        buildStatusBar();
        buildContentSlot();
        buildNavigationBar();
        return panel;
    }

    // 上游（november521/mcphone @ tag v1.10.2，只读参考 clone <upstream-mcphone>）：
    //   PhoneChassis.java:168-185 drawStatusBar 的注释就是「画顶部状态栏：左侧信号、右侧时钟」：
    //     :182  int tx = phoneLeft + metrics.screenW() - 6 - font.width(time);
    //     :183  g.drawString(font, time, tx, phoneTop + 1, FONT_COLOR_STATUS, true);            ← 时间右对齐
    //     :184  g.drawString(font, "●●●●", phoneLeft + 4, phoneTop + 1, FONT_COLOR_STATUS, true); ← 信号在左
    //   PhoneTheme.java:60   FONT_COLOR_STATUS = 0xFFFFFFFF（白）
    //   PhoneTheme.java:229  STATUS_BAR_HEIGHT = 10（高矮由字号定，不随设备变）
    // 上游那串信号逐字节核对 = U+25CF × 4（hexdump 出 e2 97 8f 重复四次），
    // 不是别的字符；我们照样用字符串实现（Qz 4.10.0 的渲染出口没有画图形/矩阵块的能力，
    // 只有 fillRect / drawSurface / drawBorder / drawImage / drawText，已 javap 核对）。
    // 我们的面板宽是动态的（panelW 260–900），所以上游那两个绝对内缩量
    // 按 guiScale = panelW / 120 等比映射（与主屏 4 列同一口径，见 buildHomeGrid）；
    // 纵向上游没有量（只有 phoneTop + 1），保持原状。

    /** 状态栏左端的信号字符：上游 PhoneChassis.java:184 原文（U+25CF × 4）。 */
    private static final String STATUS_SIGNAL = "\u25CF\u25CF\u25CF\u25CF";

    /** 信号块距屏左 4 GUI（上游 PhoneChassis.java:184 的 {@code phoneLeft + 4}）。 */
    private static final int STATUS_SIGNAL_PAD_LEFT_GUI = 4;

    /** 时钟右沿距屏右 6 GUI（上游 PhoneChassis.java:182 的 {@code phoneLeft + screenW - 6}）。 */
    private static final int STATUS_CLOCK_PAD_RIGHT_GUI = 6;

    /** 状态栏三个孩子之间的间距：我们自己的量（上游是绝对定位，没有这一项）。 */
    private static final int STATUS_GAP = 8;

    /** 时钟宽度预算的样本：形状与 TimeUtil.worldClock() 的 "%02d:%02d" 同形。 */
    private static final String STATUS_CLOCK_SAMPLE = "88:88";

    private void buildStatusBar() {
        final double guiScale = panelW / UPSTREAM_SCREEN_W_GUI;
        // 右内缩里要扣掉 spacer 与时钟之间那个 gap，使时钟右沿距面板右沿恰为 6 GUI
        // （等价上游 tx = phoneLeft + screenW - 6 - font.width(time)）。
        final int padLeft = Math.max(2, (int) Math.round(STATUS_SIGNAL_PAD_LEFT_GUI * guiScale));
        final int padRight = Math.max(2,
            (int) Math.round(STATUS_CLOCK_PAD_RIGHT_GUI * guiScale) - STATUS_GAP);

        SceneNode statusBar = SceneNode.row();
        statusBar.setFillParentWidth(true);
        // Qz 的 setPadding 四参重载顺序为 (top, right, bottom, left)
        // （SceneNode.java:1571 逐行赋值）；改动前为 (12, 12, 12, 8)。
        statusBar.setPadding(12, padRight, 12, padLeft);
        statusBar.setGap(STATUS_GAP);
        statusBar.setHitTestable(false);
        // 状态栏 = DARK_ULTRA_THIN 档底色 0x23（F3 修正后：原 0x25 的合成 T=0x46 比裁定 0x40
        // 深 5/255；禁纯黑，§5.5）。**不挂 backdrop**：状态栏横跨整块面板，挂上去会把壁纸顶边整条盖掉。
        glassifySurfaceOnly(statusBar, com.november.mcphone.client.enhance.PhoneGlass.Role.STATUS);
        // 高度先验（Qz 布局求解器要求 grow 的兄弟可先验，否则 contentSlot 高度解耦失败=主屏空白）。
        statusBar.setPreferredHeight(measurer.lineHeight(fs(16)) + 20);

        // ---- 左 1：信号块（上游字符形式，逐字照抄；我们没有信号贴图资源，用同形态文本占位）----
        SceneNode signal = new SceneNode();
        signal.setText(STATUS_SIGNAL);
        // 上游用固定白 FONT_COLOR_STATUS（底是固定的 COLOR_SCRIM 压暗层）；我们的状态栏取色
        // 走「字体颜色」预设（PhoneTheme 六套），故沿用既有取色口径，不钉死白色。
        signal.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        signal.setFontSize(fs(16));
        signal.setHitTestable(false);
        statusBar.appendChild(signal);
        final int signalW = measurer.measureWidth(STATUS_SIGNAL, fs(16));

        // ---- 左 2：设备名（我们的增量：上游状态栏只有信号 + 时间）----
        // 上游的设备名只是设置页里的一行值（SettingsList），状态栏上不出现；
        // 我们保留它、放在信号块右侧（紧挨左端），右上角留给时钟独占
        // —— 这样「右端只有时间」与上游一致。
        String dev = ItemPhone.getDeviceName(phone);
        DEVICE_NAME.set(dev == null || dev.isEmpty()
            ? StatCollector.translateToLocal("label.mcphone.default_device") : dev);
        SceneNode devName = new SceneNode();
        devName.setText(DEVICE_NAME.get());
        devName.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        devName.setFontSize(fs(14));
        // 宽度预算 = 面板宽 - 两端内缩 - 信号 - 时钟 - 三个 gap
        // （等价上游现算 font.width(time)，只是改动前写死的 panelW - 90）；
        // 不够就由 setMaxTextWidth 截断，时钟永远不会被挤出屏外。
        final int timeW = measurer.measureWidth(STATUS_CLOCK_SAMPLE, fs(16));
        devName.setMaxTextWidth(Math.max(0,
            panelW - padLeft - padRight - signalW - timeW - 3 * STATUS_GAP));
        devName.setHitTestable(false);
        runtime.bindText(devName, DEVICE_NAME);
        statusBar.appendChild(devName);

        // ---- 弹性空隙：把时钟推到右端（等价上游 tx = phoneLeft + screenW - 6 - width(time)）----
        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        statusBar.appendChild(spacer);

        // ---- 右：时钟（上游 PhoneChassis.java:183 的位置；改动前它在左端）----
        SceneNode time = new SceneNode();
        time.setText(CLOCK.get());
        time.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        time.setFontSize(fs(16));
        time.setHitTestable(false);
        runtime.bindText(time, CLOCK);
        statusBar.appendChild(time);

        panel.appendChild(statusBar);
    }

    private void buildContentSlot() {
        contentSlot = SceneNode.column();
        contentSlot.setFillParentWidth(true);
        // 高度用显式先验而非 flexGrow：grow 求解器在内容型兄弟旁会早退（间歇性主屏空白/不可滚动的根因）。
        contentSlot.setPreferredHeight(contentHeight());
        contentSlot.setClipChildren(true);
        // 内容底板 = DARK_THIN 档底色（旧 0x900E1116/56% 会压暗玻璃）。**不挂 backdrop**：
        // 内容槽占面板最大面积，挂上去等于把壁纸整块盖掉（换壁纸看不出变化的直接原因）。
        glassifySurfaceOnly(contentSlot, com.november.mcphone.client.enhance.PhoneGlass.Role.PAGE);
        panel.appendChild(contentSlot);
    }

    // ===================== 底部导航栏（◁ 返回 / ○ 主屏，版式抄上游 v1.10.2） =====================
    // 上游（november521/mcphone @ tag v1.10.2，只读参考 clone <upstream-mcphone>）：
    //   PhoneChassis.java:208   NAV_GLYPHS = {"◁", "○", "□"}
    //   PhoneChassis.java:205   NAV_ORDER  = {BACK, HOME, TASKS}（左=返回 / 中=主屏 / 右=任务）
    //   PhoneChassis.java:255-298 drawNavKeys：各键沿导航条长边等分，字符版居中画，
    //                             贴图版按【设计尺寸 NAV_ICON_WIDTH×NAV_BAR_HEIGHT】画、不撑满格子。
    //   NavBarLayout.java:49-61 cellFrom/cellTo = span/cells*i（等分，余数归最后一格）
    //   PhoneTheme.java:238     NAV_BAR_HEIGHT  = 14
    //   PhoneTheme.java:248     NAV_ICON_WIDTH  = 40（120 屏宽 / 3，一格正好 40×14）
    //   PhoneTheme.java:63      FONT_COLOR_NAV       = 0xFF888888（中灰）
    //   PhoneTheme.java:66      FONT_COLOR_NAV_HOVER = 0xFFFFFFFF
    //   PhoneTheme.java:115     COLOR_ROW_HOVER      = 0x33FFFFFF（悬停垫在整格下面）
    // 第三键 □（TASKS）按用户要求删除：上游 NavButton.TASKS 的注释就是「多任务，暂未实现」
    // （PhoneChassis.java:197），点击无行为，故不保留无功能占位键；NAV_GLYPHS 只留前两键，
    // 索引语义变为 0=返回 / 1=主屏（上游 NAV_ORDER 的前两项不变）。
    // 我们的面板宽是动态的（panelW ≈ 260 起），上游是固定 120 GUI，所以“一格 40 GUI”这种
    // 绝对宽度在这里没有意义：能照搬的是【等分】这条结构规则（每键 span/键数）与【设计尺寸
    // 不撑满格子】这条绘制规则。字号走面板无关量 NAV_GLYPH_SIZE_GUI × fs(16)，免得面板
    // 一宽字号就跟着膨胀。

    /** 导航键字符（与上游 NAV_GLYPHS 前两键逐字相同：空心左三角 / 空心圆；第三键 □ 已删）。 */
    private static final String[] NAV_GLYPHS = {"\u25C1", "\u25CB"};

    /**
     * 导航键字形相对 {@code fs(16)} 的比例。
     *
     * <p>上游一格是 40×14，图标画 8 GUI 高（{r2_shots.md §3.2 实测「图标高度约 32px
     * = 8 GUI」）⇒ 8/14 ≈ 0.57。取 0.9 是因为我们的字形本身比格子小：AWT 实测该字号下三个
     * 字符的 ink 高只有 0.75 em（33px 字号 → 25.2px ink），0.9 × fs(16)=16 时 ink 高约 11px，落在
     * 12–14px 的格子里正合适。</p>
     */
    private static final float NAV_GLYPH_SIZE_GUI = 0.9f;

    /** 导航键常态字形色 = 上游 FONT_COLOR_NAV（0xFF888888）。 */
    private static final int NAV_GLYPH_NORMAL = 0xFF888888;

    /** 导航键悬停时的字形色 = 上游 FONT_COLOR_NAV_HOVER（0xFFFFFFFF）。 */
    private static final int NAV_GLYPH_HOVER = 0xFFFFFFFF;

    /** 导航键悬停垫色 = 上游 COLOR_ROW_HOVER（0x33FFFFFF）。 */
    private static final int NAV_KEY_HOVER_BG = 0x33FFFFFF;

    /** 返回键在根页（主屏）上的不透明度：等效上游「没有上一层可回」的禁用观感。 */
    private static final float NAV_BACK_DISABLED_OPACITY = 0.35f;

    /** 导航键字形字号（面板无关，只随全局字体缩放；理由见 NAV_GLYPH_SIZE_GUI）。 */
    private static int navGlyphFontSize() {
        return Math.max(10, Math.round(fs(16) * NAV_GLYPH_SIZE_GUI));
    }

    /**
     * 底部导航栏：<b>两个等宽键</b> ◁ ○（版式抄上游 v1.10.2；第三键 □ 任务按用户要求删除，
     * 上游本就未实现，理由见 NAV_GLYPHS 上方注释）。
     *
     * <p>两个键沿条长边对半等分（上游 NavBarLayout.cellFrom/cellTo 的等分规则），
     * 保证“画在哪儿”与“点得到哪儿”完全同源 —— 这里用 flexGrow(1) 表达同一件事，
     * 布局求解器给出的格子就是命中测试用的格子，不存在看得见点不到的分歧。</p>
     *
     * <p><b>玻璃保留</b>：整条照旧走 {@link #glassifySurfaceOnly}（STATUS 档底色、不挂 backdrop），
     * 玻璃语义零改动（上游那条是纯实心 COLOR_NAV_BAR，用户明确要求保留我们的玻璃）。</p>
     */
    private void buildNavigationBar() {
        homeBar = SceneNode.row();
        homeBar.setFillParentWidth(true);
        homeBar.setCrossAxisAlign(CrossAxisAlign.CENTER);
        homeBar.setMainAxisAlign(MainAxisAlign.CENTER);
        // 两键等宽撑满整条：去掉上下左右 padding（上游各键等分屏幕宽，边缘没有内缩）。
        homeBar.setPadding(0, 0, 0, 0);
        homeBar.setGap(0);
        // 导航条 = 条状小面，同状态栏档底色（DARK_ULTRA_THIN）。**不挂 backdrop**（同状态栏：
        // 它横跨面板底边，挂上去会盖掉壁纸下沿）。
        glassifySurfaceOnly(homeBar, com.november.mcphone.client.enhance.PhoneGlass.Role.STATUS);
        // 高度先验：字形行高 + 上下各 6（与旧口径同量级；上游 NAV_BAR_HEIGHT=14 是固定 GUI
        // 高，我们这里由字号定高，面板尺寸一变条跟着变，正是 PhoneTheme 注释里“条的高矮
        // 由字号定”的同一条理由）。
        homeBar.setPreferredHeight(measurer.lineHeight(navGlyphFontSize()) + 12);
        // 键序沿上游 NAV_ORDER 前两项：左=◁ 返回、右=○ 主屏（第三键 □ 任务已删除）。
        navKeys = new SceneNode[NAV_GLYPHS.length];
        for (int i = 0; i < NAV_GLYPHS.length; i++) {
            navKeys[i] = mountNavKey(homeBar, i);
        }
        panel.appendChild(homeBar);
        refreshNavKeyStates();
    }

    /**
     * 挂第 {@code keyIndex} 个导航键（0=◁ 返回 / 1=○ 主屏），返回整格节点。
     *
     * <p>字形用字符而不是自绘：Qz 4.10.0 的渲染出口 UiRenderBackend 只有
     * {@code fillRect / drawSurface / drawBorder / drawImage / drawText}，<b>没有</b>画线/路径/三角形/圆弧
     * 的能力（javap qz_uilib-4.10.0.jar 核对），所以空心 ◁○ 只能走字形（上游也是字形）。</p>
     */
    private SceneNode mountNavKey(SceneNode bar, int keyIndex) {
        final int index = keyIndex;
        SceneNode key = SceneNode.row();
        key.setFlexGrow(1);
        key.setCrossAxisAlign(CrossAxisAlign.CENTER);
        key.setMainAxisAlign(MainAxisAlign.CENTER);
        key.setBackgroundColor(0x00000000);

        SceneNode glyph = new SceneNode();
        glyph.setText(NAV_GLYPHS[index]);
        glyph.setTextColor(NAV_GLYPH_NORMAL);
        glyph.setFontSize(navGlyphFontSize());
        glyph.setHitTestable(false);
        key.appendChild(glyph);

        // 悬停：整格垫 COLOR_ROW_HOVER + 字形提亮到 FONT_COLOR_NAV_HOVER。
        // interactionState 的 hovered() 是 Qz 4.10.0 的公开信号（SceneInteractionState.java:51）；
        // bindComputed 在它读到的信号变化时重新求值（SceneRuntime.java:583）。
        var interaction = runtime.interactionState(key);
        runtime.bindComputed(
            () -> Boolean.TRUE.equals(interaction.hovered().get()) ? NAV_KEY_HOVER_BG : 0x00000000,
            key::setBackgroundColor);
        runtime.bindComputed(
            () -> Boolean.TRUE.equals(interaction.hovered().get())
                ? NAV_GLYPH_HOVER : NAV_GLYPH_NORMAL,
            glyph::setTextColor);
        // 回调延迟到下一帧开头：与图标点击（{@link #activate} 里的 post）同一口径，
        // 避开 Qz 输入路由迭代中改树导致的 ConcurrentModificationException（踩坑 #3）。
        runtime.on(key, SceneEventType.CLICK, (event, ctx) -> PhoneUi.postAction(() -> activateNavKey(index)));
        bar.appendChild(key);
        return key;
    }

    /**
     * 导航键行为映射（上游 NavButton 前两语义 → 我们现有的等价行为）。
     *
     * <ul>
     *   <li><b>◁ BACK</b>：有 App 页开着 ⇒ {@link #backHome()}（返回主屏）；已在主屏 ⇒
     *       {@link #closePhone()}（= 上游 BACK 的“退回上一层”，主屏的上一层就是收起手机）。</li>
     *   <li><b>○ HOME</b>：{@link #backHome()}，与上游 NavButton.HOME 逐字对应。</li>
     * </ul>
     *
     * <p>上游第三键 □（TASKS）按用户要求删除，不再保留无行为分支：上游 v1.10.2 里
     * NavButton.TASKS 的枚举注释就写着「多任务，<b>暂未实现</b>」（PhoneChassis.java:197），
     * 点击本就不做事。</p>
     *
     * <p>两路都经 {@link #postAction} 延迟（runtime 的 CLICK 回调转成 post），与 {@link #activate}
     * 同一口径：输入分发期间改树会让 Qz 路由 CME（踩坑 #3）。</p>
     */
    private void activateNavKey(int keyIndex) {
        switch (keyIndex) {
            case 0:
                if (isHome()) {
                    closePhone();
                } else {
                    backHome();
                }
                return;
            case 1:
                backHome();
                return;
        }
    }

    /** 按“当前在不在主屏”刷新返回键的可用观感（主屏上压暗，等效上游“没有上一层”）。 */
    private void refreshNavKeyStates() {
        if (navKeys == null || navKeys.length == 0) return;
        SceneNode back = navKeys[0];
        if (back == null) return;
        back.setOpacity(isHome() ? NAV_BACK_DISABLED_OPACITY : 1.0f);
    }

    /**
     * 重新应用玻璃令牌到外壳与当前内容（设置页改玻璃开关/档位/强度后调用）。
     *
     * <p><b>为什么整壳重建</b>：底色 alpha 与材质档是成对裁定的（§9.2 材质档↔文字色配对表），
     * 换档会让「面的厚度 ↔ 文字色 ↔ 圆角」一起变；只改 backdrop 会留下旧底色的双重遮罩。
     * 因此这里整树重建（{@link #rebuildShellTree}）并重开当前页——按钮/卡片
     * （{@code PhoneWidgets}）的玻璃态在页面重建时才重算。</p>
     *
     * <p><b>调用时机</b>：设置页回调里经 {@link #postAction} 延迟到渲染帧开头
     * （{@link #flushPendingActions}），此时改树安全（踩坑 #3：输入路由迭代中改树会 CME）。</p>
     *
     * <p><b>常显 HUD 也要跟（review F10）</b>：HUD 用的是另一个 {@code HudPhoneUi} 实例
     * （构造后 {@code PhoneUi.ACTIVE} 被还原为全屏实例，故它不是 ACTIVE）⇒ 这里额外调
     * {@link com.november.mcphone.client.hud.PhoneHud#onGlassSettingsChanged()}，
     * 由它在本帧的 post 队列里重建 HUD 实例。没有 HUD / 没有实例时是 no-op。</p>
     */
    public static void refreshGlassShell() {
        PhoneUi ui = ACTIVE;
        if (ui == null) return;
        ui.rebuildShellTree();
        // 主页网格由 rebuildPage() 重建（currentPageId==null 分支），旧 MountHandle 一并回收。
        ui.rebuildPage();
        com.november.mcphone.client.hud.PhoneHud.onGlassSettingsChanged();
    }

    // ===================== 导航 =====================

    /** 主屏是否在前（否则有 App 页面打开）。 */
    public boolean isHome() {
        return currentPageId == null;
    }

    /** 指定 App 页面是否正开着（同步到达后按需重建用）。 */
    public boolean isPageOpen(String id) {
        return id != null && id.equals(currentPageId);
    }

    /** 打开指定 App 页面（页面型 App）；商店模式下未购付费 App 拦下并提示。 */
    public void openApp(String id) {
        IPhoneApp app = PhoneApi.byId(id);
        if (app == null || app.isDirectAction()) return;
        if (!com.november.mcphone.client.StoreClient.isUnlocked(app)) {
            toastLocked();
            return;
        }
        swapPage(app.id(), app.createPage(this));
    }

    /** 返回主屏。 */
    public void backHome() {
        swapPage(null, null);
        buildHomeGrid();
    }

    public void closePhone() {
        pendingClose = true;
    }

    public void toast(String msg) {
        SceneToast.showInfo(runtime, msg);
    }

    private void swapPage(String id, SceneNode page) {
        if (pageMount != null) {
            pageMount.dispose();
            pageMount = null;
        }
        currentPageId = id;
        if (page != null) {
            pageMount = runtime.mount(contentSlot, () -> page);
        }
        // 回到主屏时把返回键压暗（等效上游“没有上一层”），进 App 时恢复。
        //
        // 【为什么去掉了旧的 homeBar.setOpacity(0.35f/1.0f)】上游导航条在主屏
        // 与 App 页上是同一个不透明度（PhoneChassis.drawNavBar 里没有任何整条 opacity），
        // 只有「当前这一键」有状态。而且旧值会和返回键自己的压暗相乘（0.35² ≈ 0.12，
        // 返回键几乎看不见）—— 这个组合是错的。
        refreshNavKeyStates();
    }

    // ===================== 主屏网格尺度（版式抄上游 v1.10.2） =====================
    // 上游（november521/mcphone @ tag v1.10.2，只读参考 clone <upstream-mcphone>）：
    //   PhoneTheme.java:251  APP_ICON_SIZE        = 20
    //   PhoneTheme.java:254  APP_GRID_SPACING_X   = 8
    //   PhoneTheme.java:257  APP_GRID_PADDING_TOP = 6
    //   PhoneTheme.java:266  APP_COLUMNS_MAX      = 8（手机 120 宽算出正好 4 列）
    //   PhoneTheme.java:269  APP_ROWS            = 5
    //   PhoneTheme.java:272  APP_NAME_SCALE      = 0.6f
    //   PhoneTheme.java:275  PAGE_DOTS_HEIGHT    = 8
    //   HomeLayout.java:61   gridInset = (available - (cells*cellSize - spacing)) / 2（整排居中）
    //   HomeGrid.java:431    appCellWidth  = APP_ICON_SIZE + APP_GRID_SPACING_X  = 28
    //   HomeGrid.java:436    appCellHeight = APP_ICON_SIZE + (int)(lineHeight*APP_NAME_SCALE) + 4
    //   HomeGrid.java:394    renderPageDots: pages <= 1 直接 return（单页不画页码点）
    // 量测结论（_r2_shots.md §3.4，换算系数 s = 4.0 px/GUI）：
    //   列步距 112px = 28 GUI（= 20 + 8）、行步距 116px = 29 GUI（= 20 + 5.4 + 4）、
    //   首列距屏左 32px = 8 GUI（等于上游 gridInset 在 ±120 屏上的值）。
    //
    // 【为什么不能直接把 20 / 28 / 29 / 8 当场景像素用】
    // 我们的面板是动态的：1080p 、UI 100% 时 panelH = 1080*0.62 = 670，
    // panelW = 670*0.56 = 375（PhoneUi.applyPanelSize）；图标盒真正用的是渲染尺寸（本例实测
    // native 1920x1080），直接用 20px 图标在 375 宽的面板上只占 5%，与上游那种
    // “图标占屏宽 1/6”的版式完全不同。所以这里把上游布局对面板宽归一化：
    //   guiScale = panelW / 120.0        ⇐ 120 = 上游屏幕可绘制区宽（GUI）
    //   APP_ICON_SIZE    × guiScale = 20  GUI → 实际像素
    //   APP_GRID_STEP_Y  × guiScale = 29  GUI
    //   APP_GRID_PADDING_LEFT × guiScale = 8 GUI
    // 列宽/列步距（上游 APP_GRID_STEP_X = 28 GUI）不再照搬：4×固定步距在动态面板宽下
    // 会超出网格可用宽（Qz 不收缩显式钉宽的节点，第 4 格曾被 grid.setClipChildren 裁半），
    // 改为把可用宽（panelW - 左右内边距）按 4 列等分、每格取整余数忽略（见 buildHomeGrid 的 cellW）。
    // 这样「4 列、行步距 29、首列距屏左 8」跟上游，列宽改为恰好铺满可用宽。

    /** 上游屏幕可绘制区宽（GUI）：_r2_shots.md §3.1 实测 120×190。 */
    private static final double UPSTREAM_SCREEN_W_GUI = 120.0;

    /** App 图标边长（GUI，上游 APP_ICON_SIZE = 20）。 */
    private static final int APP_ICON_SIZE_GUI = 20;

    /** 图标行步距（GUI，上游实测 116px = 29）。 */
    private static final int APP_GRID_STEP_Y_GUI = 29;

    /** 单页最多几行（上游 APP_ROWS = 5）。 */
    private static final int APP_ROWS_MAX = 5;

    /** 上游主屏网格左边距（GUI）：_r2_shots.md §3.4 实测 32px = 8。 */
    private static final int APP_GRID_PAD_LEFT_GUI = 8;

    /**
     * 上游主屏网格上边距（GUI）：{@code PhoneTheme.APP_GRID_PADDING_TOP = 6}
     * （PhoneTheme.java:257），_r2_shots.md §3.4 实测「第一行顶起点距 statusH 下沿 ≈ 24px = 6 GUI」。
     * 上游 HomeGrid.java:86 把它算在【状态栏下沿】上：{@code gridStartY = phoneTop + STATUS_BAR_HEIGHT + APP_GRID_PADDING_TOP}。
     */
    private static final int APP_GRID_PADDING_TOP_GUI = 6;

    /** 主屏图标与名字的间距（上游 HomeGrid.java:438 的那个 +4）。 */
    private static final int APP_LABEL_GAP = 4;

    /**
     * 主屏标签声明字号：行高预算（buildHomeGrid 的 labelH）与 iconCell 实挂字号共用这本账
     * （t6 统一：改动前预算按 fs(13)、标签实挂 fs(14)，长名折行后行内容超出 rowH，
     * 各行图标垂直错位——用户截图二）。
     */
    private static final int APP_LABEL_FONT_SIZE_GUI = 13;

    /** 主屏标签自适应缩小的声明字号下限：再小可读性崩坏，宁可省略号截断。 */
    private static final int APP_LABEL_FONT_MIN_GUI = 9;

    /**
     * 图标盒圆角除数：{@code box / 8}（改动前 {@code box / 3}）。
     * 上游图标无自绘圆角（贴图自带透明角，IPhoneApp.java:42-47），
     * 这里是玻璃风格下的收敛值；最小 2px 保证小面板上不变成直角。
     */
    private static final int ICON_BOX_RADIUS_DIVISOR = 8;

    /**
     * 主屏网格：<b>4 列</b>、图标 <b>20 GUI</b>、行步距 <b>29 GUI</b>、整排居中；
     * 每格宽 = 网格可用宽的 1/4（面板自适应）。相对上游改了两件事：尺寸由固定 GUI 改成
     * 按面板宽归一化（理由见上方常量段）；列宽不再用固定步距 28 GUI，而是「可用宽 ÷ 4」
     * 等分（每格取整、余数忽略）—— 固定 4×stepX 会超出可用宽，Qz 布局不收缩显式钉宽节点，
     * 第 4 格曾被 grid.setClipChildren(true) 裁掉一半（2026-09 用户实测），等分后
     * 4×cellW ≤ 可用宽恒成立，一行 4 格恰好铺满。
     *
     * <p><b>不回归</b>：拖拽排序、点击打开（UP 现算落点 + 同格补发激活）、滚动
     * （SceneScrolls.attach）全部保留：单元格还是那个单元格（iconCell），只是宽度来源变了；
     * 拖拽命中全部建立在 SceneGeometry.absoluteBox 与手势阈值上，与宽度怎么来的无关；
     * setScrollable/SceneScrolls.attach 一行未动。</p>
     */
    private void buildHomeGrid() {
        List<IPhoneApp> apps = orderedForHome();

        // 上游布局对面板宽归一化：120 GUI 屏 → panelW 场景像素。
        final double guiScale = panelW / UPSTREAM_SCREEN_W_GUI;
        final int iconSize = Math.max(12, (int) Math.round(APP_ICON_SIZE_GUI * guiScale));
        final int stepY = Math.max(iconSize + 6, (int) Math.round(APP_GRID_STEP_Y_GUI * guiScale));
        // 上游一行几格 = HomeLayout.cellsThatFit(屏宽, 列步距, 上限)；
        // 手机 120 宽 / 28 = 4（上游 PhoneTheme.java:260 注释“手机 120 宽正好 4 个”）。
        final int perRow = 4;
        final int maxRows = APP_ROWS_MAX;

        // 每格宽 = 网格可用宽 ÷ perRow（取整、余数忽略）：网格容器 fillParentWidth、左右内边距
        // 各 APP_GRID_PAD_LEFT_GUI（面板与内容槽都无 padding），可用宽恰为
        // panelW - 2*APP_GRID_PAD_LEFT_GUI ⇒ 4*cellW ≤ 可用宽恒成立，一行 4 格恰好铺满、
        // 永不横向溢出；图标盒（iconSize 宽）在格内居中，图标/格宽 ≈ (panelW/6)/(panelW/4) = 2/3，
        // 与上游 20/28 ≈ 0.71 同量级。cellW 同时是标签 setMaxTextWidth 的上限。
        final int availW = Math.max(perRow, panelW - 2 * APP_GRID_PAD_LEFT_GUI);
        final int cellW = availW / perRow;

        SceneNode grid = SceneNode.column();
        grid.setFillParentWidth(true);
        // 上游 gridInset = (available - (cells*step - spacing)) / 2（整排居中）；在 120 屏上
        // 算出正好 8（_r2_shots.md §3.4 首列 32px = 8 GUI）。这里当作网格容器的
        // 左右内边距，四列自然就在剩下的宽度里居中。
        // 上边距 = 左边距 + APP_GRID_PADDING_TOP（上游 HomeGrid.java:86 同口径：
        // 图标区从状态栏下沿再下 6 GUI 开始）。我们的网格容器紧接状态栏，
        // 所以这 6 GUI 就由容器的上内边距承担。
        // Qz 的 setPadding 四参重载顺序已核实为 (top, right, bottom, left)
        // （SceneNode.java:1571 逐行赋值）。
        grid.setPadding(
            APP_GRID_PAD_LEFT_GUI + (int) Math.round(APP_GRID_PADDING_TOP_GUI * guiScale),
            APP_GRID_PAD_LEFT_GUI, APP_GRID_PAD_LEFT_GUI, APP_GRID_PAD_LEFT_GUI);
        grid.setGap(0);
        grid.setScrollable(true);
        grid.setClipChildren(true);
        // 滚轮必须显式 attach（setScrollable 只声明可滚动；踩坑 #2）。
        club.heiqi.uilib.ui.scene.runtime.SceneScrolls.attach(runtime, grid);

        // 主屏图标格底衬：CARD 档**底色**（0x5A/45% 级，远低于 90% ⇒ 不盖死玻璃）。
        // **不挂 backdrop**：网格铺满内容槽，挂上去同样会把壁纸盖掉（r2_F §5.2⑦）。
        // 刻意不挂到 iconBox：图标盒是 accent 实色（§9.1 总则 3），上玻璃会让小图标失去识别度；
        // 且玻璃只改 PAINT 属性（`SceneNode.setBackdrop` 与背景同属 paintProps，不参与布局度量）
        // ⇒ 不影响本页拖拽命中所依赖的几何（见 iconCell 的拖拽判据注释）。
        glassifySurfaceOnly(grid, com.november.mcphone.client.enhance.PhoneGlass.Role.CARD);

        // 拖拽排序共享状态：本轮网格的单元格序（扁平，行优先）与手势状态。
        final java.util.List<SceneNode> cellNodes = new java.util.ArrayList<>();
        final java.util.List<String> cellIds = new java.util.ArrayList<>();
        final HomeDrag drag = new HomeDrag();

        // 行数上限：上游 APP_ROWS = 5。上游是“算出来几行就几行，最多 5”
        // （HomeGrid.java:458 cellsThatFit(dotsTop - gridStartY, cellH, APP_ROWS)）；我们面板矮时同样只能
        // 放得下那么多行，多出来的靠 SceneScrolls 滚动（不丢 App）。
        final int availH = Math.max(stepY, contentHeight() - measurer.lineHeight(fs(16)) - 20
            - 2 * APP_GRID_PAD_LEFT_GUI);
        final int rowsFit = Math.max(1, availH / stepY);
        final int maxVisibleRows = Math.min(maxRows, Math.max(rowsFit, 1));

        int rowIndex = 0;
        for (int i = 0; i < apps.size(); i += perRow, rowIndex++) {
            // 行的高 = 图标 + 图标与名字的间距 + 名字行高（上游
            // HomeGrid.appCellHeight = APP_ICON_SIZE + (int)(lineHeight*APP_NAME_SCALE) + 4 同口径；
            // 上游用 APP_NAME_SCALE=0.6f，我们用 fs(APP_LABEL_FONT_SIZE_GUI)——与 iconCell
            // 标签实际字号同一本账（t6 统一；标签已单行化，预算不再被折行的第二行撑爆）。
            // 这里用【固定图标盒 + 真实标签行高】而不直接用 stepY：行与行之间的固定
            // 留白已经含在 stepY 里（上游 29 = 20 + 5.4 + 4），不能再重复计一次。
            final int labelH = measurer.lineHeight(fs(APP_LABEL_FONT_SIZE_GUI));
            final int rowH = Math.min(stepY,
                iconSize + APP_LABEL_GAP + labelH);
            // 行与行之间的间距已含在行高里（上游行步距 29 = 图标 20 + 标签
            // + 固定留白），所以这里不再 setGap。
            if (rowIndex >= maxVisibleRows) break;
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setMainAxisAlign(MainAxisAlign.CENTER);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            // 格宽已钉死为 cellW 且 4*cellW ≤ 行内宽 ⇒ 格间 gap 必须为 0（任何 gap 都会把
            // 行内容重新顶出可用宽）；尾行不满 4 格时仍由 CENTER 整排居中（与旧版式一致）。
            row.setGap(0);
            // 固定行高先验：避免布局求解器把单元格拉伸（与改动前同口径）。
            row.setPreferredHeight(rowH);
            for (int j = 0; j < perRow && i + j < apps.size(); j++) {
                // 单元宽 = 可用宽的 1/4（替代上游固定 appCellWidth = 28，理由见 cellW 注释）；
                // 标签宽度与拖拽命中都跟它。
                row.appendChild(iconCell(apps.get(i + j), cellW, iconSize, cellNodes, cellIds, drag));
            }
            grid.appendChild(row);
        }
        pageMount = runtime.mount(contentSlot, () -> grid);
    }

    /**
     * 主屏展示顺序：按存档隔离的拖拽顺序（HomeGridStore），文件缺失时回落全局顺序表。
     * 商店模式开启时，未购付费 App 不上主屏（购买入口在应用商店 App）。
     * 星门（t8）：服务端规则禁用的 App（StargateSync id 19 名单）直接不上主屏——
     * 未同步/旧服不发 → 名单空 → 安全退化全显示。
     */
    private List<IPhoneApp> orderedForHome() {
        java.util.List<String> known = new java.util.ArrayList<>();
        for (IPhoneApp app : PhoneApi.orderedVisibleApps()) {
            // 星门：被服务器规则禁用的 App 主屏直接不显示（服务端 toast 拒绝逻辑保留，
            // 兜住直达热键等旁路；名单来自 StargateClient，未同步恒为空集）。
            if (com.november.mcphone.client.enhance.StargateClient.isHidden(app.id())) continue;
            if (com.november.mcphone.client.StoreClient.needsPurchase(app)) continue;
            known.add(app.id());
        }
        List<IPhoneApp> out = new java.util.ArrayList<>();
        for (String id : com.november.mcphone.client.enhance.HomeGridStore.resolveOrder(known)) {
            IPhoneApp app = PhoneApi.byId(id);
            if (app != null && PhoneCanvas.isAppEnabled(id)) out.add(app);
        }
        return out;
    }

    /**
     * 主屏拖拽手势状态（一次网格构建一份）。Qz 没有网格级拖拽控件，这里用
     * 指针 DOWN/MOVE/UP 自实现最小拖拽：超过阈值激活（激活时捕获指针），
     * 落点按"指针压在哪个格子"判定，松手插入式重排并按存档持久化。
     */
    private static final class HomeDrag {

        /** 超过该位移（像素）才算拖拽，否则视为点击。 */
        static final int ACTIVATION_THRESHOLD_PX = 10;

        boolean armed;
        boolean dragging;
        int pressedIndex = -1;
        float pressX;
        float pressY;
        /**
         * 拖拽结束后要吞掉的 CLICK 所在格子（避免拖完顺手打开了 App）。
         *
         * <p>注意 {@code -1} 之外还有「明明没换位却也置上」的一路：见 POINTER_UP 的同格落定分支
         * ——那里是我们自己补发点击、再由 CLICK 分支把它消费掉，保证恰好激活一次。</p>
         */
        int suppressedClickIndex = -1;
    }

    /**
     * 主屏图标格（drag 手势与命中都在这里）。
     *
     * <p><b>玻璃化不影响拖拽命中与阈值</b>（验收项）：</p>
     * <ul>
     *   <li><b>不改几何</b>：玻璃是 PAINT 级属性（{@code SceneNode.setBackdrop} 只写
     *       {@code paintProps.backdrop} 并 {@code markSelfPaint()}，无边距/尺寸语义，
     *       {@code SceneNode.java:730-740}）⇒ 单元格的 {@code absoluteBox} 与玻璃化前逐像素相同，
     *       而命中判据完全建立在几何上：开始拖拽用
     *       {@link HomeDrag#ACTIVATION_THRESHOLD_PX}（10px，平方比较，未改），
     *       落点用 {@link #dropIndexAt} 对 {@code SceneGeometry.absoluteBox} 做矩形包含判定。</li>
     *   <li><b>不改可命中性</b>：单元格与图标盒都不调用 {@code setHitTestable(false)}；
     *       只有 label/glyph 子节点是 {@code hitTestable=false}，与改动前一致。</li>
     *   <li><b>不改事件路由</b>：指针事件仍挂在 cell（POINTER_DOWN/MOVE/UP/CANCEL/CLICK），
     *       拖动中 {@code ctx.requestPointerCapture()} 与 {@code cell.setOpacity(0.55f)}
     *       走的是 opacity（合成级）而非玻璃，两者互不覆盖。</li>
     *   <li>玻璃底衬挂在网格容器（{@link #buildHomeGrid} 的 CARD 档），不在 iconBox 上：
     *       iconBox 是 accent 实色（{@code app.iconColor()}），"刻意保留实心"。</li>
     *   <li><b>点击 / 拖拽的分界（风险 5b 修复后）</b>：起拖仍看 {@code ACTIVATION_THRESHOLD_PX}（10px）；
     *       但抬手时若落点仍是按下那一格（{@link #dropIndexAt} 现算），视为<b>点击</b>并补发
     *       {@link #activate}，而不是像旧代码那样"既不换位也不激活"。真换到别的格子才走重排，
     *       且此时 Qz 合成的 CLICK 会被 {@code suppressedClickIndex} 吞掉 ⇒ 拖拽不会误触发打开 App。</li>
     * </ul>
     */
    private SceneNode iconCell(IPhoneApp app, int cellW, int box,
                               java.util.List<SceneNode> cellNodes,
                               java.util.List<String> cellIds,
                               HomeDrag drag) {
        final int index = cellIds.size();
        cellIds.add(app.id());
        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        // 每格钉死等宽（可用宽 ÷ 4，见 buildHomeGrid 的 cellW）：Qz 布局里显式 preferredWidth
        // 是最高优先级（SizingCalculator.computeWidth 首分支，压过 SHRINK/内容回收/min-max 钳制），
        // 一行 4 格恰好铺满网格可用宽，第 4 格不再被 grid.setClipChildren 裁掉；
        // 图标盒与标签由格的 CrossAxisAlign.CENTER 水平居中。
        cell.setPreferredWidth(cellW);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(6);
        cellNodes.add(cell);

        SceneNode iconBox = SceneNode.row();
        iconBox.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        iconBox.setPreferredWidth(box);
        iconBox.setPreferredHeight(box);
        // 圆角：上游 App 图标【不画圆角也不描边】—— 图标就是贴图本身，
        // renderIcon 只有一次贴着图标矩形的 drawTexture（上游
        // api/client/app/IPhoneApp.java:42-47、core/client/PhoneApp.java:86-91、
        // core/client/AppEntry.java:83-89），圆角来自贴图自带的透明角。
        // 我们的图标盒是 accent 实色块（§9.1 总则 3「保留实心」），
        // 故保留玻璃风格的小圆角，但由 box/3 收到 box/8（改动前 box/3）。
        iconBox.setCornerRadius(Math.max(2, box / ICON_BOX_RADIUS_DIVISOR));
        iconBox.setBackgroundColor(app.iconColor());
        // 描边：**去掉**（改动前 setBorderWidth(1) + setBorderColor(0x2EFFFFFF) = 1px 白 18%）。
        // 依据同上：上游图标没有描边。borderWidth 只进 paint
        // （ScenePaintEngine.java:534-543），不参与布局（Qz 布局引擎不读 borderWidth）
        // ⇒ 图标盒几何与拖拽/点击命中一字不变。
        iconBox.setBorderWidth(0);
        iconBox.setMainAxisAlign(MainAxisAlign.CENTER);
        iconBox.setCrossAxisAlign(CrossAxisAlign.CENTER);

        ItemStack item = app.iconItem();
        String tex = app.iconTexture();
        if (tex != null) {
            iconBox.setImageSource(HostImageSource.texture(new net.minecraft.util.ResourceLocation(tex), 128, 128));
        } else if (item != null) {
            iconBox.setImageSource(HostImageSource.itemIcon(item));
        } else {
            SceneNode glyph = new SceneNode();
            glyph.setText(app.iconGlyph());
            glyph.setTextColor(0xFFFFFFFF);
            glyph.setFontSize(fs(box / 2));
            glyph.setHitTestable(false);
            iconBox.appendChild(glyph);
        }

        SceneNode label = new SceneNode();
        label.setText(app.displayName());
        label.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        // 单行硬约束（t6，用户截图二：4 字名在 fs(14) 下超过格宽折成两行，行内容超出
        // rowH 预算 → 各行图标垂直错位）：Qz 4.10.0 SceneNode.setMaxLines/setEllipsis
        // （同款用法 CompanionAppsPage.java:382-383）。省略号生效前提 = 换行宽度 > 0
        // （SceneLineClamp.java:62），下面的 setMaxTextWidth(cellW) 已保证。
        label.setMaxLines(1);
        label.setEllipsis(true);
        // 字号与行高预算同一本账 fs(APP_LABEL_FONT_SIZE_GUI)（改动前预算 fs(13)/实挂 fs(14)）；
        // 再按实测宽度自适应缩小（下限 APP_LABEL_FONT_MIN_GUI），放得下的名字就不出省略号；
        // 仍放不下由上面的单行+省略号兜底。
        label.setFontSize(fs(fitLabelFontSize(app.displayName(), cellW)));
        label.setMaxTextWidth(cellW);
        label.setTextHorizontalAlign(club.heiqi.uilib.ui.scene.node.TextHorizontalAlign.CENTER);
        label.setHitTestable(false);

        cell.appendChild(iconBox);
        cell.appendChild(label);
        runtime.on(cell, SceneEventType.POINTER_DOWN, (event, ctx) -> {
            drag.armed = true;
            drag.dragging = false;
            drag.pressedIndex = index;
            drag.suppressedClickIndex = -1;
            drag.pressX = ctx.getRawPointerX();
            drag.pressY = ctx.getRawPointerY();
        });
        runtime.on(cell, SceneEventType.POINTER_MOVE, (event, ctx) -> {
            // 只在「已按下 + 就是本格 + 还没进入拖拽」时判定起拖；进入拖拽后 MOVE 无需再做任何事
            //（落点统一在 POINTER_UP 现算，见该分支注释——旧代码在每次 MOVE 都跑一遍全格
            // absoluteBox 只为维护一个抬手时可能已经过期的 targetIndex）。
            if (!drag.armed || drag.pressedIndex != index || drag.dragging) return;
            float dx = ctx.getRawPointerX() - drag.pressX;
            float dy = ctx.getRawPointerY() - drag.pressY;
            if (dx * dx + dy * dy < HomeDrag.ACTIVATION_THRESHOLD_PX * HomeDrag.ACTIVATION_THRESHOLD_PX) {
                return;
            }
            drag.dragging = true;
            drag.suppressedClickIndex = index;
            cell.setOpacity(0.55f);
            // 捕获指针：拖出格子后 MOVE/UP 仍派发到本节点（同 Qz SceneDragReorder）。
            ctx.requestPointerCapture();
        });
        runtime.on(cell, SceneEventType.POINTER_UP, (event, ctx) -> {
            if (drag.dragging && drag.pressedIndex == index) {
                cell.setOpacity(1.0f);
                final int from = drag.pressedIndex;
                // 落点以【抬手时指针所在格】现算（与拖拽命中同源判据 dropIndexAt：格盒左闭右开，
                // 几何来自 SceneGeometry.absoluteBox，与 Qz 命中测试 SceneHitTester.java:96-98 同口径），
                // 不再沿用 MOVE 里最后一次算出的值 —— 抬手那一小段位移可能没有对应的 MOVE 事件。
                final int to = dropIndexAt(ctx, cell, cellNodes);
                drag.armed = false;
                drag.dragging = false;
                drag.pressedIndex = -1;
                if (to == from) {
                    // 「同格落定」= 这次手势没有产生任何重排（10px 阈值抖动、拖出去又拖回原格都算）。
                    // 旧代码在这里直接 return（commitHomeDrag 的 from==target 早退），而 Qz 仍会在 UP 后
                    // 合成 CLICK（SceneInputRouter.java:407-431：UP 必合成、没有任何位移阈值），
                    // 那个 CLICK 又被 suppressedClickIndex 吞掉 ⇒ 手抖超过 10px 就彻底点不开 App
                    //（G 报告风险 5b）。这里改为【直接补发一次点击】。
                    // 同时把 suppressedClickIndex 保持在 index：若 Qz 这次确实把 CLICK 派发到本格
                    //（含经子节点冒泡上来），它会被 CLICK 分支吞掉 ⇒ 恰好激活一次；若没派发，该标记
                    // 也会被下一次 POINTER_DOWN 复位（本方法上方的 POINTER_DOWN 处理器），不会误吞后续点击
                    //（任何 CLICK 都由一次 DOWN 触发，而 DOWN 必先经过本格并复位它）。
                    drag.suppressedClickIndex = index;
                    activate(app, event.isShiftDown());
                } else {
                    final java.util.List<String> ids = new java.util.ArrayList<>(cellIds);
                    // 改树（重排+重建网格）延迟到分发结束。
                    postAction(() -> commitHomeDrag(ids, from, to));
                }
            } else {
                drag.armed = false;
            }
        });
        runtime.on(cell, SceneEventType.POINTER_CANCEL, (event, ctx) -> {
            if (drag.pressedIndex == index) cell.setOpacity(1.0f);
            drag.armed = false;
            drag.dragging = false;
            drag.pressedIndex = -1;
        });
        runtime.on(cell, SceneEventType.CLICK, (event, ctx) -> {
            if (drag.suppressedClickIndex == index) {
                drag.suppressedClickIndex = -1;
                return;
            }
            activate(app, event.isShiftDown());
        });
        return cell;
    }

    /**
     * 主屏标签自适应字号：从 {@link #APP_LABEL_FONT_SIZE_GUI} 起步，用 measurer 实测
     * 名字宽度，放不进 {@code cellW} 就逐档缩小（每次 -1，下限 {@link #APP_LABEL_FONT_MIN_GUI}）；
     * 仍放不下由 setMaxLines(1)+setEllipsis(true) 单行截断兜底。返回声明字号
     * （{@link #fs(int)} 会再乘全局字体缩放，与页面其它文字同一口径）。
     */
    private int fitLabelFontSize(String name, int cellW) {
        for (int size = APP_LABEL_FONT_SIZE_GUI; size > APP_LABEL_FONT_MIN_GUI; size--) {
            if (measurer.measureWidth(name, fs(size)) <= cellW) {
                return size;
            }
        }
        return APP_LABEL_FONT_MIN_GUI;
    }

    /**
     * 指针当前压在第几格（扁平下标，行优先）。
     *
     * <p>坐标口径：Qz 的 absoluteBox 相对场景根；而本节点的局部指针坐标 +
     * 本节点的根相对框 = 指针的根相对坐标（同 Qz SceneDragReorder 的换算）。
     * 命不中任何格子（间隙上）取中心最近的格子。</p>
     */
    private static int dropIndexAt(club.heiqi.uilib.ui.scene.input.SceneEventContext ctx,
                                   SceneNode draggedCell,
                                   java.util.List<SceneNode> cellNodes) {
        club.heiqi.uilib.ui.scene.layout.AnchorRect draggedBox =
            club.heiqi.uilib.ui.scene.layout.SceneGeometry.absoluteBox(draggedCell, 0, 0);
        int px = draggedBox.getX() + ctx.getLocalPointerX();
        int py = draggedBox.getY() + ctx.getLocalPointerY();
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < cellNodes.size(); i++) {
            club.heiqi.uilib.ui.scene.layout.AnchorRect box =
                club.heiqi.uilib.ui.scene.layout.SceneGeometry.absoluteBox(cellNodes.get(i), 0, 0);
            long dist = (long) (px - (box.getX() + box.getWidth() / 2)) * (px - (box.getX() + box.getWidth() / 2))
                + (long) (py - (box.getY() + box.getHeight() / 2)) * (py - (box.getY() + box.getHeight() / 2));
            if (px >= box.getX() && px < box.getX() + box.getWidth()
                    && py >= box.getY() && py < box.getY() + box.getHeight()) {
                return i;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** 拖拽落定：可见 App 内插入式重排，隐藏 App 保持原相对顺序缀后，按存档持久化。 */
    private static void commitHomeDrag(java.util.List<String> visibleIds, int from, int to) {
        List<String> seq = new java.util.ArrayList<>(visibleIds);
        if (from < 0 || from >= seq.size()) return;
        int target = Math.max(0, Math.min(to, seq.size() - 1));
        if (from == target) return;
        String moved = seq.remove(from);
        seq.add(target, moved);

        List<String> full = new java.util.ArrayList<>(seq);
        for (IPhoneApp app : PhoneApi.orderedApps()) {
            if (!full.contains(app.id())) full.add(app.id());
        }
        com.november.mcphone.client.enhance.HomeGridStore.saveOrder(full);
        PhoneUi ui = ACTIVE;
        if (ui != null) ui.rebuildPage();
    }


    /** 图标点击：直达型立即执行（传送支持 Shift+点击绑定）；页面型 Shift+点击走 onShiftActivate。 */
    private void activate(IPhoneApp app, boolean shift) {
        post(() -> {
            // 商店模式准入：未购付费内建 App 拦下（附属 App 恒放行，见 StoreClient）。
            if (!com.november.mcphone.client.StoreClient.isUnlocked(app)) {
                toastLocked();
                return;
            }
            if (app.isDirectAction()) {
                app.onActivate(this, shift);
            } else if (shift) {
                app.onShiftActivate(this);
            } else {
                openApp(app.id());
            }
        });
    }

    private void toastLocked() {
        toast(StatCollector.translateToLocal("msg.mcphone.store_locked"));
    }

    // ===================== 公共小工具 =====================

    /** 挂一个小圆钮到指定容器（自绘按钮，字号可控；回调延迟到分发结束执行）。 */
    public SceneNode mountButton(SceneNode parent, String label, Runnable onClick) {
        return com.november.mcphone.api.PhoneWidgets.button(this, parent, label, onClick);
    }

    /** 常规标题文本（不可命中）。 */
    public static SceneNode text(String value, int color, int size) {
        SceneNode n = new SceneNode();
        n.setText(value);
        n.setTextColor(color);
        n.setFontSize(fs(size));
        n.setHitTestable(false);
        return n;
    }

    public static SceneNode title(String value) {
        return text(value, com.november.mcphone.client.enhance.PhoneTheme.text(), 20);
    }

    public static SceneNode muted(String value) {
        return text(value, com.november.mcphone.client.enhance.PhoneTheme.muted(), 13);
    }

    // ===================== 时钟与壁纸 =====================

    /** ClientHooks 客户端 tick 驱动：更新状态栏时钟。 */
    public static void tickClock() {
        String now = TimeUtil.worldClock();
        if (!now.equals(lastClock)) {
            lastClock = now;
            CLOCK.set(now);
        }
    }

    /** 相册/设置里设置壁纸后调用：立即生效。 */
    public static void refreshWallpaper() {
        WALLPAPER.set(loadWallpaper());
    }

    /**
     * 选择一张壁纸（壁纸选择页调用）。
     *
     * <p>上游这一步是 {@code MCphoneNetwork.sendToServer(new SetWallpaperPacket(fileName))}，
     * 服务端写玩家附件后回 {@code SyncWallpaperPacket}；本轮只做本地生效（见交付报告 §8）、
     * 选中项落 {@code config/mcphone/wallpapers/.current}。</p>
     *
     * @param fileName 壁纸目录下的文件名；空串/null = 恢复默认背景
     */
    public static void selectWallpaper(String fileName) {
        com.november.mcphone.client.enhance.WallpaperStore.setSelectedFileName(fileName);
        refreshWallpaper();
    }

    /**
     * 从壁纸目录取当前选中的图；目录里没有（还没选过）时退回旧的单文件
     * {@code mcphone/wallpaper.png}（相册「设为壁纸」仍写那一条路径，不属本轮 inScope）。
     */
    private static SceneImageSource loadWallpaper() {
        com.november.mcphone.client.enhance.WallpaperStore.Entry entry =
            com.november.mcphone.client.enhance.WallpaperStore
                .findEntry(com.november.mcphone.client.enhance.WallpaperStore.selectedFileName());
        BufferedImage img = entry == null ? null : entry.image();
        if (img == null) {
            File f = PhotoStore.wallpaperFile();
            if (!f.isFile()) return null;
            try {
                img = ImageIO.read(f);
            } catch (Exception e) {
                return null;
            }
        }
        if (img == null) return null;
        try {
            BufferedImage cropped = cropToPanelAspect(img);
            return HostImageSource.bufferedImage(cropped,
                com.november.mcphone.client.enhance.WallpaperStore.contentKey(entry, cropped));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 壁纸的 {@code imageKey}：<b>内容寻址</b>的稳定键（同一张裁好的图 ⇒ 同一个 key）。
     *
     * <p><b>为什么不能用 {@code System.nanoTime()}（旧写法）</b>：Qz 的动态位图纹理由
     * {@code MinecraftHostImageRenderer} 按 imageKey 缓存在一个只增不减的表里
     * （{@code Map<String,ResourceLocation> dynamicImageTextures}，put 见
     * {@code MinecraftHostImageRenderer.java:109}）；<b>唯一</b>清理入口是 {@code close()}
     * → {@code clearDynamicImageTextures()}（{@code :115-144}），<b>没有任何按键删除/淘汰的 API</b>。
     * 于是 nanoTime 键 ⇒ 每换一次壁纸都新建一份 GPU 纹理（360×640×4 ≈ 0.9 MB）且在本界面存活期内
     * 不回收（renderer 归 {@code McScreenBridge} 私有所有：建 {@code McScreenBridge.java:64}、
     * 关 {@code :328}）。稳定键 ⇒ 重复设置同一张图（含 6 个预设反复点）直接复用已上传的纹理。</p>
     *
     * <p><b>为什么必须内容寻址、不能只用文件路径</b>：{@code PhotoStore.setWallpaper} 与
     * {@code WallpaperPresets.apply} 都是<b>覆盖写同一个</b> {@code mcphone/wallpaper.png}
     * （{@code PhotoStore.java:60-70}），路径恒定而内容会变；若 key 只含路径，Qz 会一直复用第一张图的
     * 纹理 ⇒ 换壁纸彻底失效。故键 = 裁剪后尺寸 + 像素内容哈希：内容变 ⇒ 键变 ⇒ 重新上传（正确）；
     * 内容同 ⇒ 键同 ⇒ 复用（省显存）。裁剪尺寸进键还覆盖了「窗口/UI 缩放改变面板比例后重设同一张图」
     * 这一种内容会变而文件不变的情况。</p>
     *
     * @param image 已裁到面板比例的位图（即实际上传的那一份）
     * @return 稳定 imageKey
     */
    private static String wallpaperImageKey(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        try {
            int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
            if (pixels != null) {
                return "mcphone:wallpaper/" + w + "x" + h + "/"
                    + Integer.toHexString(java.util.Arrays.hashCode(pixels));
            }
        } catch (Throwable ignored) {
            // 非标准 ColorModel 等异常 ⇒ 退到「尺寸 + 文件长度/mtime」键；仍远好于 nanoTime：
            // 覆盖写同一文件会改 mtime ⇒ 不会错误复用旧图（只是同图重复设置会各自上传一次）。
        }
        File f = PhotoStore.wallpaperFile();
        long stamp = f.isFile() ? (f.length() * 31L + f.lastModified()) : 0L;
        return "mcphone:wallpaper/" + w + "x" + h + "/" + Long.toHexString(stamp);
    }

    /** 中心裁剪到手机面板宽高比（约 0.56）：避免壁纸被拉伸变形（图片源按节点边界拉伸填充）。 */
    private static BufferedImage cropToPanelAspect(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        double target = panelAspect();
        int cw = (int) Math.min(w, Math.round(h * target));
        int ch = (int) Math.min(h, Math.round(w / target));
        int x0 = (w - cw) / 2;
        int y0 = (h - ch) / 2;
        if (cw == w && ch == h) return src;
        return src.getSubimage(x0, y0, cw, ch);
    }

    private static double panelAspect() {
        PhoneUi ui = ACTIVE;
        if (ui != null && ui.panelH > 0) return ui.panelW / (double) ui.panelH;
        return 0.56;
    }

    /** 客户端缓存的传送点列表（服务端 WaypointSync 全量刷新）。 */
    private static volatile java.util.List<ItemPhone.Waypoint> clientWaypoints =
        new java.util.ArrayList<>();

    // ===================== 延迟动作队列 =====================
    // 点击回调在 Qz 输入路由的迭代中执行：直接改树/关屏会让路由器抛
    // ConcurrentModificationException（20.04.28 客户端崩溃根因）。
    // 所有回调里的场景变更与关屏一律 post 到客户端 tick 再执行。

    private static final java.util.Deque<Runnable> PENDING_ACTIONS =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static volatile boolean pendingClose;

    /** 延迟到下一帧渲染开头执行（输入分发期间改树会让 Qz 路由 CME）。 */
    public static void postAction(Runnable action) {
        if (action != null) PENDING_ACTIONS.add(action);
    }

    /** 每帧渲染开头 flush：此时改树完全安全（管线尚未 route）。 */
    public static void flushPendingActions() {
        Runnable r;
        while ((r = PENDING_ACTIONS.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                System.err.println("[mcphone] deferred action failed: " + t);
            }
        }
    }

    /** 关闭手机（延迟到客户端 tick）。仅当当前界面仍是手机时才关，避免误关刚打开的末影箱等容器。 */
    @Override
    public void post(Runnable action) {
        postAction(action);
    }

    public static void flushPendingClose() {
        if (pendingClose) {
            pendingClose = false;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen instanceof PhoneScreen) {
                mc.displayGuiScreen(null);
            }
        }
    }

    /** 服务端同步到达（客户端 tick 主线程调用）：更新缓存并刷新传送页。 */
    public static void onWaypointSync(java.util.List<ItemPhone.Waypoint> list) {
        clientWaypoints = new java.util.ArrayList<>(list);
        postAction(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null && "teleport".equals(ui.currentPageId)) {
                ui.rebuildPage();
            }
        });
    }

    /** 服务端已购 App 同步到达（客户端 tick 主线程调用）：重建当前页刷新锁标/商店。 */
    public static void onStoreSync() {
        postAction(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null) ui.rebuildPage();
        });
    }

    /** 当前客户端已知的传送点列表（传送页渲染用）。 */
    public static java.util.List<ItemPhone.Waypoint> clientWaypoints() {
        return clientWaypoints;
    }

    @Override
    public void dispose() {
        if (ACTIVE == this) ACTIVE = null;
        super.dispose();
    }

    @Override
    public void render(int w, int h, club.heiqi.uilib.ui.render.UiRenderBackend ctx, int absX, int absY) {
        flushPendingActions();
        super.render(w, h, ctx, absX, absY);
        checkScissorLeak();
    }

    /**
     * 渲染后自检：手机帧结束时 scissor 若仍开启，说明某个 App/附属留下了未恢复
     * 的裁剪，会泄漏到整个游戏画面（只剩 scissor 矩形内的一小块）。本库自身
     * ClipStack 恒成对开关、帧末恢复，所以帧末不应残留。检测到即关掉并每 App
     * 告警一次（置 PhoneCanvas.clipped 供 About 页提示），fail-safe 不崩溃。
     */
    private void checkScissorLeak() {
        if (!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) return;
        int enabled = 0;
        // 无法得知调用方堆叠了几层，最多剥 8 层兜底。
        while (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) && enabled < 8) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            enabled++;
        }
        String pageId = currentPageId;
        if (pageId != null && WARNED_PAGES.add(pageId)) {
            System.err.println("[mcphone] scissor leak detected on page '" + pageId
                + "' (disabled " + enabled + " layer(s)); the offending app left GL_SCISSOR_TEST enabled");
        }
        PhoneCanvas.setClipped(true);
    }

    /** 已告警过的页面 id（每页只告警一次，避免刷日志）。 */
    private static final java.util.Set<String> WARNED_PAGES = new java.util.HashSet<>();

    @Override
    protected SceneNode getRoot() {
        return root;
    }
}
