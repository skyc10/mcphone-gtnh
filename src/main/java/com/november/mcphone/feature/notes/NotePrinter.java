package com.november.mcphone.feature.notes;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条便签印成一本原版成书（WrittenBook NBT）：
 * 书名 = 便签标题，作者 = 玩家名，正文按 1.7.10 书页排版折行分页。
 */
public final class NotePrinter {

    /** 单页硬上限：1.7.10 成书一页 256 字符，留余量。 */
    private static final int PAGE_CHARS = 250;
    /** 原版成书页数上限（客户端 UI 限制 50 页）。 */
    private static final int MAX_PAGES = 50;
    /** 书页渲染宽度约 114px、满 14 行翻页（与上游 NotePrinter 一致）。 */
    private static final int PAGE_WIDTH = 114;
    private static final int PAGE_LINES = 14;

    private NotePrinter() {}

    /** 生成成书；书名/正文取自便签（标题空则用"便签"）。 */
    public static ItemStack buildBook(EntityPlayerMP player, Note note) {
        ItemStack book = new ItemStack(Items.written_book);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("author", player.getCommandSenderName());
        tag.setString("title", bookTitle(note));
        NBTTagList pages = new NBTTagList();
        for (String page : toPages(note.body)) {
            pages.appendTag(new NBTTagString(page));
        }
        tag.setTag("pages", pages);
        book.setTagCompound(tag);
        return book;
    }

    private static String bookTitle(Note note) {
        String t = note.title.trim();
        if (!t.isEmpty()) return t;
        // 空标题退化为正文第一行，再退化成固定名。
        int cut = note.body.indexOf('\n');
        t = (cut < 0 ? note.body : note.body.substring(0, cut)).trim();
        return t.isEmpty() ? "Notes" : t;
    }

    /** 放入玩家背包；满则掉在脚下。 */
    public static void give(EntityPlayerMP player, ItemStack book) {
        if (!player.inventory.addItemStackToInventory(book)) {
            player.dropPlayerItemWithRandomChoice(book, false);
        }
    }

    /**
     * 服务端没有字体数据，按区间估行宽上界：拉丁 6px、CJK 与全角 9px，
     * 只会偏短不会超页宽（与上游一致）。先折行再每 PAGE_LINES 行翻一页。
     */
    static List<String> toPages(String body) {
        List<String> pages = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int chars = 0; // 当前页字符数，防止单页超 256

        for (String paragraph : body.split("\n", -1)) {
            for (String line : wrap(paragraph)) {
                // 字符上限先到就提前翻页（长拉丁行 14 行 * 19 字符会破 256）。
                if (!lines.isEmpty() && (lines.size() >= PAGE_LINES || chars + line.length() > PAGE_CHARS)) {
                    pages.add(joinLines(lines));
                    lines.clear();
                    chars = 0;
                }
                lines.add(line);
                chars += line.length() + 1; // +1 = 换行符
                if (pages.size() >= MAX_PAGES) return pages;
            }
        }
        if (!lines.isEmpty()) pages.add(joinLines(lines));
        return pages;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /** 优先在空格处断行，断不了才硬断。 */
    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add(""); // 空段落也占一行，玩家写的空行不被吃掉
            return out;
        }

        int start = 0;
        int width = 0;
        int lastSpace = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') lastSpace = i;
            width += charWidth(c);

            if (width > PAGE_WIDTH) {
                boolean breakAtSpace = lastSpace > start;
                int cut = breakAtSpace ? lastSpace : i;
                // 硬断点落在 UTF-16 代理对中间时回退一个字符（空格断行不会：
                // 断点字符本身是空格，不可能配成代理对的低位）。回退后本行至少
                // 还剩 1 个字符（cut-1 > start 保证），代理对整体挪到下一行。
                if (!breakAtSpace && cut > start + 1
                        && Character.isHighSurrogate(text.charAt(cut - 1))) {
                    cut--;
                }
                out.add(text.substring(start, cut));

                start = breakAtSpace ? cut + 1 : cut;
                lastSpace = -1;
                width = 0;
                for (int j = start; j <= i; j++) width += charWidth(text.charAt(j));
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private static int charWidth(char c) {
        return c < 0x2E80 ? 6 : 9;
    }
}
