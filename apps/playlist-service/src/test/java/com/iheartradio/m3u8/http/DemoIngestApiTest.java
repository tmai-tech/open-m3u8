package com.iheartradio.m3u8.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoIngestApiTest {

    private int port;
    private File mediaRoot;

    @Before
    public void setUp() throws Exception {
        File staticRoot = Files.createTempDirectory("ingest-ui").toFile();
        Files.write(new File(staticRoot, "index.html").toPath(),
                "<html>demo</html>".getBytes(StandardCharsets.UTF_8));
        mediaRoot = Files.createTempDirectory("ingest-media").toFile();
        port = freePort();
        DemoPlayerServer server = new DemoPlayerServer(port, staticRoot, mediaRoot);
        Thread t = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "ingest-api-test");
        t.setDaemon(true);
        t.start();
        waitForHealth(port, 15000);
    }

    @After
    public void tearDown() {
        // daemon server dies with the JVM
    }

    @Test
    public void catalogStartsEmptyThenIngestQueuesMp4() throws Exception {
        HttpURLConnection get = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/catalog").openConnection();
        assertEquals(200, get.getResponseCode());
        assertTrue(readFully(get.getInputStream()).contains("\"titles\":[]"));

        String boundary = "----junitbound";
        byte[] body = DemoIngestTest.multipart(boundary, "My Clip.mp4", "video/mp4",
                new byte[] { 1, 2, 3, 4 }, "My Clip");
        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        post.getOutputStream().write(body);
        assertEquals(202, post.getResponseCode());
        String queued = readFully(post.getInputStream());
        assertTrue(queued.contains("\"id\":\"my-clip\""));
        assertTrue(queued.contains("\"status\":\"queued\""));

        File inbox = new File(mediaRoot, "inbox/my-clip.mp4");
        assertTrue(inbox.isFile());
        assertEquals(4, inbox.length());

        HttpURLConnection again = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/catalog").openConnection();
        String catalog = readFully(again.getInputStream());
        assertTrue(catalog.contains("my-clip"));
        assertTrue(catalog.contains("queued"));

        HttpURLConnection dup = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest").openConnection();
        dup.setRequestMethod("POST");
        dup.setDoOutput(true);
        dup.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        dup.getOutputStream().write(body);
        assertEquals(409, dup.getResponseCode());
        String dupBody = readFully(dup.getErrorStream() != null ? dup.getErrorStream() : dup.getInputStream());
        assertTrue(dupBody.contains("\"status\":\"duplicate\""));
        assertTrue(dupBody.contains("my-clip"));
        File[] inboxMp4 = new File(mediaRoot, "inbox").listFiles((dir, name) -> name.endsWith(".mp4"));
        assertEquals(1, inboxMp4 == null ? 0 : inboxMp4.length);
    }

    @Test
    public void ingestJobLogIsReadableAfterUpload() throws Exception {
        String boundary = "----junitbound";
        byte[] body = DemoIngestTest.multipart(boundary, "Clip.mp4", "video/mp4",
                new byte[] { 9, 9, 9 }, "Clip");
        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        post.getOutputStream().write(body);
        assertEquals(202, post.getResponseCode());

        HttpURLConnection get = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest?id=clip").openConnection();
        assertEquals(200, get.getResponseCode());
        String job = readFully(get.getInputStream());
        assertTrue(job.contains("\"status\":\"queued\""));
        assertTrue(job.contains("waiting for packager"));

        HttpURLConnection bad = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest?id=../secret").openConnection();
        assertEquals(400, bad.getResponseCode());
    }

    @Test
    public void deleteQueuedRemovesInboxAndCatalog() throws Exception {
        String boundary = "----junitbound";
        byte[] body = DemoIngestTest.multipart(boundary, "Gone.mp4", "video/mp4",
                new byte[] { 7, 7, 7, 7 }, "Gone");
        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        post.getOutputStream().write(body);
        assertEquals(202, post.getResponseCode());
        assertTrue(new File(mediaRoot, "inbox/gone.mp4").isFile());

        HttpURLConnection del = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest?id=gone").openConnection();
        del.setRequestMethod("DELETE");
        assertEquals(200, del.getResponseCode());
        String out = readFully(del.getInputStream());
        assertTrue(out.contains("\"deleted\":true"));
        assertFalse(new File(mediaRoot, "inbox/gone.mp4").exists());
        HttpURLConnection cat = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/catalog").openConnection();
        assertFalse(readFully(cat.getInputStream()).contains("\"id\":\"gone\""));
    }

    @Test
    public void deleteUnknownIs404() throws Exception {
        HttpURLConnection del = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest?id=nope").openConnection();
        del.setRequestMethod("DELETE");
        assertEquals(404, del.getResponseCode());
    }

    @Test
    public void deleteHouseAdInUseIs409() throws Exception {
        DemoCatalog.Title ad = new DemoCatalog.Title();
        ad.id = "house";
        ad.title = "House";
        ad.url = "/media/titles/house/master.m3u8";
        ad.status = DemoJobStatus.READY;
        DemoCatalog.Title show = new DemoCatalog.Title();
        show.id = "show";
        show.title = "Show";
        show.url = "/media/titles/show/master.m3u8";
        show.adUrl = "/media/titles/house/master.m3u8";
        show.status = DemoJobStatus.READY;
        java.util.List<DemoCatalog.Title> list = new java.util.ArrayList<DemoCatalog.Title>();
        list.add(ad);
        list.add(show);
        DemoCatalog.save(mediaRoot, list);

        HttpURLConnection del = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest?id=house").openConnection();
        del.setRequestMethod("DELETE");
        assertEquals(409, del.getResponseCode());
        String body = readFully(del.getErrorStream() != null ? del.getErrorStream() : del.getInputStream());
        assertTrue(body.contains("usedBy"));
        assertTrue(body.contains("show"));
    }

    @Test
    public void ingestRejectsNonMp4() throws Exception {
        String boundary = "----junitbound";
        byte[] body = DemoIngestTest.multipart(boundary, "note.txt", "text/plain",
                "hi".getBytes(StandardCharsets.UTF_8), "Note");
        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ingest").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        post.getOutputStream().write(body);
        assertEquals(400, post.getResponseCode());
    }

    private static void waitForHealth(int port, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + port + "/api/health").openConnection();
                c.setConnectTimeout(500);
                c.setReadTimeout(500);
                if (c.getResponseCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e;
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("demo server did not become healthy", last);
    }

    private static int freePort() throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int p = ss.getLocalPort();
        ss.close();
        return p;
    }

    private static String readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
