package com.iheartradio.m3u8.http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Per-title packager log at {@code media/inbox/logs/{id}.log}. Survives leaving the tab.
 */
public final class DemoJobLog {

    public static final int MAX_READ_CHARS = 80_000;

    private DemoJobLog() {
    }

    public static boolean validId(String id) {
        if (id == null || id.length() == 0 || id.length() > 80) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') {
                return false;
            }
        }
        return true;
    }

    public static File file(File mediaRoot, String id) {
        return new File(new File(DemoCatalog.inboxDir(mediaRoot), "logs"), id + ".log");
    }

    public static void append(File mediaRoot, String id, String line) {
        if (!validId(id) || line == null) {
            return;
        }
        File f = file(mediaRoot, id);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        try {
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                w.write(ts);
                w.write(' ');
                w.write(line);
                if (!line.endsWith("\n")) {
                    w.write('\n');
                }
                w.flush();
            } finally {
                fos.close();
            }
        } catch (IOException ignored) {
            // UI will show catalog status even if the log file cannot be written
        }
    }

    public static String read(File mediaRoot, String id) throws IOException {
        if (!validId(id)) {
            throw new IllegalArgumentException("invalid id");
        }
        File f = file(mediaRoot, id);
        if (!f.isFile()) {
            return "";
        }
        byte[] raw = Files.readAllBytes(f.toPath());
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.length() <= MAX_READ_CHARS) {
            return text;
        }
        return text.substring(text.length() - MAX_READ_CHARS);
    }
}
