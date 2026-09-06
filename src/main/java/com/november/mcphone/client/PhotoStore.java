package com.november.mcphone.client;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;

import com.november.mcphone.MCphone;

/**
 * 照片与壁纸存储（客户端本地）：.minecraft/mcphone/photos、mcphone/wallpaper.png。
 */
public final class PhotoStore {

    private PhotoStore() {}

    public static File photosDir() {
        File f = new File(PhoneCanvas.baseDir(), "photos");
        if (!f.exists()) f.mkdirs();
        // read-me (created once): players can drop .png photos here for Gallery/wallpaper.
        File readme = new File(f, "photos-here.txt");
        if (!readme.isFile()) {
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(readme), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write("Put .png photos into this folder.\n");
                w.write("They appear in the phone Gallery app and can be set as wallpaper.\n");
                w.write("This folder is shared across saves: .minecraft/mcphone/photos\n");
            } catch (Exception ignored) {}
        }
        return f;
    }

    public static File wallpaperFile() {
        return new File(PhoneCanvas.baseDir(), "wallpaper.png");
    }

    public static List<File> listPhotos() {
        File[] files = photosDir().listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null) return new ArrayList<>();
        List<File> out = new ArrayList<>(Arrays.asList(files));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static File savePhoto(int[] rgb, int w, int h) throws Exception {
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".png";
        File out = new File(photosDir(), name);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, rgb, 0, w);
        ImageIO.write(img, "png", out);
        return out;
    }

    public static boolean setWallpaper(File png) {
        try {
            java.nio.file.Files.copy(
                png.toPath(),
                wallpaperFile().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearWallpaper() {
        wallpaperFile().delete();
    }

    public static boolean hasWallpaper() {
        return wallpaperFile().isFile();
    }

    public static Minecraft mc() {
        return Minecraft.getMinecraft();
    }
}
