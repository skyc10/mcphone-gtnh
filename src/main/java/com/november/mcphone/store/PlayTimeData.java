package com.november.mcphone.store;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/**
 * 玩家游玩时长（按存档持久化，服务端权威）：key = 玩家 UUID。
 *
 * <p>存主世界 MapStorage（与 {@link StoreWorldData} 同模式）：跨维度共享、随存档
 * 保存。计时只累计"在服务器里"的现实 tick（由 {@link PlayTimeTracker} 按
 * ServerTick 累加），挂在大厅/暂停不算——这是服务端能给出的最诚实口径。</p>
 *
 * <p>里程碑标记（m3/m100）由客户端提示后回包确认落盘，保证"一次性"跨会话成立。</p>
 */
public class PlayTimeData extends WorldSavedData {

    private static final String NAME = "mcphone.playtime";

    private NBTTagCompound players = new NBTTagCompound();

    public PlayTimeData() {
        super(NAME);
    }

    public PlayTimeData(String name) {
        super(name);
    }

    /** 主世界实例（不存在则创建）。 */
    public static PlayTimeData get(World overworld) {
        MapStorage storage = overworld.mapStorage;
        PlayTimeData data = (PlayTimeData) storage.loadData(PlayTimeData.class, NAME);
        if (data == null) {
            data = new PlayTimeData();
            storage.setData(NAME, data);
        }
        return data;
    }

    private NBTTagCompound player(UUID id) {
        String key = id.toString();
        NBTTagCompound tag = players.getCompoundTag(key);
        if (!players.hasKey(key)) {
            players.setTag(key, tag);
        }
        return tag;
    }

    /** 累计世界总现实 tick（周期性从内存刷入，非每 tick）。 */
    public void addTotalTicks(UUID id, long delta) {
        if (delta <= 0) return;
        NBTTagCompound tag = player(id);
        tag.setLong("total", tag.getLong("total") + delta);
        markDirty();
    }

    public long totalTicks(UUID id) {
        return player(id).getLong("total");
    }

    public boolean isMilestoneShown(UUID id, int index) {
        return player(id).getBoolean(index == 0 ? "m3" : "m100");
    }

    /** 客户端提示过某里程碑后回包落盘；返回 false 表示此前已标记（重复包幂等忽略）。 */
    public boolean markMilestoneShown(UUID id, int index) {
        String key = index == 0 ? "m3" : "m100";
        NBTTagCompound tag = player(id);
        if (tag.getBoolean(key)) return false;
        tag.setBoolean(key, true);
        markDirty();
        return true;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        // MapStorage 传进来的 tag 是本数据专用的（writeToNBT 只写了 players），直接整体读回。
        players = nbt.getCompoundTag("players");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("players", players);
    }
}
