package com.iheartradio.m3u8.ads;

import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.BaseURL;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import io.lindstrom.mpd.data.Representation;
import io.lindstrom.mpd.data.Segment;
import io.lindstrom.mpd.data.SegmentTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DASH SSAI: split the content Period and insert one Period per ad creative.
 * Same job as {@link PlaylistSsaiUtil} for HLS (inline stitch, not SGAI).
 */
public final class MpdSsaiUtil {

    private MpdSsaiUtil() {
    }

    public static final class AdBreak {
        public final String id;
        public final float offsetSec;
        public final Period period;

        public AdBreak(String id, float offsetSec, Period period) {
            if (period == null) {
                throw new IllegalArgumentException("ad period is null");
            }
            this.id = id != null && id.length() > 0 ? id : "ad";
            this.offsetSec = offsetSec;
            this.period = period;
        }
    }

    /** Sidecar WebVTT bodies keyed by file name ({@code c0.vtt}). */
    public static final class Stitched {
        public final MPD mpd;
        public final Map<String, String> text;

        Stitched(MPD mpd, Map<String, String> text) {
            this.mpd = mpd;
            this.text = text;
        }
    }

    public static MPD stitch(MPD content, List<AdBreak> breaks) {
        return stitch(content, breaks, null, null).mpd;
    }

    /**
     * @param vtt full-title {@code subs-dash.vtt}, or null to omit text
     * @param textHrefPrefix path prefix such as {@code /s/{id}/text/}; each slice is {@code c0.vtt}
     */
    public static Stitched stitch(MPD content, List<AdBreak> breaks, String vtt, String textHrefPrefix) {
        Map<String, String> text = new LinkedHashMap<String, String>();
        if (content == null) {
            throw new IllegalArgumentException("content MPD is null");
        }
        if (breaks == null || breaks.isEmpty()) {
            return new Stitched(content, text);
        }
        if (content.getPeriods() == null || content.getPeriods().isEmpty()) {
            return new Stitched(content, text);
        }
        Period src = content.getPeriods().get(0);
        Timeline tl = Timeline.from(src);
        if (tl.flats.isEmpty()) {
            return new Stitched(content, text);
        }

        List<Resolved> resolved = new ArrayList<Resolved>();
        for (int i = 0; i < breaks.size(); i++) {
            AdBreak br = breaks.get(i);
            int idx = tl.snapIndex(secToTicks(br.offsetSec, tl.timescale));
            resolved.add(new Resolved(idx, i, br));
        }
        Collections.sort(resolved, (a, b) -> {
            int c = Integer.compare(a.index, b.index);
            return c != 0 ? c : Integer.compare(a.order, b.order);
        });

        AdaptationSet textSet = findText(src);
        List<Period> periods = new ArrayList<Period>();
        int from = 0;
        int cSeq = 0;
        int r = 0;
        while (r < resolved.size()) {
            int idx = resolved.get(r).index;
            if (from < idx) {
                String id = "c" + cSeq++;
                periods.add(tl.sliceContent(src, from, idx, id, vtt, textHrefPrefix, textSet, text));
                from = idx;
            }
            while (r < resolved.size() && resolved.get(r).index == idx) {
                periods.add(prepareAdPeriod(resolved.get(r).br));
                r++;
            }
        }
        if (from < tl.flats.size()) {
            String id = "c" + cSeq;
            periods.add(tl.sliceContent(src, from, tl.flats.size(), id, vtt, textHrefPrefix, textSet, text));
        }
        if (periods.isEmpty()) {
            return new Stitched(content, text);
        }

        Duration total = Duration.ZERO;
        List<Period> timed = new ArrayList<Period>();
        Duration start = Duration.ZERO;
        for (Period p : periods) {
            Duration d = p.getDuration() != null ? p.getDuration() : Duration.ZERO;
            timed.add(p.buildUpon().withStart(start).withDuration(d).build());
            start = start.plus(d);
            total = total.plus(d);
        }
        return new Stitched(content.buildUpon()
                .withPeriods(timed)
                .withMediaPresentationDuration(total)
                .build(), text);
    }

    /**
     * Keep whole segments from the start of {@code period} until the next one would
     * pass {@code maxSec}. A non-positive cap, or a period that already fits, is unchanged.
     * The first segment is kept even when it is longer than the cap.
     */
    public static Period clipPeriod(Period period, float maxSec) {
        if (period == null || maxSec <= 0f) {
            return period;
        }
        Timeline tl = Timeline.from(period);
        if (tl.flats.isEmpty()) {
            return period;
        }
        int keep = tl.segmentsThatFit(maxSec);
        if (keep >= tl.flats.size()) {
            return period;
        }
        String id = period.getId() != null && period.getId().length() > 0 ? period.getId() : "ad";
        return tl.sliceContent(period, 0, keep, id, null, null, null, null);
    }

