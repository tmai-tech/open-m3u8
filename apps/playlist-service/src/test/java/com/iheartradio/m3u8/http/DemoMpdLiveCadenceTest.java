package com.iheartradio.m3u8.http;

import com.iheartradio.dash.MpdIo;
import com.iheartradio.m3u8.http.DemoHttp.FetchResult;
import com.iheartradio.m3u8.http.DemoHttp.OriginFetch;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Dynamic MPD refreshes keep one frozen replace event and the origin segment clock.
 */
public class DemoMpdLiveCadenceTest {

    private static final String AD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
            + " mediaPresentationDuration=\"PT15S\" minBufferTime=\"PT2S\">"
            + "<Period id=\"ad\" duration=\"PT15S\">"
            + "<BaseURL>/media/titles/grok/</BaseURL>"
            + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
            + "<SegmentTemplate timescale=\"1000\" duration=\"2000\" startNumber=\"1\""
            + " initialization=\"init.m4s\" media=\"ad_$Number$.m4s\"/>"
            + "<Representation id=\"v\" bandwidth=\"300000\"/>"
            + "</AdaptationSet></Period></MPD>";

    @Test
    public void periodRefreshesKeepTheSameReplaceUntilTheMinuteRolls() throws Exception {
        final AtomicReference<String> origin = new AtomicReference<String>(periods(60, 120));
        final AtomicLong now = new AtomicLong(86_000L);
        DemoMpdPipeline pipeline = pipeline(origin, now);
        DemoSession session = session();

        String first = manifest(pipeline, session);
        String second = manifest(pipeline, session);
        assertEquals(eventAt(first), eventAt(second));
        assertTrue(first, first.contains("id=\"live-60\""));
        assertTrue(first, first.contains("presentationTime=\"23000\""));
        assertTrue(first, first.contains("urn:mpeg:dash:event:alternativeMPD:replace:2025"));
        assertFalse(first, first.contains("returnOffset"));
        assertFalse(first, first.contains("alternativeMPD:insert"));
        assertTrue(first, first.contains("<BaseURL>http://cdn/</BaseURL>"));
        assertFalse(first, first.contains("<BaseURL>/live"));
        assertTrue(first, first.contains("startNumber=\"30\""));
        assertTrue(first, first.contains("presentationTimeOffset=\"60\""));
        assertTrue(first, first.contains("startNumber=\"60\""));
        assertEquals(1, count(first, "ReplacePresentation"));
        assertTrue(first.contains("<Period id=\"P1\""));
        int p1 = first.indexOf("<Period id=\"P1\"");
        int p2 = first.indexOf("<Period id=\"P2\"");
        assertTrue(first.indexOf("ReplacePresentation") > p1);
        assertTrue(first.indexOf("ReplacePresentation") < p2);
        assertNotNull(session.dashAlt("live-60.mpd"));
        assertEquals(session.dashAlt("live-60.mpd"), session.dashAlt("live-60.mpd"));

        String alt = session.dashAlt("live-60.mpd");
        now.set(90_000L);
        String later = manifest(pipeline, session);
        assertEquals(eventAt(first), eventAt(later));
        assertEquals(alt, session.dashAlt("live-60.mpd"));
        assertTrue(later.contains("startNumber=\"60\""));
        assertTrue(later.contains("presentationTimeOffset=\"120\""));

        now.set(184_000L);
        origin.set(periods(120, 180));
        String rolled = manifest(pipeline, session);
        assertTrue(rolled, rolled.contains("id=\"live-180\""));
        assertTrue(rolled, rolled.contains("presentationTime=\"1000\""));
        assertFalse(rolled.contains("id=\"live-60\""));
        int q2 = rolled.indexOf("<Period id=\"P2\"");
        assertTrue(rolled.indexOf("ReplacePresentation") > q2);
        assertTrue(rolled.contains("startNumber=\"60\""));
        assertTrue(rolled.contains("presentationTimeOffset=\"120\""));
        assertTrue(rolled.contains("startNumber=\"90\""));
        assertTrue(rolled.contains("presentationTimeOffset=\"180\""));
        assertNotNull(session.dashAlt("live-180.mpd"));
    }

