package com.november.mcphone;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.november.mcphone.core.ItemPhone;
import com.november.mcphone.net.NetworkHandler;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * MCphone —— GTNH 2.9.0-beta3 (MC 1.7.10 / Forge 1614) 移植版。
 *
 * 从 november521/mcphone (MC 1.21.1 NeoForge) 移植，阅读/浏览器两个 App 按
 * 上游生态缺失被舍弃，新增 AE2 终端 App 与龙研传送 App。
 *
 * 第三方 App 注册见 {@link PhoneApi}（docs/addon-api.md）。
 */
@Mod(modid = "mcphone", name = "MCphone", version = Tags.VERSION,
     acceptedMinecraftVersions = "[1.7.10]",
     dependencies = "required-after:qz_uilib@[4.8,)")
public class MCphone {

    public static final String MODID = "mcphone";

    @SidedProxy(clientSide = "com.november.mcphone.ClientProxy", serverSide = "com.november.mcphone.CommonProxy")
    public static CommonProxy proxy;

    public static CreativeTabs tab;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        tab = new CreativeTabs("mcphone") {

            @Override
            public Item getTabIconItem() {
                return ItemPhone.INSTANCE;
            }
        };
        ItemPhone.register();
        proxy.initClientHooks();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        NetworkHandler.init();
        proxy.initApps();
        // 商店：登录时同步已购 App 给客户端。PlayerLoggedInEvent 由 FML post 到
        // FMLCommonHandler.bus()（1.7.10 双总线未合一），注册到 Forge 总线永不触发。
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(new com.november.mcphone.store.StoreEvents());
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(ItemPhone.INSTANCE),
                "igi",
                "iri",
                " i ",
                'i',
                "ingotIron",
                'g',
                "paneGlass",
                'r',
                "dustRedstone"));
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInitApps();
        // AE2 在场时把手机注册为无线终端（反射，无编译期依赖）。
        com.november.mcphone.net.AppIntegrations.registerAe2WirelessHandler();
    }
}
