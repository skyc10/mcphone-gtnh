package com.november.mcphone.client.scene;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

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
 *   └ panel (COLUMN，圆角面板，壁纸为背景图)
 *       ├ statusBar  (ROW：时钟 + 设备名)
 *       ├ contentSlot (COLUMN，flexGrow=1，单槽页面挂载点)
 *       └ homeBar    (ROW：主页按钮)
 * </pre>
 */
public class PhoneUi extends AbstractSceneHostWidget {

    /** 当前打开的手机实例（时钟 tick 用）；随 dispose 清空。 */
    public static volatile PhoneUi ACTIVE;

    /** 状态栏时钟（世界时间），ClientHooks 每客户端 tick 驱动。 */
    private static final Signal<String> CLOCK = Signal.create("--:--");
    private static String lastClock = "";

    /** 壁纸图片源（null = 默认深色底）。相册设为壁纸后更新，立即生效。 */
    private static final Signal<SceneImageSource> WALLPAPER = Signal.create(loadWallpaper());

    private static final int COL_BG = 0xF20E1116;
    private static final int COL_BORDER = 0xFF39404B;
    private static final int COL_TEXT = 0xFFE8EDF2;
    private static final int COL_MUTED = 0xFFB8C4D0;
    private static final int COL_STATUS_BG = 0x99000000;
    /** 页面内容底板：半透明深色，保证文字在任何壁纸/世界背景上可读。 */
    private static final int COL_PAGE_BG = 0x900E1116;

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
    private MountHandle pageMount;
    private String currentPageId;
    private int panelW;
    private int panelH;

