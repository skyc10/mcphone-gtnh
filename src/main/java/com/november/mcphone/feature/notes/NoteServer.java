package com.november.mcphone.feature.notes;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.november.mcphone.net.NetworkHandler;

/**
 * 便签服务端业务逻辑：数据存 {@link NoteWorldData}（随存档持久化），
 * 增删改后把该玩家全量便签回推（与 WaypointSync/UnlockSync 同一模式）。
 */
public final class NoteServer {

    private NoteServer() {}

    /** 处理保存/删除请求（C→S 包 12，action 0=保存 1=删除）。 */
    public static void handleSave(EntityPlayerMP player, int action, int id, String title, String body) {
        boolean changed = action == 1
            ? deleteNote(player, id)
            : saveNote(player, id, title, body);
        if (changed) syncTo(player);
    }

    /** 处理印成书请求（C→S 包 14）。 */
    public static void handlePrint(EntityPlayerMP player, int id) {
        Note note = find(player, id);
        if (note == null) return;
        if (NoteConfig.printCostsBlankBook(player.worldObj) && !consumeBlankBook(player)) {
            player.addChatMessage(new ChatComponentText(
                "§7[MC手机] §c印成书需要一本空白的书与笔。"));
            return;
        }
        NotePrinter.give(player, NotePrinter.buildBook(player, note));
        player.addChatMessage(new ChatComponentText("§7[MC手机] §a便签已印成书。"));
    }

    /** 登录/增删改后全量同步（按最近修改倒序，与便签页展示顺序一致）。 */
    public static void syncTo(EntityPlayerMP player) {
        List<Note> notes = sortedByRecent(player);
        NetworkHandler.INSTANCE.sendTo(new NetworkHandler.NoteSync(notes), player);
    }

    // ===================== 保存 / 删除 =====================

    private static boolean saveNote(EntityPlayerMP player, int id, String rawTitle, String rawBody) {
        String title = truncate(trimOneLine(rawTitle), Note.MAX_TITLE);
        String body = truncate(rawBody == null ? "" : rawBody, Note.MAX_BODY);
        NoteWorldData data = data();

        if (title.isEmpty() && body.isEmpty()) {
            return deleteNote(player, id);
        }

        List<Note> notes = data.notesOf(player.getUniqueID());
        long now = System.currentTimeMillis();

        if (id == Note.NEW_ID) {
            if (notes.size() >= Note.MAX_COUNT) {
                player.addChatMessage(new ChatComponentText(
                    "§7[MC手机] §c便签已达上限（" + Note.MAX_COUNT + " 条）。"));
                return false;
            }
            Note note = new Note(data.nextId(player.getUniqueID()), title, body);
            note.modified = now;
            notes.add(note);
            data.markDirty();
            return true;
        }

        Note existing = find(player, id);
        if (existing == null) return false; // 改不存在的 id 直接拒绝，否则客户端可指定 id 新建
        existing.title = title;
        existing.body = body;
        existing.modified = now;
        data.markDirty();
        return true;
    }

    private static boolean deleteNote(EntityPlayerMP player, int id) {
        if (id == Note.NEW_ID) return false;
        List<Note> notes = data().notesOf(player.getUniqueID());
        boolean removed = notes.removeIf(n -> n.id == id);
        if (removed) data().markDirty();
        return removed;
    }

    // ===================== 印成书成本 =====================

    /** 必须判空白：写过字的书与笔是同一种物品，不判会把玩家写的书当耗材烧掉。 */
    private static boolean consumeBlankBook(EntityPlayerMP player) {
        ItemStack[] stash = player.inventory.mainInventory;
        for (int i = 0; i < stash.length; i++) {
            ItemStack s = stash[i];
            if (s == null || s.getItem() != Items.writable_book) continue;
            // 有 pages 的书与笔不是空白书
            if (s.hasTagCompound() && s.getTagCompound().hasKey("pages")) continue;
            if (--s.stackSize <= 0) stash[i] = null;
            player.inventoryContainer.detectAndSendChanges();
            return true;
        }
        return false;
    }

    // ===================== 查询 =====================

    /** 该玩家便签按最近修改倒序（同步给客户端用）。 */
    public static List<Note> sortedByRecent(EntityPlayerMP player) {
        List<Note> out = new ArrayList<>(data().notesOf(player.getUniqueID()));
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    public static Note find(EntityPlayerMP player, int id) {
        for (Note n : data().notesOf(player.getUniqueID())) {
            if (n.id == id) return n;
        }
        return null;
    }

    /** 服务端是否已有该玩家的便签（旧数据一次性导入判断用）。 */
    public static boolean hasNotes(EntityPlayerMP player) {
        return data().hasNotes(player.getUniqueID());
    }

    private static NoteWorldData data() {
        return NoteWorldData.get(MinecraftServer.getServer().getEntityWorld());
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 标题压成单行（换行变空格），旧客户端本地文件标题可能含换行。 */
    private static String trimOneLine(String s) {
        return (s == null ? "" : s).replace('\n', ' ').replace('\r', ' ').trim();
    }
}
