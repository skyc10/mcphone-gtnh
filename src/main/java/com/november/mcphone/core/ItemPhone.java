package com.november.mcphone.core;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;

import java.util.ArrayList;
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

    // ===================== 传送点（多个） =====================

    public static final String NBT_WAYPOINTS = "Waypoints";

    /** 传送点：名称 + 坐标 + 维度 + 视角。 */
    public static final class Waypoint {

        public String name;
        public double x;
        public double y;
        public double z;
        public int dim;
        public float yaw;
        public float pitch;
    }

    /** 读取全部传送点（NBT 无列表时返回空列表）。 */
    public static List<Waypoint> getWaypoints(ItemStack stack) {
        List<Waypoint> out = new ArrayList<>();
        if (!stack.hasTagCompound()) return out;
        NBTTagList list = stack.getTagCompound().getTagList(NBT_WAYPOINTS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            Waypoint w = new Waypoint();
            w.name = t.getString("Name");
            w.x = t.getDouble("X");
            w.y = t.getDouble("Y");
            w.z = t.getDouble("Z");
            w.dim = t.getInteger("Dim");
            w.yaw = t.getFloat("Yaw");
            w.pitch = t.getFloat("Pitch");
            out.add(w);
        }
        return out;
    }

    public static void setWaypoints(ItemStack stack, List<Waypoint> waypoints) {
        NBTTagCompound root = nbt(stack).tag();
        NBTTagList list = new NBTTagList();
        for (Waypoint w : waypoints) {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("Name", w.name == null ? "" : w.name);
            t.setDouble("X", w.x);
            t.setDouble("Y", w.y);
            t.setDouble("Z", w.z);
            t.setInteger("Dim", w.dim);
            t.setFloat("Yaw", w.yaw);
            t.setFloat("Pitch", w.pitch);
            list.appendTag(t);
        }
        root.setTag(NBT_WAYPOINTS, list);
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        String name = getDeviceName(stack);
        if (name != null && !name.isEmpty()) {
            tooltip.add(name);
        }
        int n = getWaypoints(stack).size();
        if (n > 0) {
            tooltip.add("§7TP: §a" + n + " §7 waypoint(s)");
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
