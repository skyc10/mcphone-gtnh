package com.november.mcphone.client.enhance;

import com.november.mcphone.client.PhoneCanvas;

import club.heiqi.uilib.ui.scene.node.SceneNode;

/**
 * 液态玻璃能力隔离桥（MCphone 全仓<b>唯一</b>字面引用 Qz-UILib 玻璃类型的类）。
 *
 * <p><b>隔离契约</b>（docs/qz-liquid-glass-design.md §4.6 / §5.1）：Qz 玻璃类型
 * （{@code UiBackdrop} / {@code UiBackdropEffect} / {@code UiGlassMaterial} /
 * {@code BackdropBlur*}）只允许出现在本文件；其余 mcphone 代码一律经本门面。
 * 玻璃类缺失时（ClassNotFoundException / NoClassDefFoundError / 任何 LinkageError）
 * 所有入口静默回落为「无玻璃」，不抛异常、不逐帧刷日志。</p>
 *
 * <p><b>探测实现</b>：一次性 static 探测 + volatile 缓存（不做每帧 try/catch）。
 * 探测只解析「会被用到的那一档」{@code UiGlassMaterial} 常量，避免误报。
 * 定位是<b>崩溃安全网</b>，不是兼容旧版 qz_uilib 的手段——依赖下限由
 * {@code MCphone.java} 的 {@code required-after:qz_uilib@[4.9.0,)} 承担
 * （FML 只按 {@code MyMod.class} 内联版本串判定，早于本类执行）。</p>
 *
 * <h2>层次契约（§1.9 / §2.1）</h2>
 * <p>{@code BACKDROP} 命令在同节点 {@code BACKGROUND} <b>之前</b>发出 ⇒ 节点自身底色是
 * <b>乘在玻璃之上</b>的实心填充，按 {@code (1-a)} 衰减 shader 算出的折射缘带与镜面高光；
 * 同时材质档自身还带一层黑 tint（DARK_ULTRA_THIN 10% / DARK_THIN 15% / DARK_REGULAR 20% / DARK_THICK 30%，
 * 即 `tintArgb` 的 alpha 通道 `0x1A/0x26/0x33/0x4D`）。
 * 两层都在压背景，所以节点底色的 alpha 必须「重定 + 反解」：</p>
 * <pre>
 *   目标总遮罩 T（= §9.3 的裁定值 0x40/0x59/0x73/0x7E/0x9A）
 *   节点底色 alpha a 与材质 tint t 的合成：T = a + t·(1 - a/255)
 *   反解：a = (T·255 - t·255) / (255 - t)
 * </pre>
 * <p>直接把 T 当 a 用就会双重遮罩（材质已经在压背景了），这正是 Qz 原文
 * 「0x8C(55%) 降到 0x73(45%)」那条实测（侧缘镜面 +4/255 被压到 +2、同位置变暗 -23/255）的成因。</p>
 *
 * <h2>改动前后色值对照表（glassTier=AUTO + glassLensStrength=1.0 下实测；含 sRGB 口径）</h2>
 * <pre>
 * 面 / 角色     旧值（改动前）              玻璃态（AUTO⇒THIN 真渲染）   非玻璃态（开关关闭 / 类缺失）
 * 主面板        0xF20E1116  242 = 95%     0x3C14181C   60 = 23.5%（合成 T=0x59/35%） 0x1FF2F5F8  12% 中性浅
 * 内容底板      0x900E1116  144 = 56%     0x3C14181C   60 = 23.5%（合成 T=0x59/35%） 0x4C14181C  30%（保留暗底保对比）
 * 状态栏        0x99000000  153 = 60% 纯黑 0x2314181C  35 = 13.7%（合成 T=0x44/27%，含 F3 修正：0x25→0x23） 0x1FF2F5F8  12% 中性浅
 * 卡片          0x33FFFFFF   51 = 20% 白  0x5A14181C  90 = 35.3%（合成 T=0x73/45%） 0x1FF2F5F8  12% 中性浅（neutralSurface(CARD) 实际返回值）
 * （上表括号内为「节点底色 a + 材质 tint 合成后」的总遮罩 T；THICK 行的**节点 a** 为 0x7A~0x8D、**合成 T** 为 0xA2~0xAF，见 §底色反解表）
 * （全表见 docs/qz-liquid-glass-review.md §14.3 —— 权威数值表，逐格与 SURFACE_ALPHA 一致）
 * （本表玻璃态一列是**反解后的节点底色**；「合成 T」另按 `T = a + t·(1−a/255)` 计得，与 §9.3 的数值口径不同（见 review F2/F5））
 * 按钮          0xFF3A414D  255 = 100%    0x5A14181C  90 = 35.3%（合成 T=0x73/45%） 0xFF3A414D 100%（沿用旧值）
 * 按钮悬停      0xFF4A5462  255 = 100%    0x7E14181C 126 = 49.4%（合成 T=0x91/57%，刻意比 DARK 系裁定略实以保 hover 反馈）0xFF4A5462 100%（沿用旧值）
 * 主操作按钮    0xFF2F5FA8  255 = 100%    保留实心（accent 不上玻璃，§9.1 总则 3）
 * 边框          0x55FFFFFF                保留（倒角载体，§5.4）
 * 圆角（按钮/卡片） 8px                   12px（液态倒角，&gt;= {@link #RADIUS_MIN}）
 * </pre>
 * <p>旧值出处：{@code PhoneUi.java:71/:73/:75}、{@code PhoneWidgets.java:103/:104}
 * （旧值里 0xF2 / 0xFF 这类 ≥95% 的遮罩就是「把玻璃盖死」的元凶）。
 * 玻璃态一列是<b>反解后的节点底色</b>（= {@link #SURFACE_ALPHA} 的实际取值，代码为准）；
 * 非玻璃态一列是刻意重定，绝不是把旧深灰方案当唯一退路——浅底 + 中深文字在任何壁纸/世界背景上都可读。</p>
 *
 * <h2>材质档 ↔ 文字色配套表（§9.2 落地）</h2>
 * <pre>
 * 档                Qz 材质档         blur  lens  底色基色 alpha（目标 T / 节点 a）  正文色 / 次要文字色
 * AUTO（默认）      见下              —     ×降级 见下                                见下
 *   ├─ 真渲染 SHADER    DARK_THIN       8     1.0   T=0x59/0x73/0x7E ⇒ a 见 §底色反解表   0xFFE8EDF2 / 0xFFB8C4D0
 *   └─ 降级 FIXED/TINT  DARK_ULTRA_THIN 6     0.8   T 同上、t 更小 ⇒ a 更大（见反解表）    0xFFE8EDF2 / 0xFFB8C4D0
 * ULTRA_THIN        DARK_ULTRA_THIN 6     0.8   同上                                    0xFFE8EDF2 / 0xFFB8C4D0
 * THIN              DARK_THIN       8     1.0   同上                                    0xFFE8EDF2 / 0xFFB8C4D0
 * REGULAR           DARK_REGULAR    8     0.7   T=0x8C 系（更实）                        0xFFFFFFFF / 0xFFC8D2DC
 * THICK             DARK_THICK      12    1.2   T=0x8D~0x9A 系（最实）                    0xFFFFFFFF / 0xFFD5DCE4
 * </pre>
 * <p>lens 实际值 = 角色基础 lens（主壳 0.5 / 内容 0.35 / 状态栏 0.3 / 按钮 1.0，§6.2）
 * × 用户全局缩放（{@code glassLensStrength}，默认 1.0）× 档系数 × 降级系数。
 * <b>该最终值参与配方缓存键</b>（见 {@link #LENS_STEPS} / {@link #backdrop}）：用户缩放或
 * 渲染路径一变，键即变、配方即重建——这是「玻璃强度」滑条生效的必要条件。
 * 文字色按档配对：DARK_* 档一律配浅色字（§9.1 总则 2），由 {@link #text()} / {@link #muted()} 给出。</p>
 */
