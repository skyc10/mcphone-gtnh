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
        // 退出看门狗：shutdown 钩子阶段挂起（如 MCEF/JCEF 关闭阻塞）时 15s 后强制结束。
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
