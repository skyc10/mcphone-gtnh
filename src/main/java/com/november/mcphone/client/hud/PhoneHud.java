package com.november.mcphone.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import club.heiqi.uilib.ui.host.NativeDisplaySize;
import club.heiqi.uilib.ui.host.UiHostRenderSupport;
import club.heiqi.uilib.ui.render.PaintContextCompositor;
import club.heiqi.uilib.ui.render.UiMainLayerSnapshotService;
import club.heiqi.uilib.ui.render.UiRenderContext;
import club.heiqi.uilib.ui.runtime.UiRuntimeAdapters;
import club.heiqi.uilib.util.GlAttribDepth;

import com.november.mcphone.client.ClientHooks;
import com.november.mcphone.client.PhoneCanvas;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 常显手机 HUD：背包里有手机且无 GUI 打开时，把手机面板常驻画在游戏画面上。
 *
 * <p>渲染链路与 McScreenBridge.drawScreen 同构（自设 ortho 投影 + viewport +
 * compositor/snapshot beginFrame + createRenderContext + PhoneUi.render），但
 * HUD 是渲染事件里的独立绘制，不经过 GuiScreen——{@code PhoneUi} 实例因此
 * 常驻复用（全屏实例随 onGuiClosed dispose，二者互不相干）。</p>
 *
 * <p>交互：左键点击 HUD = 打开手机；拖拽 = 移动位置；Ctrl+滚轮 = 缩放；
 * G 键 = 开关 HUD（ClientHooks 注册的 keyHud）。</p>
 */
public final class PhoneHud {

    private static PhoneHud instance;

    public static PhoneHud get() {
        if (instance == null) instance = new PhoneHud();
        return instance;
    }

    /** 注册时创建实例（ClientHooks.preInit）：事件监听器必须注册即存在。 */
    public static void init() {
        get();
    }

    private final PaintContextCompositor compositor = new PaintContextCompositor();
    private final UiMainLayerSnapshotService snapshotService = new UiMainLayerSnapshotService();
    private final UiRuntimeAdapters adapters = UiRuntimeAdapters.minecraftDefaults();

    /** 常驻 HUD 手机实例；屏幕尺寸或 HUD 缩放变化时重建。 */
    private PhoneUi hudUi;
    private int builtForWidth = -1;
    private int builtForHeight = -1;
    private int builtForScale = -1;

    /** 拖拽状态（tick 驱动）。 */
    private boolean dragging;
    private int dragStartX;
    private int dragStartY;
    private int dragBaseOffsetX;
    private int dragBaseOffsetY;
    /** 最近一次采样的指针位置（物理像素，左上原点）。 */
    private int pointerX = -1;
    private int pointerY = -1;

