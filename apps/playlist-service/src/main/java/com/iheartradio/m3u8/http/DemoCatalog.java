package com.iheartradio.m3u8.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local library catalog on disk ({@code media/catalog.json}). Shared by ingest and the packager.
 */
public final class DemoCatalog {

    public static final String DEFAULT_AD_URL = "/media/titles/giff-day-1/master.m3u8";
    public static final float DEFAULT_AD_OFFSET = 10f;
    public static final float DEFAULT_AD_DURATION = 12f;

    private DemoCatalog() {
    }

    public static final class Title {
        public String id;
        public String title;
        public String sub;
        public String url;
        public String poster;
        public String adUrl;
        public float adOffset;
        public float adDuration;
        public DemoJobStatus status;
        public float durationSec;
        public String error;
        public String contentHash;
        public String duplicateOf;

        public Title() {
            this.adUrl = DEFAULT_AD_URL;
            this.adOffset = DEFAULT_AD_OFFSET;
            this.adDuration = DEFAULT_AD_DURATION;
            this.status = DemoJobStatus.READY;
        }
    }

    public static File catalogFile(File mediaRoot) {
        return new File(mediaRoot, "catalog.json");
    }

    public static File lockFile(File mediaRoot) {
        return new File(mediaRoot, "catalog.lock");
    }

    public static File inboxDir(File mediaRoot) {
        return new File(mediaRoot, "inbox");
    }

    public static File titlesDir(File mediaRoot) {
        return new File(mediaRoot, "titles");
    }

    public static File cancelFile(File mediaRoot, String id) {
        return new File(inboxDir(mediaRoot), id + ".cancel");
    }

    public static boolean isCancelled(File mediaRoot, String id) {
        return id != null && cancelFile(mediaRoot, id).isFile();
    }

