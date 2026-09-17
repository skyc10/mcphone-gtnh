package com.november.mcphone.client.apps;

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
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.control.SceneTextArea;
import club.heiqi.uilib.ui.scene.control.SceneToggle;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.control.SceneInputType;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.MountHandle;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.api.PhoneWidgets;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.AppHotkey;
import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.enhance.PhoneTheme;
import com.november.mcphone.client.TimeUtil;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.core.ItemPhone;
import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NotesClientCache;
import com.november.mcphone.net.NetworkHandler;

/**
 * 页面型内建 App 的场景页实现（Qz-UILib）。
 *
 * <p>统一约定：页面一次性建树；两态页面（列表/编辑）用内部单槽 {@link PageSlot}
 * 切换，切槽先 dispose 旧 MountHandle 再挂新树。</p>
 */
public final class ScenePages {

    private static final int COL_BORDER = 0x55FFFFFF;

    /**
     * 设置 App 当前显示的是否是「更换壁纸」子页。
     *
     * <p>上游把这一页做成独立页面（{@code PhoneScreen.WALLPAPER_PICKER}），由设置列表的
     * 「更换壁纸」那一行 {@code navigateTo} 进入、点一张壁纸后又跳回设置列表
     * （{@code WallpaperPicker.mouseClicked} 返回 true ⇒ 界面返回设置列表）。GTNH 侧设置
     * 是一个长滚动页、没有子页导航，故用这个标志把两态在 {@link #settingsPage} 内部切换：
     * 进入/返回都走 {@code PhoneUi.rebuildPage()}（与便签/相册的 {@code PageSlot} 两态同一套
     * 「重建当前页」契约，副作用由 Qz 的挂载生命周期回收）。</p>
     */
    private static boolean settingsWallpaperView;

    /**
     * 页面卡片/信息行底衬（玻璃令牌；改动前是 {@code COL_PANEL = 0x33FFFFFF} 常量）。
     *
     * <p>为什么不再用常量：底色 alpha 与材质档成对（设计文档 §9.2/§9.3），要跟随用户
     * 档位与「玻璃开关」变化，只能运行期取。取值一律经
     * {@link com.november.mcphone.client.enhance.PhoneGlass#surface}（本包不直接引用任何
     * Qz 玻璃类型），关玻璃/Qz 类缺失时自动回中性非玻璃色。</p>
     */
    private static int cardSurface() {
        return com.november.mcphone.client.enhance.PhoneGlass
            .surface(com.november.mcphone.client.enhance.PhoneGlass.Role.CARD);
    }

    /** 卡片玻璃态圆角（12；旧值 8px 挂不住液态缘带，见 PhoneGlass.RADIUS_MIN）。 */
    private static int cardRadius() {
        return com.november.mcphone.client.enhance.PhoneGlass.cardRadius();
    }

    /**
     * 卡片/信息行统一表面：玻璃底色 + 液态倒角 + 建树期挂 backdrop（旧 0x33FFFFFF + 8px 淘汰）。
     *
     * <p>只改 PAINT 级属性（底色/圆角/backdrop），不碰布局 ⇒ 不影响任何命中判据。</p>
     */
    private static void applyCardSurface(SceneNode node) {
        node.setCornerRadius(cardRadius());
        node.setBackgroundColor(cardSurface());
        com.november.mcphone.client.enhance.PhoneGlass.apply(
            node, com.november.mcphone.client.enhance.PhoneGlass.Role.CARD);
    }

    private ScenePages() {}

    /** 页面内部单槽：一次只挂一棵子树。 */
    private static final class PageSlot {

        final SceneRuntime runtime;
        final SceneNode slot;
        MountHandle handle;

        PageSlot(SceneRuntime runtime, int height) {
            this.runtime = runtime;
            this.slot = SceneNode.column();
            this.slot.setFillParentWidth(true);
            // 显式高度先验：grow 求解器在部分容器旁会早退，导致滚动/内容高度塌陷。
            this.slot.setPreferredHeight(height);
            this.slot.setClipChildren(true);
        }

        void show(SceneNode page) {
            if (handle != null) {
                handle.dispose();
                handle = null;
            }
            if (page != null) {
                handle = runtime.mount(slot, () -> page);
            }
        }
    }

    // ===================== 公共构件 =====================

    private static SceneNode scrollColumn(PhoneUi ui) {
        SceneNode col = SceneNode.column();
        col.setFillParentWidth(true);
        // 填满父级（PageSlot/contentSlot 均为显式固定高），滚动视口因此有确定高度。
        col.setFillParentHeight(true);
        col.setPadding(12);
        col.setGap(10);
        col.setScrollable(true);
        col.setClipChildren(true);
        // 必须显式挂滚轮处理器：setScrollable 只声明可滚动，滚轮事件由 attach 接线。
        club.heiqi.uilib.ui.scene.runtime.SceneScrolls.attach(ui.runtime(), col);
        return col;
    }

