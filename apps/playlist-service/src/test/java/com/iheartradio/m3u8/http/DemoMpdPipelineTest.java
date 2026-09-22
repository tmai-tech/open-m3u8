package com.iheartradio.m3u8.http;

import com.iheartradio.dash.MpdIo;
import com.iheartradio.m3u8.http.DemoHttp.FetchResult;
import com.iheartradio.m3u8.http.DemoHttp.OriginFetch;
import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.MPD;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
        Path path = Paths.get("media/titles/the-cave-charles-ancelle", name);
        if (!Files.isRegularFile(path)) {
            path = Paths.get("../../media/titles/the-cave-charles-ancelle", name);
        }
        assertTrue("missing " + path.toAbsolutePath(), Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
