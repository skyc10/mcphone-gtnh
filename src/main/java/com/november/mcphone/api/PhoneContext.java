package com.november.mcphone.api;

import java.util.List;

import net.minecraft.item.ItemStack;

import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

import com.november.mcphone.core.ItemPhone;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 附属 App 的运行上下文：统一暴露手机 UI 的安全操作面，
 * 附属 mod 只依赖本接口即可写 App，不必触碰 MCphone 内部类。
 *
 * <p>由手机宿主（PhoneUi）实现；{@link IPhoneApp#createPage} 的参数本身就是
 * PhoneContext（PhoneUi 实现了该接口），直接使用即可。</p>
 *
 * <p>常用配套工具：{@link PhoneWidgets}（控件构建）、{@link PhoneAppBase}
 * （App 基类）、{@link PhoneAppConfig}（每 App 持久化配置）。</p>
 */
@SideOnly(Side.CLIENT)
public interface PhoneContext {

    // ===================== 场景与数据 =====================

    /** 场景运行时（挂载自定义控件、bind 信号用）。 */
    SceneRuntime runtime();

    /** 当前手机物品栈（读 NBT 用；写入请走网络包）。 */
    ItemStack phoneStack();

    /** 手机面板宽（原生像素）。 */
    int panelWidth();

    /** 手机面板高（原生像素）。 */
    int panelHeight();

    // ===================== 字号与文案 =====================

    /** 按用户设置的字体缩放换算字号（所有手机内文本都应经此换算）。 */
    int scaledFont(int size);

    /** 本地化翻译。 */
    String tr(String key);

    /** 状态栏世界时钟信号（可 bindText 显示大号时钟）。 */
    Signal<String> clock();

    // ===================== 导航与反馈 =====================

    /** 打开其他 App 页面。 */
    void openApp(String appId);

    /** 返回主屏。 */
    void backHome();

    /** 主屏是否在前。 */
    boolean isHome();

    /** 关闭手机（内部已做延迟处理，可在点击回调里直接调用）。 */
    void closePhone();

    /** 弹出手机内 Toast 提示。 */
    void toast(String message);

    /**
     * 把动作推迟到输入分发结束后执行。
     *
     * <p>点击回调里直接改场景树/挂载节点会让 Qz-UILib 输入路由抛
     * ConcurrentModificationException——凡是回调里要 mount/dispose/切页，
     * 一律包进 post(...)。</p>
     */
    void post(Runnable action);

    // ===================== 网络 =====================

    /** 发送自定义 IMessage 到服务端（SimpleNetworkWrapper 封装）。 */
    void sendToServer(cpw.mods.fml.common.network.simpleimpl.IMessage msg);

    // ===================== 传送点 =====================

    /** 当前已同步到客户端的传送点列表（只读快照）。 */
    List<ItemPhone.Waypoint> waypoints();
}
