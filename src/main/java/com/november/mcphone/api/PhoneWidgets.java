package com.november.mcphone.api;

import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;

import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.enhance.PhoneGlass;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 附属 App 常用控件构建器（全部基于 {@link PhoneContext}，零内部类依赖）。
 *
 * <p>回调一律经 {@link PhoneContext#post(Runnable)} 延迟执行——点击回调里直接
 * 改场景树会让 Qz-UILib 输入路由抛 ConcurrentModificationException。</p>
 *
 * <p><b>液态玻璃（v3）</b>：控件底色/圆角/文字色的玻璃态全部经
 * {@link PhoneGlass} 门面取（本包不直接引用任何 Qz 玻璃类型）。玻璃总开关关闭或
 * Qz 玻璃类缺失时，{@link #card} / {@link #button} / {@link #primaryButton} 自动回落为
 * <b>旧值</b>（{@link #PANEL} / 私有 BTN_BG 系），即「公开 API 既有语义只新增不改」；
 * 需要立刻拿到玻璃观感的附属 App 用 {@link #glassCard} / {@link #glassButton}。</p>
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

    /**
     * 圆角卡片容器（纵向）。
     *
     * <p>玻璃开：底色 = {@link PhoneGlass#surface} 的 CARD 玻璃态（合成后约 47% 不透明，
     * 不是旧的 {@code 0x33FFFFFF} 白蒙层），圆角 12（液态倒角，&gt;= 10）。
     * 玻璃关/类缺失：**逐字沿用旧值** {@link #PANEL} + 8px 圆角。</p>
     */
    public static SceneNode card(PhoneContext ctx) {
        SceneNode card = SceneNode.column();
        card.setFillParentWidth(true);
        card.setGap(6);
        card.setPadding(8, 8, 8, 8);
        if (PhoneGlass.glassOn()) {
            card.setCornerRadius(PhoneGlass.cardRadius());
            card.setBackgroundColor(PhoneGlass.surface(PhoneGlass.Role.CARD));
            PhoneGlass.apply(card, PhoneGlass.Role.CARD);
        } else {
            card.setCornerRadius(CARD_RADIUS_LEGACY);
            card.setBackgroundColor(PANEL);
        }
        return card;
    }

    /**
     * 显式玻璃卡片：无论全局档位如何都按指定档挂玻璃（建树期调用一次）。
     *
     * <p>与 {@link #card} 的区别：card 的玻璃状态跟随全局开关，本方法在开关开启且
     * Qz 玻璃类可用时<b>一定</b>上玻璃（档位仍受角色上限约束：卡片最高 REGULAR）。</p>
     */
    public static SceneNode glassCard(PhoneContext ctx, PhoneGlass.Tier tier) {
        SceneNode card = SceneNode.column();
        card.setFillParentWidth(true);
        card.setGap(6);
        card.setPadding(8, 8, 8, 8);
        if (PhoneGlass.glassOn()) {
            card.setCornerRadius(PhoneGlass.cardRadius());
            card.setBackgroundColor(PhoneGlass.surface(PhoneGlass.Role.CARD, tier));
            PhoneGlass.apply(card, PhoneGlass.Role.CARD, tier);
        } else {
            card.setCornerRadius(CARD_RADIUS_LEGACY);
            card.setBackgroundColor(PANEL);
        }
        return card;
    }

    /** 显式玻璃卡片（用配置档）。 */
    public static SceneNode glassCard(PhoneContext ctx) {
        return glassCard(ctx, PhoneCanvas.glassTier());
    }

    /** 玻璃态文字色（按材质档配对；玻璃关闭时为中性浅底上的中深色）。 */
    public static int glassText() {
        return PhoneGlass.text();
    }

    /** 玻璃态次要文字色（按材质档配对）。 */
    public static int glassMuted() {
        return PhoneGlass.muted();
    }

    /** 键值信息行（左键名右值）。 */
    public static SceneNode infoRow(PhoneContext ctx, String key, String value) {
        SceneNode row = row(ctx);
        row.appendChild(text(ctx, key, MUTED, 16));
        row.appendChild(spacer(ctx));
        row.appendChild(text(ctx, value, TEXT, 16));
        return row;
    }

    /** 按钮文字字号 = 全局字体缩放 x 按钮字号缩放（独立滑条）。 */
    public static int buttonFontSize(PhoneContext ctx) {
        return Math.max(10, Math.round(ctx.scaledFont(16) * PhoneCanvas.getButtonScale() / 100.0f));
    }

    private static final int BTN_BG = 0xFF3A414D;
    private static final int BTN_BG_HOVER = 0xFF4A5462;
    private static final int BTN_PRIMARY_BG = 0xFF2F5FA8;
    private static final int BTN_PRIMARY_BG_HOVER = 0xFF3A72C4;

    // 玻璃态 button/card 圆角（旧值 8 挂不住液态缘带，见 PhoneGlass.RADIUS_MIN 注释）
    private static final int BTN_RADIUS_LEGACY = 8;
    private static final int CARD_RADIUS_LEGACY = 8;

    /** 自绘按钮（不依赖 SceneButton）：文字字号可控，悬停变色，回调自动延迟执行。 */
    public static SceneNode button(PhoneContext ctx, SceneNode parent, String label, Runnable onClick) {
        return mountButton(ctx, parent, label, onClick, false);
    }

    /**
     * 挂主操作按钮（强调色变体）。
     *
     * <p>accent 面<b>不上玻璃</b>（§9.1 总则 3：玻璃是容器语言、实色是状态语言），
     * 保留 #BTN_PRIMARY_BG 实心 + 白色文字，玻璃开关不影响它。</p>
     */
    public static SceneNode primaryButton(PhoneContext ctx, SceneNode parent, String label, Runnable onClick) {
        return mountButton(ctx, parent, label, onClick, true);
    }

    /**
     * 显式玻璃按钮（与全局档位无关的强调变体；开关关闭/类缺失时回落旧按钮底色）。
     *
     * <p>配方对齐 Qz {@code HudToolbarSpec} 的 HUD 工具栏按钮：DARK_THIN + blur 6 + lens 1.0，
     * 圆角 12。</p>
     */
    public static SceneNode glassButton(PhoneContext ctx, SceneNode parent, String label, Runnable onClick) {
        return mountButton(ctx, parent, label, onClick, false, PhoneCanvas.glassTier());
    }

    private static SceneNode mountButton(PhoneContext ctx, SceneNode parent, String label,
                                         Runnable onClick, boolean primary) {
        return mountButton(ctx, parent, label, onClick, primary, null);
    }

    private static SceneNode mountButton(PhoneContext ctx, SceneNode parent, String label,
                                         Runnable onClick, boolean primary, PhoneGlass.Tier forcedTier) {
        boolean glass = !primary && PhoneGlass.glassOn();
        int normalBg = primary ? BTN_PRIMARY_BG : (glass ? PhoneGlass.surface(PhoneGlass.Role.BUTTON, forcedTier) : BTN_BG);
        int hoverBg = primary ? BTN_PRIMARY_BG_HOVER
            : (glass ? PhoneGlass.surface(PhoneGlass.Role.BUTTON_HOVER, forcedTier) : BTN_BG_HOVER);
        SceneNode btn = SceneNode.row();
        btn.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        btn.setCrossAxisAlign(CrossAxisAlign.CENTER);
        btn.setMainAxisAlign(MainAxisAlign.CENTER);
        btn.setPadding(8, 8, 8, 8);
        btn.setCornerRadius(glass ? PhoneGlass.buttonRadius() : BTN_RADIUS_LEGACY);
        btn.setBackgroundColor(normalBg);
        if (glass) {
            // 建树期一次性挂玻璃：不要放进 bindComputed（Qz 侧对绑定后的节点拒绝静态写）
            PhoneGlass.apply(btn, PhoneGlass.Role.BUTTON, forcedTier);
        }
        btn.setBorderWidth(1);
        btn.setBorderColor(BORDER);
        SceneNode lbl = new SceneNode();
        lbl.setText(label);
        lbl.setTextColor(primary ? 0xFFFFFFFF : (glass ? PhoneGlass.text() : TEXT));
        lbl.setFontSize(buttonFontSize(ctx));
        lbl.setHitTestable(false);
        btn.appendChild(lbl);
        // 悬停变色
        var interaction = ctx.runtime().interactionState(btn);
        ctx.runtime().bindComputed(
            () -> Boolean.TRUE.equals(interaction.hovered().get()) ? hoverBg : normalBg,
            btn::setBackgroundColor);
        ctx.runtime().on(btn, SceneEventType.CLICK, (event, dispatch) -> ctx.post(onClick));
        parent.appendChild(btn);
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
