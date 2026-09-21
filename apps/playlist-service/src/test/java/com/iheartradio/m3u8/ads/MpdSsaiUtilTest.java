package com.iheartradio.m3u8.ads;

import com.iheartradio.dash.MpdIo;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpdSsaiUtilTest {

    private static final String CONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
            + " profiles=\"urn:mpeg:dash:profile:isoff-live:2011\""
            + " type=\"static\" mediaPresentationDuration=\"PT16S\" minBufferTime=\"PT2S\">\n"
            + "  <Period id=\"p0\" duration=\"PT16S\">\n"
            + "    <AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\">\n"
            + "      <Representation id=\"v720\" bandwidth=\"1000000\" width=\"1280\" height=\"720\">\n"
            + "        <SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\"\n"
            + "                         media=\"v720_$Number$.m4s\" startNumber=\"0\">\n"
            + "          <SegmentTimeline>\n"
            + "            <S t=\"0\" d=\"4000\" r=\"3\"/>\n"
            + "          </SegmentTimeline>\n"
            + "        </SegmentTemplate>\n"
            + "      </Representation>\n"
            + "    </AdaptationSet>\n"
            + "  </Period>\n"
            + "</MPD>\n";

    private static final String AD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
            + " profiles=\"urn:mpeg:dash:profile:isoff-live:2011\""
            + " type=\"static\" mediaPresentationDuration=\"PT8S\" minBufferTime=\"PT2S\">\n"
            + "  <Period id=\"ad\" duration=\"PT8S\">\n"
            + "    <AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\">\n"
            + "      <Representation id=\"v\" bandwidth=\"800000\">\n"
            + "        <SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\"\n"
            + "                         media=\"ad_$Number$.m4s\" startNumber=\"0\">\n"
            + "          <SegmentTimeline>\n"
            + "            <S t=\"0\" d=\"4000\" r=\"1\"/>\n"
            + "          </SegmentTimeline>\n"
            + "        </SegmentTemplate>\n"
            + "      </Representation>\n"
            + "    </AdaptationSet>\n"
            + "    <AdaptationSet id=\"1\" contentType=\"text\" mimeType=\"text/vtt\" lang=\"en\">\n"
            + "      <Representation id=\"subs\" bandwidth=\"256\">\n"
            + "        <BaseURL>subs-dash.vtt</BaseURL>\n"
            + "      </Representation>\n"
            + "    </AdaptationSet>\n"
            + "  </Period>\n"
            + "</MPD>\n";

    @Test
    public void identityWhenNoBreaks() throws Exception {
        MpdIo io = new MpdIo();
        MPD content = io.parse(CONTENT);
        MPD out = MpdSsaiUtil.stitch(content, new ArrayList<MpdSsaiUtil.AdBreak>());
        assertEquals(1, out.getPeriods().size());
        assertEquals("p0", out.getPeriods().get(0).getId());
    }

    @Test
    public void midRollSplitsContentAndInsertsAdPeriod() throws Exception {
        MpdIo io = new MpdIo();
        MPD content = io.parse(CONTENT);
        Period ad = io.parse(AD).getPeriods().get(0);
        List<MpdSsaiUtil.AdBreak> breaks = new ArrayList<MpdSsaiUtil.AdBreak>();
        breaks.add(new MpdSsaiUtil.AdBreak("ad-1", 8f, ad));
        MPD out = MpdSsaiUtil.stitch(content, breaks);
        assertEquals(3, out.getPeriods().size());
        assertEquals("c0", out.getPeriods().get(0).getId());
        assertEquals("ad-1", out.getPeriods().get(1).getId());
        assertEquals("c1", out.getPeriods().get(2).getId());
        assertEquals(8_000L, out.getPeriods().get(0).getDuration().toMillis());
        assertEquals(8_000L, out.getPeriods().get(1).getDuration().toMillis());
        assertEquals(8_000L, out.getPeriods().get(2).getDuration().toMillis());
        assertEquals(16_000L + 8_000L, out.getMediaPresentationDuration().toMillis());
        String xml = io.write(out);
        assertTrue(xml.contains("startNumber=\"0\""));
        assertTrue(xml.contains("startNumber=\"2\""));
        assertFalse(xml.contains("text/vtt"));
    }

    @Test
    public void twoAdsAtSameOffsetAreBackToBackPeriods() throws Exception {
        MpdIo io = new MpdIo();
        MPD content = io.parse(CONTENT);
        Period ad = io.parse(AD).getPeriods().get(0);
        List<MpdSsaiUtil.AdBreak> breaks = new ArrayList<MpdSsaiUtil.AdBreak>();
        breaks.add(new MpdSsaiUtil.AdBreak("ad-a", 12f, ad));
        breaks.add(new MpdSsaiUtil.AdBreak("ad-b", 12f, ad));
        MPD out = MpdSsaiUtil.stitch(content, breaks);
        assertEquals(4, out.getPeriods().size());
        assertEquals("c0", out.getPeriods().get(0).getId());
        assertEquals("ad-a", out.getPeriods().get(1).getId());
        assertEquals("ad-b", out.getPeriods().get(2).getId());
        assertEquals("c1", out.getPeriods().get(3).getId());
        assertEquals(12_000L, out.getPeriods().get(0).getDuration().toMillis());
        assertEquals(4_000L, out.getPeriods().get(3).getDuration().toMillis());
    }
}