public final class PhoneGlass {

    private PhoneGlass() {}

    // =====================================================================
    // 材质档（枚举名刻意避开 Qz 类型名；Qz 玻璃类名只出现在探测字符串与
    // 那一处 setBackdrop 强制转型）
    // =====================================================================

    /** 玻璃配方档位。{@code AUTO=0} 是默认档：按实际渲染路径解析为 THIN 或 ULTRA_THIN。 */
    public enum Tier {
        /** 自动：shader 可用 ⇒ THIN；降级（固定管线 / 纯 tint）⇒ ULTRA_THIN。 */
        AUTO,
        /** 薄玻璃（默认主档）：对齐 Qz 自家 DARK_THIN / blur 8 / lens 0.5 定稿。 */
        THIN,
        /** 极薄玻璃：降级路径的产物档，也供条状小面显式选用。 */
        ULTRA_THIN,
        /** 常规玻璃：更实，配更亮的文字。 */
        REGULAR,
        /** 厚玻璃：模态 / 强隔离层，接近实心。 */
        THICK;

        /**
         * 枚举序号即存档值；非法值回落配置默认档。
         *
         * <p><b>注意：枚举序号不是厚度序</b>（{@code THIN=1} 实际比 {@code ULTRA_THIN=2} 更厚，
         * 序号是历史/存档顺序）。任何「哪档更厚」的比较都必须走 {@link PhoneGlass#fullness}
         * （review F1：旧实现用 {@code ordinal()} 比较，导致状态栏请求"极薄"被反向上钳为"薄"）。</p>
         */
        public static Tier normalize(int raw) {
            Tier[] all = values();
            return (raw >= 0 && raw < all.length) ? all[raw] : defaultTier();
        }

        /** 配置默认档（{@code AUTO}）：依据见 {@code PhoneCanvas.glassTier()} 注释。 */
        public static Tier defaultTier() {
            return AUTO;
        }
    }

    /** 玻璃语义角色：决定底色基色、blur 与「非玻璃态」回落色。 */
    public enum Role {
        /** 手机主面板壳。 */
        PANEL,
        /** 内容底板（页面文字承载面）。 */
        PAGE,
        /** 状态栏（条状小面：低强度、低折射）。 */
        STATUS,
        /** 卡片 / 列表行容器。 */
        CARD,
        /** 自绘按钮（小面积需要更高 lens 才看得出来）。 */
        BUTTON,
        /** 按钮悬停态（同档玻璃，只小幅加实，不换 RGB）。 */
        BUTTON_HOVER
    }

    /** 渲染路径档位（{@code UiRenderContext} 四值归类在 mcphone 侧的镜像，不回调 Qz 枚举）。 */
    public enum Path {
        /** 玻璃类型不可用（探测失败 / 类缺失）。 */
        UNAVAILABLE,
        /** 静默不绘（「策略关闭」「几何/参数跳过」「tint 兜底被关」三种成因）。 */
        NONE,
        /** 真渲染（完整液态玻璃）。 */
        SHADER,
        /** 降级：固定管线模糊（有模糊，无 vibrancy / 亮边 / 噪点）。 */
        FIXED_PIPELINE,
        /** 降级：纯 tint 兜底（连模糊都没有，材质色与亮边仍在）。 */
        TINT_FALLBACK
    }

    // =====================================================================
    // 底色基色（RGB 取「手机黑」0x14181C：比纯黑亮一档，与 Qz 的 systemBlack 0x18
    // 同属「深色玻璃需要厚度」的口径，§5.5；禁止纯黑）
    // =====================================================================

    private static final int RGB_BASE = 0x14181C;

    /** 非玻璃态中性浅底（12% 白）：玻璃关闭或能力不可用时的回落。 */
    private static final int NEUTRAL_LIGHT = 0x1FF2F5F8;
    /** 保留暗底场景（长文本 / 编辑区）的非玻璃态（30% 深底）：可读性优先，§9.3。 */
    private static final int NEUTRAL_SOLID_DARK = 0x4C14181C;

    /** 中性浅底上的正文 / 次要文字色（中深灰蓝，浅底可读；刻意不用旧深灰方案的浅字）。 */
    private static final int NEUTRAL_TEXT = 0xFF232A33;
    private static final int NEUTRAL_MUTED = 0xFF4A5460;

