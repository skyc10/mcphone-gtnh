package com.november.mcphone.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import com.november.mcphone.MCphone;
import com.november.mcphone.client.PhoneCanvas;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * App 注册表。内建 App 在 preInit→proxy 注册；外部 App 支持：
 * <ul>
 * <li>任何 mod 在（任意）阶段调用 {@link #register}；</li>
 * <li>postInit 时通过 ServiceLoader 自动发现（META-INF/services）。</li>
 * </ul>
 * 关闭状态持久化在客户端 .minecraft/mcphone/config.json。
 */
@SideOnly(Side.CLIENT)
public final class PhoneApi {

    private static final Map<String, IPhoneApp> APPS = new LinkedHashMap<>();
    private static final List<String> externalIds = new ArrayList<>();
    private static boolean builtinsLoaded;

    private PhoneApi() {}

    /** 注册一个 App；id 冲突时后注册者被忽略并返回 false。 */
    public static synchronized boolean register(IPhoneApp app) {
        if (app == null || APPS.containsKey(app.id())) return false;
        APPS.put(app.id(), app);
        if (builtinsLoaded) externalIds.add(app.id());
        return true;
    }

    public static synchronized Collection<IPhoneApp> apps() {
        return Collections.unmodifiableCollection(new ArrayList<>(APPS.values()));
    }

    public static synchronized IPhoneApp byId(String id) {
        return APPS.get(id);
    }

    /** 主屏网格展示顺序：跳过被用户关闭的 App。 */
    public static synchronized List<IPhoneApp> visibleApps() {
        List<IPhoneApp> out = new ArrayList<>();
        for (IPhoneApp app : APPS.values()) {
            if (PhoneCanvas.isAppEnabled(app.id())) out.add(app);
        }
        return out;
    }

    public static synchronized List<String> externalAppIds() {
        return Collections.unmodifiableList(externalIds);
    }

    /** 供 MCphone 在 preInit 调用：注册内建 App（标记 builtinsLoaded）。 */
    public static synchronized void registerBuiltins() {
        for (IPhoneApp app : com.november.mcphone.client.apps.BuiltinApps.createAll()) {
            register(app);
        }
        builtinsLoaded = true;
    }

    /** postInit：ServiceLoader 扫描第三方 jar。 */
    public static synchronized void loadExternalApps() {
        try {
            Iterator<IPhoneApp> it = ServiceLoader.load(IPhoneApp.class, MCphone.class.getClassLoader())
                .iterator();
            while (it.hasNext()) {
                try {
                    register(it.next());
                } catch (Throwable t) {
                    System.err.println("[mcphone] Failed to load addon app: " + t);
                }
            }
        } catch (Throwable t) {
            System.err.println("[mcphone] ServiceLoader scan failed: " + t);
        }
    }
}
