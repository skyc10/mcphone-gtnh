package com.november.mcphone.client.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.StatCollector;

import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.MainAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneScrolls;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.PhoneApi;
import com.november.mcphone.client.enhance.PhoneTheme;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.common.Loader;

/**
 * 联动 App 页：列出「靠别的模组撑着的 App」以及那个模组装没装。
 *
 * <p><b>上游对照</b>（november521/mcphone tag <b>v1.10.2</b>，只读 clone 在
 * {@code <upstream-mcphone>}，用 {@code git show v1.10.2:<path>} 读）：</p>
 * <ul>
 *   <li>{@code shared/src/main/java/com/november/mcphone/feature/store/client/CompanionApps.java}
 *       -&gt; 本类：{@code PAD=6} / {@code ICON=16} / {@code GAP=4} / {@code ROW_H=21}，
 *       标题 + 1px 分隔线 + 单列行（图标 + 名字 + 「需要 XXX」 + 右侧状态），
 *       行本身<b>不可点</b>；</li>
 *   <li>{@code core/client/PhoneScreenRegistry.getCompanionApps()} -&gt; {@link #entries()}：
 *       数据源从「注册表里的 App」换成 {@link PhoneApi#orderedApps()}（GTNH 侧没有
 *       {@code CATALOG} / {@code UNAVAILABLE} 两分，见 {@link #entries()} 的注释）；</li>
 *   <li>{@code api/client/app/IPhoneApp.requiredMods()/companionMods()}
 *       + {@code platform/ModPresence.isLoaded} -&gt;
 *       {@link IPhoneApp#requiredMods()} + {@link #isLoaded(String)}
 *       （GTNH 1.7.10 的等价物是 {@code cpw.mods.fml.common.Loader#isModLoaded(String)}，
 *       单加载器不需要上游那个再包一层 ModPresence 的门面）。</li>
 * </ul>
 *
 * <p><b>视觉规格</b>（{@code <workspace>\_r2_shots.md} §3.3 逐像素实测，换算系数
 * {@code s = 4.0 px/GUI}）：图标 <b>16 GUI</b>（64px÷4）、行高 <b>21 GUI</b>（84px÷4）、
 * App 名压暗 <b>#949494</b>、右侧「未装」浅灰 <b>#CACBCB</b>、<b>无按钮</b>、
 * <b>无分页</b>、无行间分隔线。这些 GUI 量在 {@link #page} 建页时按面板宽归一化
 * （与 {@code PhoneUi.buildHomeGrid} 同口径），不直接当场景像素用。</p>
 *
 * <p><b>与上游的两处刻意差异</b>（都记在 {@code <workspace>\_p2comp.md}）：</p>
 * <ol>
 *   <li>上游用<b>翻页</b>（{@code PAGER_H=12}，只有一页时整块不画）；本侧改成
 *       <b>滚动</b>（{@code SceneScrolls.attach}）——GTNH 的页面容器是滚动视口，
 *       与商店首页/详情页同一套，混用翻页会多出一套 PageSlot 之外的页码状态；</li>
 *   <li>上游 {@code refresh()} 会把「前置已齐且当前可用」的行剪掉（{@code if (satisfied
 *       || !PhoneScreenRegistry.isRegistered(app))}）；本侧<b>全列</b>（已装的行写绿字
 *       「已装」）。理由：GTNH 侧没有 {@code UNAVAILABLE} 目录，照搬那条剪枝后
 *       「模组装齐的整合包」进这一页永远看到空页，入口形同虚设；而本侧清单本来就只有
 *       个位数条目，不存在上游担心的噪声问题。</li>
 * </ol>
 */
public final class CompanionAppsPage {

    // ===================== 上游几何常量（GUI 单位，建页时按面板归一化） =====================
    // 【不能直接当场景像素用】这些是上游 120 GUI 宽屏上的 GUI 值（_r2_shots.md 换算系数
    // s = 4.0 px/GUI 的实测），而本局面板宽是动态的（panelW ≈ 260–900）；字号又走
    // PhoneUi.fs(9)（随全局字体缩放换算成像素）。几何若不跟着面板归一化，大面板下
    // 字高（≈ fs(9) 的实测行高）会远大于钉死的行高 → 行内两行（名字 + 需要 XXX）
    // 互相重叠、21 装不下 → 行与行也重叠（2026-09 用户截图）。归一化口径与
    // PhoneUi.buildHomeGrid 一致：s = panelWidth / 120.0（下限 ≥1.0）；
    // 行高再用同一度量端口（ui.runtime() 的 measurer，与布局引擎同源）实测。

