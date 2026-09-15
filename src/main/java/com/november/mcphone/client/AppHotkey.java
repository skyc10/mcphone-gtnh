package com.november.mcphone.client;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.input.Keyboard;

/**
 * 每 App 快捷键的绑定记录：主键（LWJGL keyCode）+ 修饰键集合。
 *
 * <p>落盘格式（settings.properties）：{@code hotkey.<appId> = CTRL+SHIFT+K}——
 * 修饰键前缀按 SHIFT/CTRL/ALT 固定顺序，主键用 {@link Keyboard#getKeyName(int)}
 * 的返回值。App 名单要等附属注册完才定，不能用 KeyBinding 注册表（它在 preInit
 * 就固定了），所以自建记录 + 每次按键事件即时匹配。</p>
 */
public final class AppHotkey {

    public final int keyCode;
    public final boolean shift;
    public final boolean ctrl;
    public final boolean alt;

    private AppHotkey(int keyCode, boolean shift, boolean ctrl, boolean alt) {
        this.keyCode = keyCode;
        this.shift = shift;
        this.ctrl = ctrl;
        this.alt = alt;
    }

    /** 修饰键前缀（大写）+ 主键名（大写），如 "CTRL+SHIFT+K"；解析失败返回 null。 */
    private static final Pattern FORMAT = Pattern.compile(
        "^(?:(SHIFT)\\+)?(?:(CTRL)\\+)?(?:(ALT)\\+)?(.+)$");

    public static AppHotkey parse(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        Matcher m = FORMAT.matcher(stored.trim().toUpperCase());
        if (!m.matches()) return null;
        String keyName = m.group(4).trim();
        int keyCode = Keyboard.getKeyIndex(keyName);
        if (keyCode == Keyboard.KEY_NONE || keyName.isEmpty()) return null;
        return new AppHotkey(keyCode,
            m.group(1) != null, m.group(2) != null, m.group(3) != null);
    }

    /** 与 {@link #parse} 互逆的落盘格式；不修饰键名大小写（按 Keyboard 约定转大写）。 */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        if (shift) sb.append("SHIFT+");
        if (ctrl) sb.append("CTRL+");
        if (alt) sb.append("ALT+");
        String name = Keyboard.getKeyName(keyCode);
        return sb.append(name == null ? "" : name.toUpperCase()).toString();
    }

    /** 设置页/管理页展示文本（与 serialize 同形，人类可读即同形）。 */
    public String displayName() {
        return serialize();
    }

    /** 按键事件匹配：keyCode 相同且修饰键与物理状态一致。 */
    public boolean matches(int keyCode, boolean shift, boolean ctrl, boolean alt) {
        return this.keyCode == keyCode && this.shift == shift
            && this.ctrl == ctrl && this.alt == alt;
    }

    /**
     * 查 id 对应 App 的已存绑定；解析失败（键名失效等）按未绑定处理。
     * key != 0 时只取主键匹配的绑定（给"重复按同键=清除"判定用）。
     */
    public static AppHotkey forApp(String appId) {
        return parse(PhoneCanvas.getHotkey(appId));
    }

    /** 全量解析（路由层用）；无效记录跳过。 */
    public static Map<String, AppHotkey> all() {
        Map<String, AppHotkey> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : PhoneCanvas.getAppHotkeys().entrySet()) {
            AppHotkey hk = parse(e.getValue());
            if (hk != null) out.put(e.getKey(), hk);
        }
        return out;
    }

    /** 从当前物理键盘状态构造（绑定捕获用；keyCode 传 Keyboard.getEventKey()）。 */
    public static AppHotkey capture(int keyCode, boolean shift, boolean ctrl, boolean alt) {
        return new AppHotkey(keyCode, shift, ctrl, alt);
    }
}