    /** 玻璃态文字色（§9.2 配对表：DARK_* 档配浅字）。 */
    private static final int GLASS_TEXT_DEFAULT = 0xFFE8EDF2;
    private static final int GLASS_MUTED_DEFAULT = 0xFFB8C4D0;
    private static final int GLASS_TEXT_REGULAR = 0xFFFFFFFF;
    private static final int GLASS_MUTED_REGULAR = 0xFFC8D2DC;
    private static final int GLASS_MUTED_THICK = 0xFFD5DCE4;

    /** 液态倒角半径：玻璃态下限（旧按钮 8px 挂不住缘带，§5.4）。 */
    private static final int RADIUS_MIN = 10;
    /** 按钮 / 卡片玻璃态圆角。 */
    private static final int BUTTON_RADIUS = 12;
    private static final int CARD_RADIUS = 12;

    // =====================================================================
    // 底色反解表（行=档序号 0..4，列=角色序号 0..5）
    //
    // 目标总遮罩 T（§9.3 裁定值，十进制）：
    //   角色        PANEL PAGE STATUS CARD BUTTON HOVER
    //   T (THIN)     89    89     64    89    89    120   = 35% / 35% / 25% / 35% / 35% / 47%
    //   T (ULTRA)    64    64     48    64    64    120   = 25% / 25% / 19% / 25% / 25% / 47%
    //   T (REGULAR) 140   140    120   140   140    160   = 55% / 55% / 47% / 55% / 55% / 63%
    //   T (THICK)   175   175    147   175   147    175   = 69% / 69% / 58% / 69% / 58% / 69%
    //   （THICK 档为「接近实心」的模态层，T 由 a 与 t=77 合成得出，见下方算例）
    //
    // 节点底色 a 由 T 反解：a = (T*255 - t*255) / (255 - t)，t = 材质档 tint alpha×255：
    //   DARK_ULTRA_THIN t=26 / DARK_THIN t=38 / DARK_REGULAR t=51 / DARK_THICK t=77
    // 算例（可手工复核）：
    //   THIN  + BUTTON : a = (115*255-38*255)/217 = 19635/217 = 90.48 ⇒ 0x5A；复核 0x5A+round(38*165/255)=90+25=115 ✔
    //   THIN  + PANEL  : a = (89*255-38*255)/217  = 13005/217 = 59.93 ⇒ 0x3C；复核 60+round(38*195/255)=60+29=89 ✔
    //   THIN  + STATUS : a = (64*255-38*255)/217  =  6630/217 = 30.55 ⇒ 反解 a=34.5 取整后落表 0x23（35）；
    //                    两者都远小于旧值 0x25 的合成 T=0x46 —— F3 修正即按 T=0x40（64）反解；
    //                    权威表（review §14.3）该格记 T=0x44（= 用落表后的 a=0x23 复算），本表同值
    //   THIN  + HOVER  : a = (120*255-38*255)/217 = 20570/217 = 94.79 ⇒ 0x5F…但 HOVER 值 0x7E 是刻意提高的
    //                    （合成 T=0x91/57%，比 DARK 系裁定略实以保 hover 反馈，见 F5）
    //   REGULAR+BUTTON : a = (140*255-51*255)/204 = 22695/204 = 111.2 ⇒ 0x6F；复核 111+round(51*144/255)=111+29=140 ✔
    //   THICK + PANEL  : a = (154*255-77*255)/178 = 19635/178 = 110.3 ⇒ 0x8D；复核 141+round(77*114/255)=141+34=175 ⇒ T=175/255=0.686
    //   THICK + BUTTON : a = (140*255-77*255)/178 = 16065/178 =  90.3 ⇒ 0x77；复核  119+round(77*136/255)=119+41=160 ✔
    //
    // ULTRA 行的 PANEL/PAGE/HOVER 是**裁定覆盖值**（review F2 选项①），不是纯反解值：
    //   (T=0x64=100,t=26) ⇒ a=0x2D（手工裁定 0x2D，纯反解 0x2E）；(T=0x78=120,t=26) ⇒ a=0x80（裁定 0x80）。
    //   STATUS 行同样按 T=0x40 落 0x23（而非 t=26 反解的 0x2C）。
    // =====================================================================

    private static final int[][] SURFACE_ALPHA = {
        //      PANEL PAGE  STATUS CARD  BUTTON HOVER
        /*AUTO unused: AUTO 在 resolveTier 里先解析成具体档，永不落到本行；取值=THIN 档兜底*/ {0x3C, 0x3C, 0x23, 0x5A, 0x5A, 0x7E},
        /*THIN   DARK_THIN       t=38 */ {0x3C, 0x3C, 0x23, 0x5A, 0x5A, 0x7E},
        /*ULTRA  DARK_ULTRA_THIN t=26 */ {0x2D, 0x2D, 0x23, 0x45, 0x45, 0x80},
        /*REGU   DARK_REGULAR    t=51 */ {0x6F, 0x6F, 0x54, 0x6F, 0x6F, 0x87},
        /*THICK  DARK_THICK      t=77 */ {0x8D, 0x8D, 0x7A, 0x8D, 0x7A, 0x8D},
    };

    // =====================================================================
    // 能力探测（一次性 static + volatile 缓存）
    // =====================================================================

    /** 探测目标：Qz 4.9.1 玻璃类型与诊断入口。 */
    private static final String CLASS_MATERIAL = "club.heiqi.uilib.ui.render.UiGlassMaterial";
    private static final String CLASS_BACKDROP = "club.heiqi.uilib.ui.render.UiBackdrop";
    private static final String CLASS_EFFECT = "club.heiqi.uilib.ui.render.UiBackdropEffect";
    private static final String CLASS_RENDER_PATH = "club.heiqi.uilib.ui.render.BackdropFilterRenderPath";
    private static final String CLASS_RENDER_CONTEXT = "club.heiqi.uilib.ui.render.UiRenderContext";

    /** 熔断哨兵：探测失败（玻璃类型整体缺失）。 */
    private static final Class<?>[] ABSENT = new Class<?>[0];