    /** 页面左右内边距（上游 {@code CompanionApps.PAD = 6} GUI）。 */
    private static final int PAD = 6;

    /** 行内图标边长（上游 {@code ICON = 16}；截图实测 64px ÷ 4.0 = 16 GUI）。 */
    private static final int ICON = 16;

    /** 图标与文字、文字与右侧状态之间的间距（上游 {@code GAP = 4} GUI）。 */
    private static final int GAP = 4;

    /** 行高（上游 {@code ROW_H = 21}；截图实测 84px ÷ 4.0 = 21 GUI）。 */
    private static final int ROW_H = 21;

    /**
     * 标题 / 分隔线之间的留白（上游 {@code AppStore}/{@code CompanionApps} 的
     * {@code y += font.lineHeight + 4} 与分隔线后的 {@code y += 4}，取 4 GUI）。
     */
    private static final int HEAD_GAP = 4;

    /**
     * 行内字号（GUI 基准）。
     *
     * <p>上游画的是原版字体，{@code font.lineHeight = 9}（GUI px）：名字在行顶、
     * 「需要 XXX」在 {@code y + lineHeight + 1}，两行合计 19 &lt; {@link #ROW_H}=21，
     * 正好装得下。本侧字号经 {@link PhoneUi#fs(int)} 换算成像素（随全局字体缩放）；
     * 行高不再钉死 9，而是用 {@code ui.runtime().lineHeight(fs(9))}（Qz 公开度量端口，
     * 与布局引擎同一个 measurer，{@code SceneRuntime.java:178}）实测——
     * 见 {@link #page} 的归一化段。</p>
     */
    private static final int TEXT_SIZE = 9;

    // ===================== 配色（实测值） =====================

    /** App 名压暗色（截图实测 {@code #949494}；上游 {@code FontPalette.muted()} 暗底预设）。 */
    private static final int COLOR_NAME_MUTED = 0xFF949494;

    /** 次行「需要 XXX」与右侧「未装」浅灰（实测 {@code #CACBCB}；上游 {@code FontPalette.subtle()}）。 */
    private static final int COLOR_SUBTLE = 0xFFCACBCB;

    /** 「已装」绿（上游 {@code FontPalette.confirm()}；与 GTNH 设置页「开」同色 {@code #3FE04A}）。 */
    private static final int COLOR_CONFIRM = 0xFF3FE04A;

    /**
     * 入口格底色 / 圆点色：上游贴图 {@code assets/mcphone/textures/store/companion.png}
     * 的实测像素（20×20：全域 {@code #FFFFFFFF} 白底，四角 1–3px 透明阶梯圆角，
     * 中央 3 个 2×2 的 {@code #4B4B4B} 深灰点，横距 5、整体居中）。本侧不新增贴图
     * 资源，用同样几何的实色节点复刻这张贴图的观感（截图实测正是白底三个灰点）。
     */
    private static final int COLOR_ENTRY_BG = 0xFFFFFFFF;
    private static final int COLOR_ENTRY_DOT = 0xFF4B4B4B;

    /** 读不出图标时的兜底底色（上游 {@code drawRow} 的 {@code PhoneTheme.COLOR_BUTTON_DISABLED}）。 */
    private static final int COLOR_ICON_FALLBACK = 0xFF3A3A3A;

    /** 空数组单例。 */
    private static final String[] NONE = new String[0];

    private CompanionAppsPage() {}

    // ===================== 数据来源 =====================

