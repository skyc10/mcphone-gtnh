package com.november.mcphone.feature.chat;

import java.io.File;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.config.Configuration;

/**
 * 聊天 App 服务端配置（config/mcphone-chat.cfg）：仿上游 core/ServerConfig 的
 * 三个开关，但 1.7.10 没有服务端配置同步机制，因此只在服务端读、服务端拦。
 * 客户端压缩图片按默认 8KB 目标执行；若服主把上限调得更低，超限时服务端拒收。
 *
 * <p>文件位置走 {@link MinecraftServer#getFile(String)}：专用服务器解析到服务器
 * 根目录，单人集成服务器解析到 .minecraft——单人改配置天然就是本人意愿。</p>
 */
public final class ChatConfig {

    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static long lastModified;
    private static boolean allowFriendTeleport = true;
    private static boolean allowChatImages = true;
    private static int chatImageMaxKb = 8;

    private ChatConfig() {}

    public static boolean allowFriendTeleport() {
        load();
        return allowFriendTeleport;
    }

    public static boolean allowChatImages() {
        load();
        return allowChatImages;
    }

    public static int chatImageMaxKb() {
        load();
        return chatImageMaxKb;
    }

    private static void load() {
        synchronized (LOCK) {
            try {
                MinecraftServer server = MinecraftServer.getServer();
                if (server == null) return; // 未开服（主菜单等）：保持默认值。
                File file = server.getFile("config/mcphone-chat.cfg");
                long stamp = file.isFile() ? file.lastModified() : -1L;
                if (loaded && stamp == lastModified) return;
                loaded = true;
                lastModified = stamp;
                Configuration cfg = new Configuration(file, true);
                allowFriendTeleport = cfg.getBoolean(
                    "allowFriendTeleport", "gameplay", true,
                    "Allow players to teleport to an online friend from the chat app.");
                allowChatImages = cfg.getBoolean(
                    "allowChatImages", "gameplay", true,
                    "Allow sending photos from the gallery to friends in the chat app.");
                chatImageMaxKb = cfg.getInt(
                    "chatImageMaxKb", "gameplay", 8, 1, 24,
                    "Max size of one chat image in KB (client compresses JPEG to fit).");
                if (cfg.hasChanged()) cfg.save();
            } catch (Throwable t) {
                // 配置读失败不拦聊天：保持默认（全开）。
                System.err.println("[mcphone] chat config load failed: " + t);
            }
        }
    }
}
