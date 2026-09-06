package com.november.mcphone.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import com.november.mcphone.client.PhoneCanvas;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 附属 App 的持久化键值配置：.minecraft/mcphone/appdata/&lt;appId&gt;.properties。
 *
 * <p>跨存档全局保存（客户端本地）。用法：</p>
 * <pre>{@code
 * PhoneAppConfig cfg = PhoneAppConfig.forApp("myclock");
 * boolean showSeconds = cfg.getBoolean("seconds", false);
 * cfg.putBoolean("seconds", !showSeconds);
 * }</pre>
 */
@SideOnly(Side.CLIENT)
public final class PhoneAppConfig {

    private final String appId;
    private final Properties props;
    private final File file;

    private PhoneAppConfig(String appId) {
        this.appId = appId;
        this.props = new Properties();
        File dir = new File(PhoneCanvas.baseDir(), "appdata");
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, appId + ".properties");
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {}
        }
    }

    /** 取某个 App 的配置实例（同名 App 共享同一文件，修改后立即落盘）。 */
    public static PhoneAppConfig forApp(String appId) {
        return new PhoneAppConfig(appId);
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "MCphone app config: " + appId);
        } catch (IOException ignored) {}
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public void put(String key, String value) {
        if (value == null) props.remove(key);
        else props.setProperty(key, value);
        save();
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void putInt(String key, int value) {
        put(key, String.valueOf(value));
    }

    public boolean getBoolean(String key, boolean def) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)));
    }

    public void putBoolean(String key, boolean value) {
        put(key, String.valueOf(value));
    }

    public void remove(String key) {
        props.remove(key);
        save();
    }
}