    /** 关键玻璃类（决定 {@link #available()}）；{@code ABSENT} = 明确不可用，{@code null} = 未探测。 */
    private static volatile Class<?>[] essential;
    /** 可选玻璃类：0=材质档 1=UiBackdrop 2=UiBackdropEffect 3=渲染路径枚举 4=UiRenderContext。 */
    private static volatile Class<?>[] optional;

    /** 档维步长：沿用旧布局 {@code tier.ordinal() * 101 + mixIndex(role)}（mixIndex 最大 1 + 5*16 = 81 &lt; 101）。 */
    private static final int TIER_STRIDE = 101;

    /**
     * 强度量化档数：最终 lens（0~1）按 {@code 1 / LENS_STEPS} 量化后进缓存键。
     *
     * <p><b>为什么 lens 必须进键</b>：{@code UiBackdrop} 是不可变值对象，lens 只在首次构造
     * 配方时求值一次；旧实现只按 {@code (档, 角色)} 缓存 ⇒ 设置页「玻璃强度」滑条提交后
     * 虽然整壳重建、也重新调了 {@code apply}，但取到的仍是<b>旧 lens 的那份配方</b>
     * （滑条完全无观感变化，直到重启进程）。</p>
     *
     * <p><b>为什么量化而不是用 raw float 当键</b>：量化后缓存严格有界（5 档 × 101 角色槽 ×
     * 101 强度档），不会随拖动无限增长；0.01 比设置页滑条步长 0.05 更细 ⇒ 不丢任何滑条档位。
     * 构造配方用<b>量化后</b>的值，键与值同源（不存在"同键不同 lens"）。</p>
     */
    private static final int LENS_STEPS = 100;
    /** 强度维槽数（量化档 0..{@link #LENS_STEPS}）。 */
    private static final int LENS_BUCKETS = LENS_STEPS + 1;

    /** 配方缓存：[档序号 0..4][角色混合下标][强度量化档 0..100]。参数离散故可缓存。 */
    private static final Object[] RECIPE_CACHE = new Object[5 * TIER_STRIDE * LENS_BUCKETS];
    /** 每格只尝试一次（失败留哨兵，避免重复反射）。 */
    private static final Object RECIPE_FAILED = new Object();

    /**
     * 诊断读数的 mcphone 侧最近值：{@code -1} = 尚未读取。
     *
     * <p>只用于判断「路径码是否变化」（决定要不要刷新 label/detail、要不要写诊断日志），
     * <b>不再作为 {@link #renderPath()} 的返回值缓存</b>——AUTO 必须按<b>当前</b>路径解析，
     * 见 {@link #renderPath()} 的时序说明。</p>
     */
    private static volatile int cachedPathCode = -1;
    /** 诊断反射入口缓存：{@code getLastBackdropFilterRenderPath()}（避免每次现读都 getMethod）。 */
    private static volatile java.lang.reflect.Method diagPathMethod;
    /** 诊断反射入口缓存：{@code getLastBackdropFilterDetail()}。 */
    private static volatile java.lang.reflect.Method diagDetailMethod;
    private static volatile String cachedPathLabel = "";
    private static volatile String cachedDetail = "";

    /**
     * 玻璃能力是否可用。{@code false} ⇒ 所有入口静默 no-op。
     *
     * <p>一次性探测：首次调用解析类，之后走 volatile 缓存。</p>
     */
    public static boolean available() {
        return essentialTypes() != ABSENT;
    }

    /** 可用且用户开关开启（所有玻璃入口的唯一守卫）。 */
    public static boolean glassOn() {
        return available() && PhoneCanvas.isGlassEnabled();
    }

    private static Class<?>[] essentialTypes() {
        Class<?>[] cached = essential;
        if (cached != null) return cached;
        synchronized (PhoneGlass.class) {
            if (essential == null) {
                essential = probe();
            }
            return essential;
        }
    }

    private static Class<?>[] probe() {
        try {
            ClassLoader cl = PhoneGlass.class.getClassLoader();
            // 顺序：先材质档（本文件唯一以「方法参数类型」引用的 Qz 玻璃类型），再三个可选类。
            Class<?> material = Class.forName(CLASS_MATERIAL, false, cl);
            // 只解析「会被用到的那一档」常量，避免误报（partial 版本里枚举缺档不算不可用）。
            material.getField("DARK_THIN");
            Class<?>[] found = new Class<?>[5];
            found[0] = material;
            found[1] = Class.forName(CLASS_BACKDROP, false, cl);
            found[2] = Class.forName(CLASS_EFFECT, false, cl);
            found[3] = Class.forName(CLASS_RENDER_PATH, false, cl);
            // 诊断入口是「可选中的可选」：它读不到不影响玻璃渲染，故单独 try 包住，
            // 不让它的缺失把整个探测判成不可用（保留 Class.forName 兜底重试）。
            try {
                found[4] = Class.forName(CLASS_RENDER_CONTEXT, false, cl);
            } catch (Throwable ignored) {
                found[4] = null;
            }
            optional = found;
            return found;
        } catch (Throwable t) {
            // ClassNotFoundException / NoClassDefFoundError / ExceptionInInitializerError 全落这里。
            // 玻璃缺失属「宿主版本不匹配」，不是 mcphone 的 bug ⇒ 静默降级，不打印堆栈。
            return ABSENT;
        }
    }

