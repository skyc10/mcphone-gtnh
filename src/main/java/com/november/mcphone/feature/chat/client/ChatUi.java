package com.november.mcphone.feature.chat.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import net.minecraft.util.StatCollector;

import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.MountHandle;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

import com.november.mcphone.api.PhoneWidgets;
import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.net.NetworkHandler;

/**
 * 聊天 App 页面（Qz-UILib）：会话列表（好友/申请/添加好友）→ 气泡会话页 →
 * 大图查看/相册选图。页面写法沿用 ScenePages 的约定：一次性建树 + 内部单槽
 * {@link Slot} 切换、显式高度先验、回调一律经 {@link PhoneUi#postAction} 延迟。
 */
@cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
public final class ChatUi {

    private static final int COL_TEXT = 0xFFE8EDF2;
    private static final int COL_MUTED = 0xFF8B98A8;
    private static final int COL_PANEL = 0x33FFFFFF;
    private static final int COL_BORDER = 0x55FFFFFF;
    private static final int COL_BUBBLE_SELF = 0xFF2F5FA8;
    private static final int COL_ONLINE = 0xFF9CE89C;

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm");

    // ===================== 页面内单槽（同 ScenePages.PageSlot） =====================

    private static final class Slot {

        final SceneRuntime runtime;
        final SceneNode slot;
        MountHandle handle;

