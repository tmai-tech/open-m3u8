package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import com.iheartradio.m3u8.http.ingest.IngestService;
import com.iheartradio.m3u8.http.ingest.MultipartForm;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DemoIngestTest {

    @Test
    public void parsesMultipartBoundary() {
        assertEquals("----WebKitFormBoundary7",
                MultipartForm.multipartBoundary(
                        "multipart/form-data; boundary=----WebKitFormBoundary7"));
        assertEquals("abc",
                MultipartForm.multipartBoundary("multipart/form-data; boundary=\"abc\""));
    }

    @Test
    public void parsesFileAndTitleParts() throws Exception {
        String boundary = "----testbound";
        byte[] body = multipart(boundary, "Holiday Clip.mp4", "video/mp4",
                new byte[] { 0, 1, 2, 3, 4 }, "My Holiday");
        File inbox = Files.createTempDirectory("ingest-mp").toFile();
        File dest = new File(inbox, "tmp.part");
        MultipartForm.ParsedUpload parsed = MultipartForm.parseMultipart(
                new ByteArrayInputStream(body), boundary, dest);
        assertEquals("Holiday Clip.mp4", parsed.filename);
        assertEquals("My Holiday", parsed.title);
        assertTrue(parsed.tempFile.isFile());
        assertEquals(5, parsed.tempFile.length());
    }

    @Test
    public void headerAttrReadsQuotedFilename() {
        String headers = "Content-Disposition: form-data; name=\"file\"; filename=\"GIFF Day 1.mp4\"\r\n"
                + "Content-Type: video/mp4";
        assertEquals("file", MultipartForm.headerAttr(headers, "name"));
        assertEquals("GIFF Day 1.mp4", MultipartForm.headerAttr(headers, "filename"));
    }

    @Test
    public void deleteQueuedPurgesInboxAndLeavesCancel() throws Exception {
        File root = Files.createTempDirectory("ingest-del").toFile();
        CatalogStore store = new CatalogStore(root);
        File inbox = store.inboxDir();
        inbox.mkdirs();
        Files.write(new File(inbox, "gone.mp4").toPath(), new byte[] { 1, 2, 3 });
        Title t = new Title();
        t.id = "gone";
        t.title = "Gone";
        t.status = DemoJobStatus.QUEUED;
        java.util.List<Title> list = new java.util.ArrayList<Title>();
        list.add(t);
        store.save(list);

        Title removed = new IngestService(store).delete("gone");
        assertEquals("gone", removed.id);
        assertFalse(new File(inbox, "gone.mp4").exists());
        assertTrue(store.cancelFile("gone").isFile());
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void deleteReadyDropsCancelAndDuplicateRows() throws Exception {
        File root = Files.createTempDirectory("ingest-rdy").toFile();
        CatalogStore store = new CatalogStore(root);
        File titles = new File(store.titlesDir(), "clip");
        titles.mkdirs();
        Files.write(new File(titles, "master.m3u8").toPath(), "#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        File done = new File(store.inboxDir(), "done");
        done.mkdirs();
        Files.write(new File(done, "clip.mp4").toPath(), new byte[] { 9 });
        Title t = new Title();
        t.id = "clip";
        t.title = "Clip";
        t.status = DemoJobStatus.READY;
        Title dup = new Title();
        dup.id = "clip-dup";
        dup.status = DemoJobStatus.DUPLICATE;
        dup.duplicateOf = "clip";
        java.util.List<Title> list = new java.util.ArrayList<Title>();
        list.add(t);
        list.add(dup);
        store.save(list);

        new IngestService(store).delete("clip");
        assertFalse(new File(done, "clip.mp4").exists());
        assertFalse(titles.exists());
        assertFalse(store.cancelFile("clip").exists());
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void deleteUnknownIsNotFound() throws Exception {
        File root = Files.createTempDirectory("ingest-404").toFile();
        try {
            new IngestService(new CatalogStore(root)).delete("nope");
            fail("expected NotFoundException");
        } catch (IngestService.NotFoundException expected) {
            assertTrue(expected.getMessage().contains("nope"));
        }
    }

    @Test
    public void deleteHouseAdInUseIsBlocked() throws Exception {
        File root = Files.createTempDirectory("ingest-409").toFile();
        CatalogStore store = new CatalogStore(root);
        Title ad = new Title();
        ad.id = "house";
        ad.status = DemoJobStatus.READY;
        Title show = new Title();
        show.id = "show";
        show.status = DemoJobStatus.READY;
        show.adUrl = "/media/titles/house/master.m3u8";
        java.util.List<Title> list = new java.util.ArrayList<Title>();
        list.add(ad);
        list.add(show);
        store.save(list);
        try {
            new IngestService(store).delete("house");
            fail("expected DeleteBlockedException");
        } catch (IngestService.DeleteBlockedException expected) {
            assertEquals(1, expected.usedBy.size());
            assertEquals("show", expected.usedBy.get(0));
        }
        assertEquals(2, store.load().size());
    }

    static byte[] multipart(String boundary, String filename, String ct, byte[] payload, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename)
                .append("\"\r\n");
        sb.append("Content-Type: ").append(ct).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        String mid = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + title
                + "\r\n--" + boundary + "--\r\n";
        byte[] tail = mid.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[head.length + payload.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        System.arraycopy(tail, 0, out, head.length + payload.length, tail.length);
        return out;
    }
}
