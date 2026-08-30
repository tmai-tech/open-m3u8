package com.iheartradio.m3u8.http;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Separate process: watch {@code media/inbox/*.mp4}, package fMP4 HLS, update catalog.json.
 * Does not serve HTTP. Run beside {@link DemoPlayerServer}.
 */
public final class DemoPackager {

    private static final int POLL_MS = 2000;
    private static final int FFMPEG_TIMEOUT_MIN = 30;

    private final File mediaRoot;
    private final String ffmpeg;
    private final String ffprobe;

    public DemoPackager(File mediaRoot, String ffmpeg) {
        this.mediaRoot = mediaRoot;
        this.ffmpeg = ffmpeg;
        this.ffprobe = siblingBin(ffmpeg, "ffprobe");
    }

    public static void main(String[] args) throws Exception {
        File root = args.length > 0 ? new File(args[0]) : DemoPlayerServer.locateMediaRoot();
        if (!root.isDirectory()) {
            root.mkdirs();
        }
        String bin = resolveFfmpeg();
        if (bin == null) {
            System.err.println("demo-packager: ffmpeg not found on PATH (set FFMPEG)");
            System.exit(2);
        }
        System.out.println("demo-packager");
        System.out.println("  Media:  " + root.getAbsolutePath());
        System.out.println("  FFmpeg: " + bin);
        System.out.println("  Probe:  " + siblingBin(bin, "ffprobe"));
        System.out.println("  Inbox:  " + new File(root, "inbox").getAbsolutePath());
        DemoPackager p = new DemoPackager(root, bin);
        while (true) {
            try {
                p.tick();
            } catch (Exception e) {
                System.err.println("demo-packager: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            Thread.sleep(POLL_MS);
        }
    }

    void tick() throws IOException {
        File inbox = DemoCatalog.inboxDir(mediaRoot);
        if (!inbox.isDirectory()) {
            return;
        }
        sweepOrphanCancels(inbox);
        File[] files = inbox.listFiles();
        if (files == null) {
            return;
        }
        List<File> waiting = new ArrayList<File>();
        for (File f : files) {
            if (f == null || !f.isFile()) {
                continue;
            }
            String name = f.getName();
            if (!name.toLowerCase(Locale.US).endsWith(".mp4")) {
                continue;
            }
            if (name.endsWith(".part") || name.endsWith(".work.mp4") || name.endsWith(".failed.mp4")) {
                continue;
            }
            waiting.add(f);
        }
        Collections.sort(waiting, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : waiting) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            if (gone(id)) {
                dropCancelled(id, "skip cancelled");
                continue;
            }
            packageOne(id, f);
        }
    }

    boolean gone(String id) throws IOException {
        return DemoCatalog.isCancelled(mediaRoot, id)
                || DemoCatalog.find(DemoCatalog.load(mediaRoot), id) == null;
    }

    void dropCancelled(String id, String note) throws IOException {
        DemoJobLog.append(mediaRoot, id, note);
        DemoIngest.purgeJobFiles(mediaRoot, id);
        File cancel = DemoCatalog.cancelFile(mediaRoot, id);
        if (cancel.isFile()) {
            cancel.delete();
        }
    }

    void sweepOrphanCancels(File inbox) {
        File[] flags = inbox.listFiles();
        if (flags == null) {
            return;
        }
        for (File f : flags) {
            if (f == null || !f.isFile()) {
                continue;
            }
            String name = f.getName();
            if (!name.endsWith(".cancel")) {
                continue;
            }
            String id = name.substring(0, name.length() - ".cancel".length());
            if (!DemoJobLog.validId(id)) {
                continue;
            }
            if (new File(inbox, id + ".mp4").isFile()
                    || new File(inbox, id + ".work.mp4").isFile()) {
                continue;
            }
            try {
                if (DemoCatalog.find(DemoCatalog.load(mediaRoot), id) != null) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }
            f.delete();
        }
    }

    void packageOne(String id, File src) throws IOException {
        if (gone(id)) {
            dropCancelled(id, "cancelled");
            return;
        }
        File work = new File(src.getParentFile(), id + ".work.mp4");
        if (work.exists()) {
            work.delete();
        }
        if (!src.renameTo(work)) {
            throw new IOException("could not claim " + src.getName());
        }
        DemoJobLog.append(mediaRoot, id, "packaging claimed " + work.getName());
        if (gone(id)) {
            dropCancelled(id, "cancelled");
            return;
        }
        DemoCatalog.update(mediaRoot, titles -> {
            DemoCatalog.Title t = DemoCatalog.find(titles, id);
            if (t == null) {
                return;
            }
            t.status = DemoJobStatus.PACKAGING;
            t.sub = "Packaging…";
            t.error = null;
        });
        if (gone(id)) {
            dropCancelled(id, "cancelled");
            return;
        }
        File dest = new File(DemoCatalog.titlesDir(mediaRoot), id);
        try {
            PackageResult result = encode(id, work, dest);
            if (gone(id)) {
                throw new InterruptedException("cancelled");
            }
            writeMaster(dest, result);
            DemoJobLog.append(mediaRoot, id, "ready " + result.durationSec + "s");
            File doneDir = new File(src.getParentFile(), "done");
            doneDir.mkdirs();
            File done = new File(doneDir, id + ".mp4");
            if (done.exists()) {
                done.delete();
            }
            work.renameTo(done);
            DemoCatalog.update(mediaRoot, titles -> {
                DemoCatalog.Title t = DemoCatalog.find(titles, id);
                if (t == null) {
                    return;
                }
                t.status = DemoJobStatus.READY;
                t.contentHash = DemoCatalog.sha256(done);
                t.durationSec = result.durationSec;
                t.sub = "Local · " + Math.round(result.durationSec) + "s · 720p";
                t.url = "/media/titles/" + id + "/master.m3u8";
                t.poster = "/media/titles/" + id + "/poster.jpg";
                t.error = null;
            });
            System.out.println("demo-packager: ready " + id + " (" + result.durationSec + "s)");
        } catch (InterruptedException e) {
            dropCancelled(id, "cancelled");
        } catch (Exception e) {
            if (gone(id)) {
                dropCancelled(id, "cancelled");
                return;
            }
            DemoJobLog.append(mediaRoot, id, "failed " + e.getMessage());
            File failed = new File(src.getParentFile(), id + ".failed.mp4");
            if (failed.exists()) {
                failed.delete();
            }
            work.renameTo(failed);
            DemoCatalog.update(mediaRoot, titles -> {
                DemoCatalog.Title t = DemoCatalog.find(titles, id);
                if (t == null) {
                    return;
                }
                t.status = DemoJobStatus.FAILED;
                t.sub = "Failed";
                t.error = e.getMessage();
            });
            System.err.println("demo-packager: failed " + id + ": " + e.getMessage());
        }
    }

    static final class PackageResult {
        float durationSec;
        int fps = 24;
        int bandwidth = 2_000_000;
    }

    PackageResult encode(String id, File src, File dest) throws IOException, InterruptedException {
        if (dest.exists()) {
            deleteTree(dest);
        }
        dest.mkdirs();
        PackageResult r = new PackageResult();
        r.durationSec = probeDuration(src);
        r.fps = probeFps(src);
        if (r.fps <= 0) {
            r.fps = 24;
        }
        boolean audio = hasAudio(src);
        DemoJobLog.append(mediaRoot, id, "probe duration=" + r.durationSec
                + "s fps=" + r.fps + " audio=" + audio);
        int gop = r.fps * 4;
        List<String> cmd = new ArrayList<String>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("info");
        cmd.add("-i");
        cmd.add(src.getAbsolutePath());
        cmd.add("-map");
        cmd.add("0:v:0");
        if (audio) {
            cmd.add("-map");
            cmd.add("0:a:0");
        }
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-level");
        cmd.add("4.1");
        cmd.add("-g");
        cmd.add(Integer.toString(gop));
        cmd.add("-keyint_min");
        cmd.add(Integer.toString(gop));
        cmd.add("-sc_threshold");
        cmd.add("0");
        if (audio) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-ar");
            cmd.add("48000");
            cmd.add("-ac");
            cmd.add("2");
            cmd.add("-b:a");
            cmd.add("128k");
        }
        cmd.add("-f");
        cmd.add("hls");
        cmd.add("-hls_time");
        cmd.add("4");
        cmd.add("-hls_playlist_type");
        cmd.add("vod");
        cmd.add("-hls_flags");
        cmd.add("independent_segments");
        cmd.add("-hls_segment_type");
        cmd.add("fmp4");
        cmd.add("-hls_fmp4_init_filename");
        cmd.add("init.m4s");
        cmd.add("-hls_segment_filename");
        cmd.add(new File(dest, "v720_%d.m4s").getAbsolutePath());
        cmd.add(new File(dest, "v720.m3u8").getAbsolutePath());
        DemoJobLog.append(mediaRoot, id, "ffmpeg hls start");
        run(cmd, FFMPEG_TIMEOUT_MIN, TimeUnit.MINUTES, id);
        DemoJobLog.append(mediaRoot, id, "ffmpeg hls done");

        float posterAt = r.durationSec > 0 ? r.durationSec * 0.2f : 1f;
        List<String> poster = new ArrayList<String>();
        poster.add(ffmpeg);
        poster.add("-y");
        poster.add("-hide_banner");
        poster.add("-loglevel");
        poster.add("error");
        poster.add("-ss");
        poster.add(Float.toString(posterAt));
        poster.add("-i");
        poster.add(src.getAbsolutePath());
        poster.add("-map");
        poster.add("0:v:0");
        poster.add("-frames:v");
        poster.add("1");
        poster.add("-update");
        poster.add("1");
        poster.add("-q:v");
        poster.add("3");
        poster.add(new File(dest, "poster.jpg").getAbsolutePath());
        DemoJobLog.append(mediaRoot, id, "poster start");
        run(poster, 2, TimeUnit.MINUTES, id);
        DemoJobLog.append(mediaRoot, id, "poster done");

        r.bandwidth = estimateBandwidth(dest, r.durationSec);
        return r;
    }

    void writeMaster(File dest, PackageResult r) throws IOException {
        String codecs = "avc1.640029,mp4a.40.2";
        String body = "#EXTM3U\n"
                + "#EXT-X-VERSION:7\n"
                + "#EXT-X-INDEPENDENT-SEGMENTS\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=" + r.bandwidth
                + ",AVERAGE-BANDWIDTH=" + Math.max(1, (int) (r.bandwidth * 0.9))
                + ",RESOLUTION=1280x720,FRAME-RATE=" + r.fps
                + ",CODECS=\"" + codecs + "\"\n"
                + "v720.m3u8\n";
        Files.write(new File(dest, "master.m3u8").toPath(), body.getBytes(StandardCharsets.UTF_8));
    }

    float probeDuration(File src) {
        try {
            String out = runCapture(ffprobe, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=nw=1:nk=1", src.getAbsolutePath());
            return Float.parseFloat(out.trim());
        } catch (Exception e) {
            return 0f;
        }
    }

    int probeFps(File src) {
        try {
            String out = runCapture(ffprobe, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=r_frame_rate", "-of", "default=nw=1:nk=1",
                    src.getAbsolutePath());
            String s = out.trim();
            int slash = s.indexOf('/');
            if (slash > 0) {
                float num = Float.parseFloat(s.substring(0, slash));
                float den = Float.parseFloat(s.substring(slash + 1));
                if (den != 0) {
                    return Math.max(1, Math.round(num / den));
                }
            }
            return Math.max(1, Math.round(Float.parseFloat(s)));
        } catch (Exception e) {
            return 24;
        }
    }

    boolean hasAudio(File src) {
        try {
            String out = runCapture(ffprobe, "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=codec_type", "-of", "default=nw=1:nk=1",
                    src.getAbsolutePath());
            return out != null && out.toLowerCase(Locale.US).contains("audio");
        } catch (Exception e) {
            return false;
        }
    }

    int estimateBandwidth(File dest, float durationSec) {
        long bytes = 0;
        File[] files = dest.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".m4s") || n.endsWith(".mp4")) {
                    bytes += f.length();
                }
            }
        }
        if (durationSec <= 0.1f) {
            return 2_000_000;
        }
        return Math.max(200_000, (int) (bytes * 8.0 / durationSec));
    }

    void run(List<String> cmd, long timeout, TimeUnit unit, String jobId)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder err = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (jobId != null && (DemoCatalog.isCancelled(mediaRoot, jobId)
                        || DemoCatalog.find(DemoCatalog.load(mediaRoot), jobId) == null)) {
                    p.destroyForcibly();
                    throw new InterruptedException("cancelled");
                }
                if (jobId != null) {
                    DemoJobLog.append(mediaRoot, jobId, line);
                }
                if (err.length() < 4000) {
                    err.append(line).append('\n');
                }
            }
        }
        if (!p.waitFor(timeout, unit)) {
            p.destroyForcibly();
            throw new IOException("timed out: " + cmd.get(0));
        }
        if (p.exitValue() != 0) {
            throw new IOException(cmd.get(0) + " exited " + p.exitValue()
                    + (err.length() > 0 ? ": " + err.toString().trim() : ""));
        }
    }

    String runCapture(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timed out: " + cmd[0]);
        }
        if (p.exitValue() != 0) {
            throw new IOException(cmd[0] + " exited " + p.exitValue());
        }
        return out.toString();
    }

    static void deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    deleteTree(k);
                } else {
                    k.delete();
                }
            }
        }
        dir.delete();
    }

    static String resolveFfmpeg() {
        String env = System.getenv("FFMPEG");
        if (env != null && new File(env).isFile()) {
            return env;
        }
        String ffprobeHome = System.getenv("HOME");
        if (ffprobeHome != null) {
            File local = new File(ffprobeHome, ".local/bin/ffmpeg");
            if (local.isFile()) {
                return local.getAbsolutePath();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            String[] parts = path.split(File.pathSeparator);
            for (String part : parts) {
                File f = new File(part, "ffmpeg");
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
                File exe = new File(part, "ffmpeg.exe");
                if (exe.isFile()) {
                    return exe.getAbsolutePath();
                }
            }
        }
        return "ffmpeg";
    }

    static String siblingBin(String ffmpegPath, String name) {
        if (ffmpegPath == null) {
            return name;
        }
        File f = new File(ffmpegPath);
        File dir = f.getParentFile();
        String base = f.getName().toLowerCase(Locale.US).endsWith(".exe") ? name + ".exe" : name;
        if (dir != null) {
            File sib = new File(dir, base);
            if (sib.isFile()) {
                return sib.getAbsolutePath();
            }
        }
        return name;
    }
}
