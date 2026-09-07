package com.november.mcphone.client.enhance;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.scene.PhoneUi;

/**
 * 内置预设壁纸（程序生成的竖向渐变，不引入贴图资源）。
 *
 * <p>应用逻辑复用"相册照片设壁纸"路径：生成 BufferedImage → 写
 * mcphone/wallpaper.png → {@link PhoneUi#refreshWallpaper()}（含面板比例
 * 中心裁剪）。"重置壁纸"按钮删除该文件即回到默认深色底。</p>
 */
public final class WallpaperPresets {

    /** 一条预设：名称键 + 顶部/底部渐变色。 */
    public static final class Preset {

        public final String nameKey;
        public final int top;
        public final int bottom;

        Preset(String nameKey, int top, int bottom) {
            this.nameKey = nameKey;
            this.top = top;
            this.bottom = bottom;
        }

        /** 色块缩略图代表色（取渐变中点）。 */
        public int swatchColor() {
            return blend(top, bottom, 0.5f);
        }
    }

    private static final Preset[] PRESETS = {
        new Preset("wp.mcphone.midnight", 0xFF101828, 0xFF05070C),
        new Preset("wp.mcphone.dawn",     0xFF5A3A66, 0xFFE08A5A),
        new Preset("wp.mcphone.ocean",    0xFF1E4A6E, 0xFF0D1F33),
        new Preset("wp.mcphone.forest",   0xFF2E5238, 0xFF142318),
        new Preset("wp.mcphone.dusk",     0xFF4A2E5C, 0xFF181028),
        new Preset("wp.mcphone.graphite", 0xFF3A414D, 0xFF1A1E24),
    };

    /** 生成尺寸：竖向渐变按手机比例绘制，loadWallpaper 会再按面板比例裁剪。 */
    private static final int IMG_W = 360;
    private static final int IMG_H = 640;

    private WallpaperPresets() {}

    public static int count() {
        return PRESETS.length;
    }

    public static Preset get(int index) {
        return PRESETS[Math.max(0, Math.min(PRESETS.length - 1, index))];
    }

    /** 生成并应用预设壁纸；失败返回 false。 */
    public static boolean apply(int index) {
        Preset p = get(index);
        try {
            BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < IMG_H; y++) {
                int c = blend(p.top, p.bottom, y / (float) (IMG_H - 1));
                for (int x = 0; x < IMG_W; x++) {
                    img.setRGB(x, y, c);
                }
            }
            File out = PhotoStore.wallpaperFile();
            ImageIO.write(img, "png", out);
            PhoneUi.refreshWallpaper();
            return true;
        } catch (Exception e) {
            return false;
        }
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
