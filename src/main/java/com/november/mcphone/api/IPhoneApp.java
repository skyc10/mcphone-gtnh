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

    /**
     * 可选：用纹理图标（优先级最高），传入 ResourceLocation 路径字符串，
     * 例如 "mcphone:textures/ui/app_clock.png"，纹理按 128x128 解析。
     */
    default String iconTexture() {
        return null;
    }

    /** 是否点击直达（true 时点击图标立即执行 onActivate，不打开页面）。 */
    default boolean isDirectAction() {
        return false;
    }

    /** 直达型 App 点击回调；shift = Shift+点击（传送用于绑定当前位置）。 */
    default void onActivate(PhoneUi ui, boolean shift) {}

    /** 页面型 App 的 Shift+点击快捷动作（如传送的快速绑定）；默认无动作。 */
    default void onShiftActivate(PhoneUi ui) {}

    /**
     * 每 App 快捷键按下时的打开方式。
     *
     * <p>true（页面型 App 默认）：热键先把手机打开并直接进入本 App 页面，与点
     * 图标同路；false（直达型 App 默认）：热键不打开手机界面，直接调
     * {@link #onActivate(PhoneUi, boolean)}（shift 固定 false），与点图标同效——
     * 适合末影箱、传送这类"发出网络包后开原版容器"的 App，先开机再被容器顶掉
     * 会闪一帧。</p>
     *
     * <p>无论哪种取值，热键生效都要求手机在背包（服务端语义与 P 键一致）。
     * 附属如需覆盖默认行为直接重写本方法。</p>
     */
    default boolean opensInsidePhone() {
        return !isDirectAction();
    }

    /**
     * 可选：本 App 依赖（联动）的外部模组 id 列表。
     *
     * <p><b>语义是「软」的</b>：声明了对方没装，本 App 照样注册、照样能被点开——
     * 声明唯一的落点是商店的「联动App」页
     * （{@link com.november.mcphone.client.store.CompanionAppsPage}）：列一行
     * 「需要 XXX」+ 右侧实时「已装 / 未装」。也就是说它<b>不</b>参与「App 出不出现在
     * 主屏」的判断：GTNH 侧的 App 目录没有上游那套 {@code isAvailable()} /
     * {@code UNAVAILABLE} 分层，把声明塞进可用性判断会让附属 App 因为一个模组缺失
     * 而整体消失（见 {@link PhoneApi}）。</p>
     *
     * <p><b>上游对照</b>：november521/mcphone <b>v1.10.2</b>
     * {@code shared/src/main/java/com/november/mcphone/api/client/app/IPhoneApp.java}
     * 的 {@code requiredMods()} / {@code companionMods()}（返回
     * {@code List<RequiredMod>}，{@code RequiredMod(modId, displayName)}）。
     * 本侧把「硬前置 / 软联动」合并成<b>一个</b>方法：GTNH 侧没有
     * {@code isAvailable()} 与 {@code UNAVAILABLE} 目录，两者在行为上无差别，
     * 多一个方法只会多一份没人读的声明。</p>
     *
     * <p>返回值是<b>模组 id</b>（与 {@code cpw.mods.fml.common.Loader#isModLoaded(String)}
     * 用的同一个字符串，<b>大小写敏感</b>，例如 {@code "appliedenergistics2"}、
     * {@code "FMusic"}、{@code "mcphone_browser"}）。显示名<b>不</b>在这里给：
     * 要显示它的时候那个模组多半没装、根本查不到名字，写死在调用侧（联动页），
     * 与上游「displayName 要写死，别在运行时查」同一条规矩。</p>
     *
     * <p><b>二进制兼容</b>：本方法是 {@code default}，既有实现类（含已编译的附属
     * jar）无需重编译，也不会出现 {@code AbstractMethodError}——解析不到实现时
     * 回落到接口默认实现（JLS 13.5.4「新增 default 方法不破坏二进制兼容」）。
     * 老附属不声明即返回空数组＝不在联动页出现，行为与新增本方法之前逐字一致；
     * 附属若<b>已有</b>同名同参方法，类自己的实现优先，同样不冲突。</p>
     */
    default String[] requiredMods() {
        return NO_REQUIRED_MODS;
    }

    /** 空声明常量（共享只读）：{@link #requiredMods()} 的默认返回。调用方不得修改。 */
    String[] NO_REQUIRED_MODS = new String[0];

    /** 页面型 App：构建并返回页面根节点（一次性建树 + runtime 绑定）。 */
    default SceneNode createPage(PhoneUi ui) {
        return null;
    }

    /** 是否随包内建（内建 App 不可被 ServiceLoader 重复注册）。 */
    boolean isBuiltin();
}
