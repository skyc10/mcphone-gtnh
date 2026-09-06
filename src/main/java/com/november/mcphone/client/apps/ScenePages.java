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
        SceneButton.Props props = new SceneButton.Props(
            Signal.create(label), Signal.create(Boolean.TRUE), onClick, club.heiqi.uilib.ui.scene.control.SceneButtonVariant.PRIMARY);
        SceneNode btn = ui.runtime().mount(parent, SceneButton.create(ui.runtime(), props)).getRoot();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        return btn;
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
        big.setFontSize(56);
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
        k.setFontSize(16);
        k.setHitTestable(false);
        SceneNode v = new SceneNode();
        v.setText(value);
        v.setTextColor(COL_TEXT);
        v.setFontSize(16);
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
            t.setFontSize(16);
            t.setMaxTextWidth(ui.panelWidth() - 60);
            t.setHitTestable(false);
            row.appendChild(t);
            row.appendChild(spacer());
            SceneNode arrow = new SceneNode();
            arrow.setText("›");
            arrow.setTextColor(COL_MUTED);
            arrow.setFontSize(16);
            arrow.setHitTestable(false);
            row.appendChild(arrow);
            ui.runtime().on(row, SceneEventType.CLICK, (e, ctx) -> showNotesEditor(ui, slot, note));
            list.appendChild(row);
        }
        slot.show(list);
    }

    private static void showNotesEditor(PhoneUi ui, PageSlot slot, NotesStore.Note note) {
        String[] title = {note.title == null ? "" : note.title};
        String[] body = {note.body == null ? "" : note.body};

        SceneNode editor = SceneNode.column();
        editor.setFillParentWidth(true);
        editor.setFlexGrow(1);
        editor.setGap(8);
        editor.setPadding(10);

        mountButton(ui, editor, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showNotesList(ui, slot));

        Signal<String> titleValue = Signal.create(title[0]);
        SceneNode titleInput = ui.runtime()
            .mount(editor, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                titleValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.note_title"), 32,
                SceneInputType.TEXT, s -> title[0] = s)))
            .getRoot();
        titleInput.setFillParentWidth(true);

        Signal<String> bodyValue = Signal.create(body[0]);
        SceneNode area = ui.runtime()
            .mount(editor, SceneTextArea.create(ui.runtime(), new SceneTextArea.Props(
                bodyValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.note_body"), 20000,
                260, s -> body[0] = s)))
            .getRoot();
        area.setFillParentWidth(true);
        area.setFlexGrow(1);

        SceneNode actions = SceneNode.row();
        actions.setFillParentWidth(true);
        actions.setGap(8);
        mountPrimaryButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.save"), () -> {
            note.title = title[0];
            note.body = body[0];
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
        ui.runtime().on(node, SceneEventType.CLICK, (e, ctx) -> showGalleryViewer(ui, slot, photo));
        return node;
    }

    private static void showGalleryViewer(PhoneUi ui, PageSlot slot, File photo) {
        SceneNode view = SceneNode.column();
        view.setFillParentWidth(true);
        view.setFlexGrow(1);
        view.setGap(8);
        view.setPadding(10);

        SceneNode image = SceneNode.row();
        image.setFillParentWidth(true);
        image.setFlexGrow(1);
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

        String[] name = {def(ItemPhone.getDeviceName(ui.phoneStack()))};
        Signal<String> nameValue = Signal.create(name[0]);
        SceneNode input = ui.runtime()
            .mount(page, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                nameValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.device_name"), 24,
                SceneInputType.TEXT, s -> name[0] = s)))
            .getRoot();
        input.setFillParentWidth(true);

        SceneNode actions = SceneNode.row();
        actions.setFillParentWidth(true);
        actions.setGap(8);
        mountPrimaryButton(ui, actions, StatCollector.translateToLocal("btn.mcphone.save"), () -> {
            NetworkHandler.sendToServer(new NetworkHandler.SetDeviceName(name[0]));
            ui.toast(StatCollector.translateToLocal("msg.mcphone.saved"));
        });
        page.appendChild(actions);

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

        for (IPhoneApp app : PhoneApi.apps()) {
            Signal<Boolean> on = Signal.create(PhoneCanvas.isAppEnabled(app.id()));
            SceneNode row = SceneNode.row();
            row.setFillParentWidth(true);
            row.setCrossAxisAlign(CrossAxisAlign.CENTER);
            row.setGap(8);
            row.setPadding(6, 6, 6, 6);
            row.setCornerRadius(6);
            row.setBackgroundColor(COL_PANEL);
            SceneNode label = new SceneNode();
            label.setText(app.displayName() + "  (" + app.id() + ")");
            label.setTextColor(COL_TEXT);
            label.setFontSize(15);
            label.setHitTestable(false);
            row.appendChild(label);
            row.appendChild(spacer());
            ui.runtime().mount(row, SceneToggle.create(ui.runtime(), new SceneToggle.Props(
                on, Signal.create(""), Signal.create(Boolean.TRUE),
                v -> PhoneCanvas.setAppEnabled(app.id(), v))));
            page.appendChild(row);
        }
        return page;
    }

    private static String def(String s) {
        return s == null ? "" : s;
    }
}
