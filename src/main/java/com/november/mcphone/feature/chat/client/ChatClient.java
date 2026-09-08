package com.november.mcphone.feature.chat.client;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;

import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.feature.chat.ChatService;
import com.november.mcphone.net.NetworkHandler;

/**
 * 聊天 App 客户端状态：服务端 ConvSync/MsgPush 同步来的会话列表、消息缓存、
 * 已收图片缓存，以及相册照片的 JPEG 压缩。
 *
 * <p>线程模型：S→C 包处理器在 netty 线程执行，只把"应用动作"排进并发队列；
 * {@link #applyPending()} 由 ClientHooks 客户端 tick 在主线程调用（同
 * WaypointSync/UnlockSync 模式，见 docs/AI-DEV-NOTES.md）。</p>
 */
@cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
public final class ChatClient {

    /** 会话列表行（好友/申请/可添加玩家三张表共用一个形状）。 */
    public static final class Row {

        public final String uuid;
        public final String name;
        public final boolean online;
        public final int unread;
        public final String preview;

        public Row(String uuid, String name, boolean online, int unread, String preview) {
            this.uuid = uuid;
            this.name = name;
            this.online = online;
            this.unread = unread;
            this.preview = preview == null ? "" : preview;
        }
    }

    /** 消息视图（图片只带元数据，字节在 {@link #image} 缓存里按需拉取）。 */
    public static final class MsgView {

        public final boolean self;
        public final long time;
        public final boolean image;
        public final String text;
        public final String imageId;
        public final int w;
        public final int h;

        MsgView(boolean self, long time, boolean image, String text, String imageId, int w, int h) {
            this.self = self;
            this.time = time;
            this.image = image;
            this.text = text;
            this.imageId = imageId;
            this.w = w;
            this.h = h;
        }
    }

    public static volatile List<Row> friends = new ArrayList<>();
    public static volatile List<Row> requests = new ArrayList<>();
    public static volatile List<Row> addable = new ArrayList<>();
    public static volatile Map<String, List<MsgView>> history = new LinkedHashMap<>();

    /** 客户端压缩目标：与服务器默认 chatImageMaxKb 一致（8KB）。 */
    public static final int IMAGE_TARGET_BYTES = 8 * 1024;

