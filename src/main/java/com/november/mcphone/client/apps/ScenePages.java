package com.november.mcphone.client.apps;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
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
import com.november.mcphone.client.NotesStore;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.TimeUtil;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.core.ItemPhone;
import com.november.mcphone.net.NetworkHandler;

/**
 * 页面型内建 App 的场景页实现（Qz-UILib）。
 *
 * <p>统一约定：页面一次性建树；两态页面（列表/编辑）用内部单槽 {@link PageSlot}
 * 切换，切槽先 dispose 旧 MountHandle 再挂新树。</p>
 */
public final class ScenePages {

    private static final int COL_TEXT = 0xFFE8EDF2;
    private static final int COL_MUTED = 0xFF8B98A8;
    private static final int COL_PANEL = 0x33FFFFFF;
    private static final int COL_BORDER = 0x55FFFFFF;

    private ScenePages() {}

    /** 页面内部单槽：一次只挂一棵子树。 */
    private static final class PageSlot {

        final SceneRuntime runtime;
        final SceneNode slot;
        MountHandle handle;

        PageSlot(SceneRuntime runtime) {
            this.runtime = runtime;
            this.slot = SceneNode.column();
            this.slot.setFillParentWidth(true);
            this.slot.setFlexGrow(1);
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

    private static SceneNode scrollColumn() {
        SceneNode col = SceneNode.column();
        col.setFillParentWidth(true);
        col.setFlexGrow(1);
        col.setPadding(12);
        col.setGap(10);
        col.setScrollable(true);
        col.setClipChildren(true);
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
        SceneNode page = scrollColumn();
        page.setCrossAxisAlign(CrossAxisAlign.CENTER);
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.clock")));

        SceneNode big = new SceneNode();
        big.setText(PhoneUi.clockSignal().get());
        big.setTextColor(COL_TEXT);
        big.setFontSize(PhoneUi.fs(56));
        big.setHitTestable(false);
        ui.runtime().bindText(big, PhoneUi.clockSignal());
        page.appendChild(big);

        page.appendChild(PhoneUi.muted(
            StatCollector.translateToLocalFormatted("msg.mcphone.world_day", TimeUtil.worldDay())));
        return page;
    }

    // ===================== 天气 =====================

    public static SceneNode weatherPage(PhoneUi ui) {
        SceneNode page = scrollColumn();
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.weather")));

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null && mc.thePlayer != null) {
            int x = (int) Math.floor(mc.thePlayer.posX);
            int z = (int) Math.floor(mc.thePlayer.posZ);
            String biome = mc.theWorld.getBiomeGenForCoords(x, z).biomeName;
            boolean rain = mc.theWorld.isRaining();
            boolean thunder = mc.theWorld.isThundering();
            boolean day = mc.theWorld.isDaytime();
            page.appendChild(infoRow(StatCollector.translateToLocal("label.mcphone.biome"), biome));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.rain"),
                StatCollector.translateToLocal(rain ? "state.mcphone.yes" : "state.mcphone.no")));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.thunder"),
                StatCollector.translateToLocal(thunder ? "state.mcphone.yes" : "state.mcphone.no")));
            page.appendChild(infoRow(
                StatCollector.translateToLocal("label.mcphone.daylight"),
                StatCollector.translateToLocal(day ? "state.mcphone.day" : "state.mcphone.night")));
        } else {
            page.appendChild(PhoneUi.muted("§7--"));
        }
        return page;
    }

    private static SceneNode infoRow(String key, String value) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(8);
        SceneNode k = new SceneNode();
        k.setText(key);
        k.setTextColor(COL_MUTED);
        k.setFontSize(PhoneUi.fs(16));
        k.setHitTestable(false);
        SceneNode v = new SceneNode();
        v.setText(value);
        v.setTextColor(COL_TEXT);
        v.setFontSize(PhoneUi.fs(16));
        v.setHitTestable(false);
        row.appendChild(k);
        row.appendChild(spacer());
        row.appendChild(v);
        return row;
    }

    // ===================== 便签 =====================

    public static SceneNode notesPage(PhoneUi ui) {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setFlexGrow(1);
        page.setGap(8);
        page.setPadding(10);

        PageSlot slot = new PageSlot(ui.runtime());
        page.appendChild(slot.slot);
        showNotesList(ui, slot);
        return page;
    }

    private static void showNotesList(PhoneUi ui, PageSlot slot) {
        SceneNode list = scrollColumn();
        list.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.notes")));
        mountPrimaryButton(ui, list, StatCollector.translateToLocal("btn.mcphone.new_note"),
            () -> showNotesEditor(ui, slot, new NotesStore.Note()));

        for (NotesStore.Note n : NotesStore.list()) {
            NotesStore.Note note = n;
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(6);
            row.setPadding(6, 6, 6, 6);
            row.setCornerRadius(6);
            row.setBackgroundColor(COL_PANEL);
            SceneNode t = new SceneNode();
            t.setText(n.title.isEmpty() ? "(...)" : n.title);
            t.setTextColor(COL_TEXT);
            t.setFontSize(PhoneUi.fs(16));
            t.setMaxTextWidth(ui.panelWidth() - 60);
            t.setHitTestable(false);
            row.appendChild(t);
            row.appendChild(spacer());
            SceneNode arrow = new SceneNode();
            arrow.setText("›");
            arrow.setTextColor(COL_MUTED);
            arrow.setFontSize(PhoneUi.fs(16));
            arrow.setHitTestable(false);
            row.appendChild(arrow);
            ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> showNotesEditor(ui, slot, note)));
            list.appendChild(row);
        }
        slot.show(list);
    }

    private static void showNotesEditor(PhoneUi ui, PageSlot slot, NotesStore.Note note) {
        // 受控输入：onChange 必须把值写回 Signal（控件不自己改 value），保存时从 Signal 读。
        Signal<String> titleValue = Signal.create(note.title == null ? "" : note.title);
        Signal<String> bodyValue = Signal.create(note.body == null ? "" : note.body);

        SceneNode editor = SceneNode.column();
        editor.setFillParentWidth(true);
        editor.setFlexGrow(1);
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
                    StatCollector.translateToLocal("label.mcphone.note_body"), 20000,
                    0xFF6FB2E8, 0xFFE8EDF2, 0xFF8B98A8, 0xFF666666, bodyValue::set));
        SceneNode area = taRes.root();
        area.setFillParentWidth(true);
        area.setFontSize(PhoneUi.fs(18));
        area.setPadding(8, 8, 8, 8);
        area.setBorderWidth(1);
        area.setBorderColor(COL_BORDER);
        area.setCornerRadius(8);
        area.setBackgroundColor(0xFF1A2028);
        taRes.viewport().setPreferredHeight(260);
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
            note.title = titleValue.get();
            note.body = bodyValue.get();
            NotesStore.save(note);
            ui.toast(StatCollector.translateToLocal("msg.mcphone.saved"));
            showNotesList(ui, slot);
        });
        mountButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.delete"), () -> {
            NotesStore.delete(note);
            showNotesList(ui, slot);
        });
        editor.appendChild(actions);
        slot.show(editor);
    }

    // ===================== 传送（多传送点） =====================

    public static SceneNode teleportPage(PhoneUi ui) {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setFlexGrow(1);
        page.setPadding(10);
        page.setGap(8);

        PageSlot slot = new PageSlot(ui.runtime());
        page.appendChild(slot.slot);
        showWaypointList(ui, slot);
        return page;
    }

    private static void showWaypointList(PhoneUi ui, PageSlot slot) {
        SceneNode list = scrollColumn();
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
            card.setCornerRadius(8);
            card.setBackgroundColor(COL_PANEL);

            SceneNode nameRow = SceneNode.row();
            nameRow.setFillParentWidth(true);
            nameRow.setCrossAxisAlign(CrossAxisAlign.CENTER);
            nameRow.setGap(6);
            SceneNode name = new SceneNode();
            name.setText(w.name);
            name.setTextColor(COL_TEXT);
            name.setFontSize(PhoneUi.fs(16));
            name.setHitTestable(false);
            nameRow.appendChild(name);
            nameRow.appendChild(spacer());
            SceneNode dim = new SceneNode();
            dim.setText("D" + w.dim);
            dim.setTextColor(COL_MUTED);
            dim.setFontSize(PhoneUi.fs(12));
            dim.setHitTestable(false);
            nameRow.appendChild(dim);
            card.appendChild(nameRow);

            SceneNode coords = new SceneNode();
            coords.setText(String.format("%.0f, %.0f, %.0f", w.x, w.y, w.z));
            coords.setTextColor(COL_MUTED);
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
        editor.setFlexGrow(1);
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
        page.setFlexGrow(1);
        page.setPadding(10);
        page.setGap(8);

        PageSlot slot = new PageSlot(ui.runtime());
        page.appendChild(slot.slot);
        showGalleryGrid(ui, slot);
        return page;
    }

    private static void showGalleryGrid(PhoneUi ui, PageSlot slot) {
        SceneNode grid = scrollColumn();
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
        view.setFlexGrow(1);
        view.setGap(8);
        view.setPadding(10);

        SceneNode image = SceneNode.row();
        // 显式尺寸：查看器图片节点不参与 flexGrow（无界高度会让图片渲染区域为 0，表现为"不显示内容"）。
        image.setPreferredWidth(ui.panelWidth() - 24);
        image.setPreferredHeight((int) ((ui.panelWidth() - 24) * 0.75));
        image.setCornerRadius(10);
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

    // ===================== 设置 =====================

    public static SceneNode settingsPage(PhoneUi ui) {
        SceneNode page = scrollColumn();
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

        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.ui_scale")));
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

        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.font_scale")));
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

        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.button_scale")));
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

        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.wallpaper")));
        mountPrimaryButton(ui, page, StatCollector.translateToLocal("btn.mcphone.reset_wallpaper"), () -> {
            PhotoStore.clearWallpaper();
            PhoneUi.refreshWallpaper();
            ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_reset"));
        });
        return page;
    }

    // ===================== 应用管理 =====================

    public static SceneNode appManagerPage(PhoneUi ui) {
        SceneNode page = scrollColumn();
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.appmgr")));

        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.appmgr_hint")));
        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.appmgr_order_hint")));
        java.util.List<IPhoneApp> ordered = PhoneApi.orderedApps();
        for (int appIndex = 0; appIndex < ordered.size(); appIndex++) {
            IPhoneApp app = ordered.get(appIndex);
            final boolean first = appIndex == 0;
            final boolean last = appIndex == ordered.size() - 1;
            final boolean enabled = PhoneCanvas.isAppEnabled(app.id());
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(8);
            row.setPadding(8, 8, 8, 8);
            row.setCornerRadius(8);
            row.setBackgroundColor(COL_PANEL);
            SceneNode label = new SceneNode();
            label.setText(app.displayName() + "  (" + app.id() + ")");
            label.setTextColor(COL_TEXT);
            label.setFontSize(PhoneUi.fs(15));
            label.setHitTestable(false);
            row.appendChild(label);
            row.appendChild(spacer());
            SceneNode state = new SceneNode();
            state.setText(StatCollector.translateToLocal(enabled ? "state.mcphone.on" : "state.mcphone.off"));
            state.setTextColor(enabled ? 0xFF9CE89C : 0xFFE0A0A0);
            state.setFontSize(PhoneUi.fs(14));
            state.setHitTestable(false);
            row.appendChild(state);
            row.appendChild(orderButton(ui, app.id(), "↑", first ? null : -1));
            row.appendChild(orderButton(ui, app.id(), "↓", last ? null : 1));
            // 整行可点击切换；↑/↓ 按钮内部 stopPropagation，不会误触开关。
            ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
                PhoneCanvas.setAppEnabled(app.id(), !PhoneCanvas.isAppEnabled(app.id()));
                ui.rebuildPage();
            }));
            page.appendChild(row);
        }
        return page;
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
        g.setTextColor(active ? COL_TEXT : COL_MUTED);
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

    private static String def(String s) {
        return s == null ? "" : s;
    }
}
