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
 * 内存中每个玩家的列表可变，改动后 {@code markDirty()}，随存档保存。</p>
 */
public class NoteWorldData extends WorldSavedData {

    private static final String NAME = "mcphone.notes";

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

    /** 该玩家的便签列表（可变，改动后调用方负责 markDirty + 同步）。 */
    public List<Note> notesOf(UUID player) {
        return players.computeIfAbsent(player, k -> new ArrayList<>());
    }

    /** 该玩家是否已有便签（旧数据一次性导入时用来判断"服务端是否为空"）。 */
    public boolean hasNotes(UUID player) {
        List<Note> list = players.get(player);
        return list != null && !list.isEmpty();
    }

    /** 该玩家下一个可用 id：取最大值加一（删中间几条后按条数生成会撞号）。 */
    public int nextId(UUID player) {
        int max = 0;
        for (Note n : notesOf(player)) max = Math.max(max, n.id);
        return max + 1;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
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

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound playersTag = new NBTTagCompound();
        for (Map.Entry<UUID, List<Note>> e : players.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            NBTTagList list = new NBTTagList();
            for (Note n : e.getValue()) list.appendTag(toNbt(n));
            playersTag.setTag(e.getKey().toString(), list);
        }
        nbt.setTag("players", playersTag);
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
