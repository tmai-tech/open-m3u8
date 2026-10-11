package com.iheartradio.m3u8.http.ads;

import com.iheartradio.m3u8.http.DemoJobStatus;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Creatives the decision server may choose when a slot names none.
 * Ready catalog titles with {@code adEligible}, then packs under {@code media/ads}.
 */
public final class AdInventory {

    public static final class Creative {
        public final String id;
        public final String hlsUrl;
        public final String dashUrl;
        public final float durationSec;
        /** False excludes the creative. Unknown is allowed. */
        public final boolean audio;

        public Creative(String id, String hlsUrl, String dashUrl, float durationSec, boolean audio) {
            this.id = id;
            this.hlsUrl = hlsUrl;
            this.dashUrl = dashUrl;
            this.durationSec = durationSec;
            this.audio = audio;
        }
    }

    private AdInventory() {}

    public static List<Creative> load(CatalogStore catalog, File adsDir) {
        List<Title> titles = new ArrayList<Title>();
        if (catalog != null) {
            try {
                titles = catalog.load();
            } catch (IOException ignored) {
                titles = new ArrayList<Title>();
            }
        }
        return load(titles, adsDir);
    }

    public static List<Creative> load(List<Title> titles, File adsDir) {
        List<Creative> out = new ArrayList<Creative>();
        if (titles != null) {
            for (Title t : titles) {
                Creative creative = fromTitle(t);
                if (creative != null) {
                    out.add(creative);
                }
            }
        }
        if (adsDir != null && adsDir.isDirectory()) {
            File[] dirs = adsDir.listFiles();
            if (dirs != null) {
                List<File> sorted = new ArrayList<File>();
                Collections.addAll(sorted, dirs);
                Collections.sort(sorted, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareTo(b.getName());
                    }
                });
                for (File dir : sorted) {
                    if (dir == null || !dir.isDirectory() || containsId(out, dir.getName())) {
                        continue;
                    }
                    Creative creative = fromPack(dir);
                    if (creative != null) {
                        out.add(creative);
                    }
                }
            }
        }
        return out;
    }

    static Creative fromTitle(Title t) {
        if (t == null || !t.adEligible || t.status != DemoJobStatus.READY) {
            return null;
        }
        if (t.durationSec <= 0f || t.url == null || t.url.length() == 0) {
            return null;
        }
        if (t.hasAudio != null && !t.hasAudio.booleanValue()) {
            return null;
        }
        return new Creative(t.id, t.url, t.dashUrl, t.durationSec, true);
    }

    static Creative fromPack(File dir) {
        File master = new File(dir, "master.m3u8");
        if (!master.isFile()) {
            return null;
        }
        float dur = readDuration(master);
        if (dur <= 0f) {
            return null;
        }
        String id = dir.getName();
        String hls = "/media/ads/" + id + "/master.m3u8";
        File mpd = new File(dir, "manifest.mpd");
        String dash = mpd.isFile() ? "/media/ads/" + id + "/manifest.mpd" : null;
        return new Creative(id, hls, dash, dur, true);
    }

    private static boolean containsId(List<Creative> list, String id) {
        for (Creative c : list) {
            if (id.equals(c.id)) {
                return true;
            }
        }
        return false;
    }

    /** Media length in seconds, or 0 when the file has no readable duration. */
    public static float readDuration(File file) {
        if (file == null || !file.isFile()) {
            return 0f;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return 0f;
        }
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mpd")) {
            return mpdDuration(text);
        }
        float sum = sumExtInf(text);
        if (sum > 0f) {
            return sum;
        }
        String child = firstVariant(text);
        if (child == null) {
            return 0f;
        }
        File next = new File(file.getParentFile(), child);
        if (!next.isFile() || next.equals(file)) {
            return 0f;
        }
        return sumExtInf(readQuiet(next));
    }

    private static String readQuiet(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static float sumExtInf(String text) {
        if (text == null || text.length() == 0) {
            return 0f;
        }
        float sum = 0f;
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            String line = (nl < 0 ? text.substring(i) : text.substring(i, nl)).trim();
            if (line.startsWith("#EXTINF:")) {
                String num = line.substring("#EXTINF:".length());
                int comma = num.indexOf(',');
                if (comma >= 0) {
                    num = num.substring(0, comma);
                }
                try {
                    float d = Float.parseFloat(num.trim());
                    if (d > 0f) {
                        sum += d;
                    }
                } catch (NumberFormatException ignored) {
                    // skip a bad tag
                }
            }
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        return sum;
    }

    static String firstVariant(String text) {
        if (text == null) {
            return null;
        }
        boolean inf = false;
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            String line = (nl < 0 ? text.substring(i) : text.substring(i, nl)).trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                inf = true;
            } else if (inf && line.length() > 0 && line.charAt(0) != '#') {
                int q = line.indexOf('?');
                String uri = q >= 0 ? line.substring(0, q) : line;
                if (uri.toLowerCase().endsWith(".m3u8")) {
                    return uri;
                }
                inf = false;
            }
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        return null;
    }

    static float mpdDuration(String xml) {
        if (xml == null) {
            return 0f;
        }
        int key = xml.indexOf("mediaPresentationDuration=\"");
        if (key < 0) {
            return 0f;
        }
        int start = key + "mediaPresentationDuration=\"".length();
        int end = xml.indexOf('"', start);
        if (end < 0) {
            return 0f;
        }
        return isoDuration(xml.substring(start, end));
    }

    /** Parses {@code PT#H#M#S}. Anything else is 0. */
    static float isoDuration(String raw) {
        if (raw == null || !raw.startsWith("PT") || raw.length() < 3) {
            return 0f;
        }
        float hours = 0f;
        float minutes = 0f;
        float seconds = 0f;
        StringBuilder num = new StringBuilder();
        for (int i = 2; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                num.append(c);
                continue;
            }
            float v;
            try {
                v = num.length() == 0 ? 0f : Float.parseFloat(num.toString());
            } catch (NumberFormatException e) {
                return 0f;
            }
            num.setLength(0);
            if (c == 'H') {
                hours = v;
            } else if (c == 'M') {
                minutes = v;
            } else if (c == 'S') {
                seconds = v;
            } else {
                return 0f;
            }
        }
        return hours * 3600f + minutes * 60f + seconds;
    }
}
