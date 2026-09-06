package com.november.mcphone.api;

import net.minecraft.client.Minecraft;

import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.PhoneGui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCphone 附属 App 接口（公开 API）。
 *
 * <p>两种注册方式：
 * <ol>
 * <li>代码注册：你的 mod 在初始化阶段调用 {@link PhoneApi#register(IPhoneApp)}；</li>
 * <li>自动发现：在你的 mod jar 里放 {@code META-INF/services/com.november.mcphone.api.IPhoneApp}
 * 文件，内容为实现类全限定名。MCphone 在 postInit 阶段通过 ServiceLoader 收集。</li>
 * </ol>
 *
 * <p>注意：App 全部是客户端对象；联网行为请自行通过你的网络包完成，可以复用
 * {@link com.november.mcphone.net.NetworkHandler#sendToServer}。
 */
@SideOnly(Side.CLIENT)
public interface IPhoneApp {

    /** 唯一 id，小写英文。注册冲突时后注册者被忽略。 */
    String id();

    /** 显示名（本地化后的字符串，例如 StatCollector.translateToLocal 的返回值）。 */
    String displayName();

    /**
     * 在主屏应用网格里绘制图标。GL 状态：已关闭纹理、开启混合；需要贴图时自行绑定并恢复。
     * 坐标 (x, y) 为图标左上角，size 为边长（约 20px）。
     */
    void renderIcon(int x, int y, int size);

    /** 点击图标后创建 App 界面；界面渲染与交互由你实现，返回 null 表示点击无效果。 */
    AppScreen createScreen(PhoneGui gui);

    /** 是否随包内建（内建 App 不可被 ServiceLoader 重复注册）。 */
    boolean isBuiltin();

    /** 图标底色（ARGB），主屏用于统一绘制圆角底板。 */
    int iconColor();

    /** 便捷方法：供实现类在 renderIcon 里取字体等。 */
    default Minecraft mc() {
        return Minecraft.getMinecraft();
    }
}
