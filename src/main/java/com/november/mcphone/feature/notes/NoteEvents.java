package com.november.mcphone.feature.notes;

import net.minecraft.entity.player.EntityPlayerMP;

import com.november.mcphone.net.NetworkHandler;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * 服务端玩家事件：登录时把该玩家的便签全量同步给客户端。
 * 注册点在 {@link NetworkHandler#init()}（该文件是便签改造唯一允许追注册的共享文件，
 * MCphone.java 由商店任务占用，避免冲突）。
 */
public final class NoteEvents {

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            NoteServer.syncTo((EntityPlayerMP) event.player);
        }
    }
}