        Slot(SceneRuntime runtime, int height) {
            this.runtime = runtime;
            this.slot = SceneNode.column();
            this.slot.setFillParentWidth(true);
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

    /** 当前聊天页根节点（弱引用）：同步到达时判断页面是否还挂在树上。 */
    private static volatile java.lang.ref.WeakReference<SceneNode> pageRootRef;
    /** 当前视图的重建闭包（列表/会话/查看器各自注册）。 */
    private static volatile Runnable rebuildCurrent;

    private ChatUi() {}

    /**
     * 服务端同步到达后（客户端 tick）刷新还挂着的聊天页：
     * 根节点已从树上摘除（用户切走了 App / 关了手机）则不重建。
     */
    static void maybeRebuild() {
        java.lang.ref.WeakReference<SceneNode> ref = pageRootRef;
        if (ref == null) return;
        SceneNode root = ref.get();
        if (root == null || root.__getParent() == null) return;
        Runnable rebuild = rebuildCurrent;
        if (rebuild != null) PhoneUi.postAction(rebuild);
    }

    // ===================== 会话列表页 =====================

    public static SceneNode friendsPage(PhoneUi ui) {
        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setPreferredHeight(ui.contentHeight() - 20);
        page.setPadding(10);
        page.setGap(8);
        pageRootRef = new java.lang.ref.WeakReference<>(page);

        Slot slot = new Slot(ui.runtime(), ui.contentHeight() - 20);
        page.appendChild(slot.slot);
        showFriendsList(ui, slot);
        return page;
    }

    private static void showFriendsList(PhoneUi ui, Slot slot) {
        List<ChatClient.Row> friends = ChatClient.friends;
        List<ChatClient.Row> requests = ChatClient.requests;

        rebuildCurrent = () -> showFriendsList(ui, slot);

        SceneNode list = scrollColumn(ui);
        list.appendChild(PhoneUi.title(StatCollector.translateToLocal("app.mcphone.chat")));

        // 好友申请（有才显示这一节）。
        if (!requests.isEmpty()) {
            list.appendChild(sectionTitle(StatCollector.translateToLocal("label.mcphone.chat_requests")));
            for (ChatClient.Row r : requests) {
                ChatClient.Row req = r;
                SceneNode card = PhoneWidgets.card(ui);
                card.setFillParentWidth(true);
                SceneNode name = PhoneWidgets.text(ui, req.name, COL_TEXT, 16);
                name.setMaxTextWidth(ui.panelWidth() - 140);
                card.appendChild(name);
                SceneNode actions = PhoneWidgets.row(ui);
                PhoneWidgets.primaryButton(ui, actions,
                    StatCollector.translateToLocal("btn.mcphone.chat_accept"), () -> {
                        NetworkHandler.sendToServer(new NetworkHandler.ChatFriendAction(1, req.uuid));
                    });
                PhoneWidgets.button(ui, actions,
                    StatCollector.translateToLocal("btn.mcphone.chat_deny"), () -> {
                        NetworkHandler.sendToServer(new NetworkHandler.ChatFriendAction(2, req.uuid));
                    });
                card.appendChild(actions);
                list.appendChild(card);
            }
        }

        // 好友会话列表。
        if (friends.isEmpty()) {
            list.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.chat_no_friends")));
        }
        for (ChatClient.Row f : friends) {
            ChatClient.Row friend = f;
            SceneNode card = PhoneWidgets.card(ui);
            card.setFillParentWidth(true);
            card.setBackgroundColor(friend.unread > 0 ? 0x44FFFFFF : COL_PANEL);

            SceneNode top = PhoneWidgets.row(ui);
            top.setFillParentWidth(true);
            SceneNode name = PhoneWidgets.text(ui, friend.name, COL_TEXT, 16);
            name.setMaxTextWidth(ui.panelWidth() - 150);
            name.setHitTestable(false);
            top.appendChild(name);
            top.appendChild(PhoneWidgets.text(ui, friend.online
                ? StatCollector.translateToLocal("label.mcphone.chat_online")
                : StatCollector.translateToLocal("label.mcphone.chat_offline"),
                friend.online ? COL_ONLINE : COL_MUTED, 12));
            SceneNode sp = PhoneWidgets.spacer(ui);
            top.appendChild(sp);
            if (friend.unread > 0) {
                SceneNode badge = PhoneWidgets.text(ui, String.valueOf(friend.unread), 0xFF6FB2E8, 15);
                top.appendChild(badge);
            }
            card.appendChild(top);

            String preview = friend.preview;
            if ("[IMG]".equals(preview)) {
                preview = StatCollector.translateToLocal("msg.mcphone.chat_image_preview");
            }
            if (!preview.isEmpty()) {
                SceneNode prev = PhoneWidgets.text(ui, preview, COL_MUTED, 13);
                prev.setMaxTextWidth(ui.panelWidth() - 60);
                card.appendChild(prev);
            }

            SceneNode actions = PhoneWidgets.row(ui);
            PhoneWidgets.button(ui, actions,
                StatCollector.translateToLocal("btn.mcphone.chat_tp"), () -> {
                    NetworkHandler.sendToServer(new NetworkHandler.ChatFriendAction(4, friend.uuid));
                });
            PhoneWidgets.button(ui, actions,
                StatCollector.translateToLocal("btn.mcphone.delete"), () -> {
                    NetworkHandler.sendToServer(new NetworkHandler.ChatFriendAction(3, friend.uuid));
                });
            card.appendChild(actions);

            // 点名字行进入会话（不在整卡上挂点击，避免与传送/删除按钮互相误触）。
            ui.runtime().on(top, SceneEventType.CLICK, (e, ctx) -> {
                ctx.stopPropagation();
                PhoneUi.postAction(() -> openConversation(ui, slot, friend.uuid, friend.name));
            });
            list.appendChild(card);
        }

        PhoneWidgets.button(ui, list,
            StatCollector.translateToLocal("label.mcphone.chat_add"), () -> showAddFriend(ui, slot));
        slot.show(list);
    }

    private static void showAddFriend(PhoneUi ui, Slot slot) {
        List<ChatClient.Row> addable = ChatClient.addable;
        rebuildCurrent = () -> showAddFriend(ui, slot);

        SceneNode list = scrollColumn(ui);
        list.appendChild(PhoneUi.title(StatCollector.translateToLocal("label.mcphone.chat_add")));
        PhoneWidgets.button(ui, list, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showFriendsList(ui, slot));

        if (addable.isEmpty()) {
            list.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.chat_no_players")));
        }
        for (ChatClient.Row p : addable) {
            ChatClient.Row player = p;
            SceneNode row = PhoneWidgets.row(ui);
            row.setFillParentWidth(true);
            row.setPadding(6, 6, 6, 6);
            row.setCornerRadius(6);
            row.setBackgroundColor(COL_PANEL);
            SceneNode name = PhoneWidgets.text(ui, player.name, COL_TEXT, 15);
            name.setMaxTextWidth(ui.panelWidth() - 140);
            name.setHitTestable(false);
            row.appendChild(name);
            SceneNode sp = PhoneWidgets.spacer(ui);
            row.appendChild(sp);
            PhoneWidgets.primaryButton(ui, row,
                StatCollector.translateToLocal("btn.mcphone.chat_request"), () -> {
                    NetworkHandler.sendToServer(new NetworkHandler.ChatFriendAction(0, player.name));
                });
            list.appendChild(row);
        }
        slot.show(list);
    }

    // ===================== 会话页（气泡） =====================

    private static void openConversation(PhoneUi ui, Slot slot, String peer, String name) {
        // 拉历史 + 标已读只在"用户打开"时发一次；重建（同步推送）走纯渲染的 showConversation。
        ChatClient.pullHistory(peer);
        ChatClient.markRead(peer);
        showConversation(ui, slot, peer, name);
    }

    private static void showConversation(PhoneUi ui, Slot slot, final String peer, String name) {
        List<ChatClient.MsgView> msgs = ChatClient.historyOf(peer);
        rebuildCurrent = () -> showConversation(ui, slot, peer, name);

        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setPreferredHeight(ui.contentHeight() - 20);
        page.setPadding(10);
        page.setGap(8);

        SceneNode head = PhoneWidgets.row(ui);
        PhoneWidgets.button(ui, head, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showFriendsList(ui, slot));
        SceneNode title = PhoneWidgets.text(ui, name, COL_TEXT, 18);
        title.setMaxTextWidth(ui.panelWidth() - 160);
        head.appendChild(title);
        page.appendChild(head);

        // 消息区：显式高度（页高 - 标题行 - 输入行），滚动（踩坑 #1：不依赖 grow）。
        int msgH = Math.max(120, ui.contentHeight() - 20 - 40 - 64);
        SceneNode list = SceneNode.column();
        list.setFillParentWidth(true);
        list.setPreferredHeight(msgH);
        list.setPadding(6);
        list.setGap(6);
        list.setScrollable(true);
        list.setClipChildren(true);
        club.heiqi.uilib.ui.scene.runtime.SceneScrolls.attach(ui.runtime(), list);

        if (msgs.isEmpty()) {
            list.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.chat_no_msg")));
        }
        boolean lastWasSelf = false;
        for (int i = 0; i < msgs.size(); i++) {
            ChatClient.MsgView m = msgs.get(i);
            lastWasSelf = m.self;
            list.appendChild(bubble(ui, slot, peer, m, i == msgs.size() - 1));
        }
        page.appendChild(list);

        // 输入行：受控输入（Signal 写回，踩坑 #4）。
        Signal<String> input = Signal.create("");
        SceneNode inputRow = PhoneWidgets.row(ui);
        inputRow.setFillParentWidth(true);
        SceneNode field = ui.runtime()
            .mount(inputRow, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                input, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                StatCollector.translateToLocal("label.mcphone.chat_type"), 200,
                club.heiqi.uilib.ui.scene.control.SceneInputType.TEXT, input::set)))
            .getRoot();
        field.setPreferredWidth(ui.panelWidth() - 190);
        field.setFontSize(PhoneUi.fs(16));
        PhoneWidgets.primaryButton(ui, inputRow, StatCollector.translateToLocal("btn.mcphone.chat_send"),
            () -> {
                String text = input.get();
                if (text == null || text.trim().isEmpty()) return;
                ChatClient.sendText(peer, text);
                input.set("");
            });
        PhoneWidgets.button(ui, inputRow, StatCollector.translateToLocal("btn.mcphone.chat_photo"),
            () -> showPhotoPicker(ui, slot, peer, name));
        page.appendChild(inputRow);
        slot.show(page);
    }

