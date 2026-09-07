package com.november.mcphone.net;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.november.mcphone.core.ItemPhone;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 网络通道：0=末影箱 1=AE2终端 2=传送 3=设备名 4=WaypointSync(S→C)
 * 5=购买App(C→S) 6=解锁状态同步(S→C)
 * 7=好友操作(C→S) 8=聊天消息(C→S) 9=会话/好友全量同步(S→C) 10=消息推送(S→C)
 * 11=图片上传/拉取(C→S)。
 * Teleport 语义：mode 0=传送到指定传送点 1=绑定当前位置 2=重命名 3=删除。
 */
public final class NetworkHandler {

    public static SimpleNetworkWrapper INSTANCE;

    private NetworkHandler() {}

    public static void init() {
        INSTANCE = cpw.mods.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel("mcphone");
        INSTANCE.registerMessage(OpenEnderChest.Handler.class, OpenEnderChest.class, 0, Side.SERVER);
        INSTANCE.registerMessage(OpenAe2.Handler.class, OpenAe2.class, 1, Side.SERVER);
        INSTANCE.registerMessage(Teleport.Handler.class, Teleport.class, 2, Side.SERVER);
        INSTANCE.registerMessage(SetDeviceName.Handler.class, SetDeviceName.class, 3, Side.SERVER);
        INSTANCE.registerMessage(WaypointSync.Handler.class, WaypointSync.class, 4, Side.CLIENT);
        INSTANCE.registerMessage(PurchaseApp.Handler.class, PurchaseApp.class, 5, Side.SERVER);
        INSTANCE.registerMessage(UnlockSync.Handler.class, UnlockSync.class, 6, Side.CLIENT);
        INSTANCE.registerMessage(ChatFriendAction.Handler.class, ChatFriendAction.class, 7, Side.SERVER);
        INSTANCE.registerMessage(ChatMsgSend.Handler.class, ChatMsgSend.class, 8, Side.SERVER);
        INSTANCE.registerMessage(ChatConvSync.Handler.class, ChatConvSync.class, 9, Side.CLIENT);
        INSTANCE.registerMessage(ChatMsgPush.Handler.class, ChatMsgPush.class, 10, Side.CLIENT);
        INSTANCE.registerMessage(ChatImage.Handler.class, ChatImage.class, 11, Side.SERVER);
        // 聊天：登录时全量同步会话/好友数据给客户端（Forge 总线，仅服务端触发）。
        com.november.mcphone.feature.chat.ChatEvents.register();
    }

    public static void sendToServer(IMessage msg) {
        if (INSTANCE != null) INSTANCE.sendToServer(msg);
    }

    /**
     * 1.7.10 惯例：包处理直接执行（与原版/EnderIO 等一致）。
     * 服务端动作均幂等且轻量，无跨线程可见性问题。
     */
    public static void runOnServer(MessageContext ctx, Runnable r) {
        r.run();
    }

    // ===================== 消息定义 =====================

    public static class OpenEnderChest implements IMessage {

        public OpenEnderChest() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<OpenEnderChest, IMessage> {

            @Override
            public IMessage onMessage(OpenEnderChest msg, MessageContext ctx) {
                runOnServer(ctx, () -> AppIntegrations.openEnderChest(ctx.getServerHandler().playerEntity));
                return null;
            }
        }
    }

    public static class OpenAe2 implements IMessage {

