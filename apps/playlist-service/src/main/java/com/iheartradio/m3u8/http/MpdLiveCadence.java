package com.iheartradio.m3u8.http;

import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import io.lindstrom.mpd.data.PresentationType;
import io.lindstrom.mpd.data.Segment;
import io.lindstrom.mpd.data.SegmentTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One DASH replacement per cadence interval, held back by the player's
 * presentation delay and frozen so manifest refreshes do not slide it.
 */
final class MpdLiveCadence {

    static final class Place {
        final String periodId;
        final double offsetSec;
        final String breakId;
        final int resolveMs;
        double periodStartSec;

        Place(String periodId, double offsetSec, String breakId, int resolveMs) {
            this.periodId = periodId;
            this.offsetSec = offsetSec;
            this.breakId = breakId;
            this.resolveMs = resolveMs;
        }
    }

    private MpdLiveCadence() {
    }

    static boolean uses(DemoSession session, MPD mpd) {
        return session != null
                && session.strategy == DemoSession.Strategy.SGAI
                && !session.forceVod
                && session.liveCadenceSec > 0f
                && mpd != null
                && mpd.getType() == PresentationType.DYNAMIC;
    }

    /** Patch flow: the break must still be ahead of the playhead when the first document is parsed. */
    static final long PATCH_LOOKAHEAD_MS = 15_000L;

    static Place place(MPD mpd, DemoSession session, long nowMs) {
        return place(mpd, session, nowMs, false);
    }

    static Place placeAhead(MPD mpd, DemoSession session, long nowMs) {
        return place(mpd, session, nowMs, true);
    }

    /** Edge from a patch {@code publishTime}. Period start is the one stored on the session. */
    static Place placeAheadFromPublish(DemoSession session, String publishTime) {
        if (session == null || session.dashPatchPeriodId == null || publishTime == null) {
            return null;
        }
        long edgeMs;
        try {
            edgeMs = java.time.OffsetDateTime.parse(publishTime).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
        long intervalMs = Math.round(session.liveCadenceSec * 1000.0);
        if (intervalMs < 1000L) {
            intervalMs = 60_000L;
        }
        long breakMs = edgeMs + PATCH_LOOKAHEAD_MS;
        long bucket = (breakMs / intervalMs) * intervalMs;
        String id = "live-" + (bucket / 1000L);
        breakMs = session.freezeLiveCadenceStart(id, breakMs);
        double offset = Math.max(0.0, (breakMs / 1000.0) - session.dashPatchPeriodStartSec);
        return new Place(session.dashPatchPeriodId, offset, id, 2000);
    }

    private static Place place(MPD mpd, DemoSession session, long nowMs, boolean ahead) {
        List<Period> periods = mpd.getPeriods();
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        double edge = liveEdgeSec(mpd, nowMs);
        if (Double.isNaN(edge)) {
            return null;
        }
        double delay = presentationDelaySec(mpd);
        long intervalMs = Math.round(session.liveCadenceSec * 1000.0);
        if (intervalMs < 1000L) {
            intervalMs = 60_000L;
        }
        long edgeMs = Math.round(edge * 1000.0);
        long breakMs = ahead ? edgeMs + PATCH_LOOKAHEAD_MS : edgeMs - Math.round(delay * 1000.0);
        long bucket = ((ahead ? breakMs : edgeMs) / intervalMs) * intervalMs;
        String id = "live-" + (bucket / 1000L);
        breakMs = session.freezeLiveCadenceStart(id, breakMs);

        Period owner = periods.get(0);
        double ownerStart = startSec(owner);
        for (Period p : periods) {
            double start = startSec(p);
            if (start <= breakMs / 1000.0) {
                owner = p;
                ownerStart = start;
            }
        }
        String periodId = owner.getId();
        if (periodId == null || periodId.length() == 0) {
            return null;
        }
        double offset = Math.max(0.0, (breakMs / 1000.0) - ownerStart);
        int resolve = resolveMs(videoTemplate(owner), delay);
        Place place = new Place(periodId, offset, id, resolve);
        place.periodStartSec = ownerStart;
        return place;
    }

    /** suggestedPresentationDelay, otherwise 1.5 × minBufferTime capped by the DVR window. */
    static double presentationDelaySec(MPD mpd) {
        if (mpd.getSuggestedPresentationDelay() != null) {
            return Math.max(0.0, sec(mpd.getSuggestedPresentationDelay()));
        }
        double delay = mpd.getMinBufferTime() == null ? 0.0 : sec(mpd.getMinBufferTime()) * 1.5;
        if (mpd.getTimeShiftBufferDepth() != null) {
            delay = Math.min(delay, sec(mpd.getTimeShiftBufferDepth()));
        }
        return Math.max(0.0, delay);
    }

    static double liveEdgeSec(MPD mpd, long nowMs) {
        List<Period> periods = mpd.getPeriods();
        Period last = periods.get(periods.size() - 1);
        SegmentTemplate template = videoTemplate(last);
        if (template != null && template.getSegmentTimeline() != null
                && !template.getSegmentTimeline().isEmpty()) {
            long timescale = template.getTimescale() == null ? 1L : template.getTimescale();
            long pto = template.getPresentationTimeOffset() == null
                    ? 0L : template.getPresentationTimeOffset();
            long end = timelineEnd(template.getSegmentTimeline());
            return startSec(last) + (end - pto) / (double) timescale;
        }
        OffsetDateTime ast = mpd.getAvailabilityStartTime();
        if (ast == null) {
            return Double.NaN;
        }
        return (nowMs - ast.toInstant().toEpochMilli()) / 1000.0;
    }

    private static long timelineEnd(List<Segment> segments) {
        long t = 0L;
        long end = 0L;
        for (Segment s : segments) {
            if (s.getT() != null) {
                t = s.getT();
            }
            long d = s.getD();
            long r = s.getR() == null ? 0L : s.getR();
            end = t + d * (r + 1L);
            t = end;
        }
        return end;
    }

    private static int resolveMs(SegmentTemplate template, double delaySec) {
        double seg = 2.0;
        if (template != null && template.getDuration() != null) {
            long timescale = template.getTimescale() == null ? 1L : template.getTimescale();
            seg = template.getDuration() / (double) timescale;
        }
        int delayMs = (int) Math.round(delaySec * 1000.0);
        int want = (int) Math.round(seg * 1000.0);
        if (want >= delayMs) {
            want = Math.max(0, delayMs - 500);
        }
        return want;
    }

    private static SegmentTemplate videoTemplate(Period period) {
        if (period == null || period.getAdaptationSets() == null) {
            return null;
        }
        for (AdaptationSet set : period.getAdaptationSets()) {
            String type = set.getContentType() == null ? "" : set.getContentType();
            String mime = set.getMimeType() == null ? "" : set.getMimeType();
            if (!"video".equals(type) && !mime.startsWith("video/")) {
                continue;
            }
            if (set.getSegmentTemplate() != null) {
                return set.getSegmentTemplate();
            }
            if (set.getRepresentations() != null) {
                for (io.lindstrom.mpd.data.Representation rep : set.getRepresentations()) {
                    if (rep.getSegmentTemplate() != null) {
                        return rep.getSegmentTemplate();
                    }
                }
            }
        }
        return null;
    }

    private static double startSec(Period period) {
        return period.getStart() == null ? 0.0 : sec(period.getStart());
    }

    private static double sec(Duration d) {
        return d.toMillis() / 1000.0;
    }
}
