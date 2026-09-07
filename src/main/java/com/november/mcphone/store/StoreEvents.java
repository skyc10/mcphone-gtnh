package com.november.mcphone.store;

import net.minecraft.entity.player.EntityPlayerMP;

import com.november.mcphone.net.NetworkHandler;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * 服务端玩家事件：登录时把该玩家的已购 App 全量同步给客户端，
 * 客户端商店模式 UI（锁标/购买按钮）才能正确显示。
 */
public final class StoreEvents {

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            StoreManager.syncTo((EntityPlayerMP) event.player);
        }
    }
}