    public static boolean pointsAtTitle(Title t, String id) {
        if (t == null || id == null || t.adUrl == null) {
            return false;
        }
        String ad = t.adUrl;
        return ad.equals("/media/titles/" + id + "/master.m3u8")
                || ad.endsWith("/media/titles/" + id + "/master.m3u8")
                || ad.endsWith("/" + id + "/master.m3u8");
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

    static String titleFromFilename(String filename) {
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

    static boolean looksGenerated(String title) {
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

    static String stem(String filename) {
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

    public static String allocateId(List<Title> titles, String slug) {
        return allocateId(titles, slug, null);
    }

    public static String allocateId(List<Title> titles, String slug, File mediaRoot) {
        String want = slug == null || slug.length() == 0 ? "title" : slug;
        if (!idTaken(titles, want, mediaRoot)) {
            return want;
        }
        for (int n = 2; n < 1000; n++) {
            String next = want + "-" + n;
            if (!idTaken(titles, next, mediaRoot)) {
                return next;
            }
        }
        return want + "-" + System.currentTimeMillis();
    }

    private static boolean idTaken(List<Title> titles, String id) {
        return idTaken(titles, id, null);
    }

    private static boolean idTaken(List<Title> titles, String id, File mediaRoot) {
        if (titles != null) {
            for (Title t : titles) {
                if (t != null && id.equals(t.id)) {
                    return true;
                }
            }
        }
        return mediaRoot != null && isCancelled(mediaRoot, id);
    }

    public static Title find(List<Title> titles, String id) {
        if (titles == null || id == null) {
            return null;
        }
        for (Title t : titles) {
            if (t != null && id.equals(t.id)) {
                return t;
            }
        }
        return null;
    }

    public static List<Title> loadOrDiscover(File mediaRoot) throws IOException {
        List<Title> titles = load(mediaRoot);
        if (titles.isEmpty()) {
            titles = discoverReadyTitles(mediaRoot);
            if (!titles.isEmpty()) {
                save(mediaRoot, titles);
            }
            return titles;
        }
        if (normalizeTitles(titles)) {
            save(mediaRoot, titles);
        }
        return titles;
    }

    static boolean normalizeTitles(List<Title> titles) {
        boolean changed = false;
        if (titles == null) {
            return false;
        }
        for (Title t : titles) {
            if (t == null) {
                continue;
            }
            if (looksGenerated(t.title)) {
                String next = titleFromFilename(t.id + ".mp4");
                if (next != null && !next.equals(t.title)) {
                    t.title = next;
                    changed = true;
                }
            }
        }
        return changed;
    }

    public static List<Title> load(File mediaRoot) throws IOException {
        File f = catalogFile(mediaRoot);
        if (!f.isFile()) {
            return new ArrayList<Title>();
        }
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return parse(json);
    }

    public static List<Title> parse(String json) {
        List<Title> out = new ArrayList<Title>();
        if (json == null || json.indexOf('{') < 0) {
            return out;
        }
        String arr = DemoHttp.jsonArray(json, "titles");
        if (arr == null) {
            return out;
        }
        List<String> objs = DemoHttp.splitJsonObjects(arr);
        for (String obj : objs) {
            Title t = new Title();
            t.id = DemoHttp.jsonStringValue(obj, "id");
            t.title = DemoHttp.jsonStringValue(obj, "title");
            t.sub = DemoHttp.jsonStringValue(obj, "sub");
            t.url = DemoHttp.jsonStringValue(obj, "url");
            t.poster = DemoHttp.jsonStringValue(obj, "poster");
            String ad = DemoHttp.jsonStringValue(obj, "adUrl");
            if (ad != null && ad.length() > 0) {
                t.adUrl = ad;
            }
            t.adOffset = (float) DemoHttp.jsonNumber(obj, "adOffset", DEFAULT_AD_OFFSET);
            t.adDuration = (float) DemoHttp.jsonNumber(obj, "adDuration", DEFAULT_AD_DURATION);
            t.status = DemoJobStatus.fromWire(DemoHttp.jsonStringValue(obj, "status"));
            t.durationSec = (float) DemoHttp.jsonNumber(obj, "durationSec", 0);
            t.error = DemoHttp.jsonStringValue(obj, "error");
            t.contentHash = DemoHttp.jsonStringValue(obj, "contentHash");
            t.duplicateOf = DemoHttp.jsonStringValue(obj, "duplicateOf");
            if (t.id != null && t.id.length() > 0) {
                out.add(t);
            }
        }
        return out;
    }

    public static void save(File mediaRoot, List<Title> titles) throws IOException {
        if (mediaRoot != null && !mediaRoot.isDirectory()) {
            mediaRoot.mkdirs();
        }
        File dest = catalogFile(mediaRoot);
        File tmp = new File(dest.getParentFile(), "catalog.json.tmp");
        Files.write(tmp.toPath(), write(titles).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String write(List<Title> titles) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"titles\":[");
        if (titles != null) {
            for (int i = 0; i < titles.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeTitle(sb, titles.get(i));
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void writeTitle(StringBuilder sb, Title t) {
        sb.append('{');
        sb.append("\"id\":").append(DemoHttp.jsonString(t.id));
        sb.append(",\"title\":").append(DemoHttp.jsonString(t.title));
        sb.append(",\"sub\":").append(DemoHttp.jsonString(t.sub));
        sb.append(",\"url\":").append(DemoHttp.jsonString(t.url));
        if (t.poster != null && t.poster.length() > 0) {
            sb.append(",\"poster\":").append(DemoHttp.jsonString(t.poster));
        }
        sb.append(",\"adUrl\":").append(DemoHttp.jsonString(t.adUrl));
        sb.append(",\"adOffset\":").append(trimFloat(t.adOffset));
        sb.append(",\"adDuration\":").append(trimFloat(t.adDuration));
        sb.append(",\"status\":").append(DemoHttp.jsonString(
                t.status != null ? t.status.wire : DemoJobStatus.READY.wire));
        sb.append(",\"durationSec\":").append(trimFloat(t.durationSec));
        if (t.error != null && t.error.length() > 0) {
            sb.append(",\"error\":").append(DemoHttp.jsonString(t.error));
        }
        if (t.contentHash != null && t.contentHash.length() > 0) {
            sb.append(",\"contentHash\":").append(DemoHttp.jsonString(t.contentHash));
        }
        if (t.duplicateOf != null && t.duplicateOf.length() > 0) {
            sb.append(",\"duplicateOf\":").append(DemoHttp.jsonString(t.duplicateOf));
        }
        sb.append('}');
    }

    private static String trimFloat(float n) {
        if (n == (long) n) {
            return Long.toString((long) n);
        }
        return Float.toString(n);
    }

    public interface Mutator {
        void mutate(List<Title> titles) throws IOException;
    }

    public static List<Title> update(File mediaRoot, Mutator mutator) throws IOException {
        File lock = lockFile(mediaRoot);
        if (lock.getParentFile() != null) {
            lock.getParentFile().mkdirs();
        }
        RandomAccessFile raf = new RandomAccessFile(lock, "rw");
        try {
            FileChannel ch = raf.getChannel();
            FileLock fl = ch.lock();
            try {
                List<Title> titles = loadOrDiscover(mediaRoot);
                mutator.mutate(titles);
                save(mediaRoot, titles);
                return titles;
            } finally {
                fl.release();
            }
        } finally {
            raf.close();
        }
    }

    public static List<Title> discoverReadyTitles(File mediaRoot) {
        List<Title> out = new ArrayList<Title>();
        File root = titlesDir(mediaRoot);
        File[] dirs = root.isDirectory() ? root.listFiles() : null;
        if (dirs == null) {
            return out;
        }
        Map<String, String> pretty = knownTitles();
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File master = new File(dir, "master.m3u8");
            if (!master.isFile()) {
                continue;
            }
            Title t = new Title();
            t.id = dir.getName();
            t.title = pretty.containsKey(t.id) ? pretty.get(t.id) : prettyName(t.id);
            t.url = "/media/titles/" + t.id + "/master.m3u8";
            File poster = new File(dir, "poster.jpg");
            if (poster.isFile()) {
                t.poster = "/media/titles/" + t.id + "/poster.jpg";
            }
            t.status = DemoJobStatus.READY;
            t.sub = "Local · 720p";
            out.add(t);
        }
        return out;
    }

    static Map<String, String> knownTitles() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("summer-on-mars", "Summer on Mars");
        m.put("giff-day-1", "GIFF Day 1");
        m.put("grok-clip", "Grok clip");
        return m;
    }

    static String prettyName(String id) {
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

    public static boolean blocksTranscode(DemoJobStatus status) {
        return status == DemoJobStatus.QUEUED
                || status == DemoJobStatus.PACKAGING
                || status == DemoJobStatus.READY;
    }

    public static Title findOriginal(List<Title> titles, String contentHash, String slug) {
        if (titles == null) {
            return null;
        }
        Title bySlug = null;
        for (Title t : titles) {
            if (t == null || !blocksTranscode(t.status)) {
                continue;
            }
            if (contentHash != null && contentHash.length() > 0
                    && contentHash.equals(t.contentHash)) {
                return t;
            }
            if (slug != null && slugMatches(t.id, slug) && bySlug == null) {
                bySlug = t;
            }
        }
        return bySlug;
    }

    public static Title findDuplicateOf(List<Title> titles, String originalId) {
        if (titles == null || originalId == null) {
            return null;
        }
        for (Title t : titles) {
            if (t != null && t.status == DemoJobStatus.DUPLICATE && originalId.equals(t.duplicateOf)) {
                return t;
            }
        }
        return null;
    }

    static boolean slugMatches(String id, String slug) {
        if (id == null || slug == null || slug.length() == 0) {
            return false;
        }
        return id.equals(slug) || id.startsWith(slug + "-");
    }

    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (int i = 0; i < dig.length; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i] & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
