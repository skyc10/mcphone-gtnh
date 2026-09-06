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
 * 网络通道：0=末影箱 1=AE2终端 2=传送 3=设备名。
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
                    () -> {
                        String err = AppIntegrations.openAe2Terminal(ctx.getServerHandler().playerEntity);
                        if (err != null) {
                            ctx.getServerHandler().playerEntity.addChatMessage(new net.minecraft.util.ChatComponentText(err));
                        }
                    });
                return null;
            }
        }
    }

    public static class Teleport implements IMessage {

        public Teleport() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<Teleport, IMessage> {

            @Override
            public IMessage onMessage(Teleport msg, MessageContext ctx) {
                runOnServer(ctx, () -> AppIntegrations.teleportViaCharm(ctx.getServerHandler().playerEntity));
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
}