    /**
     * App id -&gt; 联动模组 id 表：<b>模组自己没声明时的主 mod 侧兜底</b>。
     *
     * <p>为什么需要它：{@link IPhoneApp#requiredMods()} 是这一轮新加的 default 方法，
     * 附属 mod（{@code mcphone-addon-*}）的源码不在本轮改动范围内，它们既没有声明、
     * 编译产物也不会有。内建 App 同理——{@code BuiltinApps} 是禁改文件。于是把
     * 「已知联动」集中登记在这里，一处一行，等附属自己覆盖
     * {@link IPhoneApp#requiredMods()} 之后这一段可以逐条删掉（App 自己的声明优先）。</p>
     *
     * <p>只登记<b>能证实的</b>依赖；「某个整合包里也许有」的一律不写——写错的代价是
     * 玩家看着一个自己正在用的 App 被标成「未装」，比不列出来糟得多。</p>
     *
     * <ul>
     *   <li>{@code ae2}（ME 终端）：没有 AE2 就没有无线终端可开
     *       （{@code BuiltinApps.ae2()} 发的是 {@code NetworkHandler.OpenAe2}，
     *       服务端 {@code AppIntegrations} 全程反射 {@code appeng.*}）；
     *       实例里 AE2 的 {@code mcmod.info} modid = {@code appliedenergistics2}（已核）；</li>
     *   <li>{@code wiki}（维基）：虚拟屏后端是 MCEF，而 GTNH 侧 MCEF 内核由
     *       {@code mcphone-addon-browser} 内嵌提供（该附属 {@code MCEFApi.isMCEFLoaded()}
     *       的注释原文：「Modern kernel is embedded in the mcphone_browser addon;
     *       no separate "mcef" mod container exists」）；
     *       维基附属 {@code McefBridge} 也写明「优先走 mcphone-addon-browser 给 MCEF 0.7
     *       打的 default 方法桥接」；</li>
     *   <li>{@code music}（音乐）：全部内容来自 FMusic
     *       （{@code FMusicBridge} 里就是 {@code Loader.isModLoaded("FMusic")}，
     *       modid 大小写以 {@code FMusic/gradle.properties} 的 {@code modId = FMusic} 为准）。</li>
     * </ul>
     */
    private static final Map<String, String[]> KNOWN = new LinkedHashMap<String, String[]>();

    static {
        KNOWN.put("ae2", new String[] { "appliedenergistics2" });
        KNOWN.put("wiki", new String[] { "mcphone_browser" });
        KNOWN.put("music", new String[] { "FMusic" });
    }

    /**
     * 模组 id -&gt; 显示名 lang 键。
     *
     * <p>显示名<b>写死</b>（上游 {@code RequiredMod.displayName} 的注释：
     * 「要显示它的时候那个模组多半没装」）。查不到键就退回 modId 本身，绝不画出 key。</p>
     */
    private static final Map<String, String> MOD_NAME_KEYS = new LinkedHashMap<String, String>();

    static {
        MOD_NAME_KEYS.put("appliedenergistics2", "label.mcphone.compat_ae2");
        MOD_NAME_KEYS.put("mcphone_browser", "label.mcphone.compat_browser");
        MOD_NAME_KEYS.put("FMusic", "label.mcphone.compat_fmusic");
    }

    /** 一行要画的东西：刷新时一次算好，渲染热路径不再碰第三方 App 的元数据。 */
    private static final class Entry {

        final IPhoneApp app;
        final String name;
        final String requires;
        final boolean missing;

        Entry(IPhoneApp app, String name, String requires, boolean missing) {
            this.app = app;
            this.name = name;
            this.requires = requires;
            this.missing = missing;
        }
    }

    /**
     * 商店首页要不要画「联动App」入口格（上游 {@code AppStore.refresh()} 的
     * {@code hasCompanion = !PhoneScreenRegistry.getCompanionApps().isEmpty()}）。
     */
    public static boolean hasAny() {
        return !entries().isEmpty();
    }

    /** 入口格的名字（与页面标题同一个键，上游 {@code mcphone.store.companion} 也是共用的）。 */
    public static String entryLabel() {
        return tr("msg.mcphone.store_companion");
    }