        public OpenAe2() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<OpenAe2, IMessage> {

            @Override
            public IMessage onMessage(OpenAe2 msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> AppIntegrations.openAe2Terminal(ctx.getServerHandler().playerEntity));
                return null;
            }
        }
    }

    public static class Teleport implements IMessage {

        /** 0=传送到指定点 1=绑定当前位置 2=重命名 3=删除。 */
        public int mode;
        /** mode=0/2/3 时的传送点下标；mode=1 时忽略。 */
        public int index;
        /** mode=1（可空，空则自动命名）/ mode=2 的名称。 */
        public String name = "";

        public Teleport() {}

        public Teleport(int mode, int index, String name) {
            this.mode = mode;
            this.index = index;
            this.name = name == null ? "" : name;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            mode = buf.readByte();
            index = buf.readShort();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            name = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(mode);
            buf.writeShort(index);
            buf.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static class Handler implements IMessageHandler<Teleport, IMessage> {

            @Override
            public IMessage onMessage(Teleport msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> AppIntegrations.teleportViaPhone(
                        ctx.getServerHandler().playerEntity, msg.mode, msg.index, msg.name));
                return null;
            }
        }
    }

    /** 服务端 → 客户端：传送点列表全量同步（增删改后立即刷新手机页面）。 */
    public static class WaypointSync implements IMessage {

        public java.util.List<com.november.mcphone.core.ItemPhone.Waypoint> waypoints =
            new java.util.ArrayList<>();

        public WaypointSync() {}

        public WaypointSync(java.util.List<com.november.mcphone.core.ItemPhone.Waypoint> wps) {
            this.waypoints = wps;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readShort();
            waypoints = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                com.november.mcphone.core.ItemPhone.Waypoint w =
                    new com.november.mcphone.core.ItemPhone.Waypoint();
                byte[] nb = new byte[buf.readShort()];
                buf.readBytes(nb);
                w.name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
                w.x = buf.readDouble();
                w.y = buf.readDouble();
                w.z = buf.readDouble();
                w.dim = buf.readInt();
                w.yaw = buf.readFloat();
                w.pitch = buf.readFloat();
                waypoints.add(w);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(waypoints.size());
            for (com.november.mcphone.core.ItemPhone.Waypoint w : waypoints) {
                byte[] nb = (w.name == null ? "" : w.name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buf.writeShort(nb.length);
                buf.writeBytes(nb);
                buf.writeDouble(w.x);
                buf.writeDouble(w.y);
                buf.writeDouble(w.z);
                buf.writeInt(w.dim);
                buf.writeFloat(w.yaw);
                buf.writeFloat(w.pitch);
            }
        }

        public static class Handler implements IMessageHandler<WaypointSync, IMessage> {

            @Override
            public IMessage onMessage(WaypointSync msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：先缓存，客户端 tick 中应用。
                com.november.mcphone.client.ClientHooks.pendingWaypointSync =
                    new java.util.ArrayList<>(msg.waypoints);
                return null;
            }
        }
    }

    public static class SetDeviceName implements IMessage {

        public String name = "";

        public SetDeviceName() {}

        public SetDeviceName(String name) {
            this.name = name;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int len = buf.readableBytes();
            byte[] data = new byte[len];
            buf.readBytes(data);
            name = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static class Handler implements IMessageHandler<SetDeviceName, IMessage> {

            @Override
            public IMessage onMessage(SetDeviceName msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        EntityPlayerMP p = ctx.getServerHandler().playerEntity;
                        String n = msg.name == null ? "" : msg.name.trim();
                        if (n.length() > 24) n = n.substring(0, 24);
                        ItemStack held = p.getHeldItem();
                        if (held != null && held.getItem() instanceof ItemPhone) {
                            ItemPhone.setDeviceName(held, n);
                        } else {
                            for (ItemStack s : p.inventory.mainInventory) {
                                if (s != null && s.getItem() instanceof ItemPhone) {
                                    ItemPhone.setDeviceName(s, n);
                                    break;
                                }
                            }
                        }
                    });
                return null;
            }
        }
    }

    /** 客户端 → 服务端：购买付费 App（服务端校验背包并扣物，解锁状态按存档持久化）。 */
    public static class PurchaseApp implements IMessage {

        public String appId = "";

        public PurchaseApp() {}

        public PurchaseApp(String appId) {
            this.appId = appId == null ? "" : appId;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            appId = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBytes(appId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static class Handler implements IMessageHandler<PurchaseApp, IMessage> {

            @Override
            public IMessage onMessage(PurchaseApp msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        String id = msg.appId == null ? "" : msg.appId.trim();
                        // 长度上限防御伪造包；合法性（是否付费/已购）在 StoreManager 内校验。
                        if (!id.isEmpty() && id.length() <= 64) {
                            com.november.mcphone.store.StoreManager
                                .handlePurchase(ctx.getServerHandler().playerEntity, id);
                        }
                    });
                return null;
            }
        }
    }

    /** 服务端 → 客户端：当前玩家已购 App 全量同步（登录/购买后）。 */
    public static class UnlockSync implements IMessage {

        public java.util.List<String> appIds = new java.util.ArrayList<>();

        public UnlockSync() {}

        public UnlockSync(java.util.List<String> ids) {
            this.appIds = ids;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readShort();
            appIds = new java.util.ArrayList<>(Math.min(n, 256));
            for (int i = 0; i < n; i++) {
                byte[] data = new byte[buf.readUnsignedByte()];
                buf.readBytes(data);
                appIds.add(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(appIds.size());
            for (String id : appIds) {
                byte[] data = (id == null ? "" : id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buf.writeByte(Math.min(data.length, 255));
                buf.writeBytes(data, 0, Math.min(data.length, 255));
            }
        }

        public static class Handler implements IMessageHandler<UnlockSync, IMessage> {

            @Override
            public IMessage onMessage(UnlockSync msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：先缓存，客户端 tick 中应用（同 WaypointSync）。
                com.november.mcphone.client.ClientHooks.pendingUnlockSync =
                    new java.util.ArrayList<>(msg.appIds);
                return null;
            }
        }
    }

    // ===================== 聊天 App（7-11） =====================

    /** short 长度前缀 UTF 串（1.7.10 自定义包总量须 ≤ 32KB，字符串都很短）。 */
    private static String readUtf(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] data = new byte[len];
        buf.readBytes(data);
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeUtf(ByteBuf buf, String s) {
        byte[] data = (s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(Math.min(data.length, 600));
        buf.writeBytes(data, 0, Math.min(data.length, 600));
    }

    /** 客户端 → 服务端：好友操作。op 0=申请(name) 1=接受(uuid) 2=拒绝(uuid) 3=删除(uuid) 4=传送(uuid)。 */
    public static class ChatFriendAction implements IMessage {

        public int op;
        public String payload = "";

        public ChatFriendAction() {}

        public ChatFriendAction(int op, String payload) {
            this.op = op;
            this.payload = payload == null ? "" : payload;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            op = buf.readByte();
            payload = readUtf(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(op);
            writeUtf(buf, payload);
        }

        public static class Handler implements IMessageHandler<ChatFriendAction, IMessage> {

            @Override
            public IMessage onMessage(ChatFriendAction msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.feature.chat.ChatService.handleFriendAction(
                        ctx.getServerHandler().playerEntity, msg.op, msg.payload));
                return null;
            }
        }
    }

    /**
     * 客户端 → 服务端：消息操作。mode 0=发文本(peer,text) 1=拉取历史(peer) 2=标记已读(peer,time)。
     */
    public static class ChatMsgSend implements IMessage {

        public int mode;
        public String peer = "";
        public String text = "";
        public long time;

        public ChatMsgSend() {}

        public ChatMsgSend(int mode, String peer, String text, long time) {
            this.mode = mode;
            this.peer = peer == null ? "" : peer;
            this.text = text == null ? "" : text;
            this.time = time;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            mode = buf.readByte();
            peer = readUtf(buf);
            if (mode == 0) {
                text = readUtf(buf);
            } else if (mode == 2) {
                time = buf.readLong();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(mode);
            writeUtf(buf, peer);
            if (mode == 0) {
                writeUtf(buf, text);
            } else if (mode == 2) {
                buf.writeLong(time);
            }
        }

        public static class Handler implements IMessageHandler<ChatMsgSend, IMessage> {

            @Override
            public IMessage onMessage(ChatMsgSend msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.feature.chat.ChatService.handleMsg(
                        ctx.getServerHandler().playerEntity, msg.mode, msg.peer, msg.text, msg.time));
                return null;
            }
        }
    }

    /** 服务端 → 客户端：会话/好友/申请/可添加玩家全量同步。 */
    public static class ChatConvSync implements IMessage {

        /** 一行数据：好友/申请/在线玩家三张表共用。 */
        public static class Entry {

            public final String uuid;
            public final String name;
            public final boolean online;
            public final int unread;
            public final String preview;

            public Entry(String uuid, String name, boolean online, int unread, String preview) {
                this.uuid = uuid;
                this.name = name;
                this.online = online;
                this.unread = unread;
                this.preview = preview == null ? "" : preview;
            }
        }

        public final List<Entry> entries;
        public final List<Entry> requests;
        public final List<Entry> addable;

        public ChatConvSync() {
            entries = new java.util.ArrayList<>();
            requests = new java.util.ArrayList<>();
            addable = new java.util.ArrayList<>();
        }

        public ChatConvSync(List<Entry> entries, List<Entry> requests, List<Entry> addable) {
            this.entries = entries;
            this.requests = requests;
            this.addable = addable;
        }

        private static List<Entry> readEntries(ByteBuf buf) {
            int n = buf.readUnsignedByte();
            List<Entry> out = new java.util.ArrayList<>(Math.min(n, 128));
            for (int i = 0; i < n; i++) {
                out.add(new Entry(
                    readUtf(buf), readUtf(buf), buf.readByte() != 0,
                    buf.readUnsignedByte(), readUtf(buf)));
            }
            return out;
        }

        private static void writeEntries(ByteBuf buf, List<Entry> list) {
            buf.writeByte(Math.min(list.size(), 255));
            for (Entry e : list) {
                writeUtf(buf, e.uuid);
                writeUtf(buf, e.name);
                buf.writeByte(e.online ? 1 : 0);
                buf.writeByte(Math.min(e.unread, 99));
                writeUtf(buf, e.preview);
            }
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entries.addAll(readEntries(buf));
            requests.addAll(readEntries(buf));
            addable.addAll(readEntries(buf));
        }

        @Override
        public void toBytes(ByteBuf buf) {
            writeEntries(buf, entries);
            writeEntries(buf, requests);
            writeEntries(buf, addable);
        }

        public static class Handler implements IMessageHandler<ChatConvSync, IMessage> {

            @Override
            public IMessage onMessage(ChatConvSync msg, MessageContext ctx) {
                // netty 线程：排进并发队列，客户端 tick 主线程应用（同 WaypointSync 模式）。
                com.november.mcphone.feature.chat.client.ChatClient.onConvSync(msg);
                return null;
            }
        }
    }

    /**
     * 服务端 → 客户端：消息推送。
     * kind 0=历史批（拉取响应，整表替换） 1=单条新消息（追加） 2=图片字节（按需拉取的响应）。
     */
    public static class ChatMsgPush implements IMessage {

        public static class MsgMeta {

            public boolean self;
            public long time;
            public boolean image;
            public String text = "";
            public String imageId = "";
            public int w;
            public int h;
        }

        public int kind;
        public String peer = "";
        public List<MsgMeta> messages = new java.util.ArrayList<>();
        /** kind=2 的图片字节。 */
        public byte[] data;
        public int w;
        public int h;
        public String imageId = "";

        public ChatMsgPush() {}

        public static ChatMsgPush history(String peer, List<com.november.mcphone.feature.chat.ChatWorldData.Msg> msgs, UUID self) {
            ChatMsgPush m = new ChatMsgPush();
            m.kind = 0;
            m.peer = peer;
            for (com.november.mcphone.feature.chat.ChatWorldData.Msg msg : msgs) {
                m.messages.add(meta(msg, self));
            }
            return m;
        }

        public static ChatMsgPush single(String peer, com.november.mcphone.feature.chat.ChatWorldData.Msg msg, boolean self) {
            ChatMsgPush m = new ChatMsgPush();
            m.kind = 1;
            m.peer = peer;
            MsgMeta meta = meta(msg, null);
            meta.self = self;
            m.messages.add(meta);
            if (msg.kind == 1) m.imageId = msg.imageId;
            return m;
        }

        public static ChatMsgPush imageData(String peer, com.november.mcphone.feature.chat.ChatWorldData.Msg msg) {
            ChatMsgPush m = new ChatMsgPush();
            m.kind = 2;
            m.peer = peer;
            m.imageId = msg.imageId;
            m.data = msg.data;
            m.w = msg.w;
            m.h = msg.h;
            return m;
        }

        private static MsgMeta meta(com.november.mcphone.feature.chat.ChatWorldData.Msg msg, UUID self) {
            MsgMeta meta = new MsgMeta();
            meta.time = msg.time;
            meta.image = msg.kind == 1;
            meta.text = msg.text;
            meta.imageId = msg.imageId;
            meta.w = msg.w;
            meta.h = msg.h;
            if (self != null) meta.self = msg.sender.equals(self);
            return meta;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            peer = readUtf(buf);
            if (kind == 2) {
                imageId = readUtf(buf);
                w = buf.readInt();
                h = buf.readInt();
                data = new byte[buf.readInt()];
                buf.readBytes(data);
                return;
            }
            int n = kind == 0 ? buf.readUnsignedShort() : 1;
            for (int i = 0; i < n; i++) {
                MsgMeta meta = new MsgMeta();
                meta.self = buf.readByte() != 0;
                meta.time = buf.readLong();
                meta.image = buf.readByte() != 0;
                if (meta.image) {
                    meta.imageId = readUtf(buf);
                    meta.w = buf.readInt();
                    meta.h = buf.readInt();
                } else {
                    meta.text = readUtf(buf);
                }
                messages.add(meta);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            writeUtf(buf, peer);
            if (kind == 2) {
                writeUtf(buf, imageId);
                buf.writeInt(w);
                buf.writeInt(h);
                byte[] d = data == null ? new byte[0] : data;
                buf.writeInt(d.length);
                buf.writeBytes(d);
                return;
            }
            if (kind == 0) {
                buf.writeShort(Math.min(messages.size(), 200));
            }
            for (MsgMeta meta : messages) {
                buf.writeByte(meta.self ? 1 : 0);
                buf.writeLong(meta.time);
                buf.writeByte(meta.image ? 1 : 0);
                if (meta.image) {
                    writeUtf(buf, meta.imageId);
                    buf.writeInt(meta.w);
                    buf.writeInt(meta.h);
                } else {
                    writeUtf(buf, meta.text);
                }
            }
        }

        public static class Handler implements IMessageHandler<ChatMsgPush, IMessage> {

            @Override
            public IMessage onMessage(ChatMsgPush msg, MessageContext ctx) {
                // netty 线程：排进并发队列，客户端 tick 主线程应用。
                if (msg.kind == 2) {
                    com.november.mcphone.feature.chat.client.ChatClient.onImageData(msg);
                } else {
                    com.november.mcphone.feature.chat.client.ChatClient.onMsgPush(msg);
                }
                return null;
            }
        }
    }

    /** 客户端 → 服务端：图片上传(op=0, 带 JPEG 字节)/按需拉取(op=1, 按 imageId)。 */
    public static class ChatImage implements IMessage {

        public int op;
        public String peer = "";
        public String imageId = "";
        public byte[] data;
        public int w;
        public int h;

        public ChatImage() {}

        public ChatImage(int op, String peer, String imageId, byte[] data, int w, int h) {
            this.op = op;
            this.peer = peer == null ? "" : peer;
            this.imageId = imageId == null ? "" : imageId;
            this.data = data;
            this.w = w;
            this.h = h;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            op = buf.readByte();
            peer = readUtf(buf);
            if (op == 0) {
                w = buf.readInt();
                h = buf.readInt();
                int len = buf.readInt();
                // 防伪造超大包：1.7.10 自定义包本身 ≤ 32KB，这里再按配置上限掐一道。
                if (len < 0 || len > 32 * 1024) {
                    len = 0;
                }
                data = new byte[len];
                buf.readBytes(data);
            } else {
                imageId = readUtf(buf);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(op);
            writeUtf(buf, peer);
            if (op == 0) {
                buf.writeInt(w);
                buf.writeInt(h);
                byte[] d = data == null ? new byte[0] : data;
                buf.writeInt(d.length);
                buf.writeBytes(d);
            } else {
                writeUtf(buf, imageId);
            }
        }

        public static class Handler implements IMessageHandler<ChatImage, IMessage> {

            @Override
            public IMessage onMessage(ChatImage msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                        if (msg.op == 0) {
                            com.november.mcphone.feature.chat.ChatService.handleImageUpload(
                                player, msg.peer, msg.data, msg.w, msg.h);
                        } else {
                            com.november.mcphone.feature.chat.ChatService.handleImageRequest(
                                player, msg.peer, msg.imageId);
                        }
                    });
                return null;
            }
        }
    }
}