    public PhoneUi(ItemStack phoneStack) {
        super(new LwjglInputSource(new LwjglStateReader()));
        runtime.__enableMotion();
        this.phone = phoneStack;
        clientWaypoints = new java.util.ArrayList<>(ItemPhone.getWaypoints(phoneStack));
        applyPanelSize();
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

    /** 全局字号：所有手机内文本一律经此换算（设置 App 的字体缩放）。 */
    public static int fs(int size) {
        return Math.max(9, Math.round(size * fontScale));
    }

    /** 字体缩放变化后（设置页滑条）：重开当前页让新字号生效（延迟到分发结束）。 */
    public static void refreshFontScale() {
        fontScale = PhoneCanvas.getFontScale();
        post(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null) ui.rebuildPage();
        });
    }

    /** 界面缩放变化后：重算面板尺寸并重排当前页（延迟到分发结束）。 */
    public static void refreshUiScale() {
        post(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null) {
                ui.uiScalePercent = PhoneCanvas.getUiScalePercent();
                ui.applyPanelSize();
                ui.panel.setPreferredWidth(ui.panelW);
                ui.panel.setPreferredHeight(ui.panelH);
                ui.rebuildPage();
            }
        });
    }

    /** 重建当前页（主页或当前 App），旧 MountHandle 一并回收。 */
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

    public ItemStack phoneStack() {
        return phone;
    }

    // ===================== 骨架 =====================

    private void buildShell() {
        root = SceneNode.column();
        root.setFillParentWidth(true);
        root.setFillParentHeight(true);
        root.setCrossAxisAlign(CrossAxisAlign.CENTER);
        root.setMainAxisAlign(MainAxisAlign.CENTER);

        panel = SceneNode.column();
        panel.setPreferredWidth(panelW);
        panel.setPreferredHeight(panelH);
        panel.setCornerRadius(22);
        panel.setBackgroundColor(COL_BG);
        panel.setBorderWidth(1);
        panel.setBorderColor(COL_BORDER);
        panel.setClipChildren(true);
        // 壁纸作为面板背景图铺满；相册设壁纸后经 bind 即时刷新。
        SceneImageSource wp = WALLPAPER.get();
        if (wp != null) panel.setImageSource(wp);
        runtime.bind(WALLPAPER, panel::setImageSource);
        root.appendChild(panel);

        SceneNode statusBar = SceneNode.row();
        statusBar.setFillParentWidth(true);
        statusBar.setPadding(12, 12, 12, 8);
        statusBar.setGap(8);
        statusBar.setBackgroundColor(COL_STATUS_BG);
        statusBar.setHitTestable(false);
        SceneNode time = new SceneNode();
        time.setText(CLOCK.get());
        time.setTextColor(COL_TEXT);
        time.setFontSize(fs(16));
        time.setHitTestable(false);
        runtime.bindText(time, CLOCK);
        statusBar.appendChild(time);
        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        statusBar.appendChild(spacer);
        String dev = ItemPhone.getDeviceName(phone);
        SceneNode devName = new SceneNode();
        devName.setText(dev == null || dev.isEmpty()
            ? StatCollector.translateToLocal("label.mcphone.default_device") : dev);
        devName.setTextColor(COL_TEXT);
        devName.setFontSize(fs(14));
        devName.setMaxTextWidth(panelW - 90);
        devName.setHitTestable(false);
        statusBar.appendChild(devName);
        panel.appendChild(statusBar);

        contentSlot = SceneNode.column();
        contentSlot.setFillParentWidth(true);
        contentSlot.setFlexGrow(1);
        contentSlot.setClipChildren(true);
        // 半透明深色底板：壁纸隐约可见，文字始终可读（浅色背景问题修复）。
        contentSlot.setBackgroundColor(COL_PAGE_BG);
        panel.appendChild(contentSlot);

        homeBar = SceneNode.row();
        homeBar.setFillParentWidth(true);
        homeBar.setCrossAxisAlign(CrossAxisAlign.CENTER);
        homeBar.setMainAxisAlign(MainAxisAlign.CENTER);
        homeBar.setPadding(6, 6, 6, 6);
        mountButton(homeBar, "⌂", this::backHome);
        panel.appendChild(homeBar);
    }

    // ===================== 导航 =====================

    /** 主屏是否在前（否则有 App 页面打开）。 */
    public boolean isHome() {
        return currentPageId == null;
    }

    /** 打开指定 App 页面（页面型 App）。 */
    public void openApp(String id) {
        IPhoneApp app = PhoneApi.byId(id);
        if (app == null || app.isDirectAction()) return;
        swapPage(app.id(), app.createPage(this));
    }

    /** 返回主屏。 */
    public void backHome() {
        swapPage(null, null);
        buildHomeGrid();
    }

    /** 关闭手机（延迟到输入分发结束，避免 Qz 路由 CME）。 */
    public void closePhone() {
        post(() -> Minecraft.getMinecraft().displayGuiScreen(null));
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
        homeBar.setOpacity(currentPageId == null ? 0.35f : 1.0f);
    }

    private void buildHomeGrid() {
        List<IPhoneApp> apps = PhoneApi.visibleApps();
        SceneNode grid = SceneNode.column();
        grid.setFillParentWidth(true);
        grid.setPadding(16);
        grid.setGap(16);
        grid.setScrollable(true);
        grid.setClipChildren(true);

        int perRow = 3;
        int cellW = Math.max(80, (panelW - 32 - (perRow - 1) * 18) / perRow);
        int box = Math.min(84, cellW - 8);
        for (int i = 0; i < apps.size(); i += perRow) {
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setMainAxisAlign(MainAxisAlign.CENTER);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(18);
            // 固定行高先验：图标盒 + 间距 + 标签行高，避免布局求解器把单元格拉伸。
            row.setPreferredHeight(box + 30);
            for (int j = 0; j < perRow && i + j < apps.size(); j++) {
                row.appendChild(iconCell(apps.get(i + j), cellW, box));
            }
            grid.appendChild(row);
        }
        pageMount = runtime.mount(contentSlot, () -> grid);
    }

    private SceneNode iconCell(IPhoneApp app, int cellW, int box) {
        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(6);

        SceneNode iconBox = SceneNode.row();
        iconBox.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        iconBox.setPreferredWidth(box);
        iconBox.setPreferredHeight(box);
        iconBox.setCornerRadius(box / 3);
        iconBox.setBackgroundColor(app.iconColor());
        iconBox.setBorderWidth(1);
        iconBox.setBorderColor(0x2EFFFFFF);
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
        label.setTextColor(COL_TEXT);
        label.setFontSize(fs(14));
        label.setMaxTextWidth(cellW);
        label.setHitTestable(false);

        cell.appendChild(iconBox);
        cell.appendChild(label);
        runtime.on(cell, SceneEventType.CLICK, (event, ctx) -> activate(app, event.isShiftDown()));
        return cell;
    }

    /** 图标点击：直达型立即执行（传送支持 Shift+点击绑定）；页面型 Shift+点击走 onShiftActivate。 */
    private void activate(IPhoneApp app, boolean shift) {
        post(() -> {
            if (app.isDirectAction()) {
                app.onActivate(this, shift);
            } else if (shift) {
                app.onShiftActivate(this);
            } else {
                openApp(app.id());
            }
        });
    }

    // ===================== 公共小工具 =====================

    /** 挂一个小圆钮到指定容器（按钮根按内容宽排布；回调延迟到分发结束执行）。 */
    public SceneNode mountButton(SceneNode parent, String label, Runnable onClick) {
        SceneButton.Props props = new SceneButton.Props(
            Signal.create(label), Signal.create(Boolean.TRUE),
            () -> post(onClick));
        SceneNode btn = runtime.mount(parent, SceneButton.create(runtime, props)).getRoot();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        return btn;
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
        return text(value, COL_TEXT, 20);
    }

    public static SceneNode muted(String value) {
        return text(value, COL_MUTED, 13);
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

    private static SceneImageSource loadWallpaper() {
        File f = PhotoStore.wallpaperFile();
        if (!f.isFile()) return null;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return null;
            return HostImageSource.bufferedImage(cropToPanelAspect(img), "mcphone:wallpaper");
        } catch (Exception e) {
            return null;
        }
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

    /** 延迟到本帧输入分发结束后执行（客户端 tick 中 flush）。 */
    public static void post(Runnable action) {
        if (action != null) PENDING_ACTIONS.add(action);
    }

    /** ClientHooks 客户端 tick 调用：执行排队动作。 */
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

    /** 服务端同步到达（客户端 tick 主线程调用）：更新缓存并刷新传送页。 */
    public static void onWaypointSync(java.util.List<ItemPhone.Waypoint> list) {
        clientWaypoints = new java.util.ArrayList<>(list);
        post(() -> {
            PhoneUi ui = ACTIVE;
            if (ui != null && "teleport".equals(ui.currentPageId)) {
                ui.rebuildPage();
            }
        });
    }

    /** 当前客户端已知的传送点列表（传送页渲染用）。 */
    public static java.util.List<ItemPhone.Waypoint> waypoints() {
        return clientWaypoints;
    }

    @Override
    public void dispose() {
        if (ACTIVE == this) ACTIVE = null;
        super.dispose();
    }

    @Override
    protected SceneNode getRoot() {
        return root;
    }
}
