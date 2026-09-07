package com.november.mcphone.feature.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.november.mcphone.feature.chat.ChatWorldData.Msg;
import com.november.mcphone.net.NetworkHandler;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * 聊天 App 服务端业务逻辑（参考上游 ChatService/FriendGuard/TeleportService）：
 * 好友是双向的——申请 → 对方同意 → 互为好友；非好友之间不能聊天/传图/传送。
 * 时间戳由服务端盖章，不采信客户端；所有操作服务端校验（伪造包拦在服务端）。
 */
public final class ChatService {

    /** 文本消息上限（码点以下，服务端强制）。 */
    public static final int MAX_TEXT = 200;

    private ChatService() {}

    // ===================== 包入口 =====================

    /** 包 7：好友操作。op 0=申请(name) 1=接受(uuid) 2=拒绝(uuid) 3=删除(uuid) 4=传送(uuid)。 */
    public static void handleFriendAction(EntityPlayerMP self, int op, String payload) {
        switch (op) {
            case 0:
                friendRequest(self, payload == null ? "" : payload.trim());
                break;
            case 1:
                friendAccept(self, parseUuid(payload));
                break;
            case 2:
                friendDeny(self, parseUuid(payload));
                break;
            case 3:
                friendRemove(self, parseUuid(payload));
                break;
            case 4:
                friendTeleport(self, parseUuid(payload));
                break;
            default:
                break;
        }
    }

    /**
     * 包 8：消息操作。mode 0=发文本 1=拉取历史(并标已读) 2=标记已读。
     * 历史拉取只回最近 {@link ChatWorldData#MAX_MESSAGES} 条元数据（图片不含字节，
     * 字节按需经包 10 kind=2 拉取）。
     */
    public static void handleMsg(EntityPlayerMP self, int mode, String peerUuid, String text, long readTime) {
        UUID peer = parseUuid(peerUuid);
        if (peer == null || !guardFriend(self, peer)) return;
        ChatWorldData data = data();
        switch (mode) {
            case 0: {
                String body = sanitize(text);
                if (body.isEmpty()) return;
                Msg m = new Msg();
                m.sender = self.getUniqueID();
                m.time = System.currentTimeMillis();
                m.kind = 0;
                m.text = body;
                data.addMessage(self.getUniqueID(), peer, m);
                data.markRead(self.getUniqueID(), peer, m.time);
                pushMessage(self.getUniqueID(), peer, m);
                syncBoth(self.getUniqueID(), peer);
                break;
            }
            case 1: {
                data.markRead(self.getUniqueID(), peer, System.currentTimeMillis());
                NetworkHandler.INSTANCE.sendTo(
                    NetworkHandler.ChatMsgPush.history(
                        peer.toString(),
                        data.messages(self.getUniqueID(), peer),
                        self.getUniqueID()),
                    self);
                syncTo(self);
                break;
            }
            case 2: {
                data.markRead(self.getUniqueID(), peer, readTime);
                syncTo(self);
                break;
            }
            default:
                break;
        }
    }

