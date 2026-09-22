package com.iheartradio.m3u8.ads;

import com.iheartradio.dash.MpdIo;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DASH SGAI for Shaka 5.2: one InsertPresentation event per break.
 * The content MPD is not stitched. Each event URI is a path to a session
 * alternate MPD that contains only that break's ad Periods.
 */
public final class MpdSgaiUtil {

    public static final String INSERT_SCHEME =
            "urn:mpeg:dash:event:alternativeMPD:insert:2025";

    /** EventStream timescale. presentationTime and maxDuration are in these units. */
    public static final int TIMESCALE = 1000;

    private static final String SHELL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
            + " profiles=\"urn:mpeg:dash:profile:isoff-live:2011\""
            + " type=\"static\" mediaPresentationDuration=\"PT1S\" minBufferTime=\"PT2S\">"
            + "<Period id=\"p\" duration=\"PT1S\"/>"
            + "</MPD>";

    private MpdSgaiUtil() {
    }

    public static final class Event {
        public final String id;
        public final float offsetSec;
        public final float durationSec;
        public final String uri;

        public Event(String id, float offsetSec, float durationSec, String uri) {
            this.id = id;
            this.offsetSec = offsetSec;
            this.durationSec = durationSec;
            this.uri = uri;
        }
    }

    /** Insert an EventStream into the first Period. Does not parse the result back. */
    public static String inject(String xml, List<Event> events) {
        if (xml == null || events == null || events.isEmpty()) {
            return xml;
        }
        int open = xml.indexOf("<Period");
        if (open < 0) {
            return xml;
        }
        int gt = xml.indexOf('>', open);
        if (gt < 0) {
            return xml;
        }
        StringBuilder eventsXml = new StringBuilder();
        eventsXml.append("\n    <EventStream schemeIdUri=\"").append(INSERT_SCHEME)
                .append("\" timescale=\"").append(TIMESCALE).append("\">\n");
        for (Event ev : events) {
            long at = Math.round(ev.offsetSec * TIMESCALE);
            long dur = Math.max(0, Math.round(ev.durationSec * TIMESCALE));
            eventsXml.append("      <Event presentationTime=\"").append(at)
                    .append("\" duration=\"").append(dur)
                    .append("\" id=\"").append(xmlAttr(ev.id)).append("\">\n");
            eventsXml.append("        <InsertPresentation uri=\"").append(xmlAttr(ev.uri))
                    .append("\" maxDuration=\"").append(dur)
                    .append("\" returnOffset=\"0\" earliestResolutionTimeOffset=\"")
                    .append(8 * TIMESCALE).append("\"/>\n");
            eventsXml.append("      </Event>\n");
        }
        eventsXml.append("    </EventStream>");
        return xml.substring(0, gt + 1) + eventsXml + xml.substring(gt + 1);
    }

    /** Alternate MPD: ad Periods only, sequential start. */
    public static byte[] altMpd(List<Period> periods) throws Exception {
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("no ad periods");
        }
        MpdIo io = new MpdIo();
        MPD shell = io.parse(SHELL);
        List<Period> timed = new ArrayList<Period>();
        Duration cursor = Duration.ZERO;
        Duration total = Duration.ZERO;
        int n = 0;
        for (Period p : periods) {
            n++;
            Duration d = p.getDuration() != null ? p.getDuration() : Duration.ofSeconds(1);
            String id = p.getId() != null && p.getId().length() > 0 ? p.getId() : ("ad-" + n);
            timed.add(p.buildUpon().withId(id).withStart(cursor).withDuration(d).build());
            cursor = cursor.plus(d);
            total = total.plus(d);
        }
        MPD out = shell.buildUpon()
                .withPeriods(timed)
                .withMediaPresentationDuration(total)
                .build();
        return io.writeBytes(out);
    }

    public static String altMpdString(List<Period> periods) throws Exception {
        return new String(altMpd(periods), StandardCharsets.UTF_8);
    }

    static String xmlAttr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
