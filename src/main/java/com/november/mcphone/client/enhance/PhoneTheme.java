package com.november.mcphone.client.enhance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import com.november.mcphone.client.PhoneCanvas;

/**
 * 手机 UI 文字主题色（客户端本地，持久化到现有 settings.properties 的 textColor 键）。
 *
 * <p>预设色 = 一对（正文字色 / 次要文字色）。页面/状态栏取色走
 * {@link #text()} / {@link #muted()} 动态读取——这些是方法而非常量，
 * 换主题后 rebuildPage 即全 UI 生效，无需重开手机。</p>
 *
 * <p>与 PhoneCanvas 共用同一个 properties 文件：双方都是"读全量→改→写回"，
 * 不会互相覆盖键。</p>
 */
public final class PhoneTheme {

    /** 预设：{正文 ARGB, 次要 ARGB}。下标即存档值。 */
    private static final int[][] PRESETS = {
        {0xFFE8EDF2, 0xFFB8C4D0}, // 0 默认（浅灰白）
        {0xFFCFF3DD, 0xFF8FBFA3}, // 1 薄荷
        {0xFFCFE6FF, 0xFF8FAECF}, // 2 天蓝
        {0xFFFFE9C4, 0xFFC9A96F}, // 3 琥珀
        {0xFFFFD9E0, 0xFFC792A1}, // 4 玫瑰
        {0xFFE9F5C4, 0xFFA9BC7E}, // 5 抹茶
    };

    private static final String KEY = "textColor";

    private PhoneTheme() {}

    public static int presetCount() {
        return PRESETS.length;
    }

    /** 预设色板（设置页色块用）：正文色。 */
    public static int presetText(int index) {
        return PRESETS[clampIndex(index)][0];
    }

    /** 当前正文字色（全 UI 动态读取）。 */
    public static int text() {
        return PRESETS[clampIndex(currentPreset())][0];
    }

    /** 当前次要文字色。 */
    public static int muted() {
        return PRESETS[clampIndex(currentPreset())][1];
    }

    public static int currentPreset() {
        try {
            return clampIndex(Integer.parseInt(load().getProperty(KEY, "0").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setPreset(int index) {
        Properties p = load();
        p.setProperty(KEY, String.valueOf(clampIndex(index)));
        save(p);
    }

    private static int clampIndex(int v) {
        return Math.max(0, Math.min(PRESETS.length - 1, v));
    }

    /** 换主题后重建当前页（延迟到分发结束，避免在输入回调里改树），让新配色立即生效。 */
    public static void refreshTheme() {
        com.november.mcphone.client.scene.PhoneUi.postAction(() -> {
            com.november.mcphone.client.scene.PhoneUi ui =
                com.november.mcphone.client.scene.PhoneUi.ACTIVE;
            if (ui != null) ui.rebuildPage();
        });
    }

    // ---- settings.properties 读写（与 PhoneCanvas 同文件同口径） ----

    private static Properties load() {
        Properties p = new Properties();
        File f = new File(PhoneCanvas.baseDir(), "settings.properties");
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {}
        }
        return p;
    }

    private static void save(Properties p) {
        File f = new File(PhoneCanvas.baseDir(), "settings.properties");
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "MCphone client settings");
        } catch (IOException ignored) {}
    }
}
