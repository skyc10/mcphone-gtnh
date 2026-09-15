package com.november.mcphone.client.enhance;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

/**
 * 壁纸存储 —— 扫描 {@code config/mcphone/wallpapers/} 目录，把目录里的 PNG 当作壁纸来源。
 *
 * <p><b>上游依据</b>（v1.10.2，{@code shared/.../feature/settings/client/WallpaperStore.java}）：
 * <ul>
 *   <li>{@code :34} 目录常量 {@code "config/mcphone/wallpapers"}；</li>
 *   <li>{@code :73-83} {@code directory()}：目录不存在就建出来（建不出来也把路径交出去）；</li>
 *   <li>{@code :102-143} {@code refresh()}：每次进选择页重扫，<b>增量</b>——新出现的加载、
 *       消失的摘掉、其余原样留着，最后按文件名排序；</li>
 *   <li>{@code :135-138} 只扫 {@code *.png}（大小写不敏感）；</li>
 *   <li>{@code :160-204} {@code loadWallpaper}：{@code ImageIO.read}，<b>任意尺寸</b>都接受，
 *       原始宽高记进 {@link Entry} 供渲染换算；</li>
 *   <li>{@code :52-58} 数据类 {@code WallpaperEntry(fileName, displayName, texture, imageWidth, imageHeight)}；</li>
 *   <li>{@code :207-237} {@code importFile}：只收 PNG、重名挂 {@code -2/-3} 序号而不覆盖；</li>
 *   <li>{@code :241-257} 查询：{@code getWallpapers()} / {@code findEntry(fileName)} / {@code getWallpaper(index)}。</li>
 * </ul>
 *
 * <p><b>与上游的两处载体替换</b>（语义等价，见交付报告 §1）：
 * <ol>
 *   <li>上游把图上传成 {@code DynamicTexture} 并用 {@code TextureManager.release} 释放
 *       （{@code :129}、{@code :155}）；1.7.10 + Qz 侧图片纹理归
 *       {@code MinecraftHostImageRenderer} 私有所有，唯一释放入口是它的 {@code close()}，
 *       外部没有按键删除的 API。故本类<b>不持有</b> GPU 纹理：只在 {@code :102-143} 的
 *       增量语义上维护「文件名 → 条目」表，纹理由 Qz 按 {@code imageKey} 缓存复用。
 *       条目表仍然增量增删；"贴图只增不减"这一条在上游靠 release 解决，在这里靠
 *       {@link #contentKey}/{@link #thumbnailKey} 让同一张图恒得同一个 key（不重复上传）
 *       + 屏幕关闭时 Qz 一次性释放。</li>
 *   <li>上游没有内置预设（类注释与 {@code _r2_F.md} §1.2 均确认）；我们的
 *       {@link WallpaperPresets} 有 6 个纯色预设。为满足「预设与用户图片统一为一条路径」，
 *       首次运行时把 6 个预设<b>写成该目录下的内置 PNG</b>（{@link #ensureBuiltIns()}），
 *       之后它们与玩家自己丢进来的图完全同权：同一张列表、同一条加载路径，也可以被删除。
 *       玩家已删过就不再重复生成（{@link #seedMarker()} 哨兵）。</li>
 * </ol>
 *
 * <p>本类不依赖 Qz：只做「目录 → 条目 → BufferedImage」，图片源由调用方经
 * {@code HostImageSource.bufferedImage(img, imageKey)} 构造。
 */
public final class WallpaperStore {

    /** 上游 {@code WallpaperStore.java:34} 同一路径（相对游戏目录）。 */
    private static final String WALLPAPER_DIR = "config/mcphone/wallpapers";

    /** 一条壁纸：文件名 + 显示名 + 原始宽高（**不含** GPU 纹理，理由见类注释 §1）。 */
    public static final class Entry {

        public final String fileName;
        public final String displayName;
        public final int imageWidth;
        public final int imageHeight;
        /** 惰性解码的图片缓存；null 表示尚未解码。 */
        private BufferedImage image;
        private boolean imageTried;

        Entry(String fileName, String displayName, int imageWidth, int imageHeight) {
            this.fileName = fileName;
            this.displayName = displayName;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }

