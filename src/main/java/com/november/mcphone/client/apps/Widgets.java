package com.november.mcphone.client.apps;

import org.lwjgl.opengl.GL11;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.client.AppScreen;
import com.november.mcphone.client.PhoneGui;
import com.november.mcphone.client.UiHelper;

/**
 * 小控件：按钮绘制/命中、图标底板、文字图标。
 */
public final class Widgets {

    private Widgets() {}

    public static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void drawButton(String label, int x, int y, int w, int h, int mx, int my) {
        boolean hover = hit(mx, my, x, y, w, h);
        UiHelper.roundedRect(x, y, w, h, 3, hover ? 0x666FB2E8 : 0x446FB2E8);
        UiHelper.outline(x, y, w, h, 1, 0x55FFFFFF);
        com.november.mcphone.client.UiHelper.scaledText(
            com.november.mcphone.client.AppScreen.font(),
            label,
            x + w / 2F,
            y + (h - 8) / 2F,
            1F,
            0xFFFFFFFF,
            false);
    }

    /** 图标内绘制一个汉字。 */
    public static void iconGlyph(String glyph, int x, int y, int size, float scale) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x + size / 2F, y + size / 2F, 0);
        GL11.glScalef(scale, scale, 1);
        int w = AppScreen.font().getStringWidth(glyph);
        AppScreen.font().drawStringWithShadow(glyph, (int) (-w / 2F), -4, 0xFFFFFFFF);
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** App 通用壳：id + 中文名 + 图标。 */
    public static abstract class BaseApp implements IPhoneApp {

        private final String id;
        private final String nameKey;
        private final String glyph;
        private final int color;

        protected BaseApp(String id, String nameKey, String glyph, int color) {
            this.id = id;
            this.nameKey = nameKey;
            this.glyph = glyph;
            this.color = color;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return PhoneGui.tr(nameKey);
        }

        @Override
        public void renderIcon(int x, int y, int size) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            iconGlyph(glyph, x, y, size, size / 14F);
        }

        @Override
        public boolean isBuiltin() {
            return true;
        }

        @Override
        public int iconColor() {
            return color;
        }

        protected abstract AppScreen create(PhoneGui gui);

        @Override
        public final AppScreen createScreen(PhoneGui gui) {
            return create(gui);
        }
    }
}
