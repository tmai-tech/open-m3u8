package com.iheartradio.m3u8.http.ingest;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoJobStatus;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import com.iheartradio.m3u8.http.catalog.TitleNames;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Accept a user MP4 into {@code media/inbox/} and queue a catalog row. Does not run FFmpeg.
 */
public final class IngestService {

    public static final long MAX_BYTES = MultipartForm.MAX_BYTES;

    private final CatalogStore catalog;
    private final JobLog logs;

    public IngestService(CatalogStore catalog) {
        this.catalog = catalog;
        this.logs = new JobLog(catalog);
    }

    public CatalogStore catalog() {
        return catalog;
    }

    public JobLog logs() {
        return logs;
    }

    public static final class Result {
        public final Title title;
        public final File inboxFile;

        public Result(Title title, File inboxFile) {
            this.title = title;
            this.inboxFile = inboxFile;
        }
    }

    public static final class NotFoundException extends IllegalArgumentException {
        public NotFoundException(String id) {
            super("job not found: " + id);
        }
    }

    public static final class DeleteBlockedException extends IllegalStateException {
        public final List<String> usedBy;

        public DeleteBlockedException(List<String> usedBy) {
            super("in use as house ad by " + usedBy);
            this.usedBy = usedBy;
        }
    }

    public Result accept(HttpExchange ex) throws IOException {
        String ct = DemoHttp.firstHeader(ex.getRequestHeaders(), "Content-Type");
        if (ct == null) {
            throw new IllegalArgumentException("Content-Type is required");
        }
        String cl = DemoHttp.firstHeader(ex.getRequestHeaders(), "Content-Length");
        if (cl != null) {
            try {
                long n = Long.parseLong(cl.trim());
                if (n > MAX_BYTES) {
                    throw new IllegalArgumentException("file too large (max 200MB)");
                }
            } catch (NumberFormatException ignored) {
                // parse the stream with a running cap
            }
        }
        File inbox = catalog.inboxDir();
        inbox.mkdirs();
        MultipartForm.ParsedUpload parsed = MultipartForm.parseRequest(ex.getRequestBody(), ct, inbox);
        if (parsed.filename == null || !parsed.filename.toLowerCase(Locale.US).endsWith(".mp4")) {
            if (parsed.tempFile != null) {
                parsed.tempFile.delete();
            }
            throw new IllegalArgumentException("only .mp4 uploads are accepted");
        }
        if (parsed.tempFile == null || !parsed.tempFile.isFile() || parsed.tempFile.length() == 0) {
            throw new IllegalArgumentException("missing file");
        }
        if (parsed.tempFile.length() > MAX_BYTES) {
            parsed.tempFile.delete();
            throw new IllegalArgumentException("file too large (max 200MB)");
        }

        String display = TitleNames.displayTitle(parsed.filename, parsed.title);
        String slug = TitleNames.slug(parsed.filename);
        String hash = CatalogStore.sha256(parsed.tempFile);
        final String[] idHolder = new String[1];
        final boolean[] duplicate = new boolean[] { false };
        Title queued;
        synchronized (this) {
            catalog.update(titles -> {
                Title original = CatalogStore.findOriginal(titles, hash, slug);
                if (original != null) {
                    Title existing = CatalogStore.findDuplicateOf(titles, original.id);
                    if (existing != null) {
                        idHolder[0] = existing.id;
                        duplicate[0] = true;
                        return;
                    }
                    Title dup = new Title();
                    dup.id = catalog.allocateId(titles, slug + "-dup");
                    dup.title = original.title != null ? original.title : display;
                    dup.url = original.url;
                    dup.poster = original.poster;
                    dup.status = DemoJobStatus.DUPLICATE;
                    dup.contentHash = hash;
                    dup.duplicateOf = original.id;
                    dup.jobId = Title.newJobId();
                    original.ensureJobId();
                    dup.sub = "Duplicate of " + original.jobId;
                    titles.add(dup);
                    idHolder[0] = dup.id;
                    duplicate[0] = true;
                    return;
                }
                String id = catalog.allocateId(titles, slug);
                idHolder[0] = id;
                Title t = new Title();
                t.id = id;
                t.title = display;
                t.sub = "Queued";
                t.url = "/media/titles/" + id + "/master.m3u8";
                t.poster = "/media/titles/" + id + "/poster.jpg";
                t.status = DemoJobStatus.QUEUED;
                t.contentHash = hash;
                t.jobId = Title.newJobId();
                titles.add(t);
            });
            queued = CatalogStore.find(catalog.load(), idHolder[0]);
        }
        if (duplicate[0]) {
            parsed.tempFile.delete();
            logs.append(idHolder[0],
                    "duplicate of " + (queued != null ? queued.duplicateOf : "existing job")
                            + " — not queued for packager");
            return new Result(queued, null);
        }
        File dest = new File(inbox, idHolder[0] + ".mp4");
        if (dest.exists()) {
            dest.delete();
        }
        Files.move(parsed.tempFile.toPath(), dest.toPath());
        logs.append(idHolder[0],
                "queued saved inbox/" + dest.getName() + " (" + dest.length() + " bytes)");
        logs.append(idHolder[0], "waiting for packager (separate process)");
        return new Result(queued, dest);
    }

    /**
     * Cancel (queued/packaging) or delete (ready/failed/duplicate) and purge job files.
     */
    public Title delete(String id) throws IOException {
        if (!JobLog.validId(id)) {
            throw new IllegalArgumentException("invalid id");
        }
        final Title[] removed = new Title[1];
        catalog.update(titles -> {
            Title t = CatalogStore.find(titles, id);
            if (t == null) {
                throw new NotFoundException(id);
            }
            List<String> usedBy = new ArrayList<String>();
            for (int i = 0; i < titles.size(); i++) {
                Title o = titles.get(i);
                if (o == null || id.equals(o.id)) {
                    continue;
                }
                if (o.status == DemoJobStatus.READY && o.pointsAt(id)) {
                    usedBy.add(o.id);
                }
            }
            if (!usedBy.isEmpty()) {
                throw new DeleteBlockedException(usedBy);
            }
            File cancel = catalog.cancelFile(id);
            catalog.inboxDir().mkdirs();
            if (!cancel.isFile()) {
                Files.write(cancel.toPath(), new byte[0]);
            }
            Iterator<Title> it = titles.iterator();
            while (it.hasNext()) {
                Title o = it.next();
                if (o == null) {
                    continue;
                }
                if (id.equals(o.id) || id.equals(o.duplicateOf)) {
                    it.remove();
                }
            }
            removed[0] = t;
        });
        purgeJobFiles(id);
        Title gone = removed[0];
        boolean inFlight = gone != null && (gone.status == DemoJobStatus.QUEUED
                || gone.status == DemoJobStatus.PACKAGING);
        if (!inFlight) {
            File cancel = catalog.cancelFile(id);
            if (cancel.isFile()) {
                cancel.delete();
            }
        }
        return gone;
    }

    public void purgeJobFiles(String id) {
        File inbox = catalog.inboxDir();
        File[] files = new File[] {
                new File(inbox, id + ".mp4"),
                new File(inbox, id + ".work.mp4"),
                new File(inbox, id + ".failed.mp4"),
                new File(new File(inbox, "done"), id + ".mp4"),
                new File(new File(inbox, "logs"), id + ".log"),
        };
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) {
                files[i].delete();
            }
        }
        deleteTree(new File(catalog.titlesDir(), id));
    }

    static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                if (kids[i].isDirectory()) {
                    deleteTree(kids[i]);
                } else {
                    kids[i].delete();
                }
            }
        }
        dir.delete();
    }
}
