package com.november.mcphone.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;

import com.november.mcphone.MCphone;

/**
 * 客户端本地配置：App 开关集合。存 .minecraft/mcphone/settings.properties。
 */
public final class PhoneCanvas {

    private static final String KEY_DISABLED = "disabledApps";

    private PhoneCanvas() {}

    public static File baseDir() {
        File f = new File(Minecraft.getMinecraft().mcDataDir, MCphone.MODID);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    private static Properties load() {
        Properties p = new Properties();
        File f = new File(baseDir(), "settings.properties");
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {}
        }
        return p;
    }

    private static void save(Properties p) {
        File f = new File(baseDir(), "settings.properties");
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "MCphone client settings");
        } catch (IOException ignored) {}
    }

    public static boolean isAppEnabled(String id) {
        String v = load().getProperty(KEY_DISABLED, "");
        for (String s : v.split(",")) {
            if (s.trim().equals(id)) return false;
        }
        return true;
    }

    public static void setAppEnabled(String id, boolean enabled) {
        Properties p = load();
        String v = p.getProperty(KEY_DISABLED, "");
        List<String> ids = new java.util.ArrayList<>();
        for (String s : v.split(",")) {
            if (!s.trim().isEmpty()) ids.add(s.trim());
        }
        ids.remove(id);
        if (!enabled) ids.add(id);
        p.setProperty(KEY_DISABLED, ids.stream().collect(Collectors.joining(",")));
        save(p);
    }

    public static List<String> disabledApps() {
        String v = load().getProperty(KEY_DISABLED, "");
        return Collections.unmodifiableList(new java.util.ArrayList<>(java.util.Arrays.asList(v.split(","))));
    }

    // ===================== 显示缩放 =====================

    private static final String KEY_UI_SCALE = "uiScalePercent";
    private static final String KEY_FONT_SCALE = "fontScale";

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 界面缩放百分比（50–150），100 = 面板高按窗口高的 62% 基准。 */
    public static int getUiScalePercent() {
        try {
            return clamp(Integer.parseInt(load().getProperty(KEY_UI_SCALE, "100").trim()), 50, 150);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    public static void setUiScalePercent(int percent) {
        Properties p = load();
        p.setProperty(KEY_UI_SCALE, String.valueOf(clamp(percent, 50, 150)));
        save(p);
    }

    /** 字体缩放系数（0.7–1.6），1.0 = 基准字号。 */
    public static float getFontScale() {
        try {
            float v = Float.parseFloat(load().getProperty(KEY_FONT_SCALE, "1.0").trim());
            return Math.max(0.7f, Math.min(1.6f, v));
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    public static void setFontScale(float scale) {
        Properties p = load();
        p.setProperty(KEY_FONT_SCALE, String.valueOf(Math.max(0.7f, Math.min(1.6f, scale))));
        save(p);
    }
}
