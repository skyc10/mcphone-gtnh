package com.november.mcphone.api;

import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneButtonVariant;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 附属 App 常用控件构建器（全部基于 {@link PhoneContext}，零内部类依赖）。
 *
 * <p>回调一律经 {@link PhoneContext#post(Runnable)} 延迟执行——点击回调里直接
 * 改场景树会让 Qz-UILib 输入路由抛 ConcurrentModificationException。</p>
 */
@SideOnly(Side.CLIENT)
public final class PhoneWidgets {

    public static final int TEXT = 0xFFE8EDF2;
    public static final int MUTED = 0xFFB8C4D0;
    public static final int PANEL = 0x33FFFFFF;
    public static final int BORDER = 0x55FFFFFF;

    private PhoneWidgets() {}

    /** 文本节点（不可命中，字号经用户缩放换算）。 */
    public static SceneNode text(PhoneContext ctx, String value, int color, int size) {
        SceneNode n = new SceneNode();
        n.setText(value);
        n.setTextColor(color);
        n.setFontSize(ctx.scaledFont(size));
        n.setHitTestable(false);
        return n;
    }

    /** 页面标题。 */
    public static SceneNode title(PhoneContext ctx, String value) {
        return text(ctx, value, TEXT, 20);
    }

    /** 次要说明文字。 */
    public static SceneNode muted(PhoneContext ctx, String value) {
        return text(ctx, value, MUTED, 13);
    }

    /** 可滚动纵向内容列。 */
    public static SceneNode scrollColumn(PhoneContext ctx) {
        SceneNode col = SceneNode.column();
        col.setFillParentWidth(true);
        col.setFlexGrow(1);
        col.setPadding(12);
        col.setGap(10);
        col.setScrollable(true);
        col.setClipChildren(true);
        return col;
    }

    /** 横向行容器。 */
    public static SceneNode row(PhoneContext ctx) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(8);
        return row;
    }

    /** 填充用弹性占位。 */
    public static SceneNode spacer(PhoneContext ctx) {
        SceneNode s = SceneNode.column();
        s.setFlexGrow(1);
        s.setHitTestable(false);
        return s;
    }

    /** 圆角卡片容器（纵向）。 */
    public static SceneNode card(PhoneContext ctx) {
        SceneNode card = SceneNode.column();
        card.setFillParentWidth(true);
        card.setGap(6);
        card.setPadding(8, 8, 8, 8);
        card.setCornerRadius(8);
        card.setBackgroundColor(PANEL);
        return card;
    }

    /** 键值信息行（左键名右值）。 */
    public static SceneNode infoRow(PhoneContext ctx, String key, String value) {
        SceneNode row = row(ctx);
        row.appendChild(text(ctx, key, MUTED, 16));
        row.appendChild(spacer(ctx));
        row.appendChild(text(ctx, value, TEXT, 16));
        return row;
    }

    /** 挂标准按钮（回调自动延迟执行）。 */
    public static SceneNode button(PhoneContext ctx, SceneNode parent, String label, Runnable onClick) {
        SceneButton.Props props = new SceneButton.Props(
            Signal.create(label), Signal.create(Boolean.TRUE), () -> ctx.post(onClick));
        SceneNode btn = ctx.runtime().mount(parent, SceneButton.create(ctx.runtime(), props)).getRoot();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        return btn;
    }

    /** 挂主操作按钮（强调色变体）。 */
    public static SceneNode primaryButton(PhoneContext ctx, SceneNode parent, String label, Runnable onClick) {
        SceneButton.Props props = new SceneButton.Props(
            Signal.create(label), Signal.create(Boolean.TRUE), () -> ctx.post(onClick),
            SceneButtonVariant.PRIMARY);
        SceneNode btn = ctx.runtime().mount(parent, SceneButton.create(ctx.runtime(), props)).getRoot();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        return btn;
    }

    /**
     * 给任意节点挂点击回调（回调自动延迟执行；需要 stopPropagation 时请自行用
     * runtime().on 并在 SceneEventContext 上调用）。
     */
    public static void onClick(PhoneContext ctx, SceneNode node, Runnable action) {
        ctx.runtime().on(node, SceneEventType.CLICK, (event, dispatch) -> ctx.post(action));
    }
}