    static Period prepareAdPeriod(AdBreak br) {
        Period src = br.period;
        List<AdaptationSet> sets = new ArrayList<AdaptationSet>();
        if (src.getAdaptationSets() != null) {
            for (AdaptationSet as : src.getAdaptationSets()) {
                if ("text".equals(as.getContentType())) {
                    continue;
                }
                sets.add(as);
            }
        }
        Duration dur = src.getDuration();
        if (dur == null) {
            Timeline tl = Timeline.from(src);
            dur = Duration.ofMillis(tl.durationMs());
        }
        return src.buildUpon()
                .withId(br.id)
                .withStart(null)
                .withDuration(dur)
                .withAdaptationSets(sets)
                .build();
    }

    static long secToTicks(float sec, long timescale) {
        if (sec <= 0f) {
            return 0L;
        }
        return Math.round(sec * (double) timescale);
    }

    static final class Flat {
        final long t;
        final long d;
        final long number;

        Flat(long t, long d, long number) {
            this.t = t;
            this.d = d;
            this.number = number;
        }
    }

    static final class Resolved {
        final int index;
        final int order;
        final AdBreak br;

        Resolved(int index, int order, AdBreak br) {
            this.index = index;
            this.order = order;
            this.br = br;
        }
    }

    static final class Timeline {
        final long timescale;
        final long startNumber;
        final List<Flat> flats;

        Timeline(long timescale, long startNumber, List<Flat> flats) {
            this.timescale = timescale > 0 ? timescale : 1L;
            this.startNumber = startNumber;
            this.flats = flats;
        }

        static Timeline from(Period period) {
            Representation video = firstVideoRep(period);
            if (video == null || video.getSegmentTemplate() == null) {
                return new Timeline(1000L, 0L, Collections.<Flat>emptyList());
            }
            SegmentTemplate st = video.getSegmentTemplate();
            long ts = st.getTimescale() != null ? st.getTimescale() : 1L;
            long start = st.getStartNumber() != null ? st.getStartNumber() : 1L;
            return new Timeline(ts, start, expand(st.getSegmentTimeline(), start));
        }

        int snapIndex(long offsetTicks) {
            long t = 0L;
            int best = 0;
            long bestDist = Math.abs(offsetTicks);
            for (int i = 0; i <= flats.size(); i++) {
                long dist = Math.abs(t - offsetTicks);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
                if (i < flats.size()) {
                    t += flats.get(i).d;
                }
            }
            return best;
        }

        /** Whole segments from the start. At least one, matching HLS trimTracksToMaxDuration. */
        int segmentsThatFit(float maxSec) {
            int keep = 0;
            double cumulative = 0d;
            for (Flat f : flats) {
                double seconds = f.d / (double) timescale;
                if (keep > 0 && cumulative + seconds > maxSec + 1e-4d) {
                    break;
                }
                keep++;
                cumulative += seconds;
                if (cumulative >= maxSec - 1e-4d) {
                    break;
                }
            }
            return keep < 1 ? 1 : keep;
        }

        long durationMs() {
            long ticks = 0L;
            for (Flat f : flats) {
                ticks += f.d;
            }
            return ticks * 1000L / timescale;
        }

        Period sliceContent(Period src, int from, int to, String id,
                             String vtt, String textHrefPrefix, AdaptationSet textSet,
                             Map<String, String> textOut) {
            List<AdaptationSet> sets = new ArrayList<AdaptationSet>();
            if (src.getAdaptationSets() != null) {
                for (AdaptationSet as : src.getAdaptationSets()) {
                    if ("text".equals(as.getContentType())) {
                        continue;
                    }
                    List<Representation> reps = new ArrayList<Representation>();
                    if (as.getRepresentations() != null) {
                        for (Representation r : as.getRepresentations()) {
                            reps.add(sliceRep(r, from, to));
                        }
                    }
                    sets.add(as.buildUpon().withRepresentations(reps).build());
                }
            }
            if (vtt != null && vtt.length() > 0 && textHrefPrefix != null && textSet != null) {
                long startMs = flats.get(from).t * 1000L / timescale;
                long endMs = (flats.get(to - 1).t + flats.get(to - 1).d) * 1000L / timescale;
                String file = id + ".vtt";
                textOut.put(file, sliceVtt(vtt, startMs, endMs));
                String href = textHrefPrefix.endsWith("/") ? textHrefPrefix + file : textHrefPrefix + "/" + file;
                sets.add(textSlice(textSet, href));
            }
            long ticks = 0L;
            for (int i = from; i < to; i++) {
                ticks += flats.get(i).d;
            }
            return src.buildUpon()
                    .withId(id)
                    .withStart(null)
                    .withDuration(Duration.ofMillis(ticks * 1000L / timescale))
                    .withAdaptationSets(sets)
                    .build();
        }

        Representation sliceRep(Representation r, int from, int to) {
            SegmentTemplate st = r.getSegmentTemplate();
            if (st == null) {
                return r;
            }
            long start = (st.getStartNumber() != null ? st.getStartNumber() : startNumber) + from;
            List<Segment> sliced = compress(flats.subList(from, to));
            long pto = flats.get(from).t;
            SegmentTemplate.Builder tb = st.buildUpon()
                    .withStartNumber(start)
                    .withSegmentTimeline(sliced);
            if (pto != 0L) {
                tb.withPresentationTimeOffset(pto);
            }
            return r.buildUpon().withSegmentTemplate(tb.build()).build();
        }
    }

