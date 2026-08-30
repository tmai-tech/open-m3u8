package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.http.catalog.CatalogStore;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoPackagerTest {

    @Test
    public void tickSkipsCancelledInboxWithoutResurrecting() throws Exception {
        File root = Files.createTempDirectory("pkg-cancel").toFile();
        CatalogStore store = new CatalogStore(root);
        File inbox = store.inboxDir();
        inbox.mkdirs();
        Files.write(new File(inbox, "gone.mp4").toPath(), new byte[] { 1, 2, 3 });
        Files.write(store.cancelFile("gone").toPath(), new byte[0]);
        new DemoPackager(root, "ffmpeg").tick();
        assertFalse(new File(inbox, "gone.mp4").exists());
        assertFalse(store.cancelFile("gone").exists());
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void tickSkipsOrphanInboxWithoutCatalogRow() throws Exception {
        File root = Files.createTempDirectory("pkg-orphan").toFile();
        CatalogStore store = new CatalogStore(root);
        File inbox = store.inboxDir();
        inbox.mkdirs();
        Files.write(new File(inbox, "orphan.mp4").toPath(), new byte[] { 1 });
        new DemoPackager(root, "ffmpeg").tick();
        assertFalse(new File(inbox, "orphan.mp4").exists());
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void tickSweepsOrphanCancelWhenIdle() throws Exception {
        File root = Files.createTempDirectory("pkg-sweep").toFile();
        CatalogStore store = new CatalogStore(root);
        store.inboxDir().mkdirs();
        Files.write(store.cancelFile("stale").toPath(), new byte[0]);
        new DemoPackager(root, "ffmpeg").tick();
        assertFalse(store.cancelFile("stale").exists());
    }
}
