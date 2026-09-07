package com.november.mcphone.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/**
 * 已购 App 记录（按存档持久化）：key = 玩家 UUID + ':' + appId。
 * 存在主世界 MapStorage，跨维度共享、随存档保存（换服要重新买）。
 */
public class StoreWorldData extends WorldSavedData {

    private static final String NAME = "mcphone.store";

    private final Set<String> keys = new HashSet<>();

    public StoreWorldData() {
        super(NAME);
    }

    public StoreWorldData(String name) {
        super(name);
    }

    /** 主世界实例（不存在则创建）。 */
    public static StoreWorldData get(World overworld) {
        MapStorage storage = overworld.mapStorage;
        StoreWorldData data = (StoreWorldData) storage.loadData(StoreWorldData.class, NAME);
        if (data == null) {
            data = new StoreWorldData();
            storage.setData(NAME, data);
        }
        return data;
    }

    public boolean isUnlocked(UUID player, String appId) {
        return keys.contains(player.toString() + ':' + appId);
    }

    public void unlock(UUID player, String appId) {
        if (keys.add(player.toString() + ':' + appId)) markDirty();
    }

    /** 该玩家已购 App 列表（同步给客户端用）。 */
    public List<String> unlockedFor(UUID player) {
        String prefix = player.toString() + ':';
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
        }
        return out;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        keys.clear();
        // key = uuid:appId，两类成分都不含逗号，单串存取足够。
        String joined = nbt.getString("purchased");
        if (joined != null && !joined.isEmpty()) {
            for (String k : joined.split(",")) keys.add(k);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        nbt.setString("purchased", sb.toString());
    }
}
