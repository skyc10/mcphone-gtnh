package com.november.mcphone;

import net.minecraft.item.ItemStack;

import com.november.mcphone.api.PhoneApi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class CommonProxy {

    /** 服务端无 App 概念，仅客户端在 preInit 后注册内建 App。 */
    public void initClientHooks() {}

    /** 客户端打开手机主界面。 */
    public void openPhoneGui(ItemStack phone) {}

    @SideOnly(Side.CLIENT)
    public void initApps() {
        PhoneApi.registerBuiltins();
    }

    @SideOnly(Side.CLIENT)
    public void postInitApps() {
        PhoneApi.loadExternalApps();
    }
}