    private static SceneNode rowOf(SceneNode... children) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(8);
        for (SceneNode c : children) row.appendChild(c);
        return row;
    }

    private static SceneNode mountButton(PhoneUi ui, SceneNode parent, String label, Runnable onClick) {
        return ui.mountButton(parent, label, onClick);
    }

    private static SceneNode mountPrimaryButton(PhoneUi ui, SceneNode parent, String label, Runnable onClick) {
        // 与其它按钮统一走自绘控件（旧实现用 Qz SceneButton，字号固定 16 不随设置缩放）。
        return PhoneWidgets.primaryButton(ui, parent, label, onClick);
    }

    private static SceneNode spacer() {
        SceneNode s = SceneNode.column();
        s.setFlexGrow(1);
        s.setHitTestable(false);
        return s;
    }

    // ===================== 时钟 =====================

    public static SceneNode clockPage(PhoneUi ui) {
        SceneNode page = scrollColumn(ui);
        page.setCrossAxisAlign(CrossAxisAlign.CENTER);
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.clock")));

        // 时段问候（常驻一行；打开手机时的一次性 Toast 问候由 GreetingToast 负责）。
        page.appendChild(PhoneUi.muted(com.november.mcphone.client.enhance.GreetingToast.bandText()));

        SceneNode big = new SceneNode();
        big.setText(PhoneUi.clockSignal().get());
        big.setTextColor(PhoneTheme.text());
        big.setFontSize(PhoneUi.fs(56));
        big.setHitTestable(false);
        ui.runtime().bindText(big, PhoneUi.clockSignal());
        page.appendChild(big);

        page.appendChild(PhoneUi.muted(
            StatCollector.translateToLocalFormatted("msg.mcphone.world_day", TimeUtil.worldDay())));

        // 游玩时长（服务端权威计时，PlayTimeSync 同步；未同步显示"同步中"）。
        page.appendChild(infoRow(
            StatCollector.translateToLocal("msg.mcphone.playtime_session"),
            com.november.mcphone.client.enhance.PlayTimeClient.formatSession()));
        page.appendChild(infoRow(
            StatCollector.translateToLocal("msg.mcphone.playtime_total"),
            com.november.mcphone.client.enhance.PlayTimeClient.formatTotal()));
        return page;
    }

    // ===================== 天气 =====================

    public static SceneNode weatherPage(PhoneUi ui) {
        SceneNode page = scrollColumn(ui);
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.weather")));

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null && mc.thePlayer != null) {
            int x = (int) Math.floor(mc.thePlayer.posX);
            int z = (int) Math.floor(mc.thePlayer.posZ);
            String biome = mc.theWorld.getBiomeGenForCoords(x, z).biomeName;
            boolean rain = mc.theWorld.isRaining();
            boolean thunder = mc.theWorld.isThundering();
            boolean day = mc.theWorld.isDaytime();

            // 按生物群系判定当地天气（雨/雪/无；无天空维度直接无）+ 建议文案。
            com.november.mcphone.client.enhance.WeatherAdvisor.Kind kind =
                com.november.mcphone.client.enhance.WeatherAdvisor.classify(mc.theWorld, x, z);

            page.appendChild(infoRow(StatCollector.translateToLocal("label.mcphone.biome"), biome));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.weather"),
                com.november.mcphone.client.enhance.WeatherAdvisor.name(kind)));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.rain"),
                StatCollector.translateToLocal(rain ? "state.mcphone.yes" : "state.mcphone.no")));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.thunder"),
                StatCollector.translateToLocal(thunder ? "state.mcphone.yes" : "state.mcphone.no")));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.daylight"),
                StatCollector.translateToLocal(day ? "state.mcphone.day" : "state.mcphone.night")));

            page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.advice")));
            page.appendChild(wrappedMuted(
                ui, com.november.mcphone.client.enhance.WeatherAdvisor.advice(kind, !day)));
        } else {
            page.appendChild(PhoneUi.muted("§7--"));
        }
        return page;
    }

    /**
     * 可换行的次要说明文字（{@code setMaxTextWidth} 触发 Qz 按宽拆行）。
     *
     * <p>宽 = {@code panelWidth() - 48}：滚动列自身左右各 12px padding
     * （见 {@link #scrollColumn(PhoneUi)}），再留 24px 安全余量，保证文本盒严格落在容器内。
     * Qz 的按宽拆行在布局期算一次并缓存于节点（{@code TextLinePlan}），绘制期复用，
     * 无每帧测量/分配。</p>
     */
    private static SceneNode wrappedMuted(PhoneUi ui, String value) {
        SceneNode n = PhoneUi.muted(value);
        n.setMaxTextWidth(ui.panelWidth() - 48);
        return n;
    }

    private static SceneNode infoRow(String key, String value) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(8);
        SceneNode k = new SceneNode();
        k.setText(key);
        k.setTextColor(PhoneTheme.muted());
        k.setFontSize(PhoneUi.fs(16));
        k.setHitTestable(false);
        SceneNode v = new SceneNode();
        v.setText(value);
        v.setTextColor(PhoneTheme.text());
        v.setFontSize(PhoneUi.fs(16));
        v.setHitTestable(false);
        row.appendChild(k);
        row.appendChild(spacer());
        row.appendChild(v);
        return row;
    }

    // ===================== 便签（数据源 = 服务端持久化 + 同步缓存，见 feature/notes） =====================

    public static SceneNode notesPage(PhoneUi ui) {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setPreferredHeight(ui.contentHeight() - 20);
        page.setGap(8);
        page.setPadding(10);

        PageSlot slot = new PageSlot(ui.runtime(), ui.contentHeight() - 20);
        page.appendChild(slot.slot);
        showNotesList(ui, slot);
        return page;
    }

    private static void showNotesList(PhoneUi ui, PageSlot slot) {
        SceneNode list = scrollColumn(ui);
        list.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.notes")));
        mountPrimaryButton(ui, list, StatCollector.translateToLocal("btn.mcphone.new_note"),
            () -> showNotesEditor(ui, slot, new Note()));

        // 服务端全量同步未到达前显示加载提示，避免被误读为"没有便签"。
        if (!NotesClientCache.isReceived()) {
            list.appendChild(PhoneUi.muted(StatCollector
                .translateToLocal("label.mcphone.notes_syncing")));
        }
        for (Note n : NotesClientCache.list()) {
            Note note = n;
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(6);
            row.setPadding(6, 6, 6, 6);
            applyCardSurface(row);
            SceneNode t = new SceneNode();
            t.setText(n.title.isEmpty() ? "(...)" : n.title);
            t.setTextColor(PhoneTheme.text());
            t.setFontSize(PhoneUi.fs(16));
            t.setMaxTextWidth(ui.panelWidth() - 60);
            t.setHitTestable(false);
            row.appendChild(t);
            row.appendChild(spacer());
            SceneNode arrow = new SceneNode();
            arrow.setText("›");
            arrow.setTextColor(PhoneTheme.muted());
            arrow.setFontSize(PhoneUi.fs(16));
            arrow.setHitTestable(false);
            row.appendChild(arrow);
            ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> showNotesEditor(ui, slot, note)));
            list.appendChild(row);
        }
        slot.show(list);
    }

    private static void showNotesEditor(PhoneUi ui, PageSlot slot, Note note) {
        // 受控输入：onChange 必须把值写回 Signal（控件不自己改 value），保存时从 Signal 读。
        Signal<String> titleValue = Signal.create(note.title == null ? "" : note.title);
        Signal<String> bodyValue = Signal.create(note.body == null ? "" : note.body);

        SceneNode editor = SceneNode.column();
        editor.setFillParentWidth(true);
        editor.setPreferredHeight(ui.contentHeight() - 20);
        editor.setGap(8);
        editor.setPadding(10);

        mountButton(ui, editor, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showNotesList(ui, slot));

        SceneNode titleInput = ui.runtime()
            .mount(editor, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                titleValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.note_title"), 32,
                SceneInputType.TEXT, titleValue::set)))
            .getRoot();
        titleInput.setFillParentWidth(true);
        titleInput.setFontSize(PhoneUi.fs(16));

        club.heiqi.uilib.ui.scene.control.SceneTextAreaPrimitive.Result taRes =
            club.heiqi.uilib.ui.scene.control.SceneTextAreaPrimitive.create(ui.runtime(),
                new club.heiqi.uilib.ui.scene.control.SceneTextAreaPrimitive.Props(
                    bodyValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                    StatCollector.translateToLocal("label.mcphone.note_body"), Note.MAX_BODY,
                    0xFF6FB2E8, 0xFFE8EDF2, 0xFF8B98A8, 0xFF666666, bodyValue::set));
        SceneNode area = taRes.root();
        area.setFillParentWidth(true);
        area.setFontSize(PhoneUi.fs(18));
        area.setPadding(8, 8, 8, 8);
        area.setBorderWidth(1);
        area.setBorderColor(COL_BORDER);
        area.setCornerRadius(8);
        // 【刻意保留实心，不上玻璃】长文本编辑区（设计文档 §9.3）：理由见下方 viewport 注释。
        area.setBackgroundColor(0xFF1A2028);
        taRes.viewport().setPreferredHeight(260);
        // 【刻意保留实心】TextArea 视口：①Qz 的字形绘制固定 16px（踩坑 #5），玻璃折射缘带会与
        // 文本边缘互相干扰、可读性下降；②长文本可读性优先于观感 ⇒ 保留实心底衬，不挂 backdrop。
        taRes.viewport().setBackgroundColor(0xFF101418);
        // 点击文本框任意位置即可编辑（不用精确点到文字上）；打开时自动聚焦。
        ui.runtime().on(area, club.heiqi.uilib.ui.scene.input.SceneEventType.POINTER_DOWN,
            (e, ctx) -> ui.runtime().requestFocus(taRes.content()));
        ui.runtime().requestFocus(taRes.content());
        editor.appendChild(area);
        // 不做 flexGrow：TextArea 视口高度由 Props.viewportHeight 决定，无界高度会破坏 caret/点击命中。

        SceneNode actions = SceneNode.row();
        actions.setFillParentWidth(true);
        actions.setGap(8);
        mountPrimaryButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.save"), () -> {
            // 保存走服务端（随存档持久化）；服务端回推 NoteSync 后列表自动刷新。
            NetworkHandler.sendToServer(new NetworkHandler.NoteSave(
                NetworkHandler.NoteSave.ACTION_SAVE, note.id, titleValue.get(), bodyValue.get()));
            ui.toast(StatCollector.translateToLocal("msg.mcphone.saved"));
            showNotesList(ui, slot);
        });
        mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.delete"), () -> {
            NetworkHandler.sendToServer(new NetworkHandler.NoteSave(
                NetworkHandler.NoteSave.ACTION_DELETE, note.id, "", ""));
            showNotesList(ui, slot);
        });
        mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.print_book"), () -> {
            if (note.isNew()) {
                ui.toast(StatCollector.translateToLocal("msg.mcphone.note_save_first"));
                return;
            }
            NetworkHandler.sendToServer(new NetworkHandler.NotePrint(note.id));
        });
        editor.appendChild(actions);
        slot.show(editor);
    }

    // ===================== 传送（多传送点） =====================

    public static SceneNode teleportPage(PhoneUi ui) {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setPreferredHeight(ui.contentHeight() - 20);
        page.setPadding(10);
        page.setGap(8);

        PageSlot slot = new PageSlot(ui.runtime(), ui.contentHeight() - 20);
        page.appendChild(slot.slot);
        showWaypointList(ui, slot);
        return page;
    }

    private static void showWaypointList(PhoneUi ui, PageSlot slot) {
        SceneNode list = scrollColumn(ui);
        list.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.teleport")));
        mountPrimaryButton(ui, list, StatCollector.translateToLocal("btn.mcphone.bind_here"),
            () -> NetworkHandler.sendToServer(new NetworkHandler.Teleport(1, -1, "")));

        List<ItemPhone.Waypoint> wps = ui.waypoints();
        if (wps.isEmpty()) {
            list.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.tp_empty")));
        }
        for (int i = 0; i < wps.size(); i++) {
            final int idx = i;
            ItemPhone.Waypoint w = wps.get(i);

            SceneNode card = SceneNode.column();
            card.setFillParentWidth(true);
            card.setGap(4);
            card.setPadding(8, 8, 8, 8);
            applyCardSurface(card);

            SceneNode nameRow = SceneNode.row();
            nameRow.setFillParentWidth(true);
            nameRow.setCrossAxisAlign(CrossAxisAlign.CENTER);
            nameRow.setGap(6);
            SceneNode name = new SceneNode();
            name.setText(w.name);
            name.setTextColor(PhoneTheme.text());
            name.setFontSize(PhoneUi.fs(16));
            name.setHitTestable(false);
            nameRow.appendChild(name);
            nameRow.appendChild(spacer());
            SceneNode dim = new SceneNode();
            dim.setText("D" + w.dim);
            dim.setTextColor(PhoneTheme.muted());
            dim.setFontSize(PhoneUi.fs(12));
            dim.setHitTestable(false);
            nameRow.appendChild(dim);
            card.appendChild(nameRow);

            SceneNode coords = new SceneNode();
            coords.setText(String.format("%.0f, %.0f, %.0f", w.x, w.y, w.z));
            coords.setTextColor(PhoneTheme.muted());
            coords.setFontSize(PhoneUi.fs(12));
            coords.setHitTestable(false);
            card.appendChild(coords);

            SceneNode actions = SceneNode.row();
            actions.setGap(6);
            mountPrimaryButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.tp_go"),
                () -> {
                    NetworkHandler.sendToServer(new NetworkHandler.Teleport(0, idx, ""));
                    ui.closePhone();
                });
            mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.rename"),
                () -> showWaypointRename(ui, slot, idx, w.name));
            mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.delete"),
                () -> NetworkHandler.sendToServer(new NetworkHandler.Teleport(3, idx, "")));
            card.appendChild(actions);

            list.appendChild(card);
        }
        slot.show(list);
    }

    private static void showWaypointRename(PhoneUi ui, PageSlot slot, int index, String oldName) {
        // 受控输入：onChange 写回 Signal，确认时读 Signal。
        Signal<String> nameValue = Signal.create(oldName == null ? "" : oldName);

        SceneNode editor = SceneNode.column();
        editor.setFillParentWidth(true);
        editor.setPreferredHeight(ui.contentHeight() - 20);
        editor.setGap(8);
        editor.setPadding(10);

        mountButton(ui, editor, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showWaypointList(ui, slot));
        editor.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.wp_name")));

        SceneNode input = ui.runtime()
            .mount(editor, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                nameValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.wp_name"), 24,
                SceneInputType.TEXT, nameValue::set)))
            .getRoot();
        input.setFillParentWidth(true);
        input.setFontSize(PhoneUi.fs(16));

        mountPrimaryButton(ui, editor, StatCollector.translateToLocal("btn.mcphone.save"), () -> {
            NetworkHandler.sendToServer(new NetworkHandler.Teleport(2, index, nameValue.get()));
            showWaypointList(ui, slot);
        });
        slot.show(editor);
    }

    // ===================== 相册 =====================

    public static SceneNode galleryPage(PhoneUi ui) {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setPreferredHeight(ui.contentHeight() - 20);
        page.setPadding(10);
        page.setGap(8);

        PageSlot slot = new PageSlot(ui.runtime(), ui.contentHeight() - 20);
        page.appendChild(slot.slot);
        showGalleryGrid(ui, slot);
        return page;
    }

    private static void showGalleryGrid(PhoneUi ui, PageSlot slot) {
        SceneNode grid = scrollColumn(ui);
        grid.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.gallery")));

        List<File> photos = PhotoStore.listPhotos();
        if (photos.isEmpty()) {
            grid.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.gallery_empty")));
        }
        int thumbs = 2;
        int cellW = (ui.panelWidth() - 24 - (thumbs - 1) * 10) / thumbs;
        int cellH = cellW * 9 / 16;
        for (int i = 0; i < photos.size(); i += thumbs) {
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setGap(10);
            for (int j = 0; j < thumbs && i + j < photos.size(); j++) {
                row.appendChild(thumbNode(ui, slot, photos.get(i + j), cellW, cellH));
            }
            grid.appendChild(row);
        }
        slot.show(grid);
    }

    private static SceneNode thumbNode(PhoneUi ui, PageSlot slot, File photo, int w, int h) {
        SceneNode node = SceneNode.row();
        node.setPreferredWidth(w);
        node.setPreferredHeight(h);
        node.setCornerRadius(8);
        // 【刻意保留实心】缩略图底衬（设计文档 §9.4 图片缩略图底衬）：图源不透明时它本就被
        // 图片完全覆盖（IMAGE 命令晚于 BACKGROUND，ScenePaintEngine.java:417-470），图源缺失
        // 时它承担"中性底"职责 ⇒ 保留 0xFF101418，不上玻璃（否则缺图时会透出世界背景干扰识别）。
        node.setBackgroundColor(0xFF101418);
        node.setBorderWidth(1);
        node.setBorderColor(COL_BORDER);
        node.setClipChildren(true);
        try {
            BufferedImage img = ImageIO.read(photo);
            if (img != null) {
                node.setImageSource(HostImageSource.bufferedImage(img, "mcphone:" + photo.getName()));
            }
        } catch (Exception ignored) {}
        ui.runtime().on(node, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> showGalleryViewer(ui, slot, photo)));
        return node;
    }

    private static void showGalleryViewer(PhoneUi ui, PageSlot slot, File photo) {
        SceneNode view = SceneNode.column();
        view.setFillParentWidth(true);
        view.setPreferredHeight(ui.contentHeight() - 20);
        view.setGap(8);
        view.setPadding(10);

        SceneNode image = SceneNode.row();
        // 显式尺寸：查看器图片节点不参与 flexGrow（无界高度会让图片渲染区域为 0，表现为"不显示内容"）。
        image.setPreferredWidth(ui.panelWidth() - 24);
        image.setPreferredHeight((int) ((ui.panelWidth() - 24) * 0.75));
        image.setCornerRadius(10);
        // 【刻意保留实心】图片查看底衬（§9.4）：图片需要中性背景，且图源铺满时本就被覆盖。
        image.setBackgroundColor(0xFF000000);
        image.setClipChildren(true);
        image.setMainAxisAlign(MainAxisAlign.CENTER);
        image.setCrossAxisAlign(CrossAxisAlign.CENTER);
        try {
            BufferedImage img = ImageIO.read(photo);
            if (img != null) {
                image.setImageSource(HostImageSource.bufferedImage(img, "mcphone:" + photo.getName()));
            }
        } catch (Exception ignored) {}
        view.appendChild(image);

        SceneNode actions = SceneNode.row();
        actions.setFillParentWidth(true);
        actions.setGap(8);
        mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showGalleryGrid(ui, slot));
        mountPrimaryButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.set_wallpaper"),
            () -> {
                if (PhotoStore.setWallpaper(photo)) {
                    PhoneUi.refreshWallpaper();
                    ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_set"));
                } else {
                    ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_fail"));
                }
            });
        mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.delete"), () -> {
            photo.delete();
            showGalleryGrid(ui, slot);
        });
        view.appendChild(actions);
        slot.show(view);
    }

    // ===================== 聊天（实现放 feature/chat/client/ChatUi） =====================

    public static SceneNode chatPage(PhoneUi ui) {
        return com.november.mcphone.feature.chat.client.ChatUi.friendsPage(ui);
    }

    // ===================== 设置 =====================

    public static SceneNode settingsPage(PhoneUi ui) {
        // 两态：设置列表 / 壁纸选择页（上游是独立页面 + navigateTo，见 settingsWallpaperView）。
        // 自愈：每次「重新进入设置 App」都从列表开始。进入选择页走的是 rebuildPage（不重进
        // App），返回走的是显式标志复位，两条路都不会经过这里；而任何把它留成 true 的意外
        // 路径（例如切到别的 App 再回来）都会在此处回到列表，不会卡在壁纸页。
        settingsWallpaperView = false;
        SceneNode entryRow = null;

        SceneNode page = scrollColumn(ui);
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.settings")));

        // 受控输入：onChange 写回 Signal，保存时从 Signal 读当前值。
        Signal<String> nameValue = Signal.create(def(ItemPhone.getDeviceName(ui.phoneStack())));
        SceneNode input = ui.runtime()
            .mount(page, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                nameValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.device_name"), 24,
                SceneInputType.TEXT, nameValue::set)))
            .getRoot();
        input.setFillParentWidth(true);
        input.setFontSize(PhoneUi.fs(16));

        SceneNode actions = SceneNode.row();
        actions.setFillParentWidth(true);
        actions.setGap(8);
        mountPrimaryButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.save"), () -> {
            NetworkHandler.sendToServer(new NetworkHandler.SetDeviceName(nameValue.get()));
            PhoneUi.updateDeviceName(nameValue.get());
            ui.toast(StatCollector.translateToLocal("msg.mcphone.saved"));
        });
        page.appendChild(actions);

        // ===================== 显示（缩放） =====================
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.display")));

        page.appendChild(wrappedMuted(ui, StatCollector.translateToLocal("label.mcphone.ui_scale")));
        // 滑条同为受控源：onChange 里写回 Signal 并落盘/应用。
        Signal<Double> uiScale = Signal.create((double) PhoneCanvas.getUiScalePercent());
        ui.runtime().mount(page, club.heiqi.uilib.ui.scene.control.SceneSlider.create(ui.runtime(),
            new club.heiqi.uilib.ui.scene.control.SceneSlider.Props(
                uiScale, Signal.create(Boolean.TRUE), 50.0, 150.0, 5.0,
                (v, committing) -> {
                    uiScale.set(v);
                    if (committing) {
                        PhoneCanvas.setUiScalePercent((int) Math.round(v));
                        PhoneUi.refreshUiScale();
                    }
                })));

        page.appendChild(wrappedMuted(ui, StatCollector.translateToLocal("label.mcphone.font_scale")));
        Signal<Double> fontScale = Signal.create(PhoneCanvas.getFontScale() * 100.0);
        ui.runtime().mount(page, club.heiqi.uilib.ui.scene.control.SceneSlider.create(ui.runtime(),
            new club.heiqi.uilib.ui.scene.control.SceneSlider.Props(
                fontScale, Signal.create(Boolean.TRUE), 50.0, 500.0, 5.0,
                (v, committing) -> {
                    fontScale.set(v);
                    if (committing) {
                        PhoneCanvas.setFontScale((float) (v / 100.0));
                        PhoneUi.refreshFontScale();
                    }
                })));

        page.appendChild(wrappedMuted(ui, StatCollector.translateToLocal("label.mcphone.button_scale")));
        Signal<Double> buttonScale = Signal.create((double) PhoneCanvas.getButtonScale());
        ui.runtime().mount(page, club.heiqi.uilib.ui.scene.control.SceneSlider.create(ui.runtime(),
            new club.heiqi.uilib.ui.scene.control.SceneSlider.Props(
                buttonScale, Signal.create(Boolean.TRUE), 50.0, 250.0, 5.0,
                (v, committing) -> {
                    buttonScale.set(v);
                    if (committing) {
                        PhoneCanvas.setButtonScale((int) Math.round(v));
                        PhoneUi.refreshButtonScale();
                    }
                })));

        // ===================== 液态玻璃（Liquid Glass） =====================
        // 复用既有 "label.mcphone.display"（「显示」）作为分组标题——本任务 inScope 是三个
        // .java 文件，不改 lang/*.lang，故不新增 i18n 键；档位/强度行用自绘 chip 与既有
        // state.mcphone.on/off 文案。
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.display")));
        page.appendChild(glassToggleRow(ui));
        page.appendChild(wrappedMuted(ui, glassTierLegend()));
        page.appendChild(glassTierRow(ui));

        page.appendChild(wrappedMuted(ui, glassLensLabel()));
        // 受控滑条：onChange 只写回 Signal；**提交（松手）时**才落盘 + 经 PhoneUi.post 延迟生效
        // （拖动中重建整树会杀死拖动手势，踩坑 #4）。
        Signal<Double> lensStrength = Signal.create((double) PhoneCanvas.getGlassLensStrength());
        ui.runtime().mount(page, club.heiqi.uilib.ui.scene.control.SceneSlider.create(ui.runtime(),
            new club.heiqi.uilib.ui.scene.control.SceneSlider.Props(
                lensStrength, Signal.create(Boolean.TRUE), 0.0, 1.0, 0.05,
                (v, committing) -> {
                    lensStrength.set(v);
                    if (committing) {
                        PhoneCanvas.setGlassLensStrength((float) v);
                        PhoneUi.postAction(PhoneUi::refreshGlassShell);
                    }
                })));
        page.appendChild(wrappedMuted(ui, glassHint()));

        // ===================== 字体颜色 =====================
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.font_color")));
        page.appendChild(wrappedMuted(ui, StatCollector.translateToLocal("msg.mcphone.font_color_hint")));
        page.appendChild(fontColorRow(ui));

        // 壁纸：照上游形态，设置列表里只有【一行】入口（上游 PhoneScreen.java:613-615 的
        // settingItems：「更换壁纸」→ navigateTo(WALLPAPER_PICKER)），真正的选择界面是
        // 独立一页（见 wallpaperPickerPage）。旧实现把 3 列色块预设网格直接摊在这一页里，
        // 上游没有这种内嵌网格。
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.wallpaper")));
        page.appendChild(wallpaperEntryRow(ui));

        // ===================== 商店模式 =====================
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.store_mode")));
        page.appendChild(storeModeRow(ui));
        page.appendChild(wrappedMuted(ui, StatCollector.translateToLocal("msg.mcphone.store_hint")));
        // 渲染自检提示：本会话内有 App 泄漏过 GL 裁剪时告知玩家（fail-safe 已修复）。
        if (PhoneCanvas.isClipped()) {
            page.appendChild(wrappedMuted(ui, StatCollector.translateToLocal("msg.mcphone.clipped_hint")));
        }
        return page;
    }

    // ===================== 液态玻璃设置行（设置页） =====================

    /**
     * 运行时本地化文案选取（zh / 其他）。
     *
     * <p><b>为什么写在代码里</b>：本任务 inScope 只有三个 .java 文件（不含
     * {@code src/main/resources/**lang/*.lang}），故不新增 i18n 键。同文件既有代码本来就用
     * 中文字面量（应用字形 {@code "钟"/"箱"}），这里沿用同一处理，并在交付说明里登记
     * 「下一轮把这几条挪进 lang 文件」。</p>
     */
    private static String gt(String zh, String en) {
        try {
            // 1.7.10 的 Minecraft 没有 getLanguage()（编译期实测），语言代码在 GameSettings.language。
            String code = net.minecraft.client.Minecraft.getMinecraft().gameSettings.language;
            return (code != null && code.toLowerCase(java.util.Locale.ROOT).startsWith("zh"))
                ? zh : en;
        } catch (Throwable ignored) {
            return zh;
        }
    }

    /** 玻璃总开关行（整行点击切换；整行样式与商店模式开关行一致）。 */
    private static SceneNode glassToggleRow(PhoneUi ui) {
        boolean on = PhoneCanvas.isGlassEnabled();
        SceneNode row = infoRow(gt("液态玻璃", "Liquid Glass"),
            StatCollector.translateToLocal(on ? "state.mcphone.on" : "state.mcphone.off"));
        row.setPadding(8, 8, 8, 8);
        applyCardSurface(row);
        // 回调里只落盘 + 延迟生效：PhoneUi.refreshGlassShell 内部整树重建，
        // 绝不能在输入路由迭代中做（踩坑 #3）。
        ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) ->
            PhoneUi.postAction(() -> {
                PhoneCanvas.setGlassEnabled(!PhoneCanvas.isGlassEnabled());
                PhoneUi.refreshGlassShell();
            }));
        return row;
    }

    /** 当前生效档位说明（AUTO 会按实际渲染路径解析成 THIN/ULTRA_THIN）。 */
    private static String glassTierLegend() {
        return gt("玻璃档位（自动=按渲染路径）", "Glass tier (auto = by render path)");
    }

    /** 档位 chip 行（自绘，字号可控）：自动 / 薄 / 极薄 / 常规 / 厚。 */
    private static SceneNode glassTierRow(PhoneUi ui) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(6);
        com.november.mcphone.client.enhance.PhoneGlass.Tier current = PhoneCanvas.glassTier();
        for (com.november.mcphone.client.enhance.PhoneGlass.Tier tier
                : com.november.mcphone.client.enhance.PhoneGlass.Tier.values()) {
            row.appendChild(glassTierChip(ui, tier, tier == current));
        }
        return row;
    }

    private static SceneNode glassTierChip(PhoneUi ui,
                                           com.november.mcphone.client.enhance.PhoneGlass.Tier tier,
                                           boolean selected) {
        SceneNode chip = SceneNode.row();
        chip.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        chip.setPadding(8, 4, 8, 4);
        chip.setMainAxisAlign(MainAxisAlign.CENTER);
        chip.setCrossAxisAlign(CrossAxisAlign.CENTER);
        // 玻璃令牌：未选中的 chip 是玻璃面（CARD 档），选中的用 accent 描边（"accent 不上玻璃"，
        // 这里 accent 只落在 border 上，底色仍是玻璃令牌 ⇒ 不破坏分层）。
        applyCardSurface(chip);
        chip.setBorderWidth(selected ? 2 : 1);
        chip.setBorderColor(selected ? 0xFF6FB2E8 : COL_BORDER);
        SceneNode label = new SceneNode();
        label.setText(tierLabel(tier));
        label.setTextColor(PhoneTheme.text());
        label.setFontSize(PhoneUi.fs(13));
        label.setHitTestable(false);
        chip.appendChild(label);
        if (!selected) {
            final int ordinal = tier.ordinal();
            ui.runtime().on(chip, SceneEventType.CLICK, (e, ctx) ->
                PhoneUi.postAction(() -> {
                    PhoneCanvas.setGlassTier(ordinal);
                    PhoneUi.refreshGlassShell();
                }));
        }
        return chip;
    }

    private static String tierLabel(com.november.mcphone.client.enhance.PhoneGlass.Tier tier) {
        switch (tier) {
            case ULTRA_THIN:
                return gt("极薄", "Ultra");
            case THIN:
                return gt("薄", "Thin");
            case REGULAR:
                return gt("常规", "Regular");
            case THICK:
                return gt("厚", "Thick");
            case AUTO:
            default:
                return gt("自动", "Auto");
        }
    }

    private static String glassLensLabel() {
        return gt("玻璃强度（0–100%）", "Glass strength (0-100%)");
    }

    private static String glassHint() {
        return gt("玻璃是首选风格：关闭后界面回落为中性浅底配色（不保留旧深灰方案）。",
            "Glass is the default style; turning it off falls back to a neutral light palette.");
    }

    /** 商店模式开关行（整行点击切换，样式与应用管理页的开关行一致）。 */
    private static SceneNode storeModeRow(PhoneUi ui) {
        boolean on = PhoneCanvas.isStoreMode();
        SceneNode row = infoRow(
            StatCollector.translateToLocal("label.mcphone.store_mode"),
            StatCollector.translateToLocal(on ? "state.mcphone.on" : "state.mcphone.off"));
        row.setPadding(8, 8, 8, 8);
        applyCardSurface(row);
        ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
            PhoneCanvas.setStoreMode(!PhoneCanvas.isStoreMode());
            ui.rebuildPage();
        }));
        return row;
    }

    /** 字体颜色预设色块行：一行排开所有预设，当前项描白边，点击即应用并重建页面。 */
    private static SceneNode fontColorRow(PhoneUi ui) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setGap(8);
        int current = com.november.mcphone.client.enhance.PhoneTheme.currentPreset();
        for (int i = 0; i < com.november.mcphone.client.enhance.PhoneTheme.presetCount(); i++) {
            final int index = i;
            SceneNode swatch = SceneNode.row();
            swatch.setWidthSizing(SceneNode.WidthSizing.SHRINK);
            swatch.setPreferredWidth(34);
            swatch.setPreferredHeight(34);
            swatch.setCornerRadius(8);
            swatch.setBackgroundColor(com.november.mcphone.client.enhance.PhoneTheme.presetText(i));
            swatch.setBorderWidth(index == current ? 3 : 1);
            swatch.setBorderColor(index == current ? 0xFFFFFFFF : COL_BORDER);
            ui.runtime().on(swatch, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
                com.november.mcphone.client.enhance.PhoneTheme.setPreset(index);
                com.november.mcphone.client.enhance.PhoneTheme.refreshTheme();
            }));
            row.appendChild(swatch);
        }
        return row;
    }

    // ===================== 壁纸选择页（照上游 WallpaperPicker） =====================

    /** 缩略图边长（上游 WallpaperPicker.THUMB_W/THUMB_H = 46，正方形预览框）。 */
    private static final int WP_THUMB = 46;
    /** 缩略图之间的间隙（上游 GAP = 4）。 */
    private static final int WP_GAP = 4;
    /** 悬停高亮：上游选择态用的 0x44FFFFFF（PhoneTheme.COLOR_APP_PRESSED / COLOR_SELECTION 同值）。 */
    private static final int WP_HOVER = 0x44FFFFFF;

    /**
     * 设置列表里的「更换壁纸」入口行（右端 chevron，整行可点）。
     *
     * <p>对应上游 {@code PhoneScreen.java:613-615}：
     * {@code settingItems.add(new Item("mcphone.gui.wallpaper", () -> navigateTo(WALLPAPER_PICKER)))}。
     * 回调只置标志 + {@code rebuildPage()}（经 {@code PhoneUi.postAction} 延迟到分发结束，
     * 踩坑 #3：输入路由迭代中改树会抛 CME）。</p>
     */
    private static SceneNode wallpaperEntryRow(PhoneUi ui) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(6);
        row.setPadding(8, 8, 8, 8);
        applyCardSurface(row);

        SceneNode label = new SceneNode();
        label.setText(StatCollector.translateToLocal("btn.mcphone.change_wallpaper"));
        label.setTextColor(PhoneTheme.text());
        label.setFontSize(PhoneUi.fs(16));
        label.setMaxTextWidth(ui.panelWidth() - 60);
        label.setHitTestable(false);
        row.appendChild(label);
        row.appendChild(spacer());

        SceneNode arrow = new SceneNode();
        arrow.setText("›");
        arrow.setTextColor(PhoneTheme.muted());
        arrow.setFontSize(PhoneUi.fs(16));
        arrow.setHitTestable(false);
        row.appendChild(arrow);

        ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
            settingsWallpaperView = true;
            ui.rebuildPage();
        }));
        return row;
    }

    /**
     * 壁纸选择页（上游 {@code WallpaperPicker}）。
     *
     * <p>版式逐条对照上游（v1.10.2 {@code shared/.../feature/settings/client/WallpaperPicker.java}）：
     * <ul>
     *   <li>标题行右侧一个「打开文件夹」文字键（{@code :114-135}）；</li>
     *   <li>第二行一个「重置」键（上游还有「选择图片」，那要弹 AWT 文件选择器，见交付报告
     *       §8 未解决项）；</li>
     *   <li>1px 分隔线（{@code :165-167}）；</li>
     *   <li>空目录时 4 行灰字：暂无壁纸 / 放入 PNG 到 / config/mcphone/ / wallpapers/
     *       （{@code :169-188}；注意上游那条注释——空态里<b>不能</b>把 hovered 覆盖成 -1，
     *       否则空目录时两个键永远点不动）；</li>
     *   <li>2 列 × 46×46 缩略图，等比居中、下方居中显示名（超宽截断 + {@code …}）
     *       （{@code :190-238}）；</li>
     *   <li><b>无选中标记</b>，只有 hover 时整格 {@code COLOR_SELECTION} 高亮
     *       （{@code :213-217}）。</li>
     * </ul>
     *
     * <p><b>与上游的三处载体差异</b>（语义不变）：上游自己画滚动（{@code scrollRow} /
     * {@code mouseScrolled}），这里交给 {@link #scrollColumn} 的 {@code SceneScrolls.attach}；
     * 上游每帧 11 参 blit 现算等比，这里由 {@code WallpaperStore.thumbnail} 预包成
     * 46×46 透明方框再整张贴；上游每帧在 {@code render} 里做目录列举与命中判定，
     * 这里用 Qz 的 {@code POINTER_MOVE} 几何命中 + 一个"当前高亮格"引用。</p>
     */
    private static SceneNode wallpaperPickerPage(PhoneUi ui) {
        // 上游 PhoneScreen 进入这一页时调 WallpaperStore.refresh()（:158-159）；
        // ensureBuiltIns 保证首次运行时 6 个内置预设已作为 PNG 落在该目录里。
        com.november.mcphone.client.enhance.WallpaperStore.ensureBuiltIns();
        com.november.mcphone.client.enhance.WallpaperStore.refresh();

        SceneNode page = scrollColumn(ui);
        // 表格容器（手动分行，理由见下）。
        SceneNode grid = SceneNode.column();
        grid.setFillParentWidth(true);
        grid.setPadding(0, 6, 0, 6);   // 上游 PAD_X = 6
        final SceneNode[] hotCell = { null };

        // ---- 标题行：标题 + 右端「打开文件夹」 ----
        int padX = 6;
        int contentW = Math.max(80, ui.panelWidth() - padX * 2);
        String openLabel = StatCollector.translateToLocal("msg.mcphone.wp_open_folder");
        int openW = Math.max(40, openLabel.length() * Math.max(6, PhoneUi.fs(14) / 2));
        int titleLimit = Math.max(40, contentW - openW - 6);
        SceneNode header = SceneNode.row();
        header.setFillParentWidth(true);
        header.setCrossAxisAlign(CrossAxisAlign.CENTER);
        header.setGap(4);

        SceneNode title = new SceneNode();
        title.setText(StatCollector.translateToLocal("msg.mcphone.wp_title"));
        title.setTextColor(PhoneTheme.text());
        title.setFontSize(PhoneUi.fs(16));
        title.setMaxTextWidth(titleLimit);
        title.setHitTestable(false);
        header.appendChild(title);
        header.appendChild(spacer());

        SceneNode open = new SceneNode();
        open.setText(openLabel);
        open.setTextColor(PhoneTheme.muted());
        open.setFontSize(PhoneUi.fs(14));
        header.appendChild(open);
        ui.runtime().on(open, SceneEventType.CLICK,
            (e, ctx) -> PhoneUi.postAction(() -> openWallpaperFolder(ui)));
        page.appendChild(header);

        // ---- 第二行：重置键（上游同一行是「选择图片」+「恢复默认背景」并排）----
        mountPrimaryButton(ui, page, StatCollector.translateToLocal("btn.mcphone.reset_wallpaper"), () -> {
            PhotoStore.clearWallpaper();
            PhoneUi.refreshWallpaper();
            ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_reset"));
        });

        // ---- 1px 分隔线（上游 :165-167）----
        SceneNode line = SceneNode.row();
        line.setFillParentWidth(true);
        line.setPreferredHeight(1);
        line.setBackgroundColor(0x4450567C);
        line.setHitTestable(false);
        page.appendChild(line);

        // ---- 内容 ----
        java.util.List<com.november.mcphone.client.enhance.WallpaperStore.Entry> list =
            com.november.mcphone.client.enhance.WallpaperStore.getWallpapers();

        if (list.isEmpty()) {
            // 上游空态 4 行（:169-188）：暂无壁纸 / 放入PNG到 / config/mcphone/ / wallpapers/
            // GTNH 侧把后三行并成一行完整路径（路径不再被拆成两个 i18n 片段）。
            page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.wp_empty")));
            page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.wp_hint")));
            page.appendChild(PhoneUi.muted(com.november.mcphone.client.enhance.WallpaperStore
                .directoryDisplayPath()));
            // 上游在空态里【不】把 hovered 复位（:180-185 那条注释，2026-09-08 实机踩过）：
            // 标题行与两个键的命中判定在 early-return 之前就算完了，覆盖会让"目录是空的"
            // 这个最想点它们的时刻点不动。本实现的高亮是独立的，同一道理：不重置任何东西。
            page.appendChild(backRow(ui));
            return page;
        }

        int box = WP_THUMB;
        int colStep = box + WP_GAP;
        // 标签行高：上游用 font.lineHeight；GTNH 侧看 fs(12) 的字高。
        int labelH = Math.max(10, PhoneUi.fs(12) + 2);
        int cellH = box + labelH + 4;

        // Qz 没有 flex-wrap（SceneNode 只有 FlexDirection.ROW/COLUMN，无 wrap），
        // 上游也是手写"一行满了几张就换行"（:230-237）⇒ 这里同样手动分行。
        // 列数按内容区宽度算（上游 colsFor(contentW)）：上游手机上算出来是 2、平板 4；
        // 平移到 GTNH（面板宽最小 260）就是 4 列。
        int cols = Math.max(1, (contentW + WP_GAP) / colStep);

        // 扁平格引用表：hover 判定要用（不用 __getChildren 遍历，避免依赖内部通道）。
        java.util.List<SceneNode> cells = new java.util.ArrayList<SceneNode>();
        SceneNode rowNode = null;
        int col = 0;
        for (int i = 0; i < list.size(); i++) {
            if (col == 0) {
                rowNode = SceneNode.row();
                rowNode.setFillParentWidth(true);
                rowNode.setGap(WP_GAP);
                grid.appendChild(rowNode);
            }
            SceneNode cellNode = wallpaperCell(ui, list.get(i), box);
            cells.add(cellNode);
            rowNode.appendChild(cellNode);
            col++;
            if (col >= cols) col = 0;
        }
        page.appendChild(grid);
        installWallpaperHover(ui, grid, hotCell, cells, cols, colStep, cellH);
        page.appendChild(backRow(ui));
        return page;
    }

    /**
     * 一格壁纸缩略图：46×46 等比居中预览 + 下方居中显示名。
     *
     * <p>与上游 {@code WallpaperPicker} 的差别只有"高亮怎么点出来"：上游在 render 里用
     * 鼠标坐标算，这里先按几何算（见 {@link #installWallpaperHover}）。</p>
     */
    private static SceneNode wallpaperCell(
            PhoneUi ui, com.november.mcphone.client.enhance.WallpaperStore.Entry entry, int box) {

        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(1);
        // 缩略图底衬：完全透明（0 alpha 不上玻璃、不画底），图源缺失时才需要兜底 ⇒ 缺图分支
        // 单独给一块中性底，正常路径与上游一样"只有图"。
        cell.setBackgroundColor(0);

        SceneNode thumb = SceneNode.row();
        // 显式 preferred 尺寸：Qz 求解器在部分容器旁会对 grow/无界尺寸早退（"不显示内容"），
        // 缩略图必须给出确定边长。
        thumb.setPreferredWidth(box);
        thumb.setPreferredHeight(box);
        thumb.setHitTestable(false);
        boolean hasImage = false;
        try {
            java.awt.image.BufferedImage img =
                com.november.mcphone.client.enhance.WallpaperStore.thumbnail(entry, box);
            if (img != null) {
                thumb.setImageSource(HostImageSource.bufferedImage(img,
                    com.november.mcphone.client.enhance.WallpaperStore.thumbnailKey(entry, box)));
                hasImage = true;
            }
        } catch (Throwable ignored) {
            // 缺图：留空，底下给一块中性底
        }
        if (!hasImage) thumb.setBackgroundColor(0xFF101418);
        cell.appendChild(thumb);

        SceneNode label = new SceneNode();
        label.setText(entry.label());
        label.setTextColor(PhoneTheme.muted());
        label.setFontSize(PhoneUi.fs(12));
        label.setMaxTextWidth(box);
        label.setTextHorizontalAlign(club.heiqi.uilib.ui.scene.node.TextHorizontalAlign.CENTER);
        label.setHitTestable(false);
        cell.appendChild(label);

        ui.runtime().on(cell, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
            // 只把选中文件名交给 PhoneUi（它自己决定 imageKey/裁剪口径）；
            // 上游这一步是 MCphoneNetwork.sendToServer(new SetWallpaperPacket(fileName))，
            // 我们本轮只做本地生效（服务端同步见交付报告 §8）。
            PhoneUi.selectWallpaper(entry.fileName);
            ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_set"));
            // 上游点中之后返回设置列表（WallpaperPicker.mouseClicked 返回 true）。
            settingsWallpaperView = false;
            ui.rebuildPage();
        }));
        return cell;
    }

    /**
     * 一个 hover 处理器负责整张网格（上游在 render 里逐格判断，语义相同）。
     *
     * <p>坐标口径与 {@code PhoneUi.dropIndexAt} 一致：Qz 的
     * {@code SceneGeometry.absoluteBox} 相对场景根，本节点的局部指针坐标 + 本节点的根相对框
     * = 指针的根相对坐标。挂在<b>网格容器</b>上而不是每一格上：指针移出所有格子时只有容器
     * 仍收得到 {@code POINTER_MOVE}，逐格挂会导致高亮滞留（上游每帧重算，没有这个问题）。</p>
     */
    private static void installWallpaperHover(
            PhoneUi ui, final SceneNode grid, final SceneNode[] hotCell,
            final java.util.List<SceneNode> cells, final int cols, final int colStep, final int cellH) {

        ui.runtime().on(grid, SceneEventType.POINTER_MOVE, (e, ctx) -> {
            club.heiqi.uilib.ui.scene.layout.AnchorRect gb =
                club.heiqi.uilib.ui.scene.layout.SceneGeometry.absoluteBox(grid, 0, 0);
            int relX = ctx.getLocalPointerX();
            int relY = ctx.getLocalPointerY();
            int hit = -1;
            if (relX >= 0 && relY >= 0) {
                int col = relX / Math.max(1, colStep);
                int rowIdx = relY / Math.max(1, cellH);
                int candidate = rowIdx * cols + col;
                // 尾部行不满时 col 也可能越界（落在最后一格右边的空白上），用 cells.size() 判定
                if (col < cols && candidate >= 0 && candidate < cells.size()) hit = candidate;
            }
            SceneNode wanted = hit < 0 ? null : cells.get(hit);
            SceneNode was = hotCell[0];
            if (was == wanted) return;
            if (was != null) was.setBackgroundColor(0);
            if (wanted != null) wanted.setBackgroundColor(WP_HOVER);
            hotCell[0] = wanted;
        });
    }

    /** 返回设置列表（上游点中壁纸后自动回设置列表；这里给一个显式返回键）。 */
    private static SceneNode backRow(PhoneUi ui) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setPadding(6, 6, 6, 6);
        applyCardSurface(row);
        SceneNode label = new SceneNode();
        label.setText("‹ " + StatCollector.translateToLocal("btn.mcphone.back"));
        label.setTextColor(PhoneTheme.text());
        label.setFontSize(PhoneUi.fs(14));
        label.setHitTestable(false);
        row.appendChild(label);
        ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
            settingsWallpaperView = false;
            ui.rebuildPage();
        }));
        return row;
    }

    /** 打开系统文件管理器定位到壁纸目录（上游 ImageFolder.openInFileManager）。 */
    private static void openWallpaperFolder(PhoneUi ui) {
        try {
            java.io.File dir = com.november.mcphone.client.enhance.WallpaperStore.directory();
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面的提示分支
        }
        ui.toast(StatCollector.translateToLocalFormatted(
            "msg.mcphone.wp_folder_hint",
            com.november.mcphone.client.enhance.WallpaperStore.absolutePath()));
    }

    // ===================== 应用管理 =====================

    public static SceneNode appManagerPage(PhoneUi ui) {
        SceneNode page = scrollColumn(ui);
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.appmgr")));

        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.appmgr_hint")));
        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.appmgr_order_hint")));
        // 商店模式：购买入口已移交应用商店，本页只管启用/停用；关闭时与旧版完全一致。
        boolean store = com.november.mcphone.client.StoreClient.isEnabled();
        if (store) {
            page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.store_page_hint")));
        }
        // 快捷键捕获态横幅：下一次按键（任意行点"键"后）将绑定为该 App 的热键。
        if (PhoneUi.hotkeyCaptureTarget != null) {
            SceneNode cap = new SceneNode();
            cap.setText(StatCollector.translateToLocalFormatted(
                "msg.mcphone.hotkey_capturing", PhoneUi.hotkeyCaptureTarget));
            cap.setTextColor(0xFF9CE89C);
            cap.setFontSize(PhoneUi.fs(14));
            cap.setHitTestable(false);
            page.appendChild(cap);
        }
        java.util.List<IPhoneApp> ordered = PhoneApi.orderedApps();
        boolean capturing = PhoneUi.hotkeyCaptureTarget != null;
        for (int appIndex = 0; appIndex < ordered.size(); appIndex++) {
            IPhoneApp app = ordered.get(appIndex);
            final boolean first = appIndex == 0;
            final boolean last = appIndex == ordered.size() - 1;
            final boolean enabled = PhoneCanvas.isAppEnabled(app.id());
            boolean system = store && isSystemApp(app.id());
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(8);
            row.setPadding(8, 8, 8, 8);
            applyCardSurface(row);
            SceneNode label = new SceneNode();
            label.setText(app.displayName() + "  (" + app.id() + ")");
            label.setTextColor(PhoneTheme.text());
            label.setFontSize(PhoneUi.fs(15));
            label.setHitTestable(false);
            row.appendChild(label);
            row.appendChild(spacer());
            SceneNode state = new SceneNode();
            if (system) {
                // 商店模式下系统 App 不可卸载。
                state.setText(StatCollector.translateToLocal("state.mcphone.system"));
                state.setTextColor(PhoneTheme.muted());
            } else {
                state.setText(StatCollector.translateToLocal(enabled ? "state.mcphone.on" : "state.mcphone.off"));
                state.setTextColor(enabled ? 0xFF9CE89C : 0xFFE0A0A0);
            }
            state.setFontSize(PhoneUi.fs(14));
            state.setHitTestable(false);
            row.appendChild(state);
            row.appendChild(orderButton(ui, app.id(), "↑", first ? null : -1));
            row.appendChild(orderButton(ui, app.id(), "↓", last ? null : 1));
            row.appendChild(hotkeyButton(ui, app.id(), capturing));
            if (!system) {
                // 整行可点击切换启用/停用；
                // ↑/↓ 按钮内部 stopPropagation，不会误触开关。
                ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
                    PhoneCanvas.setAppEnabled(app.id(), !PhoneCanvas.isAppEnabled(app.id()));
                    ui.rebuildPage();
                }));
            }
            page.appendChild(row);
        }
        return page;
    }

    /** 系统 App（设置/应用管理）：商店模式下不可卸载。 */
    private static boolean isSystemApp(String appId) {
        return "settings".equals(appId) || "appmgr".equals(appId);
    }

    // ===================== 应用商店 =====================

    /**
     * 应用商店页（上游形态）：首页 4 列图标网格 + 页内详情页，实现全在同包的
     * {@link com.november.mcphone.client.store.StoreFront}（它要自持槽位/选中 App/
     * 在途购买三样状态，塞进本类只能靠静态字段，会在主屏与 HUD 两个手机实例间串味）。
     */
    public static SceneNode storePage(PhoneUi ui) {
        return com.november.mcphone.client.store.StoreFront.page(ui);
    }

    /**
     * 排序小按钮（↑/↓）。delta 为 null 表示该方向不可用（置灰且不响应）；
     * 点击 stopPropagation，避免冒泡触发整行的开关切换。
     */
    private static SceneNode orderButton(PhoneUi ui, String appId, String glyph, Integer delta) {
        boolean active = delta != null;
        SceneNode btn = SceneNode.row();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        btn.setPreferredWidth(30);
        btn.setPreferredHeight(24);
        btn.setCornerRadius(6);
        btn.setBackgroundColor(active ? 0x556FB2E8 : 0x22FFFFFF);
        btn.setMainAxisAlign(MainAxisAlign.CENTER);
        btn.setCrossAxisAlign(CrossAxisAlign.CENTER);
        SceneNode g = new SceneNode();
        g.setText(glyph);
        g.setTextColor(active ? PhoneTheme.text() : PhoneTheme.muted());
        g.setFontSize(PhoneUi.fs(14));
        g.setHitTestable(false);
        btn.appendChild(g);
        if (active) {
            final int d = delta.intValue();
            ui.runtime().on(btn, SceneEventType.CLICK, (e, dispatch) -> {
                dispatch.stopPropagation();
                PhoneUi.postAction(() -> {
                    // 在完整 App 列表上交换（旧实现只查已保存的顺序表，表为空时永远无效果）。
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    for (IPhoneApp a : PhoneApi.orderedApps()) ids.add(a.id());
                    if (!ids.contains(appId)) ids.add(appId);
                    int i = ids.indexOf(appId);
                    int j = i + d;
                    if (j >= 0 && j < ids.size()) {
                        String t = ids.get(i);
                        ids.set(i, ids.get(j));
                        ids.set(j, t);
                        PhoneCanvas.setAppOrder(ids);
                    }
                    ui.rebuildPage();
                });
            });
        } else {
            btn.setHitTestable(false);
        }
        return btn;
    }

    /**
     * 每 App 快捷键按钮：显示当前绑定（如 "CTRL+K"）或 "—"；点击进入捕获态，
     * 由 PhoneScreen.keyTyped 拦截下一次按键。捕获中该行高亮。stopPropagation
     * 避免冒泡触发整行的启停切换。
     */
    private static SceneNode hotkeyButton(PhoneUi ui, String appId, boolean capturing) {
        AppHotkey current = AppHotkey.forApp(appId);
        String label = current != null ? current.displayName() : "—";
        boolean pending = capturing && appId.equals(PhoneUi.hotkeyCaptureTarget);
        SceneNode btn = SceneNode.row();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        btn.setPreferredWidth(64);
        btn.setPreferredHeight(24);
        btn.setCornerRadius(6);
        btn.setBackgroundColor(pending ? 0x886FB2E8 : 0x22FFFFFF);
        btn.setMainAxisAlign(MainAxisAlign.CENTER);
        btn.setCrossAxisAlign(CrossAxisAlign.CENTER);
        SceneNode g = new SceneNode();
        g.setText(pending ? "…" : label);
        g.setTextColor(pending ? PhoneTheme.text() : PhoneTheme.muted());
        g.setFontSize(PhoneUi.fs(12));
        g.setHitTestable(false);
        btn.appendChild(g);
        ui.runtime().on(btn, SceneEventType.CLICK, (e, dispatch) -> {
            dispatch.stopPropagation();
            PhoneUi.postAction(() -> {
                PhoneUi.hotkeyCaptureTarget = appId;
                ui.rebuildPage();
            });
        });
        return btn;
    }

    private static String def(String s) {
        return s == null ? "" : s;
    }
}
