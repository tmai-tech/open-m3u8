package com.iheartradio.m3u8.http.catalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Display names and slugs for uploaded files. Not persisted itself.
 */
public final class TitleNames {

    private TitleNames() {
    }

    /**
     * Display name for the Library rail. A real title wins; otherwise we clean the
     * filename (Grok exports are {@code grok-video-<uuid>.mp4}).
     */
    public static String displayTitle(String filename, String supplied) {
        if (supplied != null) {
            String s = supplied.trim();
            if (s.length() > 0 && !looksGenerated(s)) {
                return s;
            }
        }
        return titleFromFilename(filename);
    }

    public static String titleFromFilename(String filename) {
        String stem = stem(filename);
        if (stem.length() == 0) {
            return "Untitled";
        }
        String lower = stem.toLowerCase(Locale.US);
        if (lower.startsWith("grok-video") || lower.startsWith("grok_video")) {
            return "Grok clip";
        }
        String stripped = stem.replaceAll(
                "(?i)[-_]?[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$", "");
        stripped = stripped.replaceAll("[-_]+$", "").trim();
        if (stripped.length() == 0) {
            return "Untitled";
        }
        return prettyName(slug(stripped + ".mp4"));
    }

    public static boolean looksGenerated(String title) {
        if (title == null) {
            return true;
        }
        String n = title.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
        n = n.replaceAll("^-+", "").replaceAll("-+$", "");
        if (n.startsWith("grok-video-") || n.matches("grok-video")) {
            return true;
        }
        return n.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{8,}.*");
    }

    public static String stem(String filename) {
        if (filename == null) {
            return "";
        }
        String base = filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.trim();
    }

    public static String slug(String filename) {
        if (filename == null) {
            return "title";
        }
        String base = filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String s = base.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("-+$", "");
        }
        return s.length() == 0 ? "title" : s;
    }

    public static Map<String, String> knownTitles() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("summer-on-mars", "Summer on Mars");
        m.put("giff-day-1", "GIFF Day 1");
        m.put("grok-clip", "Grok clip");
        return m;
    }

    public static String prettyName(String id) {
        if (id == null || id.length() == 0) {
            return "Untitled";
        }
        String[] parts = id.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.length() == 0 ? id : sb.toString();
    }
}
