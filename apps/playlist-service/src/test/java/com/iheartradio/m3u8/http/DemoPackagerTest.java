package com.iheartradio.m3u8.http;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoPackagerTest {

    @Test
    public void tickSkipsCancelledInboxWithoutResurrecting() throws Exception {
        File root = Files.createTempDirectory("pkg-cancel").toFile();
        File inbox = new File(root, "inbox");
        inbox.mkdirs();
        Files.write(new File(inbox, "gone.mp4").toPath(), new byte[] { 1, 2, 3 });
        Files.write(DemoCatalog.cancelFile(root, "gone").toPath(), new byte[0]);
        new DemoPackager(root, "ffmpeg").tick();
        assertFalse(new File(inbox, "gone.mp4").exists());
        assertFalse(DemoCatalog.cancelFile(root, "gone").exists());
        assertTrue(DemoCatalog.load(root).isEmpty());
    }

    @Test
    public void tickSkipsOrphanInboxWithoutCatalogRow() throws Exception {
        File root = Files.createTempDirectory("pkg-orphan").toFile();
        File inbox = new File(root, "inbox");
        inbox.mkdirs();
        Files.write(new File(inbox, "orphan.mp4").toPath(), new byte[] { 1 });
        new DemoPackager(root, "ffmpeg").tick();
        assertFalse(new File(inbox, "orphan.mp4").exists());
        assertTrue(DemoCatalog.load(root).isEmpty());
    }

    @Test
    public void tickSweepsOrphanCancelWhenIdle() throws Exception {
        File root = Files.createTempDirectory("pkg-sweep").toFile();
        File inbox = new File(root, "inbox");
        inbox.mkdirs();
        Files.write(DemoCatalog.cancelFile(root, "stale").toPath(), new byte[0]);
        new DemoPackager(root, "ffmpeg").tick();
        assertFalse(DemoCatalog.cancelFile(root, "stale").exists());
    }
}