    private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    /** 已收图片解码缓存（LRU，上限 32 张；只有主线程访问，无并发问题）。 */
    private static final Map<String, BufferedImage> IMAGE_CACHE =
        new LinkedHashMap<String, BufferedImage>(32, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > 32;
            }
        };

    private ChatClient() {}

    /**
     * 离开世界（换服/回主菜单）时复位全部静态状态：会话表、消息缓存、图片缓存、
     * 待应用队列。旧服的会话/消息不得带到新服（ClientHooks world==null 调用）。
     */
    public static void reset() {
        friends = new ArrayList<>();
        requests = new ArrayList<>();
        addable = new ArrayList<>();
        history = new LinkedHashMap<>();
        IMAGE_CACHE.clear();
        PENDING.clear();
    }

    // ===================== netty 线程入口（只入队） =====================

    public static void onConvSync(NetworkHandler.ChatConvSync msg) {
        PENDING.add(() -> applyConvSync(msg));
    }

    public static void onMsgPush(NetworkHandler.ChatMsgPush msg) {
        PENDING.add(() -> applyMsgPush(msg));
    }

    public static void onImageData(NetworkHandler.ChatMsgPush msg) {
        PENDING.add(() -> applyImageData(msg));
    }

    /** ClientHooks 客户端 tick 主线程调用：落地队列并刷新打开的聊天页。 */
    public static void applyPending() {
        boolean changed = false;
        Runnable r;
        while ((r = PENDING.poll()) != null) {
            try {
                r.run();
                changed = true;
            } catch (Throwable t) {
                System.err.println("[mcphone] chat sync apply failed: " + t);
            }
        }
        if (changed) ChatUi.maybeRebuild();
    }

    // ===================== 应用动作（主线程） =====================

    private static void applyConvSync(NetworkHandler.ChatConvSync msg) {
        friends = copy(msg.entries);
        requests = copy(msg.requests);
        addable = copy(msg.addable);
    }

    private static List<Row> copy(List<NetworkHandler.ChatConvSync.Entry> list) {
        List<Row> out = new ArrayList<>();
        for (NetworkHandler.ChatConvSync.Entry e : list) {
            out.add(new Row(e.uuid, e.name, e.online, e.unread, e.preview));
        }
        return out;
    }

    private static void applyMsgPush(NetworkHandler.ChatMsgPush msg) {
        if (msg.kind == 0) {
            // 历史批：服务端分批连续下发（每批 ≤ HISTORY_BATCH 条）。首批（batchStart）
            // 先清空该会话缓存再追加，后续批直接追加——既支持多批也避免二次拉取重复。
            List<MsgView> list = msg.batchStart
                ? new ArrayList<>()
                : new ArrayList<>(history.getOrDefault(msg.peer, new ArrayList<>()));
            for (NetworkHandler.ChatMsgPush.MsgMeta m : msg.messages) {
                list.add(toView(m));
            }
            history.put(msg.peer, list);
        } else if (msg.kind == 1) {
            // 单条新消息：追加（去重：自己发送时服务端推回同一条）。
            List<MsgView> list = new ArrayList<>(history.getOrDefault(msg.peer, new ArrayList<>()));
            MsgView v = toView(msg.messages.get(0));
            if (!list.isEmpty()) {
                MsgView last = list.get(list.size() - 1);
                if (last.self == v.self && last.time == v.time
                    && (last.image ? last.imageId : last.text)
                        .equals(v.image ? v.imageId : v.text)) {
                    return;
                }
            }
            list.add(v);
            history.put(msg.peer, list);
        }
    }

    private static MsgView toView(NetworkHandler.ChatMsgPush.MsgMeta m) {
        return new MsgView(m.self, m.time, m.image, m.text, m.imageId, m.w, m.h);
    }

    private static void applyImageData(NetworkHandler.ChatMsgPush msg) {
        try {
            if (msg.data == null || msg.data.length == 0) return;
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(msg.data));
            if (img != null) IMAGE_CACHE.put(msg.peer + "/" + msg.imageId, img);
        } catch (Exception ignored) {
        }
    }

    // ===================== 页面查询辅助 =====================

    public static List<MsgView> historyOf(String peer) {
        List<MsgView> list = history.get(peer);
        return list == null ? new ArrayList<>() : list;
    }

    /** 图片解码缓存（会话页/查看器用；未命中返回 null，页面发起按需拉取）。 */
    public static BufferedImage image(String peer, String imageId) {
        return IMAGE_CACHE.get(peer + "/" + imageId);
    }

    public static void requestImage(String peer, String imageId) {
        NetworkHandler.sendToServer(new NetworkHandler.ChatImage(1, peer, imageId, null, 0, 0));
    }

    /**
     * 标记已读：带上该会话最后一条已知消息的时间戳（服务端水位只前进不后退）。
     * 取不到（会话刚打开还没收到历史）时退化为当前时间。
     */
    public static void markRead(String peer) {
        List<MsgView> list = history.get(peer);
        long time = (list != null && !list.isEmpty())
            ? list.get(list.size() - 1).time
            : System.currentTimeMillis();
        NetworkHandler.sendToServer(new NetworkHandler.ChatMsgSend(2, peer, "", time));
    }

    // ===================== 发送 =====================

    public static void sendText(String peer, String text) {
        NetworkHandler.sendToServer(new NetworkHandler.ChatMsgSend(0, peer, text, 0));
    }

    public static void pullHistory(String peer) {
        NetworkHandler.sendToServer(new NetworkHandler.ChatMsgSend(1, peer, "", 0));
    }

    /**
     * 压缩并发送相册照片：JPEG 迭代降质/降尺寸到 {@link #IMAGE_TARGET_BYTES} 以内。
     *
     * @return 失败原因（null = 已发送）。
     */
    public static String sendPhoto(String peer, File photo) {
        byte[] jpeg = compress(photo);
        if (jpeg == null) {
            return "msg.mcphone.chat_compress_fail";
        }
        if (jpeg.length > IMAGE_TARGET_BYTES) {
            return "msg.mcphone.chat_compress_fail";
        }
        int[] wh = lastSize;
        NetworkHandler.sendToServer(new NetworkHandler.ChatImage(0, peer, "", jpeg, wh[0], wh[1]));
        return null;
    }

    /** sendPhoto 产出的实际尺寸（compress 里记录）。 */
    private static int[] lastSize = {0, 0};

    /**
     * 迭代压缩：最长边先缩到 512px、质量 0.65；超限则先降质量（至 0.35）再缩边，
     * 直到 ≤ 8KB 或放弃（最多 7 轮，8KB 面向下游 1.7.10 的 32KB 包上限很宽裕）。
     */
    static byte[] compress(File photo) {
        try {
            BufferedImage src = ImageIO.read(photo);
            if (src == null) return null;
            int maxDim = Math.max(src.getWidth(), src.getHeight());
            double scale = maxDim > 512 ? 512.0 / maxDim : 1.0;
            float quality = 0.65f;
            byte[] best = null;
            for (int i = 0; i < 7; i++) {
                int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
                byte[] out = encode(resize(src, w, h), quality);
                if (out != null) {
                    best = out;
                    lastSize = new int[] {w, h};
                    if (out.length <= IMAGE_TARGET_BYTES) return out;
                }
                if (quality > 0.35f) {
                    quality -= 0.15f;
                } else {
                    scale *= 0.7;
                }
            }
            return best != null && best.length <= IMAGE_TARGET_BYTES ? best : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage img, float quality) {
        try {
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.stream.MemoryCacheImageOutputStream mos =
                new javax.imageio.stream.MemoryCacheImageOutputStream(out);
            writer.setOutput(mos);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            mos.flush();
            writer.dispose();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 图片消息缩略/大图查看的落地（供 ChatUi 判断是否需要拉取字节）。 */
    public static boolean hasImage(String peer, String imageId) {
        return IMAGE_CACHE.containsKey(peer + "/" + imageId);
    }

    public static UUID selfId() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
    }

    /** ChatService.parseUuid 的客户端转发（避免客户端代码直接依赖服务端类）。 */
    public static UUID parseUuid(String s) {
        return ChatService.parseUuid(s);
    }
}
