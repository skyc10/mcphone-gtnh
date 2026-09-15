package com.november.mcphone;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.ForceExitWatchdog;
import com.november.mcphone.client.scene.PhoneScreen;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class ClientProxy extends CommonProxy {

    @Override
    @SideOnly(Side.CLIENT)
    public void initClientHooks() {
        ClientHooks.preInit();
        // 退出保底看门狗 v2：注册即启动的 daemon 轮询线程，检测到退出开始后
        // 按多级时间线（25s/35s）强制结束进程；-Dmcphone.exitwatchdog=false 可禁用。
        ForceExitWatchdog.register();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void initApps() {
        // 内建 App 注册必须在客户端代理里做：PhoneApi 是 @SideOnly(CLIENT)，
        // 放在 CommonProxy 会在专用服务端被裁剪导致 NoSuchMethodError。
        PhoneApi.registerBuiltins();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void postInitApps() {
        PhoneApi.loadExternalApps();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void openPhoneGui(ItemStack phone) {
        if (Minecraft.getMinecraft().currentScreen == null) {
            Minecraft.getMinecraft().displayGuiScreen(new PhoneScreen(new PhoneUi(phone)));
        }
    }
}
