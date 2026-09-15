package com.november.mcphone.client.apps;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.net.AppIntegrations;
import com.november.mcphone.net.NetworkHandler;

/**
 * 内建 App 装配。
 *
 * <p>直达型 App（点击立即执行，不打开页面）：末影箱、传送、AE2 终端、相机。
 * 传送：点击直接传送到绑定点，Shift+点击绑定当前位置（聊天回执确认）。</p>
 */
public final class BuiltinApps {

    private BuiltinApps() {}

    public static List<IPhoneApp> createAll() {
        List<IPhoneApp> list = new ArrayList<>();
        list.add(clock());
        list.add(weather());
        list.add(notes());
        list.add(enderChest());
        list.add(teleport());
        list.add(ae2());
        list.add(camera());
        list.add(gallery());
        list.add(chat());
        list.add(settings());
        list.add(appManager());
        list.add(store());
        return list;
    }

    // ===================== 页面型 =====================

    private static IPhoneApp clock() {
        return new Page("clock", "app.mcphone.clock", "钟", 0xFF3E6E9E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_clock.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.clockPage(ui);
            }
        };
    }

    private static IPhoneApp weather() {
        return new Page("weather", "app.mcphone.weather", "天", 0xFF4E8E5E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_weather.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.weatherPage(ui);
            }
        };
    }

    private static IPhoneApp notes() {
        return new Page("notes", "app.mcphone.notes", "记", 0xFFB29E4E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_notes.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.notesPage(ui);
            }
        };
    }

    private static IPhoneApp gallery() {
        return new Page("gallery", "app.mcphone.gallery", "册", 0xFF9E6E4E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_gallery.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.galleryPage(ui);
            }
        };
    }

    private static IPhoneApp chat() {
        return new Page("chat", "app.mcphone.chat", "聊", 0xFF4E9E6E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_chat.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.chatPage(ui);
            }
        };
    }

    private static IPhoneApp settings() {
        return new Page("settings", "app.mcphone.settings", "设", 0xFF7E7E86) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_settings.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.settingsPage(ui);
            }
        };
    }

    private static IPhoneApp appManager() {
        return new Page("appmgr", "app.mcphone.appmgr", "管", 0xFF8E8E4E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_appmgr.png";
            }


            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.appManagerPage(ui);
            }
        };
    }

    /** 应用商店：列出待购的内建付费 App（购买走服务端扣物校验，本 App 自身免费）。 */
    private static IPhoneApp store() {
        return new Page("store", "app.mcphone.store", "店", 0xFF2E9E4E) {

            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.storePage(ui);
            }

            @Override
            public ItemStack iconItem() {
                // 绿宝石物品图标（与末影箱图标的画法一致；取静态字段无需判空）。
                return new ItemStack(net.minecraft.init.Items.emerald);
            }
        };
    }

    // ===================== 直达型 =====================

    private static IPhoneApp enderChest() {
        return new Direct("enderchest", "app.mcphone.enderchest", "箱", 0xFF6E4E9E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_enderchest.png";
            }


            @Override
            public ItemStack iconItem() {
                // 按注册名取物品（id 130 硬编码依赖注册顺序，附属一多就漂移）。
                Item it = Item.getItemFromBlock(net.minecraft.init.Blocks.ender_chest);
                return it == null ? null : new ItemStack(it);
            }

            @Override
            public void onActivate(PhoneUi ui, boolean shift) {
                NetworkHandler.sendToServer(new NetworkHandler.OpenEnderChest());
                ui.closePhone();
            }
        };
    }

    private static IPhoneApp teleport() {
        return new Page("teleport", "app.mcphone.teleport", "传", 0xFF9E4E6E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_teleport.png";
            }

            @Override
            public club.heiqi.uilib.ui.scene.node.SceneNode createPage(PhoneUi ui) {
                return ScenePages.teleportPage(ui);
            }

            @Override
            public void onShiftActivate(PhoneUi ui) {
                // Shift+点击图标 = 快速绑定当前位置（聊天回执）。
                NetworkHandler.sendToServer(new NetworkHandler.Teleport(1, -1, ""));
                ui.closePhone();
            }
        };
    }

    private static IPhoneApp ae2() {
        return new Direct("ae2", "app.mcphone.ae2", "ME", 0xFF4E9EA6) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_ae2.png";
            }


            @Override
            public void onActivate(PhoneUi ui, boolean shift) {
                NetworkHandler.sendToServer(new NetworkHandler.OpenAe2());
                ui.closePhone();
            }
        };
    }

    private static IPhoneApp camera() {
        return new Direct("camera", "app.mcphone.camera", "拍", 0xFF50586E) {

            @Override
            public String iconTexture() {
                return "mcphone:textures/ui/app_camera.png";
            }


            @Override
            public void onActivate(PhoneUi ui, boolean shift) {
                ui.closePhone();
                ClientHooks.setCameraMode(true);
            }
        };
    }

    // ===================== 基类 =====================

    private static abstract class Base implements IPhoneApp {

        final String id;
        final String nameKey;
        final String glyph;
        final int color;

        Base(String id, String nameKey, String glyph, int color) {
            this.id = id;
            this.nameKey = nameKey;
            this.glyph = glyph;
            this.color = color;
        }

        @Override
        public final String id() {
            return id;
        }

        @Override
        public final String displayName() {
            return StatCollector.translateToLocal(nameKey);
        }

        @Override
        public final int iconColor() {
            return color;
        }

        @Override
        public final String iconGlyph() {
            return glyph;
        }

        @Override
        public final boolean isBuiltin() {
            return true;
        }
    }

    private static abstract class Page extends Base {

        Page(String id, String nameKey, String glyph, int color) {
            super(id, nameKey, glyph, color);
        }
    }

    private static abstract class Direct extends Base {

        Direct(String id, String nameKey, String glyph, int color) {
            super(id, nameKey, glyph, color);
        }

        @Override
        public final boolean isDirectAction() {
            return true;
        }
    }
}
