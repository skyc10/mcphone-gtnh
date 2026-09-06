package com.november.mcphone;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.scene.PhoneScreen;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class ClientProxy extends CommonProxy {

    @Override
    @SideOnly(Side.CLIENT)
    public void initClientHooks() {
        ClientHooks.preInit();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void openPhoneGui(ItemStack phone) {
        if (Minecraft.getMinecraft().currentScreen == null) {
            Minecraft.getMinecraft().displayGuiScreen(new PhoneScreen(new PhoneUi(phone)));
        }
    }
}
