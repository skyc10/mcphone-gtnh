package com.november.mcphone.feature.notes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Properties;

import net.minecraft.world.World;

/**
 * 便签服务端配置（每存档一份，随用随建）：{@code <存档>/mcphone/notes.cfg}。
 *
 * <p>印书消耗开关，默认关闭（免费）。开启后每次印成书消耗一本
 * 空白的书与笔（写在字的书不算：耐久同为 0 但有 pages 的会烧掉玩家内容，
 * 与上游 ICost 判空白同一逻辑，见 NoteServer.handlePrint）。</p>
 */
public final class NoteConfig {

    private static volatile boolean loaded;
    private static boolean printCostsBlankBook;

    private NoteConfig() {}

    public static boolean printCostsBlankBook(World overworld) {
        ensureLoaded(overworld);
        return printCostsBlankBook;
    }

    private static void ensureLoaded(World overworld) {
        if (loaded) return;
        synchronized (NoteConfig.class) {
            if (loaded) return;
            File file = cfgFile(overworld);
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (Exception ignored) {
                // 首次不存在：写出默认模板，便于服主发现这个开关。
                try (OutputStream out = new FileOutputStream(file)) {
                    props.store(out, "MCphone notes (server) config");
                } catch (Exception ignored2) {}
            }
            printCostsBlankBook = Boolean.parseBoolean(
                props.getProperty("print-book-costs-blank-book", "false").trim());
            loaded = true;
        }
    }

    private static File cfgFile(World overworld) {
        File dir = new File(overworld.getSaveHandler().getWorldDirectory(), "mcphone");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "notes.cfg");
    }
}
