package com.november.mcphone;

import net.minecraft.item.ItemStack;

/**
 * 服务端代理。注意：这里的方法都不能标 @SideOnly(CLIENT)——GTNH 构建链在专用
 * 服务端会裁掉 SideOnly(CLIENT) 成员，导致 MCphone.init 调用时 NoSuchMethodError
 * （CI 服务器 90 秒试跑崩溃的根因）。真正的客户端逻辑在 ClientProxy 覆写里。
 */
public class CommonProxy {

    /** 服务端无按键/HUD 钩子。 */
    public void initClientHooks() {}

    /** 服务端无 GUI。 */
    public void openPhoneGui(ItemStack phone) {}

    /** 服务端无 App 注册（ClientProxy 覆写做真实注册）。 */
    public void initApps() {}

    /** 服务端无外部 App 扫描。 */
    public void postInitApps() {}
}
