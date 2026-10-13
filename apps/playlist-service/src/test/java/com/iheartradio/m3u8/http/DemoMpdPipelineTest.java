package com.iheartradio.m3u8.http;

import com.iheartradio.dash.MpdIo;
import com.iheartradio.m3u8.http.DemoHttp.FetchResult;
import com.iheartradio.m3u8.http.DemoHttp.OriginFetch;
import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.MPD;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cave VOD MPD + subs-dash.vtt through the same SSAI stitch the session serves.
 */
public class DemoMpdPipelineTest {

    @Test
    public void parserKeepsCaveTextAdaptationSet() throws Exception {
        String xml = readCave("manifest.mpd");
        MPD mpd = new MpdIo().parse(xml);
        assertNotNull("text AdaptationSet dropped on parse", findText(mpd));
    }

    @Test
    public void ssaiStitchEmitsSlicedSubtitleTracks() throws Exception {
        final String caveMpd = readCave("manifest.mpd");
        final String caveVtt = readCave("subs-dash.vtt");
        final String adMpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
                + " mediaPresentationDuration=\"PT4S\" minBufferTime=\"PT2S\">"
                + "<Period id=\"ad\" duration=\"PT4S\">"
                + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
                + "<Representation id=\"v\" bandwidth=\"800000\">"
                + "<SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\""
                + " media=\"ad_$Number$.m4s\" startNumber=\"0\">"
                + "<SegmentTimeline><S t=\"0\" d=\"4000\"/></SegmentTimeline>"
                + "</SegmentTemplate></Representation></AdaptationSet>"
                + "</Period></MPD>";
        OriginFetch fetch = new OriginFetch() {
            @Override
            public FetchResult fetch(String url) {
                String body;
                if (url.endsWith("subs-dash.vtt")) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return new FetchResult(400, "text/plain", new byte[0]);
                    }
                    body = caveVtt;
                } else if (url.contains("/ad/")) {
                    body = adMpd;
                } else {
                    body = caveMpd;
                }
                return new FetchResult(200, "application/xml", body.getBytes(StandardCharsets.UTF_8));
            }
        };
        DemoSession session = DemoSession.fromJson("sid",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://127.0.0.1:8765/media/titles/the-cave-charles-ancelle/manifest.mpd\","
                        + "\"breaks\":[{\"id\":\"ssai-1\",\"offsetSec\":12,\"ads\":[{\"assetUri\":\"http://127.0.0.1:8765/media/titles/ad/master.m3u8\",\"durationSec\":4}]}]}");
        DemoMpdPipeline.Result result = new DemoMpdPipeline(fetch, new MpdIo())
                .process(session, session.contentUrl, "http://127.0.0.1:8765");
        String xml = new String(result.body, StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("text/vtt"));
        assertTrue(xml, xml.contains("/s/sid/text/c0.vtt"));
        assertTrue(xml, xml.contains("/s/sid/text/c1.vtt"));
        assertNotNull(session.dashText("c0.vtt"));
        assertTrue(session.dashText("c0.vtt").contains("WEBVTT"));
    }

    @Test
    public void sgaiLeavesOnePeriodAndPointsAtRelativeAltMpd() throws Exception {
        final String caveMpd = readCave("manifest.mpd");
        final String adMpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
                + " mediaPresentationDuration=\"PT4S\" minBufferTime=\"PT2S\">"
                + "<Period id=\"ad\" duration=\"PT4S\">"
                + "<BaseURL>/media/titles/ad/</BaseURL>"
                + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
                + "<Representation id=\"v\" bandwidth=\"800000\">"
                + "<SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\""
                + " media=\"ad_$Number$.m4s\" startNumber=\"0\">"
                + "<SegmentTimeline><S t=\"0\" d=\"4000\"/></SegmentTimeline>"
                + "</SegmentTemplate></Representation></AdaptationSet>"
                + "</Period></MPD>";
        DemoHttp.OriginFetch fetch = new DemoHttp.OriginFetch() {
            @Override
            public DemoHttp.FetchResult fetch(String url) {
                String body = url.contains("/ad/") ? adMpd : caveMpd;
                return new DemoHttp.FetchResult(200, "application/xml",
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        DemoSession session = DemoSession.fromJson("sid",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://127.0.0.1:8765/media/titles/the-cave-charles-ancelle/manifest.mpd\","
                        + "\"breaks\":[{\"id\":\"ssai-1\",\"offsetSec\":12,\"ads\":["
                        + "{\"assetUri\":\"http://127.0.0.1:8765/media/titles/ad/master.m3u8\",\"durationSec\":4},"
                        + "{\"assetUri\":\"http://127.0.0.1:8765/media/titles/ad/master.m3u8\",\"durationSec\":4}"
                        + "]}]}");
        DemoMpdPipeline.Result result = new DemoMpdPipeline(fetch, new com.iheartradio.dash.MpdIo())
                .process(session, session.contentUrl, "http://127.0.0.1:8765");
        String xml = new String(result.body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("urn:mpeg:dash:event:alternativeMPD:insert:2025"));
        assertTrue(xml, xml.contains("uri=\"/s/sid/alt/ssai-1.mpd\""));
        assertTrue(xml, xml.contains("presentationTime=\"12000\""));
        assertFalse(xml.contains("https://"));
        assertEquals(1, countPeriods(xml));
        String alt = session.dashAlt("ssai-1.mpd");
        assertNotNull(alt);
        assertEquals(2, countPeriods(alt));
        assertTrue(alt.contains("/media/titles/ad/"));
    }

    @Test
    public void unavailableDashAdIsDiscardedAndGoodAdStays() throws Exception {
        final String content = mpd("c", "PT20S", "c_$Number$.m4s", "10000", "1");
        final String good = mpd("ad", "PT4S", "good_$Number$.m4s", "4000", null);
        OriginFetch fetch = new OriginFetch() {
            @Override
            public FetchResult fetch(String url) {
                String body = url.contains("/good/manifest.mpd") ? good
                        : url.endsWith("/content/manifest.mpd") ? content : null;
                if (body == null) {
                    return new FetchResult(404, "text/plain", new byte[0]);
                }
                return new FetchResult(200, "application/xml",
                        body.getBytes(StandardCharsets.UTF_8));
            }
        };
        String breaks = "\"breaks\":["
                + "{\"id\":\"break-1\",\"offsetSec\":10,\"ads\":["
                + "{\"assetUri\":\"http://ads/good/master.m3u8\",\"durationSec\":4},"
                + "{\"assetUri\":\"http://ads/bad/master.m3u8\",\"durationSec\":4}]},"
                + "{\"id\":\"break-2\",\"offsetSec\":0,\"ads\":["
                + "{\"assetUri\":\"http://ads/gone/master.m3u8\",\"durationSec\":4}]}"
                + "]}";
        DemoSession ssai = DemoSession.fromJson("sid-dash",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/content/manifest.mpd\"," + breaks + "}");
        String xml = new String(new DemoMpdPipeline(fetch, new MpdIo())
                .process(ssai, ssai.contentUrl, "http://127.0.0.1:8765").body, StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("good_$Number$.m4s"));
        assertFalse(xml.contains("bad"));
        assertFalse(xml.contains("gone"));
        assertEquals(3, countPeriods(xml));
        assertTrue(ssai.isSkippedAd("http://ads/bad/master.m3u8"));
        assertTrue(ssai.isSkippedAd("http://ads/gone/master.m3u8"));
        assertFalse(ssai.isSkippedAd("http://ads/good/master.m3u8"));
        assertEquals(2, ssai.getWarnings().size());
        assertEquals("Break break-1 at 10s discarded\n"
                        + "  http://ads/bad/master.m3u8\n"
                        + "  reason: no MPD/CMAF",
                ssai.getWarnings().get(0));
        assertEquals("Break break-2 at 0s discarded\n"
                        + "  http://ads/gone/master.m3u8\n"
                        + "  reason: no MPD/CMAF",
                ssai.getWarnings().get(1));

        DemoSession sgai = DemoSession.fromJson("sid-dash-sgai",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/content/manifest.mpd\"," + breaks + "}");
        DemoMpdPipeline.Result sgaiResult = new DemoMpdPipeline(fetch, new MpdIo())
                .process(sgai, sgai.contentUrl, "http://127.0.0.1:8765");
        String sgaiXml = new String(sgaiResult.body, StandardCharsets.UTF_8);
        assertEquals(1, countPeriods(sgaiXml));
        assertTrue(sgaiXml.contains("uri=\"/s/sid-dash-sgai/alt/break-1.mpd\""));
        assertFalse(sgaiXml.contains("break-2.mpd"));
        String alt = sgai.dashAlt("break-1.mpd");
        assertNotNull(alt);
        assertEquals(1, countPeriods(alt));
        assertTrue(alt.contains("good_$Number$.m4s"));
        assertEquals("Break break-1 at 10s discarded\n"
                        + "  http://ads/bad/master.m3u8\n"
                        + "  reason: no MPD/CMAF",
                sgai.getWarnings().get(0));
    }

    private static String mpd(String id, String dur, String media, String d, String r) {
        String repeat = r == null ? "" : " r=\"" + r + "\"";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
                + " mediaPresentationDuration=\"" + dur + "\" minBufferTime=\"PT2S\">"
                + "<Period id=\"" + id + "\" duration=\"" + dur + "\">"
                + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
                + "<Representation id=\"v\" bandwidth=\"800000\">"
                + "<SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\""
                + " media=\"" + media + "\" startNumber=\"0\">"
                + "<SegmentTimeline><S t=\"0\" d=\"" + d + "\"" + repeat + "/></SegmentTimeline>"
                + "</SegmentTemplate></Representation></AdaptationSet>"
                + "</Period></MPD>";
    }

    private static int countPeriods(String xml) {
        int n = 0;
        int i = 0;
        while ((i = xml.indexOf("<Period", i)) >= 0) {
            n++;
            i += 7;
        }
        return n;
    }

    private static AdaptationSet findText(MPD mpd) {
        if (mpd.getPeriods() == null || mpd.getPeriods().isEmpty()) {
            return null;
        }
        List<AdaptationSet> sets = mpd.getPeriods().get(0).getAdaptationSets();
        if (sets == null) {
            return null;
        }
        for (AdaptationSet as : sets) {
            if ("text".equals(as.getContentType())
                    || (as.getMimeType() != null && as.getMimeType().contains("vtt"))) {
                return as;
            }
        }
        return null;
    }

    private static String readCave(String name) throws Exception {
        String resource = "/fixtures/the-cave-charles-ancelle/" + name;
        try (InputStream in = DemoMpdPipelineTest.class.getResourceAsStream(resource)) {
            assertNotNull("missing " + resource, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
