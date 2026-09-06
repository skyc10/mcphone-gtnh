package com.november.mcphone.client.apps;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.PhoneGui;

import java.util.ArrayList;
import java.util.List;

/**
 * 内建 App 装配。
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
        list.add(settings());
        list.add(appManager());
        return list;
    }

    private static final class Def extends Widgets.BaseApp {

        private final java.util.function.Function<PhoneGui, AppScreen> factory;

        private Def(String id, String nameKey, String glyph, int color, java.util.function.Function<PhoneGui, AppScreen> factory) {
            super(id, nameKey, glyph, color);
            this.factory = factory;
        }

        @Override
        protected AppScreen create(PhoneGui gui) {
            return factory.apply(gui);
        }
    }

    public static IPhoneApp clock() {
        return new Def("clock", "app.mcphone.clock", "钟", 0xFF3E6E9E, BasicScreens.ClockScreen::new);
    }

    public static IPhoneApp weather() {
        return new Def("weather", "app.mcphone.weather", "天", 0xFF4E8E5E, BasicScreens.WeatherScreen::new);
    }

    public static IPhoneApp notes() {
        return new Def("notes", "app.mcphone.notes", "记", 0xFFB29E4E, BasicScreens.NotesScreen::new);
    }

    public static IPhoneApp enderChest() {
        return new Def("enderchest", "app.mcphone.enderchest", "箱", 0xFF6E4E9E, BasicScreens.EnderChestScreen::new);
    }

    public static IPhoneApp teleport() {
        return new Def("teleport", "app.mcphone.teleport", "传", 0xFF9E4E6E, ConnectivityScreens.TeleportScreen::new);
    }

    public static IPhoneApp ae2() {
        return new Def("ae2", "app.mcphone.ae2", "ME", 0xFF4E9EA6, ConnectivityScreens.Ae2Screen::new);
    }

    public static IPhoneApp camera() {
        return new Def("camera", "app.mcphone.camera", "拍", 0xFF50586E, MiscScreens.CameraScreen::new);
    }

    public static IPhoneApp gallery() {
        return new Def("gallery", "app.mcphone.gallery", "册", 0xFF9E6E4E, GalleryScreen.Screen::new);
    }

    public static IPhoneApp settings() {
        return new Def("settings", "app.mcphone.settings", "设", 0xFF7E7E86, MiscScreens.SettingsScreen::new);
    }

    public static IPhoneApp appManager() {
        return new Def("appmgr", "app.mcphone.appmgr", "管", 0xFF8E8E4E, MiscScreens.AppManagerScreen::new);
    }
}