    /** 一条气泡消息：自己靠右（蓝底）、对方靠左（面板底）；图片消息显示缩略图。 */
    private static SceneNode bubble(PhoneUi ui, Slot slot, String peer, ChatClient.MsgView m, boolean isLast) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        SceneNode sp = SceneNode.column();
        sp.setFlexGrow(1);
        sp.setHitTestable(false);

        SceneNode bubble = SceneNode.column();
        bubble.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        bubble.setGap(2);
        bubble.setPadding(8, 8, 8, 8);
        bubble.setCornerRadius(10);
        bubble.setBackgroundColor(m.self ? COL_BUBBLE_SELF : COL_PANEL);

        if (m.image) {
            SceneNode thumb = SceneNode.row();
            thumb.setWidthSizing(SceneNode.WidthSizing.SHRINK);
            int tw = Math.min(ui.panelWidth() / 2, m.w > 0 ? Math.max(64, Math.min(m.w, 160)) : 96);
            int th = m.w > 0 && m.h > 0 ? Math.max(48, tw * m.h / m.w) : 72;
            thumb.setPreferredWidth(tw);
            thumb.setPreferredHeight(th);
            thumb.setCornerRadius(6);
            thumb.setBackgroundColor(0xFF101418);
            thumb.setClipChildren(true);
            thumb.setMainAxisAlign(MainAxisAlign.CENTER);
            thumb.setCrossAxisAlign(CrossAxisAlign.CENTER);
            BufferedImage img = ChatClient.image(peer, m.imageId);
            if (img != null) {
                thumb.setImageSource(HostImageSource.bufferedImage(img, "mcphone:chatimg/" + m.imageId));
            } else {
                SceneNode hint = PhoneWidgets.text(ui,
                    StatCollector.translateToLocal("msg.mcphone.chat_image"), COL_MUTED, 13);
                thumb.appendChild(hint);
                // 未加载：点击进入查看器时按需拉取字节。
                ui.runtime().on(thumb, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
                    ChatClient.requestImage(peer, m.imageId);
                    showImageViewer(ui, slot, peer, m);
                }));
            }
            bubble.appendChild(thumb);
            ui.runtime().on(thumb, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
                if (!ChatClient.hasImage(peer, m.imageId)) ChatClient.requestImage(peer, m.imageId);
                showImageViewer(ui, slot, peer, m);
            }));
        } else {
            SceneNode text = PhoneWidgets.text(ui, m.text,
                m.self ? 0xFFFFFFFF : COL_TEXT, 15);
            text.setMaxTextWidth((int) (ui.panelWidth() * 0.62));
            text.setHitTestable(false);
            bubble.appendChild(text);
        }

        // 时间戳：最后一气泡才显示，避免每条都重复刷屏。
        if (isLast) {
            SceneNode time = PhoneWidgets.text(ui, TIME_FMT.format(new Date(m.time)),
                m.self ? 0xAAC8D8F0 : COL_MUTED, 10);
            time.setHitTestable(false);
            bubble.appendChild(time);
        }
        // 自己的气泡在右（先弹性占位），对方的在左。
        if (m.self) {
            row.appendChild(sp);
            row.appendChild(bubble);
        } else {
            row.appendChild(bubble);
            row.appendChild(sp);
        }
        return row;
    }

    // ===================== 大图查看器 =====================

    private static void showImageViewer(PhoneUi ui, Slot slot, String peer, ChatClient.MsgView m) {
        rebuildCurrent = () -> showImageViewer(ui, slot, peer, m);

        SceneNode view = SceneNode.column();
        view.setFillParentWidth(true);
        view.setPreferredHeight(ui.contentHeight() - 20);
        view.setGap(8);
        view.setPadding(10);

        SceneNode image = SceneNode.row();
        image.setPreferredWidth(ui.panelWidth() - 24);
        image.setPreferredHeight((int) ((ui.panelWidth() - 24) * 0.75));
        image.setCornerRadius(10);
        image.setBackgroundColor(0xFF000000);
        image.setClipChildren(true);
        image.setMainAxisAlign(MainAxisAlign.CENTER);
        image.setCrossAxisAlign(CrossAxisAlign.CENTER);
        BufferedImage img = ChatClient.image(peer, m.imageId);
        if (img != null) {
            image.setImageSource(HostImageSource.bufferedImage(img, "mcphone:chatimgfull/" + m.imageId));
        } else {
            ChatClient.requestImage(peer, m.imageId);
            view.appendChild(PhoneUi.muted(StatCollector.translateToLocal("msg.mcphone.chat_loading")));
        }
        view.appendChild(image);

        PhoneWidgets.button(ui, view, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showConversation(ui, slot, peer, currentName(peer)));
        slot.show(view);
    }

    private static String currentName(String peerUuid) {
        for (ChatClient.Row r : ChatClient.friends) {
            if (r.uuid.equals(peerUuid)) return r.name;
        }
        return peerUuid;
    }

    // ===================== 相册选图发送 =====================

    private static void showPhotoPicker(PhoneUi ui, Slot slot, String peer, String name) {
        List<File> photos = PhotoStore.listPhotos();
        rebuildCurrent = () -> showPhotoPicker(ui, slot, peer, name);

        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        page.setPreferredHeight(ui.contentHeight() - 20);
        page.setGap(8);
        page.setPadding(10);

        PhoneWidgets.button(ui, page, StatCollector.translateToLocal("btn.mcphone.back"),
            () -> showConversation(ui, slot, peer, name));
        page.appendChild(PhoneUi.muted(StatCollector.translateToLocal("label.mcphone.chat_pick_photo")));

        // 显式高度的可滚动缩略图区（踩坑 #1：不依赖 grow/fill 求解）。
        SceneNode grid = SceneNode.column();
        grid.setFillParentWidth(true);
        grid.setPreferredHeight(Math.max(100, ui.contentHeight() - 96));
        grid.setPadding(12);
        grid.setGap(10);
        grid.setScrollable(true);
        grid.setClipChildren(true);
        club.heiqi.uilib.ui.scene.runtime.SceneScrolls.attach(ui.runtime(), grid);
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
                row.appendChild(photoCell(ui, slot, peer, name, photos.get(i + j), cellW, cellH));
            }
            grid.appendChild(row);
        }
        page.appendChild(grid);
        slot.show(page);
    }

    private static SceneNode photoCell(PhoneUi ui, Slot slot, String peer, String name,
                                       File photo, int w, int h) {
        SceneNode node = SceneNode.row();
        node.setPreferredWidth(w);
        node.setPreferredHeight(h);
        node.setCornerRadius(8);
        node.setBackgroundColor(0xFF101418);
        node.setBorderWidth(1);
        node.setBorderColor(COL_BORDER);
        node.setClipChildren(true);
        try {
            BufferedImage img = javax.imageio.ImageIO.read(photo);
            if (img != null) {
                node.setImageSource(HostImageSource.bufferedImage(img, "mcphone:" + photo.getName()));
            }
        } catch (Exception ignored) {
        }
        ui.runtime().on(node, SceneEventType.CLICK, (e, ctx) -> PhoneUi.postAction(() -> {
            // 压缩是 IO/CPU 活，但每张只有几 ms 量级（8KB JPEG）；失败给 toast。
            String err = ChatClient.sendPhoto(peer, photo);
            if (err != null) {
                ui.toast(StatCollector.translateToLocal(err));
            } else {
                ui.toast(StatCollector.translateToLocal("msg.mcphone.chat_sent"));
            }
            showConversation(ui, slot, peer, name);
        }));
        return node;
    }

    // ===================== 小工具 =====================

    private static SceneNode scrollColumn(PhoneUi ui) {
        SceneNode col = SceneNode.column();
        col.setFillParentWidth(true);
        col.setFillParentHeight(true);
        col.setPadding(12);
        col.setGap(10);
        col.setScrollable(true);
        col.setClipChildren(true);
        club.heiqi.uilib.ui.scene.runtime.SceneScrolls.attach(ui.runtime(), col);
        return col;
    }

    private static SceneNode sectionTitle(String text) {
        SceneNode n = new SceneNode();
        n.setText(text);
        n.setTextColor(COL_MUTED);
        n.setFontSize(PhoneUi.fs(14));
        n.setHitTestable(false);
        return n;
    }
}
