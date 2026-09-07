package com.november.mcphone.net;

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
 * 5=购买App(C→S) 6=解锁状态同步(S→C)。
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
}
