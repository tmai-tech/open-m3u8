package com.iheartradio.m3u8.http.catalog;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoJobStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local library catalog on disk ({@code media/catalog.json}). Shared by ingest and the packager.
 */
public final class CatalogStore {

    public static final String DEFAULT_AD_URL = Title.DEFAULT_AD_URL;
    public static final float DEFAULT_AD_OFFSET = Title.DEFAULT_AD_OFFSET;
    public static final float DEFAULT_AD_DURATION = Title.DEFAULT_AD_DURATION;

    private final File mediaRoot;

    public CatalogStore(File mediaRoot) {
        this.mediaRoot = mediaRoot != null ? mediaRoot : new File("media");
    }

    public File mediaRoot() {
        return mediaRoot;
    }

    public File catalogFile() {
        return new File(mediaRoot, "catalog.json");
    }

    public File lockFile() {
        return new File(mediaRoot, "catalog.lock");
    }

    public File inboxDir() {
        return new File(mediaRoot, "inbox");
    }

    public File titlesDir() {
        return new File(mediaRoot, "titles");
    }

    public File cancelFile(String id) {
        return new File(inboxDir(), id + ".cancel");
    }

    public boolean isCancelled(String id) {
        return id != null && cancelFile(id).isFile();
    }

    public String allocateId(List<Title> titles, String slug) {
        String want = slug == null || slug.length() == 0 ? "title" : slug;
        if (!idTaken(titles, want)) {
            return want;
        }
        for (int n = 2; n < 1000; n++) {
            String next = want + "-" + n;
            if (!idTaken(titles, next)) {
                return next;
            }
        }
        return want + "-" + System.currentTimeMillis();
    }

    private boolean idTaken(List<Title> titles, String id) {
        if (titles != null) {
            for (Title t : titles) {
                if (t != null && id.equals(t.id)) {
                    return true;
                }
            }
        }
        return isCancelled(id);
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

    public Title find(String id) throws IOException {
        return find(loadOrDiscover(), id);
    }

    public List<Title> loadOrDiscover() throws IOException {
        List<Title> titles = load();
        if (titles.isEmpty()) {
            titles = discoverReadyTitles();
            if (!titles.isEmpty()) {
                save(titles);
            }
            return titles;
        }
        if (normalizeTitles(titles)) {
            save(titles);
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
            if (TitleNames.looksGenerated(t.title)) {
                String next = TitleNames.titleFromFilename(t.id + ".mp4");
                if (next != null && !next.equals(t.title)) {
                    t.title = next;
                    changed = true;
                }
            }
        }
        return changed;
    }

    public List<Title> load() throws IOException {
        File f = catalogFile();
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
            t.adOffset = (float) DemoHttp.jsonNumber(obj, "adOffset", Title.DEFAULT_AD_OFFSET);
            t.adDuration = (float) DemoHttp.jsonNumber(obj, "adDuration", Title.DEFAULT_AD_DURATION);
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

    public void save(List<Title> titles) throws IOException {
        if (mediaRoot != null && !mediaRoot.isDirectory()) {
            mediaRoot.mkdirs();
        }
        File dest = catalogFile();
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

    public List<Title> update(Mutator mutator) throws IOException {
        File lock = lockFile();
        if (lock.getParentFile() != null) {
            lock.getParentFile().mkdirs();
        }
        RandomAccessFile raf = new RandomAccessFile(lock, "rw");
        try {
            FileChannel ch = raf.getChannel();
            FileLock fl = ch.lock();
            try {
                List<Title> titles = loadOrDiscover();
                mutator.mutate(titles);
                save(titles);
                return titles;
            } finally {
                fl.release();
            }
        } finally {
            raf.close();
        }
    }

    public List<Title> discoverReadyTitles() {
        List<Title> out = new ArrayList<Title>();
        File root = titlesDir();
        File[] dirs = root.isDirectory() ? root.listFiles() : null;
        if (dirs == null) {
            return out;
        }
        Map<String, String> pretty = TitleNames.knownTitles();
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
            t.title = pretty.containsKey(t.id) ? pretty.get(t.id) : TitleNames.prettyName(t.id);
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
