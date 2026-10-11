package com.iheartradio.m3u8.ads;

import com.iheartradio.dash.MpdIo;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import io.lindstrom.mpd.data.Representation;
import io.lindstrom.mpd.data.Segment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        assertEquals(0L, out.getPeriods().get(0).getStart().toMillis());
        assertEquals(8_000L, out.getPeriods().get(1).getStart().toMillis());
        assertEquals(16_000L, out.getPeriods().get(2).getStart().toMillis());
        assertEquals(16_000L + 8_000L, out.getMediaPresentationDuration().toMillis());
        String xml = io.write(out);
        assertTrue(xml.contains("startNumber=\"0\""));
        assertTrue(xml.contains("startNumber=\"2\""));
        assertFalse(xml.contains("text/vtt"));
        assertNull(out.getPeriods().get(0).getAdaptationSets().get(0)
                .getRepresentations().get(0).getSegmentTemplate().getPresentationTimeOffset());
        Long pto = out.getPeriods().get(2).getAdaptationSets().get(0)
                .getRepresentations().get(0).getSegmentTemplate().getPresentationTimeOffset();
        assertEquals(Long.valueOf(8000L), pto);
        assertEquals(Long.valueOf(8000L), out.getPeriods().get(2).getAdaptationSets().get(0)
                .getRepresentations().get(0).getSegmentTemplate().getSegmentTimeline().get(0).getT());
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
        assertEquals(12_000L + 8_000L + 8_000L, out.getPeriods().get(3).getStart().toMillis());
        Long pto = out.getPeriods().get(3).getAdaptationSets().get(0)
                .getRepresentations().get(0).getSegmentTemplate().getPresentationTimeOffset();
        assertEquals(Long.valueOf(12_000L), pto);
        assertEquals(Long.valueOf(12_000L), out.getPeriods().get(3).getAdaptationSets().get(0)
                .getRepresentations().get(0).getSegmentTemplate().getSegmentTimeline().get(0).getT());
    }

    /** A 72s ad clipped to a 9s remainder keeps two whole 4s segments, on every rendition. */
    @Test
    public void clipPeriodKeepsWholeSegmentsThatFitTheSlot() throws Exception {
        String longAd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
                + " profiles=\"urn:mpeg:dash:profile:isoff-live:2011\""
                + " type=\"static\" mediaPresentationDuration=\"PT72S\" minBufferTime=\"PT2S\">\n"
                + "  <Period id=\"ad\" duration=\"PT72S\">\n"
                + "    <AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\">\n"
                + "      <Representation id=\"v360\" bandwidth=\"600000\" width=\"640\" height=\"360\">\n"
                + "        <SegmentTemplate timescale=\"1000\" initialization=\"init360.m4s\"\n"
                + "                         media=\"v360_$Number$.m4s\" startNumber=\"0\">\n"
                + "          <SegmentTimeline><S t=\"0\" d=\"4000\" r=\"17\"/></SegmentTimeline>\n"
                + "        </SegmentTemplate>\n"
                + "      </Representation>\n"
                + "      <Representation id=\"v720\" bandwidth=\"1700000\" width=\"1280\" height=\"720\">\n"
                + "        <SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\"\n"
                + "                         media=\"v720_$Number$.m4s\" startNumber=\"0\">\n"
                + "          <SegmentTimeline><S t=\"0\" d=\"4000\" r=\"17\"/></SegmentTimeline>\n"
                + "        </SegmentTemplate>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "    <AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"audio/mp4\">\n"
                + "      <Representation id=\"a\" bandwidth=\"128000\">\n"
                + "        <SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\"\n"
                + "                         media=\"a_$Number$.m4s\" startNumber=\"0\">\n"
                + "          <SegmentTimeline><S t=\"0\" d=\"4000\" r=\"17\"/></SegmentTimeline>\n"
                + "        </SegmentTemplate>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "  </Period>\n"
                + "</MPD>\n";
        MpdIo io = new MpdIo();
        Period ad = io.parse(longAd).getPeriods().get(0);
        Period clipped = MpdSsaiUtil.clipPeriod(ad, 9f);
        assertEquals(8_000L, clipped.getDuration().toMillis());
        assertEquals(2, segmentCount(clipped, 0, 0));
        assertEquals(2, segmentCount(clipped, 0, 1));
        assertEquals(2, segmentCount(clipped, 1, 0));
        for (int set = 0; set < 2; set++) {
            int reps = set == 0 ? 2 : 1;
            for (int rep = 0; rep < reps; rep++) {
                Representation r = clipped.getAdaptationSets().get(set).getRepresentations().get(rep);
                assertEquals(Long.valueOf(0L), r.getSegmentTemplate().getStartNumber());
                assertNull(r.getSegmentTemplate().getPresentationTimeOffset());
            }
        }
        Period untouched = MpdSsaiUtil.clipPeriod(ad, 72f);
        assertEquals(72_000L, untouched.getDuration().toMillis());
        assertEquals(18, segmentCount(untouched, 0, 0));
    }

    private static int segmentCount(Period period, int set, int rep) {
        int n = 0;
        for (Segment s : period.getAdaptationSets().get(set).getRepresentations().get(rep)
                .getSegmentTemplate().getSegmentTimeline()) {
            long repeat = s.getR() == null ? 0L : s.getR();
            n += (int) repeat + 1;
        }
        return n;
    }

    @Test
    public void contentSubtitlesAreSlicedPerPeriodAndAdsHaveNone() throws Exception {
        String withText = CONTENT.replace("</AdaptationSet>\n  </Period>",
                "</AdaptationSet>\n"
                        + "    <AdaptationSet id=\"1\" contentType=\"text\" mimeType=\"text/vtt\" lang=\"en\">\n"
                        + "      <Representation id=\"subs\" bandwidth=\"256\"><BaseURL>subs-dash.vtt</BaseURL></Representation>\n"
                        + "    </AdaptationSet>\n  </Period>");
        String vtt = "WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:03.950\nDummy 1\n\n"
                + "00:00:08.000 --> 00:00:11.950\nDummy 3\n\n"
                + "00:00:12.000 --> 00:00:15.950\nDummy 4\n\n";
        MpdIo io = new MpdIo();
        MPD content = io.parse(withText);
        Period ad = io.parse(AD).getPeriods().get(0);
        List<MpdSsaiUtil.AdBreak> breaks = new ArrayList<MpdSsaiUtil.AdBreak>();
        breaks.add(new MpdSsaiUtil.AdBreak("ad-1", 8f, ad));
        MpdSsaiUtil.Stitched out = MpdSsaiUtil.stitch(content, breaks, vtt, "/s/sid/text/");
        String c0 = out.text.get("c0.vtt");
        String c1 = out.text.get("c1.vtt");
        assertTrue(c0.contains("00:00:00.000 --> 00:00:03.950"));
        assertTrue(c0.contains("Dummy 1"));
        assertFalse(c0.contains("Dummy 4"));
        assertTrue(c1.contains("00:00:04.000 --> 00:00:07.950"));
        assertTrue(c1.contains("Dummy 4"));
        assertFalse(c1.contains("Dummy 1"));
        String xml = io.write(out.mpd);
        assertTrue(xml.contains("/s/sid/text/c0.vtt"));
        assertTrue(xml.contains("/s/sid/text/c1.vtt"));
        assertEquals(2, count(xml, "text/vtt"));
        int adAt = xml.indexOf("id=\"ad-1\"");
        int c1At = xml.indexOf("id=\"c1\"");
        assertTrue(adAt > 0 && c1At > adAt);
        assertFalse(xml.substring(adAt, c1At).contains("text/vtt"));
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