    private PhoneHud() {
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this);
    }

    /** HUD 是否持有手机实例（时钟 tick 用）。 */
    public boolean hasUi() {
        return hudUi != null;
    }

    /** 释放 HUD 实例（关世界/关 HUD 时）。 */
    public void disposeUi() {
        if (hudUi != null) {
            try {
                hudUi.dispose();
            } catch (Throwable t) {
                System.err.println("[mcphone] hud dispose failed: " + t);
            }
            hudUi = null;
            builtForWidth = -1;
            builtForHeight = -1;
            builtForScale = -1;
        }
    }

    /** HUD 面板尺寸（物理像素）：与全屏同一套换算，但用 hudScalePercent。 */
    private int[] panelSize(int screenW, int screenH) {
        int h = (int) (screenH * PhoneUi.basePanelHeight() * PhoneCanvas.getHudScalePercent() / 100.0f);
        h = Math.max(320, Math.min(1100, h));
        int w = (int) (h * 0.56);
        w = Math.max(200, Math.min(620, w));
        if (w > screenW - 40) w = Math.max(200, screenW - 40);
        return new int[] {w, h};
    }

    /** HUD 面板左上角（物理像素，含玩家偏移）。 */
    private int[] panelOrigin(int screenW, int screenH, int w, int h) {
        String anchor = PhoneCanvas.getHudAnchor();
        int margin = 24;
        boolean left = anchor.endsWith("LEFT");
        boolean right = anchor.endsWith("RIGHT");
        boolean top = anchor.startsWith("TOP");
        boolean bottom = anchor.startsWith("BOTTOM");
        int x = left ? margin : right ? screenW - w - margin : (screenW - w) / 2;
        int y = top ? margin : bottom ? screenH - h - margin : (screenH - h) / 2;
        return new int[] {x + PhoneCanvas.getHudOffsetX(), y + PhoneCanvas.getHudOffsetY()};
    }

    // ===================== 客户端 tick：交互 =====================

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            disposeUi();
            dragging = false;
            return;
        }
        if (mc.currentScreen != null) {
            dragging = false;
            return;
        }
        pointerX = Mouse.getX();
        pointerY = mc.displayHeight - Mouse.getY() - 1; // GL 原点在左下
        if (!PhoneCanvas.isHudEnabled() || ClientHooks.isCameraMode()) {
            dragging = false;
            return;
        }
        ItemStack phone = ClientHooks.findPhone(mc);
        if (phone == null) {
            dragging = false;
            return;
        }
        int[] size = panelSize(mc.displayWidth, mc.displayHeight);
        int[] origin = panelOrigin(mc.displayWidth, mc.displayHeight, size[0], size[1]);

        // Ctrl+滚轮缩放（步进 10%，40–150）。
        int wheel = Mouse.getDWheel();
        if (wheel != 0 && isCtrlDown() && (dragging || insidePanel(pointerX, pointerY, origin, size))) {
            PhoneCanvas.setHudScalePercent(PhoneCanvas.getHudScalePercent() + (wheel > 0 ? 10 : -10));
            return;
        }

        boolean pressed = Mouse.isButtonDown(0);
        if (pressed && !dragging && insidePanel(pointerX, pointerY, origin, size)) {
            dragging = true;
            dragStartX = pointerX;
            dragStartY = pointerY;
            dragBaseOffsetX = PhoneCanvas.getHudOffsetX();
            dragBaseOffsetY = PhoneCanvas.getHudOffsetY();
        } else if (!pressed && dragging) {
            // 松开：基本没动 = 点击打开手机；拖过 = 落盘位置。
            dragging = false;
            int dx = pointerX - dragStartX;
            int dy = pointerY - dragStartY;
            if (Math.abs(dx) < 4 && Math.abs(dy) < 4) {
                openPhone(mc, phone);
            } else {
                PhoneCanvas.setHudOffsetX(dragBaseOffsetX + dx);
                PhoneCanvas.setHudOffsetY(dragBaseOffsetY + dy);
            }
        } else if (dragging) {
            PhoneCanvas.setHudOffsetX(dragBaseOffsetX + (pointerX - dragStartX));
            PhoneCanvas.setHudOffsetY(dragBaseOffsetY + (pointerY - dragStartY));
        }
    }

    private boolean insidePanel(int px, int py, int[] origin, int[] size) {
        return px >= origin[0] && px < origin[0] + size[0]
            && py >= origin[1] && py < origin[1] + size[1];
    }

    private static boolean isCtrlDown() {
        return org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
    }

    private static void openPhone(Minecraft mc, ItemStack phone) {
        mc.displayGuiScreen(new com.november.mcphone.client.scene.PhoneScreen(
            new com.november.mcphone.client.scene.PhoneUi(phone)));
    }

    // ===================== 渲染 =====================

    /** 每帧渲染（RenderGameOverlayEvent.Post(ALL) 由 ClientHooks 转发）。 */
    public void renderHud(Minecraft mc, float partialTicks) {
        if (!PhoneCanvas.isHudEnabled() || ClientHooks.isCameraMode()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        ItemStack phone = ClientHooks.findPhone(mc);
        if (phone == null) return;

        int screenW = Math.max(1, mc.displayWidth > 0 ? mc.displayWidth : NativeDisplaySize.width());
        int screenH = Math.max(1, mc.displayHeight > 0 ? mc.displayHeight : NativeDisplaySize.height());
        int[] size = panelSize(screenW, screenH);
        int[] origin = panelOrigin(screenW, screenH, size[0], size[1]);

        ensureUi(mc, phone, screenW, screenH);

        int frameBaseDepth = GlAttribDepth.current();
        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        try {
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, screenW, screenH, 0.0D, -1000.0D, 1000.0D);
            GL11.glViewport(0, 0, screenW, screenH);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                GL11.glLoadIdentity();
                UiHostRenderSupport.prepareMainUiRenderState();
                compositor.beginFrame();
                snapshotService.beginFrame();
                try {
                    UiRenderContext context = UiHostRenderSupport.createRenderContext(
                        screenW, screenH, pointerX, pointerY, partialTicks,
                        compositor, snapshotService, adapters);
                    // 渲染到目标矩形：w/h = HUD 面板尺寸，absX/absY = 面板原点。
                    hudUi.render(size[0], size[1], context, origin[0], origin[1]);
                } finally {
                    snapshotService.finishFrame();
                    compositor.finishFrame();
                }
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(previousMatrixMode);
            GlAttribDepth.popExcess(frameBaseDepth);
        }
    }

    /**
     * 保证 HUD 专用 PhoneUi 实例与当前屏幕/HUD 缩放匹配。HUD 缩放不走全局
     * uiScalePercent（静态共享会污染全屏实例），改用 setPanelSize 重写实例面板。
     */
    private void ensureUi(Minecraft mc, ItemStack phone, int screenW, int screenH) {
        int scalePct = PhoneCanvas.getHudScalePercent();
        if (hudUi == null || builtForWidth != screenW || builtForHeight != screenH
                || builtForScale != scalePct) {
            disposeUi();
            PhoneUi prev = PhoneUi.ACTIVE;
            hudUi = new PhoneUi(phone);
            // PhoneUi 构造会抢占 ACTIVE（全屏实例指针/时钟语义），立即还原。
            PhoneUi.ACTIVE = prev;
            int[] size = panelSize(screenW, screenH);
            hudUi.setPanelSize(size[0], size[1]);
            builtForWidth = screenW;
            builtForHeight = screenH;
            builtForScale = scalePct;
        }
    }
}
