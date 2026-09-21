package com.iheartradio.m3u8.ads;

import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import io.lindstrom.mpd.data.Representation;
import io.lindstrom.mpd.data.Segment;
import io.lindstrom.mpd.data.SegmentTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public static MPD stitch(MPD content, List<AdBreak> breaks) {
        if (content == null) {
            throw new IllegalArgumentException("content MPD is null");
        }
        if (breaks == null || breaks.isEmpty()) {
            return content;
        }
        if (content.getPeriods() == null || content.getPeriods().isEmpty()) {
            return content;
        }
        Period src = content.getPeriods().get(0);
        Timeline tl = Timeline.from(src);
        if (tl.flats.isEmpty()) {
            return content;
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

        List<Period> periods = new ArrayList<Period>();
        int from = 0;
        int cSeq = 0;
        int r = 0;
        while (r < resolved.size()) {
            int idx = resolved.get(r).index;
            if (from < idx) {
                periods.add(tl.sliceContent(src, from, idx, "c" + cSeq++));
                from = idx;
            }
            while (r < resolved.size() && resolved.get(r).index == idx) {
                periods.add(prepareAdPeriod(resolved.get(r).br));
                r++;
            }
        }
        if (from < tl.flats.size()) {
            periods.add(tl.sliceContent(src, from, tl.flats.size(), "c" + cSeq));
        }
        if (periods.isEmpty()) {
            return content;
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
        return content.buildUpon()
                .withPeriods(timed)
                .withMediaPresentationDuration(total)
                .build();
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

        long durationMs() {
            long ticks = 0L;
            for (Flat f : flats) {
                ticks += f.d;
            }
            return ticks * 1000L / timescale;
        }

        Period sliceContent(Period src, int from, int to, String id) {
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
            // T and PTO use the CMAF movie time of the first included segment
            // (v720_3 tfdt=12s). EPT=(T-PTO)/timescale is then 0 at Period@start.
            long pto = flats.get(from).t;
            SegmentTemplate next = st.buildUpon()
                    .withStartNumber(start)
                    .withSegmentTimeline(sliced)
                    .withPresentationTimeOffset(pto)
                    .build();
            return r.buildUpon().withSegmentTemplate(next).build();
        }
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