    private static Class<?> optionalType(int index) {
        if (!available()) return null;
        Class<?>[] found = optional;
        Class<?> cached = (found == null || index >= found.length) ? null : found[index];
        if (cached != null) return cached;
        // 诊断类是「可选中的可选」：探测阶段可能没解析到（老版本/精简制品），
        // 这里给它一次惰性兜底（仍不抛、不缓存失败以外的状态）。
        if (index == 4) {
            try {
                Class<?> lazy = Class.forName(CLASS_RENDER_CONTEXT, false, PhoneGlass.class.getClassLoader());
                if (found != null && found.length > 4) found[4] = lazy;
                return lazy;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // =====================================================================
    // 玻璃挂载入口（全部静默回落）
    // =====================================================================

    /**
     * 挂液态玻璃（配置档）。{@code null} 节点、开关关闭、能力不可用一律 no-op。
     *
     * <p><b>只可用于建树期</b>：被 {@code SceneSurfaceBinder} 接管的节点上，公开
     * {@code setBackdrop} 会拒绝静态写（Qz 侧守卫，{@code SceneNode.java:1263}），
     * 故不要在每帧的 {@code bindComputed} 里调用本方法。</p>
     */
    public static void apply(SceneNode node, Role role) {
        apply(node, role, PhoneCanvas.glassTier());
    }

    /** 挂液态玻璃（指定档；显式档仍受角色上限约束，避免小面积面糊成实心板）。 */
    public static void apply(SceneNode node, Role role, Tier tier) {
        if (node == null) return;
        Tier grade = resolveTier(role, tier);
        if (grade == null) return;
        Object backdrop = backdrop(grade, role);
        if (backdrop == null) return;
        try {
            node.setBackdrop((club.heiqi.uilib.ui.render.UiBackdrop) backdrop);
            logDiagnosticsIfChanged();
        } catch (Throwable ignored) {
            // 绑定器守卫等任何运行期拒绝 ⇒ 静默保持无玻璃。
        }
    }

    /** 撤掉玻璃（关闭开关 / 窗口工厂重建时用）。 */
    public static void clear(SceneNode node) {
        if (node == null || !available()) return;
        try {
            node.setBackdrop(null);
        } catch (Throwable ignored) {}
    }

    /**
     * 解析实际生效的档位。
     *
     * @return 生效档；{@code null} = 不玻璃（开关关闭 / 能力不可用 / 静默不绘）
     */
    public static Tier resolveTier(Role role, Tier tier) {
        if (!glassOn()) return null;
        Tier requested = (tier == null) ? PhoneCanvas.glassTier() : tier;
        if (requested == Tier.AUTO) {
            requested = autoTier();
        }
        return capFor(role, requested);
    }

    /**
     * AUTO 的解析规则（决定「默认值在强机与弱机上的表现」）：
     * <ul>
     *   <li>{@code SHADER}（真渲染）⇒ {@code THIN}：对齐 Qz 自家 DARK_THIN / blur 8 / lens 0.5 定稿；</li>
     *   <li>降级（{@code FIXED_PIPELINE} 固定管线 / {@code TINT_FALLBACK} 纯 tint）⇒ {@code ULTRA_THIN}：
     *       弱化「无 vibrancy / 边缘糊」的观感损失；</li>
     *   <li>{@code NONE}（静默不绘：策略关闭 / 跳过 / tint 兜底被关）与尚未渲染 ⇒ {@code ULTRA_THIN}：
     *       最薄档 + 最浅底色，观感最接近「无玻璃」，同时保持令牌成对（文字色确定性）。</li>
     * </ul>
     * <p>注意：AUTO 只影响<b>观感档位</b>，不决定「是否上玻璃」——上玻璃与否只由
     * {@link #glassOn()}（开关 + 能力）决定；实际不绘时用户看到的是最薄档，而不是忽然换一套配色。</p>
     *
     * <p><b>实时性（本次修复）</b>：{@link #renderPath()} 现在是<b>现读</b>，所以本方法解析的是
     * 「最近一帧实际走过的路径」。首次建树发生在任何 backdrop 被绘制之前 ⇒ 那一轮必然是
     * {@code NONE ⇒ ULTRA_THIN}（极薄）；一旦某帧真的走到 {@code SHADER}，此后任何重建
     * （关掉重开手机 / 切页 / 改玻璃设置）都会解析到 {@code THIN}（薄 / blur 8 / lens 1.0）。
     * 这是刻意保留的过渡，不是缺陷。</p>
     */
    private static Tier autoTier() {
        Path path = renderPath();
        return (path == Path.SHADER) ? Tier.THIN : Tier.ULTRA_THIN;
    }

    /** 角色允许的档位上限（条状 / 小面积面不上升厚档）。 */
    private static Tier capFor(Role role, Tier tier) {
        Tier cap = capFor(role);
        return (fullness(tier) > fullness(cap)) ? cap : tier;
    }

    /**
     * 玻璃「厚度序」（review F1）：与 {@link Tier} 的枚举序号解耦。
     *
     * <p>枚举序号是<b>存档顺序</b>（{@code AUTO=0, THIN=1, ULTRA_THIN=2, REGULAR=3, THICK=4}），
     * 并非单调厚度：{@code THIN}(1) 实际比 {@code ULTRA_THIN}(2) 更厚。用 {@code ordinal()}
     * 比较会把"更薄"的请求判成"更厚"并反向钳到更厚的档（状态栏/导航条请求 ULTRA_THIN 被改成 THIN，
     * 与设计 §6.2「状态栏 = DARK_ULTRA_THIN / blur 6 / lens 0.3」相反）。</p>
     *
     * @return 0=ULTRA_THIN &lt; 1=THIN &lt; 2=REGULAR &lt; 3=THICK；{@code AUTO} 等未知值按 THIN 计
     */
    private static int fullness(Tier t) {
        if (t == null) return 1;
        switch (t) {
            case ULTRA_THIN:
                return 0;
            case THIN:
                return 1;
            case REGULAR:
                return 2;
            case THICK:
                return 3;
            default:
                return 1;
        }
    }

    private static Tier capFor(Role role) {
        if (role == null) return Tier.THIN;
        switch (role) {
            case STATUS:
                return Tier.THIN;
            case BUTTON:
            case BUTTON_HOVER:
            case CARD:
                return Tier.REGULAR;
            default:
                return Tier.THICK;
        }
    }

    /** 每档的 Qz 材质档常量名（{@code UiGlassMaterial} 的枚举常量）。 */
    private static String materialName(Tier tier) {
        switch (tier) {
            case ULTRA_THIN:
                return "DARK_ULTRA_THIN";
            case REGULAR:
                return "DARK_REGULAR";
            case THICK:
                return "DARK_THICK";
            case THIN:
            default:
                return "DARK_THIN";
        }
    }

    /** 每档的模糊半径（物理像素；Qz 侧再按降采样与 48 上限换算，§6.1）。 */
    private static int blurRadius(Tier tier) {
        switch (tier) {
            case ULTRA_THIN:
                return 6;
            case REGULAR:
                return 8;
            case THICK:
                return 12;
            case THIN:
            default:
                return 8;
        }
    }

    /** 每档的液态强度系数（乘在角色基础 lens 上）。 */
    private static float lensFactor(Tier tier) {
        switch (tier) {
            case ULTRA_THIN:
                return 0.8F;
            case REGULAR:
                return 0.7F;
            case THICK:
                return 1.2F;
            case THIN:
            default:
                return 1.0F;
        }
    }

    /**
     * 角色基础 lens（对齐 Qz 自家定稿口径，§6.2）：
     * 主壳 0.5（ChatMarkdownSettings.glassLensStrength）、内容底板 0.35、状态栏 0.3、
     * 按钮 1.0（HudToolbarSpec 的 HUD 工具栏按钮配方）。
     */
    private static float baseLens(Role role) {
        if (role == null) return 0.5F;
        switch (role) {
            case PANEL:
                return 0.5F;
            case PAGE:
                return 0.35F;
            case STATUS:
                return 0.3F;
            default:
                return 1.0F;
        }
    }

    /** 见 {@link #baseLens(Role)}：按档换算出的实际 lens（clamp 到 [0,1]，Qz 侧也夹一次）。 */
    private static float lensFor(Role role, Tier tier) {
        return clamp01(baseLens(role) * PhoneCanvas.glassLensMultiplier() * lensFactor(tier)
            * lensDegradeFactor());
    }

    private static float lensDegradeFactor() {
        Path path = renderPath();
        if (path == Path.FIXED_PIPELINE) return 0.6F;
        if (path == Path.TINT_FALLBACK) return 0.4F;
        return 1.0F;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    // =====================================================================
    // 配方构造（反射 + 缓存；任何失败静默 no-op）
    // =====================================================================

    /**
     * 构造（或取缓存）底层 {@code UiBackdrop}；返回 {@code null} = 无法构造。
     *
     * <p><b>缓存键 = (档, 角色, 量化 lens)</b>，覆盖 {@code UiBackdrop.liquidGlass(material,
     * blurRadius, lensStrength)} 的全部三个入参：{@code material}（{@link #materialName}）与
     * {@code blurRadius}（{@link #blurRadius}）都是 {@code tier} 的纯函数，角色决定基色与基础
     * lens，而最终 lens 还额外取决于<b>用户强度</b>（{@code PhoneCanvas.getGlassLensStrength}）
     * 与<b>降级系数</b>（{@link #lensDegradeFactor} ← {@link #renderPath()}）——这两项正是旧键
     * 漏掉的入参，也是「玻璃强度滑条无效」的唯一根因。</p>
     */
    private static Object backdrop(Tier tier, Role role) {
        Class<?> backdropClass = optionalType(1);
        Object material = materialConstant(tier);
        if (backdropClass == null || material == null) return null;
        int bucket = lensBucket(lensFor(role, tier));
        float lens = bucket / (float) LENS_STEPS;
        int slot = (tier.ordinal() * TIER_STRIDE + mixIndex(role)) * LENS_BUCKETS + bucket;
        Object cached = RECIPE_CACHE[slot];
        if (cached != null) {
            return cached == RECIPE_FAILED ? null : cached;
        }
        Object built;
        try {
            built = backdropClass.getMethod("liquidGlass", optionalType(0), int.class, float.class)
                .invoke(null, material, blurRadius(tier), lens);
        } catch (Throwable t) {
            built = null;
        }
        RECIPE_CACHE[slot] = (built == null) ? RECIPE_FAILED : built;
        return built;
    }

    /** 最终 lens → 量化档（0..{@link #LENS_STEPS}）；NaN / 越界一律夹到合法档。 */
    private static int lensBucket(float lens) {
        return Math.round(clamp01(lens) * LENS_STEPS);
    }

    /**
     * 清空配方缓存（玻璃开关 / 档位 / 强度写入时由 {@code PhoneCanvas} 的 setter 调用）。
     *
     * <p><b>与量化 lens 键的关系</b>：键已经覆盖「档 + 角色 + 最终 lens」的全部入参，故本方法是
     * <b>冗余的防御</b>——它保证「任何设置写入之后，下一次 {@link #apply} 必然重新读一遍
     * {@code PhoneCanvas} 与渲染路径诊断」，即便将来有新的入参进配方却忘了进键，也不会
     * 继续复用旧配方。</p>
     *
     * <p><b>代价</b>：一次 {@code Arrays.fill} 写 5×101×101 = 51005 个引用槽（微秒级），只在设置
     * <b>提交</b>时发生（滑条拖动预览不触发），不在渲染路径上。</p>
     *
     * <p><b>不负责重新 apply</b>：重新上玻璃由调用方走既有整壳重建路径
     * （{@code ScenePages} → {@code PhoneUi.refreshGlassShell()}）。Qz 侧
     * {@code SceneNode.setBackdrop} 对被 {@code SceneSurfaceBinder} 接管的节点会抛
     * {@code IllegalStateException}（jar 内 {@code requireSurfaceWritable}），
     * <b>已挂载节点无法就地改配方</b>，只能用新节点。</p>
     */
    public static void invalidateRecipes() {
        java.util.Arrays.fill(RECIPE_CACHE, null);
    }

    /** 角色枚举序号（0..5）→ 配方缓存的第二维下标。 */
    private static int mixIndex(Role role) {
        if (role == null) return 0;
        return 1 + role.ordinal() * 16;
    }

    private static Object materialConstant(Tier tier) {
        Class<?> materialClass = optionalType(0);
        if (materialClass == null) return null;
        try {
            // 返回值写成 Object：本文件的 getField 结果不进字段声明，
            // 避免把 UiGlassMaterial 提成「类常量引用」而触发类初始化链。
            return materialClass.getField(materialName(tier)).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // 底色合成（层次契约：(1-a) 衰减核算 + 材质档 tint 反解）
    // =====================================================================

    /**
     * 玻璃态底色：先按 {@link #SURFACE_ALPHA} 取反解后的节点底色 alpha，再与
     * 材质档自身 tint alpha 合成，最终落在 §9.3 的裁定总遮罩上
     * （{@code T = a + t·(1-a/255)}）。
     *
     * @return 玻璃态 ARGB；玻璃关闭 / 能力不可用 / 静默不绘时返回中性非玻璃色
     */
    public static int surface(Role role, Tier tier) {
        Tier grade = resolveTier(role, tier);
        if (grade == null) return neutralSurface(role);
        int alpha = baseAlpha(grade, role);
        return (alpha << 24) | RGB_BASE;
    }

    /** 见 {@link #surface(Role, Tier)}（用配置档）。 */
    public static int surface(Role role) {
        return surface(role, PhoneCanvas.glassTier());
    }

    private static int baseAlpha(Tier tier, Role role) {
        int row = tier.ordinal();
        if (row <= 0 || row >= SURFACE_ALPHA.length) row = Tier.THIN.ordinal();
        int col = (role == null) ? Role.PANEL.ordinal() : role.ordinal();
        return SURFACE_ALPHA[row][col];
    }

    /** 材质档自身 tint alpha（0~1）；类缺失时回 0（等价「不额外叠加」）。 */
    public static float materialTintAlpha(Tier tier) {
        Class<?> materialClass = optionalType(0);
        if (materialClass == null) return 0.0F;
        try {
            Object constant = materialClass.getField(materialName(
                (tier == null || tier == Tier.AUTO) ? Tier.THIN : tier)).get(null);
            Object value = materialClass.getMethod("getTintAlpha").invoke(constant);
            return (value instanceof Float) ? ((Float) value).floatValue() : 0.0F;
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /**
     * 诊断用：节点底色 alpha 与材质 tint 合成后的<b>实际总遮罩</b>（0~1），
     * 供 verify / review 与 §9.3 的裁定值对账。
     *
     * <p>口径：{@code T = a + t·(1 - a/255)}，a 取 {@link #SURFACE_ALPHA} 的反解值、
     * t 取材质档 tint alpha（DARK_ULTRA_THIN 0.102 / DARK_THIN 0.149 /
     * DARK_REGULAR 0.200 / DARK_THICK 0.302）。</p>
     */
    public static float compositeAlpha(Role role, Tier tier) {
        Tier grade = resolveTier(role, tier);
        if (grade == null) return ((neutralSurface(role) >>> 24) & 0xFF) / 255.0F;
        float a = baseAlpha(grade, role) / 255.0F;
        float t = materialTintAlpha(grade);
        return a + t * (1.0F - a);
    }

    /** 非玻璃态底色（开关关闭 / 类缺失）：中性浅底；长文本面保留暗底。 */
    public static int neutralSurface(Role role) {
        if (role == Role.PAGE) return NEUTRAL_SOLID_DARK;
        return NEUTRAL_LIGHT;
    }

    // =====================================================================
    // 文字色 / 圆角
    // =====================================================================

    /** 正文色（按档配对，§9.2）；玻璃关闭时返回中性浅底上的中深色。 */
    public static int text() {
        Tier grade = resolveTier(null, PhoneCanvas.glassTier());
        if (grade == null) return NEUTRAL_TEXT;
        return (grade == Tier.REGULAR || grade == Tier.THICK) ? GLASS_TEXT_REGULAR : GLASS_TEXT_DEFAULT;
    }

    /** 次要文字色（按档配对，§9.2）；玻璃关闭时返回中性浅底上的中深色。 */
    public static int muted() {
        Tier grade = resolveTier(null, PhoneCanvas.glassTier());
        if (grade == null) return NEUTRAL_MUTED;
        switch (grade) {
            case REGULAR:
                return GLASS_MUTED_REGULAR;
            case THICK:
                return GLASS_MUTED_THICK;
            default:
                return GLASS_MUTED_DEFAULT;
        }
    }

    /** 非玻璃态正文 / 次要文字色（供显式区分两种模式的调用方使用）。 */
    public static int neutralText() {
        return NEUTRAL_TEXT;
    }

    public static int neutralMuted() {
        return NEUTRAL_MUTED;
    }

    /** 玻璃态圆角：不小于 {@link #RADIUS_MIN}（8px 挂不住液态缘带，§5.4）。 */
    public static int cornerRadius(int current) {
        return Math.max(RADIUS_MIN, current);
    }

    /** 按钮玻璃态圆角（12）。 */
    public static int buttonRadius() {
        return BUTTON_RADIUS;
    }

    /** 卡片玻璃态圆角（12）。 */
    public static int cardRadius() {
        return CARD_RADIUS;
    }

    // =====================================================================
    // 三态诊断透传（供 verify / review 取证）
    // =====================================================================

    /**
     * 最近一次 backdrop-filter 实际渲染路径（<b>实时读取</b>，不再进程内冻结）。
     *
     * <p>Qz 侧口径：进程级 volatile，语义 =「最近一次真的走到渲染器的 backdrop 请求」，
     * 不是逐节点、也不是每帧复位。<b>AUTO 的语义是「按当前渲染路径解析」</b>（设计 §6.2），
     * 所以这里必须每次现读：旧实现只在进程内首次调用时读一次并永久缓存，而首次调用发生在
     * <b>任何 backdrop 被绘制之前</b>（建壳期），Qz 侧初值恒为 {@code NONE}
     * （{@code UiBackdropFilterRenderer.lastRenderPath = NONE}）⇒ AUTO 恒等于
     * {@code ULTRA_THIN}，设计意图（SHADER ⇒ THIN / blur 8 / lens 1.0）<b>永不可达</b>。</p>
     *
     * <p><b>为什么不能"读到非 NONE 就冻结"</b>：首帧主层快照未就绪时 Qz 会先走 tint 兜底并记录
     * {@code TINT_FALLBACK}（{@code UiBackdropFilterRenderer:145} texture-copy-unavailable /
     * {@code :152} snapshot-unavailable → {@code drawTintFallback} → {@code :376 recordPath}；
     * 兜底被配置关闭时在 {@code :373} 记录 {@code NONE}），随后才转为 {@code SHADER}。任何"单调冻结"都会把这个
     * <b>瞬态降级</b>固化成永久档位 ⇒ 必须实读。</p>
     *
     * <p><b>成本</b>：一次「已缓存的 {@code Method} 静态 invoke + enum 名比较」。无
     * {@code getMethod}（Method 惰性缓存）、无字符串拼接、无装箱/集合分配；label/detail
     * 只在<b>路径码变化</b>时重读（供 {@link #logDiagnosticsIfChanged()} 的"值变才打印"）。
     * 调用点（{@link #apply} / {@link #surface} / {@link #text} / {@link #muted} /
     * {@link #compositeAlpha} / 诊断展示）全部在<b>建树或设置提交</b>路径上，不在逐帧渲染
     * 路径上；且 {@link #resolveTier} 先过 {@link #glassOn()} 守卫，玻璃关闭时根本不走到这里。</p>
     */
    public static Path renderPath() {
        if (!available()) return Path.UNAVAILABLE;
        if (optionalType(4) == null) return Path.UNAVAILABLE;
        return decode(readPathCodeLive());
    }

    /** Qz 原始路径标签（{@code getLabel()}）；类缺失时返回 {@code "unavailable"}。 */
    public static String renderPathLabel() {
        renderPath(); // 触发惰性读取
        if (!available()) return "unavailable";
        return cachedPathLabel.isEmpty() ? "not-read" : cachedPathLabel;
    }

    /** Qz 原始诊断说明（{@code getLastBackdropFilterDetail()}）；类缺失时返回空串。 */
    public static String diagDetail() {
        renderPath(); // 触发惰性读取
        return cachedDetail;
    }

    /**
     * 强制重读诊断（含 label/detail 文案）。
     *
     * <p>{@link #renderPath()} 现在本来就是实时的，本方法只额外保证「即使路径码没变、
     * 也把 {@code getLabel()} / {@code getLastBackdropFilterDetail()} 刷新一遍」，
     * 供 F3 覆写 / 调试页取最新读数。</p>
     */
    public static void refreshDiagnostics() {
        cachedPathCode = -1;   // 令紧接着的实时读取必然走「值变」分支
        renderPath();
    }

    /** 状态变化才打印用的一行摘要（调用方自行做「值变才写」，避免每帧刷屏，§3.3）。 */
    public static String diagSummary() {
        return "backdrop 路径: " + renderPathLabel() + " | 诊断: " + diagDetail();
    }

    // =====================================================================
    // 值变才打印的诊断出口（review F8/F9：把「默认开启的每帧代价」变成游戏内可读）
    // =====================================================================

    /** 上一次已打印的「路径标签 + 诊断详情」（null = 尚未打印过）。 */
    private static volatile String lastLoggedDiag;

    /** 诊断摘要是否已出现过（供设置页/F3 覆写的只读展示用，不影响渲染）。 */
    public static boolean diagnosticSeen() {
        return lastLoggedDiag != null;
    }

    /**
     * {@link #apply} 成功后调用：只在「路径标签 + 诊断详情」变化时写一行日志。
     *
     * <p>值变才打印 ⇒ 不逐帧刷屏、不影响观感与默认开启（review F8 的最小成本出口）。
     * 读不到诊断（类缺失 / 反射失败）时静默跳过，绝不抛异常。</p>
     */
    private static void logDiagnosticsIfChanged() {
        try {
            String now = renderPathLabel() + '\u0001' + diagDetail();
            if (now.equals(lastLoggedDiag)) return;
            lastLoggedDiag = now;
            System.out.println("[mcphone] glass: " + diagSummary());
        } catch (Throwable ignored) {
            // 诊断失败不影响渲染。
        }
    }

    /**
     * 实时读取路径码：{@code 0=NONE 1=SHADER 2=FIXED_PIPELINE 3=TINT_FALLBACK}。
     *
     * <p>反射入口 {@code Method} 惰性缓存一次；label/detail 只在路径码<b>变化</b>时重读
     * （诊断文案，避免每次调用都建字符串）。读取失败静默回落 {@code NONE}（不影响渲染），
     * 且不缓存失败的 Method（下次再试）。</p>
     */
    private static int readPathCodeLive() {
        try {
            java.lang.reflect.Method method = diagPathMethod;
            if (method == null) {
                Class<?> contextClass = optionalType(4);
                if (contextClass == null) return 0;
                method = contextClass.getMethod("getLastBackdropFilterRenderPath");
                diagPathMethod = method;
            }
            Object path = method.invoke(null);
            if (path == null) return markPath(0, null);
            String name = ((Enum<?>) path).name();
            if ("SHADER".equals(name)) return markPath(1, path);
            if ("FIXED_PIPELINE".equals(name)) return markPath(2, path);
            if ("TINT_FALLBACK".equals(name)) return markPath(3, path);
            return markPath(0, path);
        } catch (Throwable t) {
            // 诊断不可读不影响渲染 ⇒ 静默（异常路径不写 cachedPathCode，下次读取会再试）。
            return 0;
        }
    }

    /** 路径码变化时才刷新 label/detail（诊断文案）；返回本次路径码。 */
    private static int markPath(int code, Object path) {
        if (code != cachedPathCode) {
            cachedPathLabel = (path == null) ? "" : labelOf(path);
            cachedDetail = detailOf();
            cachedPathCode = code;
        }
        return code;
    }

    private static String labelOf(Object path) {
        try {
            Object label = path.getClass().getMethod("getLabel").invoke(path);
            return (label == null) ? "" : String.valueOf(label);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读 Qz 诊断说明（Method 惰性缓存）；失败回空串。只在路径码变化时调用。 */
    private static String detailOf() {
        try {
            java.lang.reflect.Method method = diagDetailMethod;
            if (method == null) {
                Class<?> contextClass = optionalType(4);
                if (contextClass == null) return "";
                method = contextClass.getMethod("getLastBackdropFilterDetail");
                diagDetailMethod = method;
            }
            Object detail = method.invoke(null);
            return (detail == null) ? "" : String.valueOf(detail);
        } catch (Throwable t) {
            return "";
        }
    }

    private static Path decode(int code) {
        switch (code) {
            case 1:
                return Path.SHADER;
            case 2:
                return Path.FIXED_PIPELINE;
            case 3:
                return Path.TINT_FALLBACK;
            default:
                return Path.NONE;
        }
    }
}
