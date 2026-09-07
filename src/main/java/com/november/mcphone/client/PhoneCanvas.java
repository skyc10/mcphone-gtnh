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
    private static final String KEY_STORE_MODE = "storeMode";

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

    // ===================== 商店模式 =====================

    /**
     * 商店模式：开启后内建付费 App 需购买解锁（附属 App 始终免费可用）。
     * 默认关闭——关闭时与旧版行为完全一致。
     */
    public static boolean isStoreMode() {
        return "true".equalsIgnoreCase(load().getProperty(KEY_STORE_MODE, "false").trim());
    }

    public static void setStoreMode(boolean on) {
        Properties p = load();
        p.setProperty(KEY_STORE_MODE, String.valueOf(on));
        save(p);
    }

    // ===================== 按钮字号（独立于全局字体缩放） =====================

    private static final String KEY_BUTTON_SCALE = "buttonScale";

    /** 按钮字号缩放百分比（50–250），100 = 跟随全局字体缩放的基准按钮字号。 */
    public static int getButtonScale() {
        try {
            return clamp(Integer.parseInt(load().getProperty(KEY_BUTTON_SCALE, "100").trim()), 50, 250);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    public static void setButtonScale(int percent) {
        Properties p = load();
        p.setProperty(KEY_BUTTON_SCALE, String.valueOf(clamp(percent, 50, 250)));
        save(p);
    }

    // ===================== App 图标顺序 =====================

    private static final String KEY_ORDER = "appOrder";

    /** 主屏图标顺序（未列出的 App 按注册顺序排在后面）。 */
    public static java.util.List<String> getAppOrder() {
        String v = load().getProperty(KEY_ORDER, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : v.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public static void setAppOrder(java.util.List<String> order) {
        Properties p = load();
        p.setProperty(KEY_ORDER, String.join(",", order));
        save(p);
    }

    /** 交换相邻两个 App 的顺序；越界静默忽略。返回新顺序。 */
    public static java.util.List<String> moveApp(String id, int delta) {
        java.util.List<String> order = getAppOrder();
        int i = order.indexOf(id);
        if (i < 0) {
            order.add(id);
            i = order.size() - 1;
        }
        int j = i + delta;
        if (j >= 0 && j < order.size()) {
            String t = order.get(i);
            order.set(i, order.get(j));
            order.set(j, t);
            setAppOrder(order);
        }
        return getAppOrder();
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

    /** 字体缩放系数（0.5–5.0），1.0 = 基准字号。 */
    public static float getFontScale() {
        try {
            float v = Float.parseFloat(load().getProperty(KEY_FONT_SCALE, "1.0").trim());
            return Math.max(0.5f, Math.min(5.0f, v));
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    public static void setFontScale(float scale) {
        Properties p = load();
        p.setProperty(KEY_FONT_SCALE, String.valueOf(Math.max(0.5f, Math.min(5.0f, scale))));
        save(p);
    }
}