    /** 包 11 op=0：上传图片（客户端已压缩为 JPEG；服务端二次校验大小/开关/好友）。 */
    public static void handleImageUpload(EntityPlayerMP self, String peerUuid, byte[] jpeg, int w, int h) {
        UUID peer = parseUuid(peerUuid);
        if (peer == null) return;
        if (!ChatConfig.allowChatImages()) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §c服务器已关闭聊天图片。"));
            return;
        }
        if (!guardFriend(self, peer)) return;
        int max = ChatConfig.chatImageMaxKb() * 1024;
        if (jpeg == null || jpeg.length == 0 || jpeg.length > max || w <= 0 || h <= 0
            || w > 4096 || h > 4096) {
            self.addChatMessage(new ChatComponentText(
                "§7[MC手机] §c图片发送失败：超过服务器限制（" + ChatConfig.chatImageMaxKb() + "KB）。"));
            return;
        }
        ChatWorldData data = data();
        Msg m = new Msg();
        m.sender = self.getUniqueID();
        m.time = System.currentTimeMillis();
        m.kind = 1;
        m.imageId = UUID.randomUUID().toString();
        m.w = w;
        m.h = h;
        m.data = jpeg;
        data.addMessage(self.getUniqueID(), peer, m);
        data.markRead(self.getUniqueID(), peer, m.time);
        pushMessage(self.getUniqueID(), peer, m);
        syncBoth(self.getUniqueID(), peer);
    }

    /** 包 11 op=1：按需拉取图片字节（必须是好友，且图在自己这段会话里）。 */
    public static void handleImageRequest(EntityPlayerMP self, String peerUuid, String imageId) {
        UUID peer = parseUuid(peerUuid);
        if (peer == null || imageId == null || imageId.length() > 40) return;
        if (!guardFriend(self, peer)) return;
        ChatWorldData data = data();
        Msg m = data.findImage(self.getUniqueID(), peer, imageId);
        if (m == null || m.data == null) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §e图片已过期或不存在。"));
            return;
        }
        NetworkHandler.INSTANCE.sendTo(
            NetworkHandler.ChatMsgPush.imageData(peer.toString(), m), self);
    }

    // ===================== 好友操作 =====================

    private static void friendRequest(EntityPlayerMP self, String name) {
        EntityPlayerMP target = onlinePlayerByName(name);
        if (target == null || target == self) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §e未找到该在线玩家：" + name));
            return;
        }
        ChatWorldData data = data();
        UUID a = self.getUniqueID();
        UUID b = target.getUniqueID();
        if (data.areFriends(a, b)) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §e你们已经是好友了。"));
            return;
        }
        if (!data.addRequest(a, b)) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §e已发送过申请或对方也申请了你。"));
            syncTo(self);
            return;
        }
        self.addChatMessage(new ChatComponentText(
            "§7[MC手机] §a已向 " + target.getCommandSenderName() + " 发送好友申请。"));
        target.addChatMessage(new ChatComponentText(
            "§7[MC手机] §e" + self.getCommandSenderName() + " 请求加你为好友（手机聊天 App 里处理）。"));
        syncTo(target);
        syncTo(self);
    }

    private static void friendAccept(EntityPlayerMP self, UUID from) {
        if (from == null) return;
        ChatWorldData data = data();
        if (!data.hasRequest(from, self.getUniqueID())) return;
        data.removeRequest(from, self.getUniqueID());
        if (data.addFriend(from, self.getUniqueID())) {
            EntityPlayerMP other = onlinePlayer(from);
            String myName = self.getCommandSenderName();
            self.addChatMessage(new ChatComponentText("§7[MC手机] §a已添加好友 " + data.nameOf(from) + "。"));
            if (other != null) {
                other.addChatMessage(new ChatComponentText(
                    "§7[MC手机] §a" + myName + " 已通过你的好友申请。"));
            }
        }
        syncBoth(self.getUniqueID(), from);
    }

    private static void friendDeny(EntityPlayerMP self, UUID from) {
        if (from == null) return;
        ChatWorldData data = data();
        data.removeRequest(from, self.getUniqueID());
        EntityPlayerMP other = onlinePlayer(from);
        if (other != null) other.addChatMessage(new ChatComponentText(
            "§7[MC手机] §7" + self.getCommandSenderName() + " 拒绝了你的好友申请。"));
        syncBoth(self.getUniqueID(), from);
    }

    private static void friendRemove(EntityPlayerMP self, UUID target) {
        if (target == null) return;
        ChatWorldData data = data();
        if (data.removeFriend(self.getUniqueID(), target)) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §7已删除好友 " + data.nameOf(target) + "。"));
            EntityPlayerMP other = onlinePlayer(target);
            if (other != null) other.addChatMessage(new ChatComponentText(
                "§7[MC手机] §7" + self.getCommandSenderName() + " 将你从好友列表移除。"));
        }
        syncBoth(self.getUniqueID(), target);
    }

    // ===================== 好友传送 =====================

    private static void friendTeleport(EntityPlayerMP self, UUID target) {
        if (target == null) return;
        if (!ChatConfig.allowFriendTeleport()) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §c服务器已关闭好友传送。"));
            return;
        }
        if (!guardFriend(self, target)) return;
        EntityPlayerMP other = onlinePlayer(target);
        if (other == null) {
            self.addChatMessage(new ChatComponentText("§7[MC手机] §c对方不在线，无法传送。"));
            return;
        }
        // 跨维度走下一 tick 队列（travelToDimension 后立即 setPositionAndUpdate 会被传送覆盖）。
        ChatEvents.scheduleTeleport(self.getCommandSenderName(), target.toString());
        self.addChatMessage(new ChatComponentText(
            "§7[MC手机] §a正在传送到 " + other.getCommandSenderName() + " 身边…"));
    }

    // ===================== 会话同步 =====================

    /** 全量同步会话/好友/申请/可添加玩家给一个客户端（登录、任何变更后）。 */
    public static void syncTo(EntityPlayerMP player) {
        ChatWorldData data = data();
        UUID self = player.getUniqueID();

        List<NetworkHandler.ChatConvSync.Entry> entries = new ArrayList<>();
        for (UUID peer : data.friendsOf(self)) {
            EntityPlayerMP online = onlinePlayer(peer);
            boolean isOnline = online != null;
            if (isOnline) data.rememberName(peer, online.getCommandSenderName());
            List<Msg> msgs = data.messages(self, peer);
            Msg last = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
            String preview;
            if (last == null) preview = "";
            else if (last.kind == 1) preview = "[IMG]";
            else preview = last.text;
            entries.add(new NetworkHandler.ChatConvSync.Entry(
                peer.toString(), data.nameOf(peer), isOnline, data.unread(self, peer), preview));
        }

        List<NetworkHandler.ChatConvSync.Entry> requests = new ArrayList<>();
        for (UUID from : data.requestsTo(self)) {
            EntityPlayerMP online = onlinePlayer(from);
            if (online != null) data.rememberName(from, online.getCommandSenderName());
            requests.add(new NetworkHandler.ChatConvSync.Entry(
                from.toString(), data.nameOf(from), online != null, 0, ""));
        }

        List<NetworkHandler.ChatConvSync.Entry> addable = new ArrayList<>();
        for (Object o : server().getConfigurationManager().playerEntityList) {
            if (!(o instanceof EntityPlayerMP)) continue;
            EntityPlayerMP p = (EntityPlayerMP) o;
            UUID id = p.getUniqueID();
            if (id.equals(self) || data.areFriends(self, id) || data.hasRequestEitherWay(self, id)) continue;
            addable.add(new NetworkHandler.ChatConvSync.Entry(
                id.toString(), p.getCommandSenderName(), true, 0, ""));
            if (addable.size() >= 50) break;
        }

        NetworkHandler.INSTANCE.sendTo(
            new NetworkHandler.ChatConvSync(entries, requests, addable), player);
    }

    private static void syncBoth(UUID a, UUID b) {
        EntityPlayerMP pa = onlinePlayer(a);
        if (pa != null) syncTo(pa);
        EntityPlayerMP pb = onlinePlayer(b);
        if (pb != null) syncTo(pb);
    }

    /** 新消息元数据推送给会话两端（自己一份标 self=true）。 */
    private static void pushMessage(UUID sender, UUID peer, Msg m) {
        EntityPlayerMP ps = onlinePlayer(sender);
        if (ps != null) {
            NetworkHandler.INSTANCE.sendTo(NetworkHandler.ChatMsgPush.single(peer.toString(), m, true), ps);
        }
        EntityPlayerMP pp = onlinePlayer(peer);
        if (pp != null) {
            NetworkHandler.INSTANCE.sendTo(NetworkHandler.ChatMsgPush.single(sender.toString(), m, false), pp);
        }
    }

    // ===================== 工具 =====================

    /** 好友校验：非好友操作一律拒绝并提示。 */
    private static boolean guardFriend(EntityPlayerMP self, UUID peer) {
        if (data().areFriends(self.getUniqueID(), peer)) return true;
        self.addChatMessage(new ChatComponentText("§7[MC手机] §c只能与好友聊天。"));
        return false;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        // 去掉 § 颜色码与控制字符，防聊天注入（参考上游 TextSanitizer）。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (c == '§' || c < ' ') {
                if (c == '\n') sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public static UUID parseUuid(String s) {
        if (s == null || s.isEmpty() || s.length() > 36) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static EntityPlayerMP onlinePlayer(UUID id) {
        for (Object o : server().getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP && ((EntityPlayerMP) o).getUniqueID().equals(id)) {
                return (EntityPlayerMP) o;
            }
        }
        return null;
    }

    public static EntityPlayerMP onlinePlayerByName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return null;
        for (Object o : server().getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP
                && ((EntityPlayerMP) o).getCommandSenderName().equalsIgnoreCase(name)) {
                return (EntityPlayerMP) o;
            }
        }
        return null;
    }

    private static MinecraftServer server() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

    private static ChatWorldData data() {
        World overworld = server().getEntityWorld();
        return ChatWorldData.get(overworld);
    }
}
