package com.november.mcphone.feature.notes;

import java.util.ArrayList;
import java.util.List;

import com.november.mcphone.client.NotesStore;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.net.NetworkHandler;

/**
 * 便签客户端缓存（仅客户端加载）：NoteSync 全量覆盖 + 旧本地便签一次性导入。
 *
 * <p>导入逻辑：首次收到服务端同步且服务端为空、而本地 {@code mcphone/notes/*.txt}
 * 非空时，把本地便签逐条推给服务端（id=0 由服务端分配）；本地文件保留不删
 * （只读备份）。每个客户端会话只导入一次。</p>
 */
public final class NotesClientCache {

    private static volatile List<Note> notes = new ArrayList<>();
    /** 是否收到过服务端同步（未收到时便签页显示"正在同步"而不是空列表）。 */
    private static volatile boolean received;
    private static volatile boolean imported;

    private NotesClientCache() {}

    /** NoteSync 包落地（客户端 tick 主线程调用）。 */
    public static void onSync(List<Note> list) {
        boolean first = !received;
        notes = new ArrayList<>(list);
        received = true;

        if (first && list.isEmpty() && !imported) {
            imported = true;
            importLocalNotes();
        }
        refreshNotesPage();
    }

    /** 当前客户端已知的便签列表（便签页渲染用，最近修改倒序）。 */
    public static List<Note> list() {
        return notes;
    }

    public static boolean isReceived() {
        return received;
    }

    /** 把本地文件里的旧便签推给服务端（服务端无该玩家便签时的一次性迁移）。 */
    private static void importLocalNotes() {
        List<NotesStore.Note> local = NotesStore.list();
        if (local.isEmpty()) return;
        int count = 0;
        for (NotesStore.Note n : local) {
            if (count++ >= Note.MAX_COUNT) break;
            String title = n.title == null ? "" : n.title;
            String body = n.body == null ? "" : n.body;
            if (title.trim().isEmpty() && body.trim().isEmpty()) continue;
            NetworkHandler.sendToServer(new NetworkHandler.NoteSave(
                NetworkHandler.NoteSave.ACTION_SAVE, Note.NEW_ID, title, body));
        }
        PhoneUi.postAction(() -> {
            PhoneUi ui = PhoneUi.ACTIVE;
            if (ui != null) {
                ui.toast(net.minecraft.util.StatCollector
                    .translateToLocal("msg.mcphone.notes_imported"));
            }
        });
    }

    /** 便签同步到达后刷新（同 StoreClient：当前手机页开着就重建，列表/编辑页拿最新数据）。 */
    private static void refreshNotesPage() {
        PhoneUi.postAction(() -> {
            PhoneUi ui = PhoneUi.ACTIVE;
            if (ui != null) ui.rebuildPage();
        });
    }
}
