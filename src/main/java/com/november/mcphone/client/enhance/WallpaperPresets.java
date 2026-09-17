package com.november.mcphone.client.enhance;

import java.awt.image.BufferedImage;

/**
 * 内置预设壁纸（程序生成的竖向渐变）。
 *
 * <p><b>本轮改动（壁纸机制上游化）</b>：上游 {@code WallpaperStore}（v1.10.2）<b>没有内置预设</b>，
 * 壁纸唯一来源是扫描 {@code config/mcphone/wallpapers/*.png}。为了让「预设」与「用户图片」
 * 统一成同一条路径，本类<b>不再自己落盘、也不再自己调 {@code PhoneUi.refreshWallpaper()}</b>，
 * 只保留纯数据与纯生成：
 * <ol>
 *   <li>{@link #generate(int)}：按索引生成预设位图；</li>
 *   <li>{@link #fileNameOf(int)} / {@link #nameKeyForFile(String)} / {@link #indexOfFile(String)}：
 *       预设与外置图片之间的文件名 ↔ 本地化名映射，供
 *       {@link WallpaperStore#ensureBuiltIns()} 首次运行把 6 个预设写成目录下的 PNG。</li>
 * </ol>
 *
 * <p>落盘后这些 PNG 与玩家自己丢进目录的图<b>完全同权</b>：同一份列表、同一条加载路径、
 * 同一套缩略图渲染，也可以被玩家删除（删了不会自动重生成）。</p>
 */
public final class WallpaperPresets {

    /** 一条预设：名称键 + 顶部/底部渐变色 + 落盘文件名。 */
    public static final class Preset {

        public final String nameKey;
        public final int top;
        public final int bottom;
        /** 写进壁纸目录的文件名（统一的 {@code *.png} 口径）。 */
        public final String fileName;

        Preset(String nameKey, int top, int bottom, String fileName) {
            this.nameKey = nameKey;
            this.top = top;
            this.bottom = bottom;
            this.fileName = fileName;
        }

        /** 色块缩略图代表色（取渐变中点）。 */
        public int swatchColor() {
            return blend(top, bottom, 0.5f);
        }
    }

    /**
     * 6 条预设。
     *
     * <p>文件名刻意只用 {@code [a-z0-9-]}（上游 {@code WallpaperStore.java:39-46} 那条注释
     * 记过"我的 壁纸.png / 我的_壁纸.png 撞键"的旧 bug）；{@code preset-N-} 前缀用于排序，
     * 排序键落在 {@link WallpaperStore#refresh()} 的 {@code rankOf} 里。</p>
     */
    private static final Preset[] PRESETS = {
        new Preset("wp.mcphone.midnight", 0xFF101828, 0xFF05070C, "preset-1-midnight.png"),
        new Preset("wp.mcphone.dawn",     0xFF5A3A66, 0xFFE08A5A, "preset-2-dawn.png"),
        new Preset("wp.mcphone.ocean",    0xFF1E4A6E, 0xFF0D1F33, "preset-3-ocean.png"),
        new Preset("wp.mcphone.forest",   0xFF2E5238, 0xFF142318, "preset-4-forest.png"),
        new Preset("wp.mcphone.dusk",     0xFF4A2E5C, 0xFF181028, "preset-5-dusk.png"),
        new Preset("wp.mcphone.graphite", 0xFF3A414D, 0xFF1A1E24, "preset-6-graphite.png"),
    };

    /**
     * 生成尺寸：竖向渐变按手机比例绘制，{@code PhoneUi.loadWallpaper} 会再按面板比例裁剪。
     *
     * <p>取值与旧版 {@code apply()} 一致（360×640），保证升级后预设的观感不变。</p>
     */
    private static final int IMG_W = 360;
    private static final int IMG_H = 640;

    private WallpaperPresets() {}

    public static int count() {
        return PRESETS.length;
    }

    public static Preset get(int index) {
        return PRESETS[Math.max(0, Math.min(PRESETS.length - 1, index))];
    }

    /** 第 {@code index} 条预设写进壁纸目录时用的文件名。 */
    public static String fileNameOf(int index) {
        return get(index).fileName;
    }

    /** 文件名对应的预设下标；不是内置预设名时返回 -1。 */
    public static int indexOfFile(String fileName) {
        if (fileName == null) return -1;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].fileName.equalsIgnoreCase(fileName)) return i;
        }
        return -1;
    }

    /** 文件名对应的本地化名键；不是内置预设名时返回 null（调用方回退到文件名）。 */
    public static String nameKeyForFile(String fileName) {
        int i = indexOfFile(fileName);
        return i < 0 ? null : PRESETS[i].nameKey;
    }

    /**
     * 生成第 {@code index} 条预设的位图（竖向渐变）。
     *
     * <p>旧版本 {@code apply(index)} 是「生成 → 写 {@code mcphone/wallpaper.png} → 刷新」；
     * 现在只保留"生成"这一步，写盘与刷新由 {@link WallpaperStore} 与设置页负责。</p>
     */
    public static BufferedImage generate(int index) {
        Preset p = get(index);
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < IMG_H; y++) {
            int c = blend(p.top, p.bottom, y / (float) (IMG_H - 1));
            for (int x = 0; x < IMG_W; x++) {
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
