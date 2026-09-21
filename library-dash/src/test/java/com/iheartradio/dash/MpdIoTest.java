package com.iheartradio.dash;

import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import io.lindstrom.mpd.data.PresentationType;
import io.lindstrom.mpd.data.Representation;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MpdIoTest {

    private static final String MINIMAL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
            + " profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\""
            + " type=\"static\" mediaPresentationDuration=\"PT10S\" minBufferTime=\"PT1S\">\n"
            + "  <Period id=\"p0\" duration=\"PT10S\">\n"
            + "    <AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">\n"
            + "      <Representation id=\"v1\" bandwidth=\"100000\" codecs=\"avc1.42c01e\">\n"
            + "        <BaseURL>video.mp4</BaseURL>\n"
            + "      </Representation>\n"
            + "    </AdaptationSet>\n"
            + "  </Period>\n"
            + "</MPD>\n";

    @Test
    public void parseAndWriteRoundTrip() throws Exception {
        MpdIo io = new MpdIo();
        MPD mpd = io.parse(MINIMAL);
        assertNotNull(mpd);
        assertEquals(1, mpd.getPeriods().size());
        Period p0 = mpd.getPeriods().get(0);
        assertEquals("p0", p0.getId());

        String xml = io.write(mpd);
        assertTrue(xml.contains("Period"));
        assertTrue(xml.contains("p0"));
        assertTrue(xml.contains("video.mp4") || xml.contains("BaseURL"));

        MPD again = io.parse(xml);
        assertEquals("p0", again.getPeriods().get(0).getId());
    }

    @Test
    public void parseFfmpegGrokManifest() throws Exception {
        String xml = readResource("/grok-ffmpeg.mpd");
        MpdIo io = new MpdIo();
        MPD mpd = io.parse(xml);
        assertNotNull(mpd);
        assertEquals(PresentationType.STATIC, mpd.getType());
        assertEquals(1, mpd.getPeriods().size());

        Period period = mpd.getPeriods().get(0);
        List<AdaptationSet> sets = period.getAdaptationSets();
        assertEquals(2, sets.size());

        AdaptationSet video = null;
        AdaptationSet audio = null;
        for (AdaptationSet set : sets) {
            if ("video".equals(set.getContentType())) {
                video = set;
            } else if ("audio".equals(set.getContentType())) {
                audio = set;
            }
        }
        assertNotNull(video);
        assertNotNull(audio);
        assertEquals(2, video.getRepresentations().size());
        assertEquals(1, audio.getRepresentations().size());

        Representation r0 = video.getRepresentations().get(0);
        Representation r1 = video.getRepresentations().get(1);
        assertNotNull(r0.getSegmentTemplate());
        assertNotNull(r1.getSegmentTemplate());
        assertTrue(r0.getSegmentTemplate().getMedia().contains("$Number"));
        assertTrue(r0.getSegmentTemplate().getInitialization().contains("$RepresentationID$")
                || r0.getSegmentTemplate().getInitialization().contains("init-"));

        String out = io.write(mpd);
        MPD again = io.parse(out);
        assertEquals(mpd.getPeriods().get(0).getId(), again.getPeriods().get(0).getId());
        assertEquals(2, videoRepCount(again));
        AdaptationSet videoAgain = null;
        for (AdaptationSet set : again.getPeriods().get(0).getAdaptationSets()) {
            if ("video".equals(set.getContentType())) {
                videoAgain = set;
                break;
            }
        }
        assertNotNull(videoAgain);
        assertEquals("0", videoAgain.getRepresentations().get(0).getId());
        assertEquals("1", videoAgain.getRepresentations().get(1).getId());
    }

    private static int videoRepCount(MPD mpd) {
        int n = 0;
        for (AdaptationSet set : mpd.getPeriods().get(0).getAdaptationSets()) {
            if ("video".equals(set.getContentType())) {
                n += set.getRepresentations().size();
            }
        }
        return n;
    }

    private static String readResource(String path) throws Exception {
        InputStream in = MpdIoTest.class.getResourceAsStream(path);
        assertNotNull("missing " + path, in);
        try {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            assertTrue(n > 0);
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
