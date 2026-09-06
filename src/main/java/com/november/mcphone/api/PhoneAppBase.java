package com.november.mcphone.api;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 附属 App 便捷基类：只填构造参数 + 覆写 createPage/onActivate 即可得到一个完整 App。
 *
 * <pre>{@code
 * public final class MyClockApp extends PhoneAppBase {
 *     public MyClockApp() { super("myclock", "app.mymod.clock", 0xFF3E6E9E); }
 *
 *     &#64;Override
 *     public SceneNode createPage(PhoneContext ctx) {
 *         SceneNode page = PhoneWidgets.scrollColumn(ctx);
 *         page.appendChild(PhoneWidgets.title(ctx, PhoneWidgets.text(ctx, ...).getText()));
 *         // ... 用 PhoneWidgets 搭页面
 *         return page;
 *     }
 * }
 * // 注册：PhoneApi.register(new MyClockApp()); 或 META-INF/services 自动发现
 * }</pre>
 */
@SideOnly(Side.CLIENT)
public abstract class PhoneAppBase implements IPhoneApp {

    private final String id;
    private final String nameKey;
    private final String glyph;
    private final int color;

    /** @param nameKey 语言文件键（displayName 自动 StatCollector 翻译）。 */
    protected PhoneAppBase(String id, String nameKey, String glyph, int color) {
        this.id = id;
        this.nameKey = nameKey;
        this.glyph = glyph;
        this.color = color;
    }

    /** 无字形版本（配合 iconTexture/iconItem 使用）。 */
    protected PhoneAppBase(String id, String nameKey, int color) {
        this(id, nameKey, "", color);
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public String displayName() {
        return StatCollector.translateToLocal(nameKey);
    }

    @Override
    public String iconGlyph() {
        return glyph;
    }

    @Override
    public int iconColor() {
        return color;
    }

    @Override
    public boolean isBuiltin() {
        return false;
    }

    /** 便捷：读本 App 的持久化配置。 */
    protected PhoneAppConfig config() {
        return PhoneAppConfig.forApp(id);
    }
}
