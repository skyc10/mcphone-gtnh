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
 *
 * <p>满编 200 条一次发送会超 1.7.10 自定义包 32767 字节上限，{@link #syncTo}
 * 按 {@value #SYNC_BATCH_NOTES} 条且 ≤ {@value #SYNC_BATCH_BYTES} 字节拆批发送
 * （线格式见 {@link NetworkHandler.NoteSync}）。</p>
 */
public final class NoteServer {

    /** 单批最大条数。 */
    private static final int SYNC_BATCH_NOTES = 8;

    /** 单批 serialized 字节上限（留足余量，32767 上限内）。 */
    private static final int SYNC_BATCH_BYTES = 24 * 1024;

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
        // getEntityWorld：印书可能在下界触发，worldObj 会读错维度目录（同 data() 的口径）。
        if (NoteConfig.printCostsBlankBook(player.getEntityWorld()) && !consumeBlankBook(player)) {
            player.addChatMessage(new ChatComponentText(
                "§7[MC手机] §c印成书需要一本空白的书与笔。"));
            return;
        }
        NotePrinter.give(player, NotePrinter.buildBook(player, note));
        player.addChatMessage(new ChatComponentText("§7[MC手机] §a便签已印成书。"));
    }

    /**
     * 登录/增删改后全量同步（按最近修改倒序，与便签页展示顺序一致）。
     * 分批发送：每批 ≤ {@value #SYNC_BATCH_NOTES} 条且序列化估计 ≤
     * {@value #SYNC_BATCH_BYTES} 字节；客户端按 offset/total 拼回完整列表。
     */
    public static void syncTo(EntityPlayerMP player) {
        List<Note> notes = sortedByRecent(player);
        int total = notes.size();
        int offset = 0;
        do {
            int bytes = 8; // count(short)+offset(int)+total(int) 线格式头部
            int end = offset;
            while (end < total
                    && end - offset < SYNC_BATCH_NOTES
                    && (end == offset || bytes + noteWireBytes(notes.get(end)) <= SYNC_BATCH_BYTES)) {
                bytes += noteWireBytes(notes.get(end));
                end++;
            }
            // 空列表也要发一条空批（total=0），客户端依赖它触发导入判断。
            NetworkHandler.INSTANCE.sendTo(
                new NetworkHandler.NoteSync(new ArrayList<>(notes.subList(offset, end)), offset, total),
                player);
            offset = end;
        } while (offset < total);
    }

    /** 一条便签的线格式字节数：id4 + title前缀2 + body前缀2 + modified8 + 内容。 */
    private static int noteWireBytes(Note n) {
        byte[] t = (n.title == null ? "" : n.title)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = (n.body == null ? "" : n.body)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return 16 + t.length + b.length;
    }

    // ===================== 保存 / 删除 =====================

    private static boolean saveNote(EntityPlayerMP player, int id, String rawTitle, String rawBody) {
        String title = truncate(trimOneLine(rawTitle), Note.MAX_TITLE);
        String body = truncate(rawBody == null ? "" : rawBody, Note.MAX_BODY);

        if (title.isEmpty() && body.isEmpty()) {
            return deleteNote(player, id);
        }

        // 上限校验 + id 分配在数据类同一临界区内完成（见 NoteWorldData.addNote）。
        if (id == Note.NEW_ID) {
            if (data().addNote(player.getUniqueID(), title, body) == null) {
                player.addChatMessage(new ChatComponentText(
                    "§7[MC手机] §c便签已达上限（" + Note.MAX_COUNT + " 条）。"));
                return false;
            }
            return true;
        }

        return data().updateNote(player.getUniqueID(), id, title, body);
    }

    private static boolean deleteNote(EntityPlayerMP player, int id) {
        if (id == Note.NEW_ID) return false;
        return data().removeNote(player.getUniqueID(), id);
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

    /** 截断到 max 字符；截断点落在代理对中间时回退一个字符，避免切出半个 emoji。 */
    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** 标题压成单行（换行变空格），旧客户端本地文件标题可能含换行。 */
    private static String trimOneLine(String s) {
        return (s == null ? "" : s).replace('\n', ' ').replace('\r', ' ').trim();
    }
}
