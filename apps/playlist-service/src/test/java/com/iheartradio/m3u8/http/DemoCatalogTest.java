package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import com.iheartradio.m3u8.http.catalog.TitleNames;
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
        assertEquals("Grok clip", TitleNames.displayTitle(
                "grok-video-8e30b4bc-5d97-4e1e-ab64-129b5.mp4",
                "grok-video-8e30b4bc-5d97-4e1e-ab64-129b5"));
        assertEquals("Grok clip", TitleNames.displayTitle(
                "Grok-Video-8E30B4Bc-5D97-4E1E-Ab64-129B5.mp4", null));
        assertEquals("My Holiday", TitleNames.displayTitle("My Holiday.mp4", null));
        assertEquals("A real name", TitleNames.displayTitle(
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
        List<Title> titles = new ArrayList<Title>();
        Title a = new Title();
        a.id = "summer-on-mars";
        a.contentHash = "abc";
        a.status = DemoJobStatus.READY;
        titles.add(a);
        assertEquals("summer-on-mars", CatalogStore.findOriginal(titles, "abc", "other").id);
        assertEquals("summer-on-mars", CatalogStore.findOriginal(titles, "nope", "summer-on-mars").id);
        Title fail = new Title();
        fail.id = "clip";
        fail.contentHash = "dead";
        fail.status = DemoJobStatus.FAILED;
        titles.add(fail);
        assertEquals(null, CatalogStore.findOriginal(titles, "dead", "clip"));
    }

    @Test
    public void slugStripsExtensionAndPunctuation() {
        assertEquals("giff-day-1", TitleNames.slug("GIFF Day 1.mp4"));
        assertEquals("title", TitleNames.slug("???"));
        assertEquals("my-holiday", TitleNames.slug("/tmp/My Holiday.MP4"));
    }

    @Test
    public void allocateIdIncrementsOnCollision() throws Exception {
        File root = Files.createTempDirectory("catalog-alloc").toFile();
        CatalogStore store = new CatalogStore(root);
        List<Title> titles = new ArrayList<Title>();
        Title a = new Title();
        a.id = "clip";
        titles.add(a);
        assertEquals("clip-2", store.allocateId(titles, "clip"));
        assertEquals("other", store.allocateId(titles, "other"));
    }

    @Test
    public void roundTripJson() throws Exception {
        File root = Files.createTempDirectory("catalog-rt").toFile();
        CatalogStore store = new CatalogStore(root);
        Title t = new Title();
        t.id = "mars";
        t.title = "Summer on Mars";
        t.sub = "Local · 73s · 720p";
        t.summary = "A red rover on rust soil.";
        t.url = "/media/titles/summer-on-mars/master.m3u8";
        t.status = DemoJobStatus.QUEUED;
        t.durationSec = 73.4f;
        List<Title> one = new ArrayList<Title>();
        one.add(t);
        store.save(one);
        List<Title> back = store.load();
        assertEquals(1, back.size());
        assertEquals("mars", back.get(0).id);
        assertEquals("Summer on Mars", back.get(0).title);
        assertEquals(DemoJobStatus.QUEUED, back.get(0).status);
        assertEquals(Title.DEFAULT_AD_URL, back.get(0).adUrl);
        assertEquals("A red rover on rust soil.", back.get(0).summary);
        assertTrue(back.get(0).durationSec > 73f);
    }

    @Test
    public void normalizeAssignsJobIdAndRoundTrips() throws Exception {
        File root = Files.createTempDirectory("catalog-jobid").toFile();
        CatalogStore store = new CatalogStore(root);
        Title t = new Title();
        t.id = "clip";
        t.title = "Clip";
        java.util.List<Title> one = new java.util.ArrayList<Title>();
        one.add(t);
        store.save(one);
        java.util.List<Title> assigned = store.loadOrDiscover();
        assertEquals(1, assigned.size());
        assertTrue(assigned.get(0).jobId != null && assigned.get(0).jobId.length() > 8);
        String jobId = assigned.get(0).jobId;
        assertEquals(jobId, store.load().get(0).jobId);
    }

    @Test
    public void normalizeRepairsBrokenMiddleDot() throws Exception {
        File root = Files.createTempDirectory("catalog-dot").toFile();
        CatalogStore store = new CatalogStore(root);
        Title t = new Title();
        t.id = "clip";
        t.title = "Clip";
        t.sub = "Local u00b7 12s u00b7 720p";
        java.util.List<Title> one = new java.util.ArrayList<Title>();
        one.add(t);
        store.save(one);
        assertEquals("Local \u00b7 12s \u00b7 720p", store.loadOrDiscover().get(0).sub);
    }

    @Test
    public void updateIsLockedAndAppends() throws Exception {
        File root = Files.createTempDirectory("catalog-up").toFile();
        CatalogStore store = new CatalogStore(root);
        store.update(titles -> {
            Title t = new Title();
            t.id = "a";
            t.title = "A";
            t.url = "/media/titles/a/master.m3u8";
            titles.add(t);
        });
        store.update(titles -> {
            Title t = new Title();
            t.id = "b";
            t.title = "B";
            t.url = "/media/titles/b/master.m3u8";
            titles.add(t);
        });
        assertEquals(2, store.load().size());
    }

    @Test
    public void pointsAtTitleMatchesHouseAdUrl() {
        Title t = new Title();
        t.adUrl = "/media/titles/giff-day-1/master.m3u8";
        assertTrue(t.pointsAt("giff-day-1"));
        assertFalse(t.pointsAt("giff-day"));
        assertFalse(t.pointsAt("summer-on-mars"));
    }

    @Test
    public void allocateIdSkipsCancelFlag() throws Exception {
        File root = Files.createTempDirectory("catalog-cancel").toFile();
        CatalogStore store = new CatalogStore(root);
        store.inboxDir().mkdirs();
        Files.write(store.cancelFile("clip").toPath(), new byte[0]);
        assertEquals("clip-2", store.allocateId(new ArrayList<Title>(), "clip"));
        File other = Files.createTempDirectory("catalog-nocancel").toFile();
        assertEquals("clip", new CatalogStore(other).allocateId(new ArrayList<Title>(), "clip"));
    }
}
