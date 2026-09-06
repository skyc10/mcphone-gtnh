package com.november.mcphone.client.apps;

import java.io.File;
import java.util.List;

import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.PhotoStore;
import com.november.mcphone.client.PhoneGui;
import com.november.mcphone.client.UiHelper;

/**
 * 相册：缩略图网格 + 查看器（设为壁纸/删除）。
 */
public final class GalleryScreen {

    private GalleryScreen() {}

    public static class Screen extends AppScreen {

        private List<File> photos;
        private int viewing = -1;
        private String toast;
        private long toastUntil;

        Screen(PhoneGui gui) {
            super(gui);
        }

        @Override
        public void onOpened() {
            refresh();
        }

        private void refresh() {
            photos = PhotoStore.listPhotos();
            if (viewing >= photos.size()) viewing = -1;
        }

        private void toast(String key) {
            toast = PhoneGui.tr(key);
            toastUntil = System.currentTimeMillis() + 1500;
        }

        @Override
        public void render(int sx, int sy, int sw, int sh, int mx, int my, float pt) {
            if (viewing >= 0) {
                renderViewer(sx, sy, sw, sh, mx, my);
                return;
            }
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.camera"), sx + 10, sy + 2, sw - 20, 14, mx, my);
            int cols = 2;
            int cellW = (sw - 26) / 2;
            int cellH = 34;
            int y = sy + 22;
            int row = 0;
            for (int i = 0; i < photos.size(); i++) {
                int col = i % cols;
                if (col == 0 && i > 0) row++;
                int cx = sx + 10 + col * (cellW + 6);
                int cy = y + row * (cellH + 6);
                if (cy + cellH > sy + sh) {
                    font().drawStringWithShadow(PhoneGui.tr("msg.mcphone.more_photos"), sx + 12, sy + sh - 10, 0xFF8899AA);
                    break;
                }
                File f = photos.get(i);
                int tex = UiHelper.loadImageTexture(f);
                boolean hover = Widgets.hit(mx, my, cx, cy, cellW, cellH);
                UiHelper.roundedRect(cx, cy, cellW, cellH, 2, 0x33000000);
                if (tex > 0) {
                    UiHelper.drawImageCover(tex, cx + 1, cy + 1, cellW - 2, cellH - 2, UiHelper.texWidth(f), UiHelper.texHeight(f));
                }
                if (hover) UiHelper.outline(cx, cy, cellW, cellH, 1, 0x88FFFFFF);
            }
            if (photos.isEmpty()) {
                font().drawStringWithShadow(PhoneGui.tr("msg.mcphone.no_photos"), sx + 14, y + 8, 0xFF8899AA);
            }
            drawToast(sx, sy, sw, sh);
        }

        private void renderViewer(int sx, int sy, int sw, int sh, int mx, int my) {
            File f = photos.get(viewing);
            UiHelper.rect(sx, sy, sw, sh, 0xCC000814);
            int tex = UiHelper.loadImageTexture(f);
            if (tex > 0) {
                UiHelper.drawImageContain(tex, sx + 4, sy + 4, sw - 8, sh - 46, UiHelper.texWidth(f), UiHelper.texHeight(f));
            }
            font().drawStringWithShadow(
                UiHelper.ellipsize(font(), f.getName(), sw - 8),
                sx + 4,
                sy + sh - 38,
                0xFFBFD4E6);
            int bw = (sw - 26) / 3;
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.set_wallpaper"), sx + 6, sy + sh - 24, bw, 14, mx, my);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.delete"), sx + 10 + bw, sy + sh - 24, bw, 14, mx, my);
            Widgets.drawButton(PhoneGui.tr("btn.mcphone.back"), sx + 14 + bw * 2, sy + sh - 24, bw, 14, mx, my);
            drawToast(sx, sy, sw, sh);
        }

        private void drawToast(int sx, int sy, int sw, int sh) {
            if (toast != null && System.currentTimeMillis() < toastUntil) {
                int w = font().getStringWidth(toast) + 10;
                UiHelper.roundedRect(sx + (sw - w) / 2, sy + sh - 44, w, 12, 3, 0xAA000000);
                font().drawStringWithShadow(toast, sx + (sw - w) / 2 + 5, sy + sh - 41, 0xFFFFFFFF);
            }
        }

        @Override
        public void mouseClicked(int sx, int sy, int sw, int sh, int mx, int my, int button) {
            if (viewing >= 0) {
                int bw = (sw - 26) / 3;
                int by = sy + sh - 24;
                if (Widgets.hit(mx, my, sx + 6, by, bw, 14)) {
                    if (PhotoStore.setWallpaper(photos.get(viewing))) toast("msg.mcphone.wallpaper_set");
                    else toast("msg.mcphone.wallpaper_fail");
                } else if (Widgets.hit(mx, my, sx + 10 + bw, by, bw, 14)) {
                    photos.get(viewing).delete();
                    toast("msg.mcphone.photo_deleted");
                    refresh();
                    viewing = -1;
                } else if (Widgets.hit(mx, my, sx + 14 + bw * 2, by, bw, 14)) {
                    viewing = -1;
                }
                return;
            }
            if (Widgets.hit(mx, my, sx + 10, sy + 2, sw - 20, 14)) {
                gui.openAppById("camera");
                return;
            }
            int cols = 2;
            int cellW = (sw - 26) / 2;
            int cellH = 34;
            int y = sy + 22;
            for (int i = 0; i < photos.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int cx = sx + 10 + col * (cellW + 6);
                int cy = y + row * (cellH + 6);
                if (cy + cellH > sy + sh) break;
                if (Widgets.hit(mx, my, cx, cy, cellW, cellH)) {
                    viewing = i;
                    return;
                }
            }
        }
    }
}
