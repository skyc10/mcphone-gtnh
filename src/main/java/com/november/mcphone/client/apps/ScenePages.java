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
import com.november.mcphone.client.PhoneCanvas;
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

    private static final int COL_PANEL = 0x33FFFFFF;
    private static final int COL_BORDER = 0x55FFFFFF;

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

    /** 可换行的次要说明文字（setMaxTextWidth 触发 Qz 按宽拆行）。 */
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
            row.setCornerRadius(6);
            row.setBackgroundColor(COL_PANEL);
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
            card.setCornerRadius(8);
            card.setBackgroundColor(COL_PANEL);

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

        // ===================== 字体颜色 =====================
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.font_color")));
        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.font_color_hint")));
        page.appendChild(fontColorRow(ui));

        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.wallpaper")));
        page.appendChild(wallpaperPresetGrid(ui));
        mountPrimaryButton(ui, page, StatCollector.translateToLocal("btn.mcphone.reset_wallpaper"), () -> {
            PhotoStore.clearWallpaper();
            PhoneUi.refreshWallpaper();
            ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_reset"));
        });

        // ===================== 商店模式 =====================
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.store_mode")));
        page.appendChild(storeModeRow(ui));
        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.store_hint")));
        return page;
    }

    /** 商店模式开关行（整行点击切换，样式与应用管理页的开关行一致）。 */
    private static SceneNode storeModeRow(PhoneUi ui) {
        boolean on = PhoneCanvas.isStoreMode();
        SceneNode row = infoRow(
            StatCollector.translateToLocal("label.mcphone.store_mode"),
            StatCollector.translateToLocal(on ? "state.mcphone.on" : "state.mcphone.off"));
        row.setPadding(8, 8, 8, 8);
        row.setCornerRadius(8);
        row.setBackgroundColor(COL_PANEL);
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

    /** 预设壁纸网格（3 列色块缩略图）：点击生成渐变并写入 wallpaper.png，立即生效。 */
    private static SceneNode wallpaperPresetGrid(PhoneUi ui) {
        SceneNode grid = SceneNode.column();
        grid.setFillParentWidth(true);
        grid.setGap(8);
        int perRow = 3;
        int cellW = (ui.panelWidth() - 24 - (perRow - 1) * 8) / perRow;
        for (int i = 0; i < com.november.mcphone.client.enhance.WallpaperPresets.count(); i += perRow) {
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setGap(8);
            for (int j = 0; j < perRow && i + j < com.november.mcphone.client.enhance.WallpaperPresets.count(); j++) {
                row.appendChild(wallpaperPresetCell(ui, i + j, cellW));
            }
            grid.appendChild(row);
        }
        return grid;
    }

    private static SceneNode wallpaperPresetCell(PhoneUi ui, int index, int cellW) {
        com.november.mcphone.client.enhance.WallpaperPresets.Preset preset =
            com.november.mcphone.client.enhance.WallpaperPresets.get(index);
        SceneNode cell = SceneNode.column();
        cell.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        cell.setCrossAxisAlign(CrossAxisAlign.CENTER);
        cell.setGap(4);

        SceneNode swatch = SceneNode.row();
        swatch.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        swatch.setPreferredWidth(cellW);
        swatch.setPreferredHeight(cellW * 16 / 9);
        swatch.setCornerRadius(8);
        swatch.setBackgroundColor(preset.swatchColor());
        swatch.setBorderWidth(1);
        swatch.setBorderColor(COL_BORDER);
        swatch.setClipChildren(true);
        ui.runtime().on(swatch, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
            if (com.november.mcphone.client.enhance.WallpaperPresets.apply(index)) {
                ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_set"));
            } else {
                ui.toast(StatCollector.translateToLocal("msg.mcphone.wallpaper_fail"));
            }
        }));
        cell.appendChild(swatch);

        SceneNode label = new SceneNode();
        label.setText(StatCollector.translateToLocal(preset.nameKey));
        label.setTextColor(com.november.mcphone.client.enhance.PhoneTheme.muted());
        label.setFontSize(PhoneUi.fs(12));
        label.setMaxTextWidth(cellW);
        label.setTextHorizontalAlign(club.heiqi.uilib.ui.scene.node.TextHorizontalAlign.CENTER);
        label.setHitTestable(false);
        cell.appendChild(label);
        return cell;
    }

    // ===================== 应用管理 =====================

    public static SceneNode appManagerPage(PhoneUi ui) {
        SceneNode page = scrollColumn(ui);
        page.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.appmgr")));

        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.appmgr_hint")));
        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.appmgr_order_hint")));
        // 商店模式：本页升级为商店页（展示价格/购买）；关闭时与旧版完全一致。
        boolean store = com.november.mcphone.client.StoreClient.isEnabled();
        if (store) {
            page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.store_page_hint")));
        }
        java.util.List<IPhoneApp> ordered = PhoneApi.orderedApps();
        for (int appIndex = 0; appIndex < ordered.size(); appIndex++) {
            IPhoneApp app = ordered.get(appIndex);
            final boolean first = appIndex == 0;
            final boolean last = appIndex == ordered.size() - 1;
            final boolean enabled = PhoneCanvas.isAppEnabled(app.id());
            boolean system = store && isSystemApp(app.id());
            boolean needsPurchase = store && com.november.mcphone.client.StoreClient.needsPurchase(app);
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(8);
            row.setPadding(8, 8, 8, 8);
            row.setCornerRadius(8);
            row.setBackgroundColor(COL_PANEL);
            SceneNode label = new SceneNode();
            label.setText(app.displayName() + "  (" + app.id() + ")");
            label.setTextColor(PhoneTheme.text());
            label.setFontSize(PhoneUi.fs(15));
            label.setHitTestable(false);
            row.appendChild(label);
            row.appendChild(spacer());
            if (needsPurchase) {
                // 价格 + 购买按钮：购买走服务端校验扣物，成功后 UnlockSync 触发本页重建。
                String price = com.november.mcphone.client.StoreClient.priceText(app);
                SceneNode priceNode = new SceneNode();
                priceNode.setText(price == null ? "" : price);
                priceNode.setTextColor(PhoneTheme.muted());
                priceNode.setFontSize(PhoneUi.fs(14));
                priceNode.setHitTestable(false);
                row.appendChild(priceNode);
                mountPrimaryButton(ui, row, StatCollector.translateToLocal("btn.mcphone.buy"),
                    () -> NetworkHandler.sendToServer(new NetworkHandler.PurchaseApp(app.id())));
            } else {
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
            }
            row.appendChild(orderButton(ui, app.id(), "↑", first ? null : -1));
            row.appendChild(orderButton(ui, app.id(), "↓", last ? null : 1));
            if (!needsPurchase && !system) {
                // 整行可点击切换（商店模式下即卸载/安装：已购 App 卸载后免费重装）；
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

    private static String def(String s) {
        return s == null ? "" : s;
    }
}
