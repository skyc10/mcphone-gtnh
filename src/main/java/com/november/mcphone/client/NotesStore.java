package com.november.mcphone.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 便签存储：.minecraft/mcphone/notes/*.txt，首行为标题，其余为正文。
 */
public final class NotesStore {

    public static final class Note {

        public String title;
        public String body;
        public File file;
    }

    private NotesStore() {}

    private static File dir() {
        File f = new File(PhoneCanvas.baseDir(), "notes");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static List<Note> list() {
        List<Note> out = new ArrayList<>();
        File[] files = dir().listFiles((d, n) -> n.toLowerCase().endsWith(".txt"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            Note n = read(f);
            if (n != null) out.add(n);
        }
        return out;
    }

    private static Note read(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            Note n = new Note();
            n.file = f;
            n.title = r.readLine();
            if (n.title == null) n.title = "";
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            n.body = sb.toString();
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    public static Note save(Note note) {
        boolean isNew = note.file == null;
        if (isNew) {
            note.file = new File(dir(), "note_" + System.currentTimeMillis() + ".txt");
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(note.file), StandardCharsets.UTF_8)) {
            w.write((note.title == null ? "" : note.title).replace("\n", " ") + "\n");
            w.write(note.body == null ? "" : note.body);
        } catch (Exception ignored) {}
        return note;
    }

    public static void delete(Note note) {
        if (note.file != null) note.file.delete();
    }
}