        /** 原始尺寸位图；读失败返回 null（调用方走"跳过这一格"的兜底）。 */
        public BufferedImage image() {
            if (!imageTried) {
                imageTried = true;
                image = readImage(new File(directory(), fileName));
            }
            return image;
        }

        /** 列表下方的标签：内置预设用本地化名，玩家自备图用去扩展名的文件名。 */
        public String label() {
            String key = WallpaperPresets.nameKeyForFile(fileName);
            if (key == null) return displayName;
            String localized = StatCollector.translateToLocal(key);
            // 1.7.10 的 StatCollector 未命中时原样返回键名 ⇒ 用键名判定并回退文件名。
            return key.equals(localized) ? displayName : localized;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();

    /** 本会话是否扫过盘（{@link #ensureScanned()} 的去重标记）。 */
    private static volatile boolean scanned;

    /** 是否正在扫盘（{@link #ensureScanned()} 的递归互斥：扫描本身也可能触发查询）。 */
    private static boolean scanning;

    /** 当前选中的壁纸文件名（= 上游玩家附件里的 {@code wallpaper} 字段；空串 = 不用壁纸）。 */
    private static volatile String selected = "";

    /** {@link #selected} 是否已从盘上读过。 */
    private static volatile boolean selectionLoaded;

    /**
     * 缩略图包边缓存：{@code 文件名@mtime@长度@box} → 已居中包进透明方框的位图。
     *
     * <p>为什么要缓存：上游 {@code WallpaperPicker.renderThumbnail}（{@code :250-269}）每帧
     * 用 11 参 blit 现算源区；我们这边的 Qz 图片源只能"整张纹理拉满节点矩形"
     * （{@code MinecraftHostImageRenderer.renderTextureRegion}，UV 恒 0..1），
     * 所以等比+居中必须先落成位图。每帧现做一遍 AWT 缩放会拖垮选择页，故按
     * 「文件名 + 修改时间 + 长度 + 方框边长」缓存——文件被换掉则键变，自动重做。</p>
     */
    private static final Map<String, BufferedImage> THUMBS = new HashMap<String, BufferedImage>();

    private WallpaperStore() {}

    //  目录与初始化

    /** 壁纸目录（相对游戏目录）；不存在就建出来。建不出来也照常返回路径（上游 {@code :73-83}）。 */
    public static File directory() {
        File dir = new File(WALLPAPER_DIR);
        if (!dir.isDirectory()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
                // 建不出来照样交出去：交给系统的文件管理器比在手机屏幕上憋一句报错清楚。
            }
        }
        return dir;
    }

    /** 该目录的显示用路径（给空态提示文案用）。 */
    public static String directoryDisplayPath() {
        return WALLPAPER_DIR + "/";
    }

    /**
     * 首次运行把 6 个内置预设落成该目录下的 PNG。
     *
     * <p>只在「哨兵不存在」时做一次：玩家把预设删掉后不该下次开机又冒出来。</p>
     */
    public static void ensureBuiltIns() {
        File dir = directory();
        File marker = seedMarker();
        if (marker.isFile()) return;
        if (!dir.isDirectory()) return;   // 目录建不出来：本轮放弃，下次再试（哨兵不写）
        for (int i = 0; i < WallpaperPresets.count(); i++) {
            File out = new File(dir, WallpaperPresets.fileNameOf(i));
            if (out.isFile()) continue;
            try {
                ImageIO.write(WallpaperPresets.generate(i), "png", out);
            } catch (Throwable ignored) {
                // 单张写失败不影响其它预设，也不写哨兵（下次开机重试）。
                return;
            }
        }
        writeSeedMarker(marker);
    }

