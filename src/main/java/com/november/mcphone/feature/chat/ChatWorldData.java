package com.november.mcphone.feature.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/**
 * 聊天 App 的存档数据（主世界 MapStorage，随存档保存，跨维度共享）：
 * 好友关系、待处理好友申请、最后已知名字、会话消息（环形上限）、已读水位。
 *
 * <p>消息按「一对玩家」存一份（pairKey 归一化，参考上游 FriendGraph.pairKey），
 * 图片消息内嵌 JPEG 字节（≤ chatImageMaxKb，默认 8KB），每个会话最多保留
 * {@link #MAX_IMAGES} 张——最坏 8KB × 20 = 160KB/会话，存档体积可控。</p>
 *
 * <p>仅服务端主线程访问（包处理器按 NetworkHandler 惯例同步执行），无需并发容器。</p>
 */
public class ChatWorldData extends WorldSavedData {

    private static final String NAME = "mcphone.chat";

    /** 每个会话最多保留的消息条数（环形）。 */
    public static final int MAX_MESSAGES = 200;
    /** 每个会话最多保留的图片消息条数（控制存档体积）。 */
    public static final int MAX_IMAGES = 20;

    /** 好友关系邻接表（双向都存，参考上游 FriendGraph）。 */
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();
    /** 待处理申请：key=收件人，value=申请人集合。 */
    private final Map<UUID, Set<UUID>> requests = new HashMap<>();
    /** 最后已知玩家名（好友列表离线时显示）。 */
    private final Map<UUID, String> names = new HashMap<>();
    /** 会话：pairKey → 消息环形队列（旧→新）。 */
    private final Map<String, Deque<Msg>> convs = new HashMap<>();
    /** 已读水位：key = 玩家UUID#pairKey → 时间戳。 */
    private final Map<String, Long> lastRead = new HashMap<>();

    public ChatWorldData() {
        super(NAME);
    }

    public ChatWorldData(String name) {
        super(name);
    }

    public static ChatWorldData get(World overworld) {
        MapStorage storage = overworld.mapStorage;
        ChatWorldData data = (ChatWorldData) storage.loadData(ChatWorldData.class, NAME);
        if (data == null) {
            data = new ChatWorldData();
            storage.setData(NAME, data);
        }
        return data;
    }

    // ===================== pairKey（参考上游 FriendGraph.pairKey） =====================

    public static String pairKey(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return sa.compareTo(sb) <= 0 ? sa + "|" + sb : sb + "|" + sa;
    }

    // ===================== 好友 =====================

    public boolean areFriends(UUID a, UUID b) {
        Set<UUID> of = friends.get(a);
        return of != null && of.contains(b);
    }