    static AdaptationSet findText(Period period) {
        if (period.getAdaptationSets() == null) {
            return null;
        }
        for (AdaptationSet as : period.getAdaptationSets()) {
            if ("text".equals(as.getContentType())) {
                return as;
            }
        }
        return null;
    }

    static AdaptationSet textSlice(AdaptationSet src, String href) {
        Representation rep = Representation.builder()
                .withId("subs")
                .withBandwidth(256L)
                .withBaseURLs(Collections.singletonList(BaseURL.builder().withValue(href).build()))
                .build();
        return src.buildUpon()
                .withRepresentations(Collections.singletonList(rep))
                .build();
    }

    /** Cues overlapping [startMs, endMs), shifted so the slice starts at 0. */
    static String sliceVtt(String vtt, long startMs, long endMs) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        if (vtt == null || startMs >= endMs) {
            return out.toString();
        }
        String[] lines = vtt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int arrow = lines[i].indexOf("-->");
            if (arrow < 0) {
                continue;
            }
            String[] ends = lines[i].substring(arrow + 3).trim().split("\\s+");
            if (ends.length == 0) {
                continue;
            }
            long cueStart;
            long cueEnd;
            try {
                cueStart = parseVttTime(lines[i].substring(0, arrow).trim());
                cueEnd = parseVttTime(ends[0]);
            } catch (RuntimeException e) {
                continue;
            }
            if (cueEnd <= startMs || cueStart >= endMs) {
                continue;
            }
            long a = Math.max(cueStart, startMs) - startMs;
            long b = Math.min(cueEnd, endMs) - startMs;
            StringBuilder payload = new StringBuilder();
            for (int j = i + 1; j < lines.length && lines[j].length() > 0; j++) {
                if (payload.length() > 0) {
                    payload.append('\n');
                }
                payload.append(lines[j]);
            }
            out.append(fmtVtt(a)).append(" --> ").append(fmtVtt(b)).append('\n');
            if (payload.length() > 0) {
                out.append(payload).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    static long parseVttTime(String raw) {
        String s = raw.trim();
        int dot = s.indexOf('.');
        int ms = 0;
        if (dot >= 0) {
            String frac = s.substring(dot + 1);
            s = s.substring(0, dot);
            while (frac.length() < 3) {
                frac = frac + "0";
            }
            ms = Integer.parseInt(frac.substring(0, 3));
        }
        String[] p = s.split(":");
        long h = 0;
        long m;
        long sec;
        if (p.length == 3) {
            h = Long.parseLong(p[0]);
            m = Long.parseLong(p[1]);
            sec = Long.parseLong(p[2]);
        } else if (p.length == 2) {
            m = Long.parseLong(p[0]);
            sec = Long.parseLong(p[1]);
        } else {
            throw new IllegalArgumentException(raw);
        }
        return ((h * 60 + m) * 60 + sec) * 1000 + ms;
    }

    static String fmtVtt(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long h = ms / 3600000;
        ms %= 3600000;
        long m = ms / 60000;
        ms %= 60000;
        long sec = ms / 1000;
        long frac = ms % 1000;
        return String.format("%02d:%02d:%02d.%03d", h, m, sec, frac);
    }

    static Representation firstVideoRep(Period period) {
        if (period.getAdaptationSets() == null) {
            return null;
        }
        Representation any = null;
        for (AdaptationSet as : period.getAdaptationSets()) {
            if (as.getRepresentations() == null || as.getRepresentations().isEmpty()) {
                continue;
            }
            Representation r = as.getRepresentations().get(0);
            if (any == null) {
                any = r;
            }
            if ("video".equals(as.getContentType()) || (as.getMimeType() != null
                    && as.getMimeType().startsWith("video"))) {
                return r;
            }
        }
        return any;
    }

    static List<Flat> expand(List<Segment> timeline, long startNumber) {
        List<Flat> out = new ArrayList<Flat>();
        if (timeline == null || timeline.isEmpty()) {
            return out;
        }
        long t = 0L;
        long number = startNumber;
        for (Segment s : timeline) {
            if (s.getT() != null) {
                t = s.getT();
            }
            long r = s.getR() != null ? s.getR() : 0L;
            for (long i = 0; i <= r; i++) {
                out.add(new Flat(t, s.getD(), number++));
                t += s.getD();
            }
        }
        return out;
    }

    static List<Segment> compress(List<Flat> flats) {
        List<Segment> out = new ArrayList<Segment>();
        if (flats == null || flats.isEmpty()) {
            return out;
        }
        int i = 0;
        boolean first = true;
        while (i < flats.size()) {
            long d = flats.get(i).d;
            long r = 0L;
            int j = i + 1;
            while (j < flats.size() && flats.get(j).d == d) {
                r++;
                j++;
            }
            Segment.Builder b = Segment.builder().withD(d);
            if (first) {
                b.withT(flats.get(i).t);
                first = false;
            }
            if (r > 0) {
                b.withR(r);
            }
            out.add(b.build());
            i = j;
        }
        return out;
    }
}