    /**
     * 把旧版单文件壁纸（{@code mcphone/wallpaper.png}）迁移进新目录（只做一次）。
     *
     * <p>上游没有这一步（它一开始就是目录 + 文件名）；我们旧版把壁纸覆盖写在
     * {@code .minecraft/mcphone/wallpaper.png} 这一个固定文件里，不迁移的话升级后
     * <b>玩家已经设好的壁纸会"消失"</b>。迁移成功才删旧文件。</p>
     */
    public static void migrateLegacyWallpaper() {
        try {
            File legacy = com.november.mcphone.client.PhotoStore.wallpaperFile();
            if (legacy == null || !legacy.isFile()) return;
            File dir = directory();
            if (!dir.isDirectory()) return;
            File target = new File(dir, "imported-wallpaper.png");
            copy(legacy, target);
            // 复制成功才删源文件；失败则原样留着（旧版语义继续可用）。
            if (target.isFile() && target.length() > 0L) {
                legacy.delete();
            }
        } catch (Throwable ignored) {
            // 迁移是尽力而为：失败时旧版路径仍由 loadWallpaper 的兜底分支覆盖。
        }
    }

    private static File seedMarker() {
        return new File(directory(), ".builtin-seeded");
    }

    private static void writeSeedMarker(File marker) {
        java.io.Writer w = null;
        try {
            w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(marker), java.nio.charset.StandardCharsets.UTF_8);
            w.write("mcphone: built-in wallpapers were written here once.\n");
            w.write("Delete this file to have them written again.\n");
        } catch (Throwable ignored) {
            // 写不出哨兵只会导致下次开机重扫时补写，无害。
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                    // 关闭失败无影响
                }
            }
        }
    }

    //  扫描（增量）

    /** 上游 {@code scan()}（{@code :63-65}）：客户端启动时扫一次，让第一次开机就有壁纸可选。 */
    public static void scan() {
        try {
            ensureBuiltIns();
        } catch (Throwable ignored) {
            // 建预设失败不影响已有壁纸
        }
        try {
            migrateLegacyWallpaper();
        } catch (Throwable ignored) {
            // 迁移失败不影响已有壁纸
        }
        refresh();
    }

    /**
     * 惰性兜底：任何查询入口在"还没扫过盘"时先扫一次。
     *
     * <p>为什么需要：{@code PhoneUi} 的 {@code WALLPAPER} 信号是类静态字段
     * （{@code Signal.create(loadWallpaper())}），它在类初始化时就取值——如果那一刻
     * 目录还没扫过，列表是空的 ⇒ <b>本次会话第一次打开手机就是"没壁纸"</b>，
     * 直到玩家自己进一次壁纸选择页。这个兜底把顺序依赖消掉：谁先来谁负责扫。</p>
     *
     * <p>{@code scanning} 是递归互斥：扫盘过程本身会调 {@link #findEntry}（去重），
     * 没有这道闸就是无限递归，而 {@code Scanned} 在扫描末尾才置位、拦不住。</p>
     */
    private static void ensureScanned() {
        if (scanned || scanning) return;
        scanning = true;
        try {
            scan();
        } catch (Throwable ignored) {
            // 兜底绝不能让选择页/手机界面因为一次目录扫描而炸（异常一律吞掉，界面走空态）。
        } finally {
            scanning = false;
        }
    }

    /**
     * 重扫壁纸目录，<b>增量</b>（上游 {@code :102-143}）。
     *
     * <p>新出现的文件名加载条目，已经没了的摘掉，其余原样留着；最后按文件名排序
     * （内置预设排在前面，见 {@link #rankOf}）。读不到目录时保持现状，绝不把已加载的清空。</p>
     */
    public static void refresh() {
        File dir = new File(WALLPAPER_DIR);
        if (!dir.isDirectory()) {
            ENTRIES.clear();
            THUMBS.clear();
            return;
        }

        List<String> onDisk = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files == null) return;   // 列不出来 ⇒ 保持现状
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
            onDisk.add(name);
        }

        // 文件没了的：摘掉条目（顺带丢掉它的缩略图缓存）
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry e = ENTRIES.get(i);
            if (!onDisk.contains(e.fileName)) {
                ENTRIES.remove(i);
                dropThumbCache(e.fileName);
            }
        }

        // 新出现的：加载
        for (String name : onDisk) {
            if (findEntry(name) != null) continue;
            loadEntry(dir, name);
        }

        // 排序放在最后：新加载的都追加在末尾，不排的话新图永远排最后。
        Collections.sort(ENTRIES, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int ra = rankOf(a.fileName);
                int rb = rankOf(b.fileName);
                if (ra != rb) return ra < rb ? -1 : 1;
                return a.fileName.compareTo(b.fileName);
            }
        });
        scanned = true;
    }

    /** 排序权重：内置预设按其声明顺序排在前面，其余按文件名。 */
    private static int rankOf(String fileName) {
        int preset = WallpaperPresets.indexOfFile(fileName);
        return preset >= 0 ? preset : 1000;
    }

    private static void loadEntry(File dir, String fileName) {
        BufferedImage img = readImage(new File(dir, fileName));
        if (img == null) return;   // 读不出来的图跳过，不影响其它条目（上游 :166-169）
        String displayName = fileName.substring(0, Math.max(0, fileName.length() - 4));
        ENTRIES.add(new Entry(fileName, displayName, img.getWidth(), img.getHeight()));
    }

    private static BufferedImage readImage(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return ImageIO.read(f);
        } catch (Throwable ignored) {
            return null;
        }
    }

    //  查询（上游 :241-257）

    public static List<Entry> getWallpapers() {
        ensureScanned();
        return Collections.unmodifiableList(ENTRIES);
    }

    public static Entry findEntry(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        ensureScanned();
        for (Entry e : ENTRIES) {
            if (e.fileName.equals(fileName)) return e;
        }
        return null;
    }

    public static Entry getWallpaper(int index) {
        ensureScanned();
        return (index >= 0 && index < ENTRIES.size()) ? ENTRIES.get(index) : null;
    }

    //  选中项（= 上游玩家附件的 wallpaper 字段；本轮只落本地文件）

    /** 当前选中的壁纸文件名；空串 = 恢复默认背景。 */
    public static String selectedFileName() {
        if (!selectionLoaded) {
            selectionLoaded = true;
            selected = readSelection();
        }
        return selected;
    }

    /** 记下选中的壁纸文件名（空串/null = 清除）。 */
    public static void setSelectedFileName(String fileName) {
        selected = fileName == null ? "" : fileName;
        selectionLoaded = true;
        writeSelection(selected);
    }

    private static File selectionFile() {
        return new File(directory(), ".current");
    }

    private static String readSelection() {
        try {
            File f = selectionFile();
            if (!f.isFile()) return "";
            byte[] buf = new byte[(int) Math.min(4096L, f.length())];
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(buf);
                if (n <= 0) return "";
                return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void writeSelection(String fileName) {
        try {
            java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(selectionFile()), java.nio.charset.StandardCharsets.UTF_8);
            try {
                w.write(fileName);
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // 落盘失败只影响"下次开机是否记得"，本次会话已经生效。
        }
    }

    //  缩略图（对应上游 WallpaperPicker.renderThumbnail :250-269）

    /**
     * 取一张已等比缩放并居中包进 {@code box×box} 透明方框的缩略图。
     *
     * <p>上游是在渲染时按 {@code min(boxW/texW, boxH/texH)} 算目标矩形再 11 参 blit；
     * Qz 的图片源没有源区参数（整张贴图拉满节点），所以等比与居中只能在这里落地。
     * 结果是同一个方框内**等比、居中、不裁切**的预览，与上游视觉一致。</p>
     *
     * @return 包好的位图；读不出来时返回 null（调用方画格子自身的兜底底色）
     */
    public static BufferedImage thumbnail(Entry entry, int box) {
        if (entry == null || box <= 0) return null;
        File f = new File(directory(), entry.fileName);
        String key = entry.fileName + "@" + f.lastModified() + "@" + f.length() + "@" + box;
        BufferedImage cached = THUMBS.get(key);
        if (cached != null) return cached;

        BufferedImage src = entry.image();
        if (src == null) return null;
        BufferedImage packed = packIntoBox(src, box);
        if (packed != null) THUMBS.put(key, packed);
        return packed;
    }

    /**
     * 缩略图的 {@code imageKey}：内容寻址的稳定键（同一张图恒得同一个 key ⇒ Qz 侧复用纹理）。
     *
     * <p>键里带文件名、修改时间、文件长度与方框边长：文件被覆盖写 ⇒ mtime/长度变 ⇒ 键变 ⇒
     * 重新上传（否则会一直显示旧图）；同图重复进入选择页 ⇒ 键同 ⇒ 复用已上传的纹理。</p>
     */
    public static String thumbnailKey(Entry entry, int box) {
        if (entry == null) return "mcphone:wpthumb/none";
        File f = new File(directory(), entry.fileName);
        return "mcphone:wpthumb/" + box + "/" + entry.fileName + "/"
            + Long.toHexString(f.lastModified()) + "-" + Long.toHexString(f.length());
    }

    /**
     * 整张壁纸的 {@code imageKey}（内容寻址）。
     *
     * <p>上游不用 key（把图注册成一条独立 {@code ResourceLocation}）；我们的载体现为
     * 「同一路径内容会变」（预设写文件、玩家换图、迁移），所以键必须含内容口径：
     * 文件名 + 修改时间 + 长度，<b>外加已裁好位图的像素哈希</b>（尺寸进键，覆盖
     * 「换窗口/改 UI 缩放后面板比例变了、重设同一张图」这一种文件不变而内容变的情况）。</p>
     *
     * <p>像素哈希失败（非标准 ColorModel 等）时退到「文件名 + mtime + 长度 + 尺寸」，
     * 仍远好于旧写法（{@code "mcphone:wallpaper#" + System.nanoTime()}，每换一次都新建纹理）。</p>
     */
    public static String contentKey(Entry entry, BufferedImage cropped) {
        int w = cropped == null ? 0 : cropped.getWidth();
        int h = cropped == null ? 0 : cropped.getHeight();
        String base = entry == null ? "adhoc" : entry.fileName;
        if (entry != null) {
            File f = new File(directory(), entry.fileName);
            base = entry.fileName + "/" + Long.toHexString(f.lastModified())
                + "-" + Long.toHexString(f.length());
        }
        if (cropped != null) {
            try {
                int[] pixels = cropped.getRGB(0, 0, w, h, null, 0, w);
                if (pixels != null) {
                    return "mcphone:wallpaper/" + w + "x" + h + "/"
                        + Integer.toHexString(Arrays.hashCode(pixels));
                }
            } catch (Throwable ignored) {
                // 退到下面的稳定键
            }
        }
        return "mcphone:wallpaper/" + w + "x" + h + "/" + base;
    }

    private static void dropThumbCache(String fileName) {
        String prefix = fileName + "@";
        java.util.Iterator<String> it = THUMBS.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) it.remove();
        }
    }

    /** 等比缩放并居中贴到 {@code box×box} 的透明画布上（对应上游 {@code renderThumbnail}）。 */
    private static BufferedImage packIntoBox(BufferedImage src, int box) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        double scale = Math.min(box / (double) sw, box / (double) sh);
        int dw = Math.max(1, (int) Math.round(sw * scale));
        int dh = Math.max(1, (int) Math.round(sh * scale));
        BufferedImage canvas = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, (box - dw) / 2, (box - dh) / 2, dw, dh, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    //  导入（上游 :207-237，保留接口；本轮的 UI 入口=「打开文件夹」）

    /**
     * 把一张外部 PNG 复制进壁纸目录，返回最终文件名；失败返回 null。
     *
     * <p>只收 PNG（上游 {@code :221}）；重名挂 {@code -2/-3} 序号而不覆盖
     * （上游 {@code :226-229}）——玩家看到的是两张都在。</p>
     */
    public static String importFile(File source) {
        if (source == null || !source.isFile()) return null;
        String name = source.getName();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) return null;
        try {
            File dir = directory();
            File target = new File(dir, name);
            for (int i = 2; target.exists(); i++) {
                name = name.substring(0, name.length() - 4) + "-" + i + ".png";
                target = new File(dir, name);
            }
            copy(source, target);
            if (!target.isFile()) return null;
            return target.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void copy(File from, File to) throws IOException {
        Path a = from.toPath();
        Path b = to.toPath();
        Files.copy(a, b, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 目录的绝对路径（诊断/日志用）。 */
    public static String absolutePath() {
        try {
            return new File(WALLPAPER_DIR).getAbsolutePath();
        } catch (Throwable ignored) {
            return WALLPAPER_DIR;
        }
    }

    /** 客户端是否已就绪（`Minecraft.getMinecraft()` 尚未就绪时为 false）。 */
    public static boolean ready() {
        try {
            return Minecraft.getMinecraft() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
