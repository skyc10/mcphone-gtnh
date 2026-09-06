package com.november.mcphone.core;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;

import java.util.List;

/**
 * 手机物品。右击打开手机主界面；设备名等属性存在 ItemStack NBT 上。
 */
public class ItemPhone extends Item {

    public static final ItemPhone INSTANCE = new ItemPhone();

    public static final String NBT_DEVICE_NAME = "DeviceName";

    private ItemPhone() {
        setUnlocalizedName("mcphone.phone");
        setTextureName("mcphone:phone");
        setMaxStackSize(1);
        setNoRepair();
    }

    public static void register() {
        GameRegistry.registerItem(INSTANCE, "phone");
    }

    public static String getDeviceName(ItemStack stack) {
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey(NBT_DEVICE_NAME)) {
            return stack.getTagCompound().getString(NBT_DEVICE_NAME);
        }
        return null;
    }

    public static void setDeviceName(ItemStack stack, String name) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound().setString(NBT_DEVICE_NAME, name);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) {
            com.november.mcphone.MCphone.proxy.openPhoneGui(stack);
        }
        return stack;
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        String name = getDeviceName(stack);
        if (name != null && !name.isEmpty()) {
            tooltip.add(name);
        }
    }
}