    public boolean addFriend(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b) || areFriends(a, b)) return false;
        friends.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        friends.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        markDirty();
        return true;
    }

    /** 双向解除（被删好友双向清理），返回是否真的删了。 */
    public boolean removeFriend(UUID a, UUID b) {
        if (!areFriends(a, b)) return false;
        removeOneWay(a, b);
        removeOneWay(b, a);
        markDirty();
        return true;
    }

    private void removeOneWay(UUID from, UUID to) {
        Set<UUID> of = friends.get(from);
        if (of == null) return;
        of.remove(to);
        if (of.isEmpty()) friends.remove(from);
    }

    public Set<UUID> friendsOf(UUID player) {
        Set<UUID> of = friends.get(player);
        return of == null ? new HashSet<>() : of;
    }

    // ===================== 好友申请 =====================

    /** 双向都没有待处理申请才入队，返回是否成功。 */
    public boolean addRequest(UUID from, UUID to) {
        if (from.equals(to) || hasRequestEitherWay(from, to)) return false;
        requests.computeIfAbsent(to, k -> new HashSet<>()).add(from);
        markDirty();
        return true;
    }

    public boolean hasRequest(UUID from, UUID to) {
        Set<UUID> set = requests.get(to);
        return set != null && set.contains(from);
    }

    public boolean hasRequestEitherWay(UUID a, UUID b) {
        return hasRequest(a, b) || hasRequest(b, a);
    }

    public boolean removeRequest(UUID from, UUID to) {
        Set<UUID> set = requests.get(to);
        if (set == null || !set.remove(from)) return false;
        if (set.isEmpty()) requests.remove(to);
        markDirty();
        return true;
    }

    /** 发给我的申请（申请人列表）。 */
    public List<UUID> requestsTo(UUID to) {
        Set<UUID> set = requests.get(to);
        return set == null ? new ArrayList<>() : new ArrayList<>(set);
    }

    // ===================== 名字缓存 =====================

    public void rememberName(UUID player, String name) {
        if (name == null || name.isEmpty()) return;
        if (!name.equals(names.put(player, name))) markDirty();
    }

    public String nameOf(UUID player) {
        String n = names.get(player);
        return n == null ? player.toString().substring(0, 8) : n;
    }

    // ===================== 消息 =====================

    public static final class Msg {

        public UUID sender;
        public long time;
        /** 0=文本 1=图片。 */
        public int kind;
        public String text = "";
        public String imageId = "";
        public int w;
        public int h;
        /** 图片 JPEG 字节（仅 kind=1，随消息持久化）。 */
        public byte[] data;
    }

    public void addMessage(UUID a, UUID b, Msg msg) {
        Deque<Msg> deque = convs.computeIfAbsent(pairKey(a, b), k -> new ArrayDeque<>());
        deque.addLast(msg);
        while (deque.size() > MAX_MESSAGES) deque.removeFirst();
        if (msg.kind == 1) {
            // 图片条数上限：挤掉最旧的图片消息（字节随消息一起释放）。
            int images = 0;
            for (Msg m : deque) if (m.kind == 1) images++;
            while (images > MAX_IMAGES) {
                for (Msg m : deque) {
                    if (m.kind == 1) {
                        deque.remove(m);
                        images--;
                        break;
                    }
                }
            }
        }
        markDirty();
    }

    /** 会话消息快照（旧→新）；无会话返回空表。 */
    public List<Msg> messages(UUID a, UUID b) {
        Deque<Msg> deque = convs.get(pairKey(a, b));
        return deque == null ? new ArrayList<>() : new ArrayList<>(deque);
    }

    /** 会话里是否有这张图（图片请求鉴权用）。 */
    public boolean hasImage(UUID a, UUID b, String imageId) {
        for (Msg m : messages(a, b)) {
            if (m.kind == 1 && m.imageId.equals(imageId)) return true;
        }
        return false;
    }

    public Msg findImage(UUID a, UUID b, String imageId) {
        for (Msg m : messages(a, b)) {
            if (m.kind == 1 && m.imageId.equals(imageId)) return m;
        }
        return null;
    }

    // ===================== 已读水位 / 未读数 =====================

    public void markRead(UUID self, UUID peer, long time) {
        String key = self + "#" + pairKey(self, peer);
        Long old = lastRead.get(key);
        if (old == null || old.longValue() < time) {
            lastRead.put(key, time);
            markDirty();
        }
    }

    private long readAt(UUID self, UUID peer) {
        Long t = lastRead.get(self + "#" + pairKey(self, peer));
        return t == null ? 0L : t.longValue();
    }

    /** peer 发来、晚于我已读水位的消息数（封顶 99 显示用）。 */
    public int unread(UUID self, UUID peer) {
        int n = 0;
        for (Msg m : messages(self, peer)) {
            if (m.sender.equals(peer) && m.time > readAt(self, peer)) n++;
        }
        return Math.min(n, 99);
    }

    // ===================== NBT =====================

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        friends.clear();
        requests.clear();
        names.clear();
        convs.clear();
        lastRead.clear();

        for (String k : stringList(nbt, "friends")) {
            int sep = k.indexOf('|');
            if (sep <= 0) continue;
            try {
                UUID a = UUID.fromString(k.substring(0, sep));
                UUID b = UUID.fromString(k.substring(sep + 1));
                friends.computeIfAbsent(a, x -> new HashSet<>()).add(b);
                friends.computeIfAbsent(b, x -> new HashSet<>()).add(a);
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String k : stringList(nbt, "requests")) {
            int sep = k.indexOf('|');
            if (sep <= 0) continue;
            try {
                requests.computeIfAbsent(
                    UUID.fromString(k.substring(sep + 1)), x -> new HashSet<>())
                    .add(UUID.fromString(k.substring(0, sep)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String k : stringList(nbt, "names")) {
            int sep = k.indexOf('|');
            if (sep <= 0) continue;
            try {
                names.put(UUID.fromString(k.substring(0, sep)), k.substring(sep + 1));
            } catch (IllegalArgumentException ignored) {
            }
        }
        NBTTagList convList = nbt.getTagList("convs", 10);
        for (int i = 0; i < convList.tagCount(); i++) {
            NBTTagCompound c = convList.getCompoundTagAt(i);
            NBTTagList msgList = c.getTagList("msgs", 10);
            Deque<Msg> deque = new ArrayDeque<>();
            for (int j = 0; j < msgList.tagCount(); j++) {
                Msg m = new Msg();
                NBTTagCompound mt = msgList.getCompoundTagAt(j);
                try {
                    m.sender = UUID.fromString(mt.getString("s"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                m.time = mt.getLong("t");
                m.kind = mt.getByte("k");
                m.text = mt.getString("x");
                m.imageId = mt.getString("id");
                m.w = mt.getInteger("w");
                m.h = mt.getInteger("h");
                if (mt.hasKey("d")) m.data = mt.getByteArray("d");
                deque.addLast(m);
            }
            convs.put(c.getString("key"), deque);
        }
        for (String k : stringList(nbt, "lastRead")) {
            int sep = k.lastIndexOf('#');
            if (sep <= 0) continue;
            try {
                lastRead.put(k.substring(0, sep), Long.parseLong(k.substring(sep + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagList friendList = new NBTTagList();
        for (String k : pairKeys()) friendList.appendTag(new NBTTagString(k));
        nbt.setTag("friends", friendList);
        NBTTagList requestList = new NBTTagList();
        for (String k : requestKeys()) requestList.appendTag(new NBTTagString(k));
        nbt.setTag("requests", requestList);
        NBTTagList nameList = new NBTTagList();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            nameList.appendTag(new NBTTagString(e.getKey() + "|" + e.getValue()));
        }
        nbt.setTag("names", nameList);

        NBTTagList convList = new NBTTagList();
        for (Map.Entry<String, Deque<Msg>> e : convs.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("key", e.getKey());
            NBTTagList msgList = new NBTTagList();
            for (Msg m : e.getValue()) {
                NBTTagCompound mt = new NBTTagCompound();
                mt.setString("s", m.sender.toString());
                mt.setLong("t", m.time);
                mt.setByte("k", (byte) m.kind);
                mt.setString("x", m.text);
                mt.setString("id", m.imageId);
                mt.setInteger("w", m.w);
                mt.setInteger("h", m.h);
                if (m.data != null) mt.setByteArray("d", m.data);
                msgList.appendTag(mt);
            }
            c.setTag("msgs", msgList);
            convList.appendTag(c);
        }
        nbt.setTag("convs", convList);

        NBTTagList readList = new NBTTagList();
        for (Map.Entry<String, Long> e : lastRead.entrySet()) {
            readList.appendTag(new NBTTagString(e.getKey() + "#" + e.getValue()));
        }
        nbt.setTag("lastRead", readList);
    }

    private Set<String> pairKeys() {
        // TreeSet 归一化去重 + 输出稳定（参考上游 FriendGraph.toPairKeys）。
        Set<String> keys = new TreeSet<>();
        friends.forEach((self, set) -> set.forEach(other -> keys.add(pairKey(self, other))));
        return keys;
    }

    private List<String> requestKeys() {
        List<String> keys = new ArrayList<>();
        requests.forEach((to, set) -> set.forEach(from -> keys.add(from + "|" + to)));
        return keys;
    }

    private static List<String> stringList(NBTTagCompound nbt, String key) {
        List<String> out = new ArrayList<>();
        NBTTagList list = nbt.getTagList(key, 8);
        for (int i = 0; i < list.tagCount(); i++) out.add(list.getStringTagAt(i));
        return out;
    }
}
