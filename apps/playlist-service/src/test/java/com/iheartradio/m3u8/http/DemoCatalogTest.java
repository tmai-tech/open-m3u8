package com.iheartradio.m3u8.http;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoCatalogTest {

    @Test
    public void displayTitleCleansGrokUuidFilename() {
        assertEquals("Grok clip", DemoCatalog.displayTitle(
                "grok-video-8e30b4bc-5d97-4e1e-ab64-129b5.mp4",
                "grok-video-8e30b4bc-5d97-4e1e-ab64-129b5"));
        assertEquals("Grok clip", DemoCatalog.displayTitle(
                "Grok-Video-8E30B4Bc-5D97-4E1E-Ab64-129B5.mp4", null));
        assertEquals("My Holiday", DemoCatalog.displayTitle("My Holiday.mp4", null));
        assertEquals("A real name", DemoCatalog.displayTitle(
                "grok-video-aaaa.mp4", "A real name"));
    }

    @Test
    public void jobStatusFromWire() {
        assertEquals(DemoJobStatus.QUEUED, DemoJobStatus.fromWire("queued"));
        assertEquals(DemoJobStatus.PACKAGING, DemoJobStatus.fromWire("PACKAGING"));
        assertEquals(DemoJobStatus.READY, DemoJobStatus.fromWire(null));
        assertEquals(DemoJobStatus.READY, DemoJobStatus.fromWire("nope"));
        assertEquals("failed", DemoJobStatus.FAILED.wire);
        assertEquals(DemoJobStatus.DUPLICATE, DemoJobStatus.fromWire("duplicate"));
    }

    @Test
    public void findOriginalMatchesHashOrSlug() {
        List<DemoCatalog.Title> titles = new ArrayList<DemoCatalog.Title>();
        DemoCatalog.Title a = new DemoCatalog.Title();
        a.id = "summer-on-mars";
        a.contentHash = "abc";
        a.status = DemoJobStatus.READY;
        titles.add(a);
        assertEquals("summer-on-mars", DemoCatalog.findOriginal(titles, "abc", "other").id);
        assertEquals("summer-on-mars", DemoCatalog.findOriginal(titles, "nope", "summer-on-mars").id);
        DemoCatalog.Title fail = new DemoCatalog.Title();
        fail.id = "clip";
        fail.contentHash = "dead";
        fail.status = DemoJobStatus.FAILED;
        titles.add(fail);
        assertEquals(null, DemoCatalog.findOriginal(titles, "dead", "clip"));
    }

    @Test
    public void slugStripsExtensionAndPunctuation() {
        assertEquals("giff-day-1", DemoCatalog.slug("GIFF Day 1.mp4"));
        assertEquals("title", DemoCatalog.slug("???"));
        assertEquals("my-holiday", DemoCatalog.slug("/tmp/My Holiday.MP4"));
    }

    @Test
    public void allocateIdIncrementsOnCollision() {
        List<DemoCatalog.Title> titles = new ArrayList<DemoCatalog.Title>();
        DemoCatalog.Title a = new DemoCatalog.Title();
        a.id = "clip";
        titles.add(a);
        assertEquals("clip-2", DemoCatalog.allocateId(titles, "clip"));
        assertEquals("other", DemoCatalog.allocateId(titles, "other"));
    }

    @Test
    public void roundTripJson() throws Exception {
        File root = Files.createTempDirectory("catalog-rt").toFile();
        DemoCatalog.Title t = new DemoCatalog.Title();
        t.id = "mars";
        t.title = "Summer on Mars";
        t.sub = "Local · 73s · 720p";
        t.url = "/media/titles/summer-on-mars/master.m3u8";
        t.status = DemoJobStatus.QUEUED;
        t.durationSec = 73.4f;
        List<DemoCatalog.Title> one = new ArrayList<DemoCatalog.Title>();
        one.add(t);
        DemoCatalog.save(root, one);
        List<DemoCatalog.Title> back = DemoCatalog.load(root);
        assertEquals(1, back.size());
        assertEquals("mars", back.get(0).id);
        assertEquals("Summer on Mars", back.get(0).title);
        assertEquals(DemoJobStatus.QUEUED, back.get(0).status);
        assertEquals(DemoCatalog.DEFAULT_AD_URL, back.get(0).adUrl);
        assertTrue(back.get(0).durationSec > 73f);
    }

    @Test
    public void updateIsLockedAndAppends() throws Exception {
        File root = Files.createTempDirectory("catalog-up").toFile();
        DemoCatalog.update(root, titles -> {
            DemoCatalog.Title t = new DemoCatalog.Title();
            t.id = "a";
            t.title = "A";
            t.url = "/media/titles/a/master.m3u8";
            titles.add(t);
        });
        DemoCatalog.update(root, titles -> {
            DemoCatalog.Title t = new DemoCatalog.Title();
            t.id = "b";
            t.title = "B";
            t.url = "/media/titles/b/master.m3u8";
            titles.add(t);
        });
        assertEquals(2, DemoCatalog.load(root).size());
    }

    @Test
    public void pointsAtTitleMatchesHouseAdUrl() {
        DemoCatalog.Title t = new DemoCatalog.Title();
        t.adUrl = "/media/titles/giff-day-1/master.m3u8";
        assertTrue(DemoCatalog.pointsAtTitle(t, "giff-day-1"));
        assertFalse(DemoCatalog.pointsAtTitle(t, "giff-day"));
        assertFalse(DemoCatalog.pointsAtTitle(t, "summer-on-mars"));
    }

    @Test
    public void allocateIdSkipsCancelFlag() throws Exception {
        File root = Files.createTempDirectory("catalog-cancel").toFile();
        new File(root, "inbox").mkdirs();
        Files.write(DemoCatalog.cancelFile(root, "clip").toPath(), new byte[0]);
        assertEquals("clip-2", DemoCatalog.allocateId(new ArrayList<DemoCatalog.Title>(), "clip", root));
        assertEquals("clip", DemoCatalog.allocateId(new ArrayList<DemoCatalog.Title>(), "clip"));
    }
}
