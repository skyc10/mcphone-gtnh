package com.november.mcphone.client;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.client.scene.PhoneScreen;
import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.core.ItemPhone;

/**
 * 每 App 快捷键路由：无 GUI 时按键 → 匹配绑定 → 打开/直达对应 App。
 *
 * <p>对齐上游 mcphone v1.9.2/v1.10.1 的语义：
 * <ul>
 * <li>{@code opensInsidePhone()==true}（页面型默认）：先开机（复用 P 键同一路径）
 * 并直接进入该 App 页面；</li>
 * <li>{@code opensInsidePhone()==false}（直达型默认）：不打开手机界面，直接调
 * {@code onActivate(ui, false)}——末影箱这类"发包后开原版容器"的 App 不会被
 * 先开机再顶掉闪一帧。</li>
 * </ul>
 * 两种都要求手机在背包（与 P 键服务端语义一致）。GUI 打开时键盘事件走
 * GuiScreen，本路由天然不触发，无需自查。</p>
 */
public final class AppHotkeys {

    private AppHotkeys() {}

    /** ClientHooks.onKeyInput 末尾调用；事件参数取自 Keyboard.getEventKey()。 */
    public static void onKeyInput(Minecraft mc, int keyCode, boolean keyState) {
        if (!keyState || keyCode == Keyboard.KEY_NONE || mc.currentScreen != null) return;
        if (mc.thePlayer == null || ClientHooks.isCameraMode()) return;
        AppHotkey pressed = AppHotkey.capture(keyCode,
            shift(), ctrl(), alt());
        for (Map.Entry<String, AppHotkey> e : AppHotkey.all().entrySet()) {
            if (!e.getValue().matches(keyCode, pressed.shift, pressed.ctrl, pressed.alt)) continue;
            IPhoneApp app = PhoneApi.byId(e.getKey());
            if (app == null || !PhoneCanvas.isAppEnabled(app.id())) continue;
            launch(mc, app);
            return;
        }
    }

    /** 执行热键：检查手机在背包 → 按打开方式分流。 */
    private static void launch(Minecraft mc, IPhoneApp app) {
        ItemStack phone = findPhone(mc);
        if (phone == null) {
            mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                StatCollector.translateToLocal("msg.mcphone.nophone")));
            return;
        }
        if (app.opensInsidePhone()) {
            // 与 P 键同路：新 PhoneUi 开机，随后直接进页。
            PhoneUi ui = new PhoneUi(phone);
            mc.displayGuiScreen(new PhoneScreen(ui));
            ui.openApp(app.id());
        } else {
            // 直达型：不 开机。onActivate 需要一个 PhoneUi（closePhone/toast 等），
            // 构建临时实例执行；若动作没把界面顶掉（没开容器/屏幕），丢弃临时实例。
            PhoneUi temp = new PhoneUi(phone);
            try {
                app.onActivate(temp, false);
            } catch (RuntimeException ex) {
                System.out.println("[mcphone] hotkey onActivate failed for " + app.id() + ": " + ex);
            }
            if (mc.currentScreen instanceof PhoneScreen) {
                return; // App 自己开了手机页/容器，临时实例即当前界面，保留。
            }
            temp.dispose();
        }
    }

    /** 与 ClientHooks.findPhone 相同的查找（主背包扫 ItemPhone）。 */
    private static ItemStack findPhone(Minecraft mc) {
        for (ItemStack s : mc.thePlayer.inventory.mainInventory) {
            if (s != null && s.getItem() instanceof ItemPhone) return s;
        }
        return null;
    }

    /** 事件时刻的物理修饰键状态。 */
    public static boolean shift() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    public static boolean ctrl() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    public static boolean alt() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    /** 绑定时若与已注册 KeyBinding 的主键相同，聊天警告一次（不阻止）。 */
    public static void warnIfConflicts(Minecraft mc, AppHotkey binding, String appId) {
        for (KeyBinding kb : registeredKeybinds()) {
            if (kb.getKeyCode() == binding.keyCode && kb.getKeyCode() != Keyboard.KEY_NONE) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    StatCollector.translateToLocalFormatted(
                        "msg.mcphone.hotkey_conflict", appId, kb.getKeyDescription())));
                return;
            }
        }
    }

    /**
     * 已注册的 KeyBinding 列表。{@code keybindArray} 是私有静态字段且运行时混淆
     * （1.7.10 srg 下名字不同），按类型反射取 KeyBinding 唯一的静态 List 字段，
     * 与映射名解耦；找不到返回空列表。
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<KeyBinding> registeredKeybinds() {
        try {
            for (java.lang.reflect.Field f : KeyBinding.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.isSynthetic()
                        || !java.util.List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                java.util.List<?> list = (java.util.List<?>) f.get(null);
                java.util.List<KeyBinding> out = new java.util.ArrayList<>();
                for (Object o : list) {
                    if (o instanceof KeyBinding) out.add((KeyBinding) o);
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable t) {
            System.err.println("[mcphone] keybind conflict check unavailable: " + t);
        }
        return java.util.Collections.emptyList();
    }
}
