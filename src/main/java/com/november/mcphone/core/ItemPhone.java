package com.november.mcphone.core;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;

import java.util.List;

/**
 * 手机物品。右击打开手机主界面；设备名、AE2 绑定密钥与传送绑定点都在 ItemStack NBT 上。
 *
 * <p>服务端潜行 + 右击：对准 ME 安全站时绑定 AE2 密钥（AE2 集成，见 AppIntegrations）。</p>
 */
public class ItemPhone extends Item {

    public static final ItemPhone INSTANCE = new ItemPhone();

    public static final String NBT_DEVICE_NAME = "DeviceName";
    public static final String NBT_AE2_KEY = "AE2Key";
    private static final String NBT_TP_X = "TpX";
    private static final String NBT_TP_Y = "TpY";
    private static final String NBT_TP_Z = "TpZ";
    private static final String NBT_TP_DIM = "TpDim";
    private static final String NBT_TP_YAW = "TpYaw";
    private static final String NBT_TP_PITCH = "TpPitch";

    private ItemPhone() {
        setUnlocalizedName("mcphone.phone");
        setTextureName("mcphone:phone");
        setMaxStackSize(1);
        setNoRepair();
    }

    public static void register() {
        GameRegistry.registerItem(INSTANCE, "phone");
    }

    private static NBTAccessor nbt(ItemStack stack) {
        return new NBTAccessor(stack);
    }

    // ===================== 设备名 =====================

    public static String getDeviceName(ItemStack stack) {
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey(NBT_DEVICE_NAME)) {
            return stack.getTagCompound().getString(NBT_DEVICE_NAME);
        }
        return null;
    }

    public static void setDeviceName(ItemStack stack, String name) {
        nbt(stack).putString(NBT_DEVICE_NAME, name);
    }

    // ===================== AE2 密钥 =====================

    public static String getAe2Key(ItemStack stack) {
        return nbt(stack).getString(NBT_AE2_KEY);
    }

    public static void setAe2Key(ItemStack stack, String key) {
        nbt(stack).putString(NBT_AE2_KEY, key);
    }

    // ===================== 传送绑定点 =====================

    public static boolean hasTeleportTarget(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound().hasKey(NBT_TP_DIM);
    }

    public static void setTeleportTarget(ItemStack stack, double x, double y, double z,
                                         int dim, float yaw, float pitch) {
        NBTAccessor n = nbt(stack);
        n.putDouble(NBT_TP_X, x);
        n.putDouble(NBT_TP_Y, y);
        n.putDouble(NBT_TP_Z, z);
        n.putInt(NBT_TP_DIM, dim);
        n.putFloat(NBT_TP_YAW, yaw);
        n.putFloat(NBT_TP_PITCH, pitch);
    }

    public static double getTeleportX(ItemStack stack) {
        return stack.getTagCompound().getDouble(NBT_TP_X);
    }

    public static double getTeleportY(ItemStack stack) {
        return stack.getTagCompound().getDouble(NBT_TP_Y);
    }

    public static double getTeleportZ(ItemStack stack) {
        return stack.getTagCompound().getDouble(NBT_TP_Z);
    }

    public static int getTeleportDim(ItemStack stack) {
        return stack.getTagCompound().getInteger(NBT_TP_DIM);
    }

    public static float getTeleportYaw(ItemStack stack) {
        return stack.getTagCompound().getFloat(NBT_TP_YAW);
    }

    public static float getTeleportPitch(ItemStack stack) {
        return stack.getTagCompound().getFloat(NBT_TP_PITCH);
    }

    // ===================== 交互 =====================

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) {
            com.november.mcphone.MCphone.proxy.openPhoneGui(stack);
        } else if (player.isSneaking()) {
            // 潜行右击：优先尝试绑定 AE2 安全站（未装 AE2 或没对准时给聊天提示）。
            com.november.mcphone.net.AppIntegrations.bindAe2SecurityStation(
                (net.minecraft.entity.player.EntityPlayerMP) player, stack);
        }
        return stack;
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        String name = getDeviceName(stack);
        if (name != null && !name.isEmpty()) {
            tooltip.add(name);
        }
        if (hasTeleportTarget(stack)) {
            tooltip.add("§7TP: " + String.format("%.0f, %.0f, %.0f @ %d",
                getTeleportX(stack), getTeleportY(stack), getTeleportZ(stack), getTeleportDim(stack)));
        }
        String key = getAe2Key(stack);
        if (key != null && !key.isEmpty()) {
            tooltip.add("§7ME: §a✔");
        }
    }

    /** 小工具：惰性创建根 NBT。 */
    private static final class NBTAccessor {

        private final ItemStack stack;

        NBTAccessor(ItemStack stack) {
            this.stack = stack;
        }

        private NBTTagCompound tag() {
            if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
            return stack.getTagCompound();
        }

        void putString(String key, String v) {
            if (v == null) v = "";
            tag().setString(key, v);
        }

        String getString(String key) {
            return stack.hasTagCompound() && stack.getTagCompound().hasKey(key)
                ? stack.getTagCompound().getString(key) : "";
        }

        void putDouble(String key, double v) {
            tag().setDouble(key, v);
        }

        void putInt(String key, int v) {
            tag().setInteger(key, v);
        }

        void putFloat(String key, float v) {
            tag().setFloat(key, v);
        }
    }
}