    /**
     * 扫一遍当前目录，挑出有联动声明的 App。
     *
     * <p>与上游 {@code PhoneScreenRegistry.getCompanionApps()} 的差别：上游在
     * {@code CATALOG}（全部）里按「{@code requiredMods} 或 {@code companionMods} 非空」筛，
     * 再补上 {@code UNAVAILABLE}（不可用）那一批；GTNH 侧只有一份目录
     * （{@link PhoneApi#orderedApps()}，附属 mod 没装就根本没有这个 App 对象），
     * 所以「按声明筛」就是全部。</p>
     *
     * <p>读第三方 App 的元数据一律兜 {@code Throwable}：联动对象的类很可能引用着
     * 没装的模组，读它任何一样东西都可能抛 {@code NoClassDefFoundError}（是 Error
     * 不是 Exception，只兜 Exception 会整页崩）。</p>
     */
    private static List<Entry> entries() {
        List<Entry> out = new ArrayList<Entry>();
        for (IPhoneApp app : PhoneApi.orderedApps()) {
            String[] mods = modsOf(app);
            if (mods.length == 0) continue;

            boolean satisfied = true;
            StringBuilder names = new StringBuilder();
            for (String mod : mods) {
                if (mod == null || mod.isEmpty()) continue;
                if (names.length() > 0) names.append("、");
                names.append(modName(mod));
                if (!isLoaded(mod)) satisfied = false;
            }
            if (names.length() == 0) continue;

            out.add(new Entry(
                app,
                nameOf(app),
                tr("msg.mcphone.store_companion_requires", names.toString()),
                !satisfied));
        }
        return out;
    }

    /** App 自己的声明优先，没有就用主 mod 侧的兜底表（见 {@link #KNOWN}）。 */
    private static String[] modsOf(IPhoneApp app) {
        String id = idOf(app);
        try {
            String[] declared = app.requiredMods();
            if (declared != null && declared.length > 0) return declared;
        } catch (Throwable t) {
            System.err.println("[mcphone] 读取 " + app.getClass().getName()
                + " 的联动声明失败，回退内置表：" + t);
        }
        if (id == null) return NONE;
        String[] known = KNOWN.get(id);
        return known == null ? NONE : known;
    }