    @Test
    public void timelineRefreshesFreezeTheEventWhileTheWindowSlides() throws Exception {
        final AtomicReference<String> origin = new AtomicReference<String>(timeline(100));
        DemoMpdPipeline pipeline = pipeline(origin, new AtomicLong(0L));
        DemoSession session = session();
        String first = manifest(pipeline, session);
        origin.set(timeline(106));
        String second = manifest(pipeline, session);
        assertEquals(eventAt(first), eventAt(second));
        assertTrue(second.contains("t=\"106\""));
        assertTrue(second.contains("id=\"live-120\""));
        assertTrue(second.contains("presentationTime=\"119000\""));
        assertTrue(second.contains("presentationTimeOffset=\"0\""));
        assertEquals(1, count(second, "<Period"));
    }

    private static DemoSession session() {
        return DemoSession.fromJson("sid",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.mpd\","
                        + "\"adUrl\":\"http://ads/grok/master.m3u8\","
                        + "\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
    }

    private static DemoMpdPipeline pipeline(final AtomicReference<String> origin, final AtomicLong now) {
        OriginFetch fetch = new OriginFetch() {
            @Override
            public FetchResult fetch(String url) {
                String body = url.contains("/grok/") ? AD : origin.get();
                return new FetchResult(200, "application/xml", body.getBytes(StandardCharsets.UTF_8));
            }
        };
        return new DemoMpdPipeline(fetch, new MpdIo(), new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return now.get();
            }
        });
    }

    private static String manifest(DemoMpdPipeline pipeline, DemoSession session) throws Exception {
        return new String(pipeline.process(session, session.contentUrl, "http://127.0.0.1:8765").body,
                StandardCharsets.UTF_8);
    }

    /** Two minute-long periods. startNumber = start/2, presentationTimeOffset = start. */
    private static String periods(int firstStart, int secondStart) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"dynamic\""
                + " availabilityStartTime=\"1970-01-01T00:00:00Z\""
                + " minBufferTime=\"PT2S\" timeShiftBufferDepth=\"PT1M\" minimumUpdatePeriod=\"PT2S\">"
                + period("P1", firstStart) + period("P2", secondStart)
                + "</MPD>";
    }

    private static String period(String id, int start) {
        return "<Period id=\"" + id + "\" start=\"PT" + start + "S\">"
                + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
                + "<SegmentTemplate media=\"V300/$Number$.m4s\" initialization=\"V300/init.mp4\""
                + " duration=\"2\" startNumber=\"" + (start / 2) + "\""
                + " presentationTimeOffset=\"" + start + "\"/>"
                + "<Representation id=\"V300\" bandwidth=\"300000\"/>"
                + "</AdaptationSet></Period>";
    }

    private static String timeline(int t) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"dynamic\""
                + " availabilityStartTime=\"1970-01-01T00:00:00Z\""
                + " minBufferTime=\"PT2S\" timeShiftBufferDepth=\"PT1M\">"
                + "<Period id=\"P0\" start=\"PT0S\">"
                + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
                + "<SegmentTemplate timescale=\"1\" presentationTimeOffset=\"0\""
                + " media=\"V300/$Time$.m4s\" initialization=\"V300/init.mp4\">"
                + "<SegmentTimeline><S t=\"" + t + "\" d=\"2\" r=\"10\"/></SegmentTimeline>"
                + "</SegmentTemplate>"
                + "<Representation id=\"V300\" bandwidth=\"300000\"/>"
                + "</AdaptationSet></Period></MPD>";
    }

    private static String eventAt(String xml) {
        int i = xml.indexOf("<Event ");
        int j = xml.indexOf("</Event>");
        assertTrue(xml, i >= 0 && j > i);
        return xml.substring(i, j);
    }

    private static int count(String xml, String token) {
        int n = 0;
        int i = 0;
        while ((i = xml.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }
}
