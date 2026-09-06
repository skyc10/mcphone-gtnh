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
}
