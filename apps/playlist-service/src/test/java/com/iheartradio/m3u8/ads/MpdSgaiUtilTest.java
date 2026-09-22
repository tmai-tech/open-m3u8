package com.iheartradio.m3u8.ads;

import com.iheartradio.dash.MpdIo;
import io.lindstrom.mpd.data.BaseURL;
import io.lindstrom.mpd.data.Period;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpdSgaiUtilTest {

    private static final String CONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
            + " mediaPresentationDuration=\"PT16S\" minBufferTime=\"PT2S\">\n"
            + "  <Period id=\"p0\" duration=\"PT16S\">\n"
            + "    <AdaptationSet contentType=\"video\"/>\n"
            + "  </Period>\n"
            + "</MPD>\n";

    @Test
    public void injectKeepsOnePeriodAndUsesRelativeAltUri() {
        List<MpdSgaiUtil.Event> events = new ArrayList<MpdSgaiUtil.Event>();
        events.add(new MpdSgaiUtil.Event("ssai-1", 12f, 30f, "/s/sid/alt/ssai-1.mpd"));
        events.add(new MpdSgaiUtil.Event("ssai-2", 24f, 16f, "/s/sid/alt/ssai-2.mpd"));
        String xml = MpdSgaiUtil.inject(CONTENT, events);
        assertEquals(1, count(xml, "<Period"));
        assertTrue(xml.contains(MpdSgaiUtil.INSERT_SCHEME));
        assertTrue(xml.contains("presentationTime=\"12000\""));
        assertTrue(xml.contains("presentationTime=\"24000\""));
        assertTrue(xml.contains("uri=\"/s/sid/alt/ssai-1.mpd\""));
        assertTrue(xml.contains("uri=\"/s/sid/alt/ssai-2.mpd\""));
        assertTrue(xml.contains("maxDuration=\"30000\""));
        assertTrue(xml.contains("returnOffset=\"0\""));
        assertFalse(xml.contains("https://"));
        assertFalse(xml.contains("trycloudflare"));
    }

    @Test
    public void altMpdIsAdsOnly() throws Exception {
        Period a = Period.builder().withId("grok").withDuration(Duration.ofMillis(15042))
                .withBaseURLs(Collections.singletonList(
                        BaseURL.builder().withValue("/media/titles/grok/").build()))
                .build();
        Period b = Period.builder().withId("giff").withDuration(Duration.ofMillis(12100))
                .withBaseURLs(Collections.singletonList(
                        BaseURL.builder().withValue("/media/titles/giff/").build()))
                .build();
        List<Period> ads = new ArrayList<Period>();
        ads.add(a);
        ads.add(b);
        String xml = MpdSgaiUtil.altMpdString(ads);
        assertEquals(2, count(xml, "<Period"));
        assertTrue(xml.contains("/media/titles/grok/"));
        assertTrue(xml.contains("/media/titles/giff/"));
        assertFalse(xml.contains("InsertPresentation"));
        assertFalse(xml.contains("https://"));
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