    /** 「这个模组装了没有」——全类唯一碰加载器的地方（上游 {@code platform/ModPresence} 的等价物）。 */
    private static boolean isLoaded(String modId) {
        try {
            return Loader.isModLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 模组显示名：lang 键优先，缺键退回 modId（绝不画 key，也绝不填空串）。 */
    private static String modName(String modId) {
        String key = MOD_NAME_KEYS.get(modId);
        if (key == null) return modId;
        String name = tr(key);
        return key.equals(name) ? modId : name;
    }

    /** 读不出来就用 id 顶上（上游 {@code readName} 同法）。 */
    private static String nameOf(IPhoneApp app) {
        try {
            String name = app.displayName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable t) {
            System.err.println("[mcphone] 读取 " + app.getClass().getName()
                + " 的名字失败，用 id 代替：" + t);
        }
        String id = idOf(app);
        return id == null ? "?" : id;
    }

    private static String idOf(IPhoneApp app) {
        try {
            return app.id();
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== 页面 =====================

    /**
     * 构建联动页（挂进 {@link StoreFront} 的页面槽）。
     *
     * <p><b>几何随面板归一化</b>：上游那些 GUI 常量 {@code PAD/ICON/GAP/ROW_H/HEAD_GAP}
     * 是 120 GUI 宽屏上的量，直接当场景像素用而字号又走 {@link PhoneUi#fs(int)}
     * （随全局字体缩放）时，大面板下「字高 &gt; 行高」——行内两行（名字 + 需要 XXX）
     * 互相重叠、{@code ROW_H=21} 装不下 → 行与行也重叠（2026-09 用户截图）。
     * 归一化口径与 {@code PhoneUi.buildHomeGrid} 同源：{@code s = panelWidth / 120.0}
     * （下限 ≥1.0）。行高用 Qz 公开度量端口 {@code SceneRuntime#lineHeight(int)}
     * （{@code requireTextMeasurer()}，与布局引擎同一个 measurer）按实际字号实测，
     * 并保证 {@code ROW_H ≥ 2*lineH + 1 + 2*round(2s)}（装得下两行 + 行间 1px
     * + 上下各 2 GUI 等比的呼吸）。文本行高仍<b>显式钉死</b>为实测值
     * （Qz 是显式声明制：不设就是自动行高），文本必须 {@code setMaxTextWidth}
     * 才会折行/省略（4.10.0 显式声明制）。</p>
     */
    public static SceneNode page(PhoneUi ui) {
        final double s = Math.max(1.0, ui.panelWidth() / 120.0);
        final int pad = (int) Math.round(PAD * s);
        final int iconSize = (int) Math.round(ICON * s);
        final int gap = (int) Math.round(GAP * s);
        final int headGap = (int) Math.round(HEAD_GAP * s);
        final int lineH = ui.runtime().lineHeight(PhoneUi.fs(TEXT_SIZE));
        // 行高两取一大：21 GUI 等比 vs「两行字 + 行间 1px + 上下各 2 GUI 等比呼吸」。
        final int rowH = Math.max((int) Math.round(ROW_H * s),
            2 * lineH + 1 + 2 * (int) Math.round(2 * s));

        SceneNode page = SceneNode.column();
        page.setFillParentWidth(true);
        // 填满父级（StoreFront 的槽是显式固定高），滚动视口因此有确定高度。
        page.setFillParentHeight(true);
        page.setPadding(pad);
        page.setGap(0);
        page.setScrollable(true);
        page.setClipChildren(true);
        // 滚轮必须显式 attach：setScrollable 只声明可滚动，滚轮事件由 attach 接线（踩坑 #2）。
        SceneScrolls.attach(ui.runtime(), page);

        page.appendChild(PhoneUi.title(tr("msg.mcphone.store_companion")));
        page.appendChild(gap(headGap));
        page.appendChild(StoreFront.separator());
        page.appendChild(gap(headGap));

        List<Entry> rows = entries();
        if (rows.isEmpty()) {
            // 上游 mcphone.store.companion.empty（一行浅灰字，后面不画任何行）。
            page.appendChild(PhoneUi.muted(tr("msg.mcphone.store_companion_empty")));
            return page;
        }

        int inner = Math.max(120, ui.panelWidth() - 2 * pad);
        for (Entry e : rows) {
            page.appendChild(row(ui, e, inner, iconSize, gap, rowH, lineH));
        }
        return page;
    }

    /**
     * 一行：图标 + 名字 + 「需要 XXX」 + 右侧状态（上游 {@code drawRow}）。
     *
     * <p>右对齐不靠 {@code flexGrow}：文字列宽 = 可用宽 - 图标 - 两个间距 - 状态宽，
     * 于是「图标 + 间距 + 文字列 + 间距 + 状态」恰好等于整行宽，状态自然贴右沿。</p>
     *
     * <p>几何量（图标/间距/行高/行内行高）全部来自 {@link #page} 的归一化结果，
     * 不再用绝对常量。</p>
     */
    private static SceneNode row(PhoneUi ui, Entry e, int inner,
                                 int iconSize, int gap, int rowH, int lineH) {
        String state = tr(e.missing
            ? "msg.mcphone.store_companion_missing"
            : "msg.mcphone.store_companion_installed");
        int stateW = Math.max(0, ui.runtime().measureTextWidth(state, PhoneUi.fs(TEXT_SIZE)));

        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setPreferredHeight(rowH);
        row.setGap(gap);
        row.setCrossAxisAlign(CrossAxisAlign.START);
        row.setHitTestable(false);

        row.appendChild(icon(e.app, iconSize));

        // 名字给状态文字让位，否则长名字会压在「未装」上（上游同一句注释）。
        int textW = Math.max(24, inner - iconSize - gap - stateW - gap);
        SceneNode texts = SceneNode.column();
        texts.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        texts.setPreferredWidth(textW);
        texts.setPreferredHeight(rowH);
        texts.setGap(1);
        texts.setCrossAxisAlign(CrossAxisAlign.START);
        texts.setHitTestable(false);
        texts.appendChild(line(e.name, textW, lineH,
            e.missing ? COLOR_NAME_MUTED : PhoneTheme.text()));
        texts.appendChild(line(e.requires, textW, lineH, COLOR_SUBTLE));
        row.appendChild(texts);

        // 状态文字不给 maxTextWidth：它是整行的右边界，截断它等于截断对齐基准。
        row.appendChild(line(state, 0, lineH, e.missing ? COLOR_SUBTLE : COLOR_CONFIRM));
        return row;
    }

    /**
     * 行内一段文字（字号随 {@link PhoneUi#fs(int)} 缩放；行高显式钉死为实测行高，
     * Qz 显式声明制：不设就是自动行高）。maxW &gt; 0 时才声明折行 + 单行省略。
     */
    private static SceneNode line(String value, int maxW, int lineH, int color) {
        SceneNode n = new SceneNode();
        n.setText(value == null ? "" : value);
        n.setTextColor(color);
        n.setFontSize(PhoneUi.fs(TEXT_SIZE));
        n.setLineHeightPx(lineH);
        if (maxW > 0) {
            n.setMaxTextWidth(maxW);
            n.setMaxLines(1);
            n.setEllipsis(true);
        }
        n.setHitTestable(false);
        return n;
    }

    /** 图标盒：与商店首页/详情页共用 {@link StoreFront#iconBox}（三页同一套画法）。 */
    private static SceneNode icon(IPhoneApp app, int size) {
        try {
            return StoreFront.iconBox(app, size, Math.max(2, size / 4));
        } catch (Throwable t) {
            // 第三方 App 的图标读不出来（多半是引用着没装的模组）：画占位方块，别让整页崩。
            System.err.println("[mcphone] 画联动页图标失败，改用占位方块：" + t);
            return plainBox(size);
        }
    }

    private static SceneNode plainBox(int size) {
        SceneNode box = SceneNode.row();
        box.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        box.setPreferredWidth(size);
        box.setPreferredHeight(size);
        box.setCornerRadius(Math.max(2, size / 4));
        box.setBackgroundColor(COLOR_ICON_FALLBACK);
        box.setHitTestable(false);
        return box;
    }

    /** 固定高度的空档（显式 preferred；不依赖 gap/grow 求解）。 */
    private static SceneNode gap(int h) {
        SceneNode g = SceneNode.column();
        g.setFillParentWidth(true);
        g.setPreferredHeight(h);
        g.setHitTestable(false);
        return g;
    }

    /**
     * 商店首页最后一格的「联动App」入口图标（上游 {@code AppStore.drawCompanionCell()}）。
     *
     * <p>上游是「贴图优先、纯色兜底」：{@code textures/store/companion.png} 存在就用它，
     * 否则画 {@code COLOR_STATUS_BAR} 底 + 三个小方块。本侧<b>不新增贴图资源</b>
     * （本轮只允许改 java + lang），于是按<b>实测截图</b>（{@code _r2_shots.md} §3.3：
     * 「纯白圆角方块 + 3 个灰点」）＝<b>那张贴图的像素本身</b>复刻：白底圆角方块 +
     * 3 个深灰点。也不画字符——好看的符号在部分字体下会掉成方框，而这是玩家进商店
     * 第一眼看到的格子之一（上游 {@code drawCompanionCell} 的同一句理由）。</p>
     *
     * <p>几何按贴图比例换算到 {@code size}：点的边长 {@code size/10}、点距
     * {@code 3*size/20}（贴图 20px 里点是 2px、横距 5px、三个点合计 12px 居中）。</p>
     */
    public static SceneNode entryIcon(int size) {
        SceneNode box = SceneNode.row();
        box.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        box.setPreferredWidth(size);
        box.setPreferredHeight(size);
        // 贴图四角是 1–3px 的透明阶梯（半径 ≈ size*3/20），近似成圆角半径 size/7。
        box.setCornerRadius(Math.max(2, size / 7));
        box.setBackgroundColor(COLOR_ENTRY_BG);
        box.setMainAxisAlign(MainAxisAlign.CENTER);
        box.setCrossAxisAlign(CrossAxisAlign.CENTER);
        box.setHitTestable(false);

        int s = Math.max(2, size / 10);
        int gap = Math.max(1, (3 * size) / 20);
        SceneNode dots = SceneNode.row();
        dots.setWidthSizing(SceneNode.WidthSizing.SHRINK);
        dots.setGap(gap);
        dots.setMainAxisAlign(MainAxisAlign.CENTER);
        dots.setCrossAxisAlign(CrossAxisAlign.CENTER);
        dots.setHitTestable(false);
        for (int i = 0; i < 3; i++) {
            SceneNode dot = SceneNode.row();
            dot.setWidthSizing(SceneNode.WidthSizing.SHRINK);
            dot.setPreferredWidth(s);
            dot.setPreferredHeight(s);
            dot.setBackgroundColor(COLOR_ENTRY_DOT);
            dot.setHitTestable(false);
            dots.appendChild(dot);
        }
        box.appendChild(dots);
        return box;
    }

    // ===================== 小工具 =====================

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private static String tr(String key, Object arg) {
        return StatCollector.translateToLocalFormatted(key, arg);
    }
}
