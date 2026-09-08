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
 * （只读备份）。每个客户端会话只导入一次；{@link #reset()}（离开世界时调用）
 * 会把 imported 一并重置——导入只在"服务端为空"时触发，换存档/重连后重新给
 * 一次导入机会是正确语义，不会产生重复导入。</p>
 */
public final class NotesClientCache {

    private static volatile List<Note> notes = new ArrayList<>();
    /** 是否收到过服务端同步（未收到时便签页显示"正在同步"而不是空列表）。 */
    private static volatile boolean received;
    private static volatile boolean imported;

    private NotesClientCache() {}

    /**
     * 完整列表落地（客户端 tick 主线程调用；NoteSync 分批在 ClientHooks 累加，
     * 收齐才整体到达这里）。每次调用都是全量覆盖。
     */
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

    /** 离开世界时清空缓存：换存档后未重新同步前不得展示旧便签。 */
    public static void reset() {
        notes = new ArrayList<>();
        received = false;
        imported = false;
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

    /**
     * 便签同步到达后刷新：仅当便签页正开着才重建，避免同步包把用户正在编辑的
     * 页面（如主屏/其他 App 的输入状态）打掉。便签列表/编辑页都挂在 "notes"
     * 应用页内，isPageOpen("notes") 能覆盖。
     */
    private static void refreshNotesPage() {
        PhoneUi.postAction(() -> {
            PhoneUi ui = PhoneUi.ACTIVE;
            if (ui != null && ui.isPageOpen("notes")) ui.rebuildPage();
        });
    }
}
