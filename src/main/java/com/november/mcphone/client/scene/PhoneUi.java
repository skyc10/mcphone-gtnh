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
public class PhoneUi extends AbstractSceneHostWidget implements com.november.mcphone.api.PhoneContext {

    /** 当前打开的手机实例（时钟 tick 用）；随 dispose 清空。 */
    public static volatile PhoneUi ACTIVE;

    /** 状态栏时钟（世界时间），ClientHooks 每客户端 tick 驱动。 */
    private static final Signal<String> CLOCK = Signal.create("--:--");
    private static String lastClock = "";

    /** 设备名（状态栏绑定；设置页保存后即时更新）。 */
    private static final Signal<String> DEVICE_NAME = Signal.create("");

    /** 壁纸图片源（null = 默认深色底）。相册设为壁纸后更新，立即生效。 */
    private static final Signal<SceneImageSource> WALLPAPER = Signal.create(loadWallpaper());

    private static final int COL_BG = 0xF20E1116;
    private static final int COL_BORDER = 0xFF39404B;
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
        time.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        time.setFontSize(fs(16));
        time.setHitTestable(false);
        runtime.bindText(time, CLOCK);
        statusBar.appendChild(time);
        // 高度先验（Qz 布局求解器要求 grow 的兄弟可先验，否则 contentSlot 高度解耦失败=主屏空白）。
        statusBar.setPreferredHeight(measurer.lineHeight(fs(16)) + 20);
        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        statusBar.appendChild(spacer);
        String dev = ItemPhone.getDeviceName(phone);
        DEVICE_NAME.set(dev == null || dev.isEmpty()
            ? StatCollector.translateToLocal("label.mcphone.default_device") : dev);
        SceneNode devName = new SceneNode();
        devName.setText(DEVICE_NAME.get());
        devName.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        devName.setFontSize(fs(14));
        devName.setMaxTextWidth(panelW - 90);
        devName.setHitTestable(false);
        runtime.bindText(devName, DEVICE_NAME);
        statusBar.appendChild(devName);
        panel.appendChild(statusBar);

        contentSlot = SceneNode.column();
        contentSlot.setFillParentWidth(true);
        // 高度用显式先验而非 flexGrow：grow 求解器在内容型兄弟旁会早退（间歇性主屏空白/不可滚动的根因）。
        contentSlot.setPreferredHeight(contentHeight());
        contentSlot.setClipChildren(true);
        // 半透明深色底板：壁纸隐约可见，文字始终可读（浅色背景问题修复）。
        contentSlot.setBackgroundColor(COL_PAGE_BG);
        panel.appendChild(contentSlot);

        homeBar = SceneNode.row();
        homeBar.setFillParentWidth(true);
        homeBar.setCrossAxisAlign(CrossAxisAlign.CENTER);
        homeBar.setMainAxisAlign(MainAxisAlign.CENTER);
        homeBar.setPadding(6, 6, 6, 6);
        // 高度先验：按钮行高 + 上下 padding + 按钮内边距（与 playground navBar 同口径）。
        homeBar.setPreferredHeight(measurer.lineHeight(fs(16)) + 12
            + 2 * club.heiqi.uilib.ui.scene.paint.SceneChromeTokens.PAD_LG);
        mountButton(homeBar, "⌂", this::backHome);
        panel.appendChild(homeBar);
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
        homeBar.setOpacity(currentPageId == null ? 0.35f : 1.0f);
    }

    private void buildHomeGrid() {
        List<IPhoneApp> apps = orderedForHome();
        SceneNode grid = SceneNode.column();
        grid.setFillParentWidth(true);
        grid.setPadding(16);
        grid.setGap(16);
        grid.setScrollable(true);
        grid.setClipChildren(true);
        club.heiqi.uilib.ui.scene.runtime.SceneScrolls.attach(runtime, grid);

        // 拖拽排序共享状态：本轮网格的单元格序（扁平，行优先）与手势状态。
        final java.util.List<SceneNode> cellNodes = new java.util.ArrayList<>();
        final java.util.List<String> cellIds = new java.util.ArrayList<>();
        final HomeDrag drag = new HomeDrag();

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
                row.appendChild(iconCell(apps.get(i + j), cellW, box, cellNodes, cellIds, drag));
            }
            grid.appendChild(row);
        }
        pageMount = runtime.mount(contentSlot, () -> grid);
    }

    /**
     * 主屏展示顺序：按存档隔离的拖拽顺序（HomeGridStore），文件缺失时回落全局顺序表。
     * 商店模式开启时，未购付费 App 不上主屏（购买入口在应用商店 App）。
     */
    private List<IPhoneApp> orderedForHome() {
        java.util.List<String> known = new java.util.ArrayList<>();
        for (IPhoneApp app : PhoneApi.orderedVisibleApps()) {
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
        int targetIndex = -1;
        float pressX;
        float pressY;
        /** 拖拽结束后要吞掉的 CLICK 所在格子（避免拖完顺手打开了 App）。 */
        int suppressedClickIndex = -1;
    }

    private SceneNode iconCell(IPhoneApp app, int cellW, int box,
                               java.util.List<SceneNode> cellNodes,
                               java.util.List<String> cellIds,
                               HomeDrag drag) {
        final int index = cellIds.size();
        cellIds.add(app.id());
        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(6);
        cellNodes.add(cell);

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
        label.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.text());
        label.setFontSize(fs(14));
        label.setMaxTextWidth(cellW);
        label.setTextHorizontalAlign(club.heiqi.uilib.ui.scene.node.TextHorizontalAlign.CENTER);
        label.setHitTestable(false);

        cell.appendChild(iconBox);
        cell.appendChild(label);
        runtime.on(cell, SceneEventType.POINTER_DOWN, (event, ctx) -> {
            drag.armed = true;
            drag.dragging = false;
            drag.pressedIndex = index;
            drag.targetIndex = index;
            drag.suppressedClickIndex = -1;
            drag.pressX = ctx.getRawPointerX();
            drag.pressY = ctx.getRawPointerY();
        });
        runtime.on(cell, SceneEventType.POINTER_MOVE, (event, ctx) -> {
            if (!drag.armed || drag.pressedIndex != index) return;
            if (!drag.dragging) {
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
            }
            drag.targetIndex = dropIndexAt(ctx, cell, cellNodes);
        });
        runtime.on(cell, SceneEventType.POINTER_UP, (event, ctx) -> {
            if (drag.dragging && drag.pressedIndex == index) {
                cell.setOpacity(1.0f);
                final int from = drag.pressedIndex;
                final int to = drag.targetIndex;
                final java.util.List<String> ids = new java.util.ArrayList<>(cellIds);
                drag.armed = false;
                drag.dragging = false;
                drag.pressedIndex = -1;
                // 改树（重排+重建网格）延迟到分发结束。
                postAction(() -> commitHomeDrag(ids, from, to));
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

    private static SceneImageSource loadWallpaper() {
        File f = PhotoStore.wallpaperFile();
        if (!f.isFile()) return null;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return null;
            return HostImageSource.bufferedImage(cropToPanelAspect(img),
                "mcphone:wallpaper#" + System.nanoTime());
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
    }

    @Override
    protected SceneNode getRoot() {
        return root;
    }
}
