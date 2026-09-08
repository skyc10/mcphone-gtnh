package com.november.mcphone.feature.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/**
 * 便签数据（按存档持久化）：主世界 MapStorage，key = 玩家 UUID。
 * 与 {@link com.november.mcphone.store.StoreWorldData} 同一模式，独立 data 名 "mcphone.notes"。
 *
 * <p>NBT 结构：{@code { "players": { "<uuid>": [ {id,t,b,m}, ... ] } }}。
 * 内存中每个玩家的列表可变，改动后由本类内部 {@code markDirty()}，随存档保存。</p>
 *
 * <p>并发：登录同步等 C→S 包在 netty 线程处理，而存档自动保存在主线程遍历
 * {@link #writeToNBT}，两条线程可能同时碰 {@code players} 里的 ArrayList →
 * ConcurrentModificationException。所有读写在 {@link #lock} 上串行化；
 * {@link #notesOf} 返回快照拷贝，调用方拿到的列表不会被后续改动波及。</p>
 */
public class NoteWorldData extends WorldSavedData {

    private static final String NAME = "mcphone.notes";

    /** 读写同一把锁：netty 线程的增删改 vs 主线程 NBT 序列化互斥。 */
    private final Object lock = new Object();

    private final Map<UUID, List<Note>> players = new ConcurrentHashMap<>();

    public NoteWorldData() {
        super(NAME);
    }

    public NoteWorldData(String name) {
        super(name);
    }

    /** 主世界实例（不存在则创建）。 */
    public static NoteWorldData get(World overworld) {
        MapStorage storage = overworld.mapStorage;
        NoteWorldData data = (NoteWorldData) storage.loadData(NoteWorldData.class, NAME);
        if (data == null) {
            data = new NoteWorldData();
            storage.setData(NAME, data);
        }
        return data;
    }

    /** 该玩家的便签快照（拷贝；改动请走 addNote/updateNote/removeNote）。 */
    public List<Note> notesOf(UUID player) {
        synchronized (lock) {
            List<Note> list = players.get(player);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        }
    }

    /** 该玩家是否已有便签（旧数据一次性导入时用来判断"服务端是否为空"）。 */
    public boolean hasNotes(UUID player) {
        synchronized (lock) {
            List<Note> list = players.get(player);
            return list != null && !list.isEmpty();
        }
    }

    /** 该玩家下一个可用 id：取最大值加一（删中间几条后按条数生成会撞号）。调用方须持锁。 */
    private int nextIdLocked(UUID player) {
        int max = 0;
        List<Note> list = players.get(player);
        if (list != null) {
            for (Note n : list) max = Math.max(max, n.id);
        }
        return max + 1;
    }

    /**
     * 新增一条便签（条数上限校验与 id 分配在同一临界区内，不会超卖）。
     *
     * @return 新建的 Note；已达上限（{@link Note#MAX_COUNT}）时返回 null。
     */
    public Note addNote(UUID player, String title, String body) {
        synchronized (lock) {
            List<Note> list = players.get(player);
            if (list == null) {
                list = new ArrayList<>();
                players.put(player, list);
            }
            if (list.size() >= Note.MAX_COUNT) return null;
            Note note = new Note(nextIdLocked(player), title, body);
            note.modified = System.currentTimeMillis();
            list.add(note);
            markDirty();
            return note;
        }
    }

    /** 更新已有便签的标题/正文并刷新时间戳；id 不存在返回 false。 */
    public boolean updateNote(UUID player, int id, String title, String body) {
        synchronized (lock) {
            List<Note> list = players.get(player);
            if (list != null) {
                for (Note n : list) {
                    if (n.id == id) {
                        n.title = title;
                        n.body = body;
                        n.modified = System.currentTimeMillis();
                        markDirty();
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** 删除指定便签；确实删掉返回 true（并 markDirty）。 */
    public boolean removeNote(UUID player, int id) {
        synchronized (lock) {
            List<Note> list = players.get(player);
            if (list != null) {
                for (java.util.Iterator<Note> it = list.iterator(); it.hasNext();) {
                    if (it.next().id == id) {
                        it.remove();
                        markDirty();
                        return true;
                    }
                }
            }
            return false;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        synchronized (lock) {
            players.clear();
            if (!nbt.hasKey("players")) return;
            NBTTagCompound playersTag = nbt.getCompoundTag("players");
            // func_150296_c = getKeySet（MCP 1.7.10 未重映射），用 raw Set 规避签名差异。
            for (Object key : playersTag.func_150296_c()) {
                UUID id;
                try {
                    id = UUID.fromString((String) key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                List<Note> notes = new ArrayList<>();
                NBTTagList list = playersTag.getTagList((String) key, 10);
                for (int i = 0; i < list.tagCount(); i++) {
                    Note n = fromNbt(list.getCompoundTagAt(i));
                    if (n != null) notes.add(n);
                }
                players.put(id, notes);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        synchronized (lock) {
            NBTTagCompound playersTag = new NBTTagCompound();
            for (Map.Entry<UUID, List<Note>> e : players.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                NBTTagList list = new NBTTagList();
                for (Note n : e.getValue()) list.appendTag(toNbt(n));
                playersTag.setTag(e.getKey().toString(), list);
            }
            nbt.setTag("players", playersTag);
        }
    }

    private static NBTTagCompound toNbt(Note n) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("id", n.id);
        tag.setString("t", n.title);
        tag.setString("b", n.body);
        tag.setLong("m", n.modified);
        return tag;
    }

    private static Note fromNbt(NBTTagCompound tag) {
        if (!tag.hasKey("id")) return null;
        Note n = new Note(
            tag.getInteger("id"),
            tag.hasKey("t") ? tag.getString("t") : "",
            tag.hasKey("b") ? tag.getString("b") : "");
        n.modified = tag.hasKey("m") ? tag.getLong("m") : System.currentTimeMillis();
        return n;
    }
}
