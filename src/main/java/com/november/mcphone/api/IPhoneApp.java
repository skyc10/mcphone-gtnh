package com.november.mcphone.api;

import net.minecraft.item.ItemStack;

import club.heiqi.uilib.ui.scene.node.SceneNode;

import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCphone 附属 App 接口（公开 API，基于 Qz-UILib 场景渲染）。
 *
 * <p>两种注册方式：
 * <ol>
 * <li>代码注册：你的 mod 在初始化阶段调用 {@link PhoneApi#register(IPhoneApp)}；</li>
 * <li>自动发现：在你的 mod jar 里放 {@code META-INF/services/com.november.mcphone.api.IPhoneApp}
 * 文件，内容为实现类全限定名。MCphone 在 postInit 阶段通过 ServiceLoader 收集。</li>
 * </ol>
 *
 * <p>App 分两类：
 * <ul>
 * <li><b>页面型</b>（默认）：点击图标后 {@link #createPage(PhoneUi)} 构建页面场景树；</li>
 * <li><b>直达型</b>（{@link #isDirectAction()} = true）：点击立即执行 {@link #onActivate}，
 * 例如拍照、开末影箱；Shift+点击会作为参数传入（用于传送的"绑定当前位置"）。</li>
 * </ul>
 *
 * <p>注意：App 全部是客户端对象；联网行为请通过你的网络包完成，可复用
 * {@link com.november.mcphone.net.NetworkHandler#sendToServer}。场景树只用 Java API
 * （{@code SceneNode.column()/row()} + 控件 {@code SceneButton/SceneLabel...} 经
 * {@code ui.runtime().mount(...)} 挂载），不要在树构建期读世界状态做动态外观——
 * 请用 {@code runtime().bind(signal, ...)} 派生。</p>
 */
@SideOnly(Side.CLIENT)
public interface IPhoneApp {

    /** 唯一 id，小写英文。注册冲突时后注册者被忽略。 */
    String id();

    /** 显示名（本地化后的字符串，例如 StatCollector.translateToLocal 的返回值）。 */
    String displayName();

    /** 图标底色（ARGB），主屏统一绘制圆角底板。 */
    int iconColor();

    /** 图标字形（1-2 个字符，用原版字体绘制在底板上）；iconItem() 非空时忽略。 */
    String iconGlyph();

    /** 可选：用物品图标代替字形图标（如末影箱方块、AE2 无线终端）。 */
    default ItemStack iconItem() {
        return null;
    }

    /** 是否点击直达（true 时点击图标立即执行 onActivate，不打开页面）。 */
    default boolean isDirectAction() {
        return false;
    }

    /** 直达型 App 点击回调；shift = Shift+点击（传送用于绑定当前位置）。 */
    default void onActivate(PhoneUi ui, boolean shift) {}

    /** 页面型 App：构建并返回页面根节点（一次性建树 + runtime 绑定）。 */
    default SceneNode createPage(PhoneUi ui) {
        return null;
    }

    /** 是否随包内建（内建 App 不可被 ServiceLoader 重复注册）。 */
    boolean isBuiltin();
}
