package com.iheartradio.m3u8.ads;

import com.iheartradio.m3u8.data.CueOutContData;
import com.iheartradio.m3u8.data.CueOutData;
import com.iheartradio.m3u8.data.DateRangeData;
import com.iheartradio.m3u8.data.EncryptionData;
import com.iheartradio.m3u8.data.EncryptionMethod;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Classic <strong>SSAI (Server-Side Ad Insertion)</strong> helpers: splice ad media segments
 * into a content media playlist with cue markers and discontinuities.
 *
 * <p>This is different from HLS Interstitials / SGAI ({@link PlaylistRewriteUtil}), which only
 * injects {@code EXT-X-DATERANGE} metadata so the <em>player</em> loads a separate ad asset.
 * Classic SSAI rewrites the media playlist so ad segments appear <em>inline</em> between content
 * segments — the approach used by most SSAI proxies (AWS MediaTailor-style manifest stitching).
 *
 * <h3>Produced tag pattern (per ad break)</h3>
 * <pre>
 * # content segments...
 * #EXT-X-DISCONTINUITY          (optional, default on — content → ad)
 * #EXT-X-CUE-OUT:DUR
 * #EXTINF:...,
 * ad-seg-0
 * #EXT-X-CUE-OUT-CONT:elapsed/DUR
 * #EXTINF:...,
 * ad-seg-1
 * ...
 * #EXT-X-CUE-IN
 * #EXT-X-DISCONTINUITY          (optional, default on — ad → content)
 * # content continues...
 * </pre>
 *
 * <p>Library scope remains playlist models only: fetch origin/ad creatives, VAST/VMAP resolution,
 * and HTTP proxying are the caller's responsibility.
 *
 * @see PlaylistRewriteUtil for interstitial (DATERANGE / X-ASSET-URI) injection
 * @see docs/SUPPORTED_FEATURES.md
 */
public final class PlaylistSsaiUtil {

    /** Clear-key marker used when splicing unencrypted ads into encrypted content. */
    public static final EncryptionData CLEAR_KEY =
            new EncryptionData.Builder().withMethod(EncryptionMethod.NONE).build();

    private PlaylistSsaiUtil() {
    }

    /**
     * Where an ad pod is placed relative to content timeline.
     */
    public enum Placement {
        /** Before the first content segment. */
        PRE_ROLL,
        /** After {@link AdBreak#getAfterTrackIndex()} content tracks (0-based). */
        AFTER_TRACK,
        /** At the first content segment boundary nearest {@link AdBreak#getOffsetSec()}. */
        MID_ROLL_OFFSET,
        /** After the last content segment. */
        POST_ROLL
    }

    /**
     * One ad pod (one or more ad media segments) to insert at a content position.
     */
    public static final class AdBreak {
        private final String id;
        private final Placement placement;
        private final int afterTrackIndex;
        private final float offsetSec;
        private final List<TrackData> adTracks;
        private final String scte35Out;
        private final boolean emitDateRange;

        private AdBreak(String id, Placement placement, int afterTrackIndex, float offsetSec,
                        List<TrackData> adTracks, String scte35Out, boolean emitDateRange) {
            this.id = id;
            this.placement = placement;
            this.afterTrackIndex = afterTrackIndex;
            this.offsetSec = offsetSec;
            this.adTracks = Collections.unmodifiableList(new ArrayList<TrackData>(adTracks));
            this.scte35Out = scte35Out;
            this.emitDateRange = emitDateRange;
        }

        public String getId() {
            return id;
        }

        public Placement getPlacement() {
            return placement;
        }

        public int getAfterTrackIndex() {
            return afterTrackIndex;
        }

        public float getOffsetSec() {
            return offsetSec;
        }

        public List<TrackData> getAdTracks() {
            return adTracks;
        }

        public String getScte35Out() {
            return scte35Out;
        }

        public boolean isEmitDateRange() {
            return emitDateRange;
        }

        public float totalDurationSec() {
            float total = 0f;
            for (TrackData t : adTracks) {
                if (t.hasTrackInfo()) {
                    total += t.getTrackInfo().duration;
                }
            }
            return total;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String id;
            private Placement placement = Placement.AFTER_TRACK;
            private int afterTrackIndex = -1;
            private float offsetSec = 0f;
            private List<TrackData> adTracks = new ArrayList<TrackData>();
            private String scte35Out;
            private boolean emitDateRange = true;

            public Builder withId(String id) {
                this.id = id;
                return this;
            }

            public Builder withPlacement(Placement placement) {
                this.placement = placement;
                return this;
            }

            /**
             * Insert after content track at this 0-based index (inclusive of that track).
             * Use {@code -1} for pre-roll (same as {@link Placement#PRE_ROLL}).
             */
            public Builder afterTrackIndex(int index) {
                this.placement = Placement.AFTER_TRACK;
                this.afterTrackIndex = index;
                return this;
            }

            /** Insert at the content timeline offset (seconds from start), snapped to a segment boundary. */
            public Builder atOffsetSec(float offsetSec) {
                this.placement = Placement.MID_ROLL_OFFSET;
                this.offsetSec = offsetSec;
                return this;
            }

            public Builder preRoll() {
                this.placement = Placement.PRE_ROLL;
                this.afterTrackIndex = -1;
                return this;
            }

            public Builder postRoll() {
                this.placement = Placement.POST_ROLL;
                return this;
            }

            public Builder withAdTracks(List<TrackData> tracks) {
                this.adTracks = tracks == null
                        ? new ArrayList<TrackData>()
                        : new ArrayList<TrackData>(tracks);
                return this;
            }

            public Builder addAdTrack(TrackData track) {
                if (track != null) {
                    this.adTracks.add(track);
                }
                return this;
            }

            /** Convenience: single clear TS/fMP4 segment. */
            public Builder addAdSegment(String uri, float durationSec) {
                return addAdSegment(uri, durationSec, null);
            }

            public Builder addAdSegment(String uri, float durationSec, String title) {
                this.adTracks.add(new TrackData.Builder()
                        .withUri(uri)
                        .withTrackInfo(new TrackInfo(durationSec, title))
                        .build());
                return this;
            }

            /**
             * Use every track from a parsed ad media playlist (e.g. creative .m3u8).
             * Cue / discontinuity flags on the ad playlist are stripped; stitch applies its own.
             */
            public Builder withAdPlaylist(Playlist adPlaylist) {
                if (adPlaylist == null || !adPlaylist.hasMediaPlaylist()) {
                    throw new IllegalArgumentException("adPlaylist must be a media playlist");
                }
                this.adTracks = stripCueMarkers(adPlaylist.getMediaPlaylist().getTracks());
                return this;
            }

            /**
             * Same as {@link #withAdPlaylist(Playlist)} but keeps only enough segments so the
             * pod duration is at most {@code maxDurationSec} (whole segments; see
             * {@link PlaylistSsaiUtil#trimTracksToMaxDuration}).
             *
             * @param maxDurationSec max ad length in seconds; {@code <= 0} means no trim
             */
            public Builder withAdPlaylist(Playlist adPlaylist, float maxDurationSec) {
                withAdPlaylist(adPlaylist);
                this.adTracks = trimTracksToMaxDuration(this.adTracks, maxDurationSec);
                return this;
            }

            public Builder withScte35Out(String scte35Out) {
                this.scte35Out = scte35Out;
                return this;
            }

            public Builder withEmitDateRange(boolean emit) {
                this.emitDateRange = emit;
                return this;
            }

            public AdBreak build() {
                if (adTracks == null || adTracks.isEmpty()) {
                    throw new IllegalArgumentException("AdBreak requires at least one ad track");
                }
                if (placement == null) {
                    throw new IllegalArgumentException("placement is null");
                }
                String resolvedId = id != null && id.length() > 0 ? id : "ssai-break";
                return new AdBreak(resolvedId, placement, afterTrackIndex, offsetSec, adTracks,
                        scte35Out, emitDateRange);
            }
        }
    }

    /**
     * Options for {@link #stitch(Playlist, List, StitchOptions)}.
     */
    public static final class StitchOptions {
        public final boolean discontinuityIntoAd;
        public final boolean discontinuityOutOfAd;
        public final boolean emitCueTags;
        public final boolean emitCueOutCont;
        public final boolean clearEncryptionForAds;
        public final boolean snapOffsetToSegment;
        public final String synthProgramDateTime;

        public StitchOptions(boolean discontinuityIntoAd,
                             boolean discontinuityOutOfAd,
                             boolean emitCueTags,
                             boolean emitCueOutCont,
                             boolean clearEncryptionForAds,
                             boolean snapOffsetToSegment,
                             String synthProgramDateTime) {
            this.discontinuityIntoAd = discontinuityIntoAd;
            this.discontinuityOutOfAd = discontinuityOutOfAd;
            this.emitCueTags = emitCueTags;
            this.emitCueOutCont = emitCueOutCont;
            this.clearEncryptionForAds = clearEncryptionForAds;
            this.snapOffsetToSegment = snapOffsetToSegment;
            this.synthProgramDateTime = synthProgramDateTime;
        }

        public static StitchOptions defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean discontinuityIntoAd = true;
            private boolean discontinuityOutOfAd = true;
            private boolean emitCueTags = true;
            private boolean emitCueOutCont = true;
            private boolean clearEncryptionForAds = true;
            private boolean snapOffsetToSegment = true;
            private String synthProgramDateTime = PlaylistRewriteUtil.DEFAULT_SYNTH_PDT;

            public Builder discontinuityIntoAd(boolean v) {
                this.discontinuityIntoAd = v;
                return this;
            }

            public Builder discontinuityOutOfAd(boolean v) {
                this.discontinuityOutOfAd = v;
                return this;
            }

            public Builder emitCueTags(boolean v) {
                this.emitCueTags = v;
                return this;
            }

            public Builder emitCueOutCont(boolean v) {
                this.emitCueOutCont = v;
                return this;
            }

            public Builder clearEncryptionForAds(boolean v) {
                this.clearEncryptionForAds = v;
                return this;
            }

            public Builder snapOffsetToSegment(boolean v) {
                this.snapOffsetToSegment = v;
                return this;
            }

            public Builder synthProgramDateTime(String iso) {
                this.synthProgramDateTime = iso;
                return this;
            }

            public StitchOptions build() {
                return new StitchOptions(discontinuityIntoAd, discontinuityOutOfAd, emitCueTags,
                        emitCueOutCont, clearEncryptionForAds, snapOffsetToSegment, synthProgramDateTime);
            }
        }
    }

    /**
     * Stitch ad breaks into a content media playlist using default options.
     *
     * @param contentPlaylist content master is rejected; must have a media playlist
     * @param breaks          ad pods (empty → content returned unchanged)
     * @return new playlist with ad segments spliced in
     */
    public static Playlist stitch(Playlist contentPlaylist, List<AdBreak> breaks) {
        return stitch(contentPlaylist, breaks, StitchOptions.defaults());
    }

    /**
     * Stitch ad breaks into a content media playlist.
     *
     * @param contentPlaylist content media playlist
     * @param breaks          ad pods to insert
     * @param options         discontinuity / cue / encryption behavior
     * @return new playlist; master playlists are returned unchanged
     */
    public static Playlist stitch(Playlist contentPlaylist, List<AdBreak> breaks, StitchOptions options) {
        if (contentPlaylist == null) {
            throw new IllegalArgumentException("contentPlaylist is null");
        }
        if (!contentPlaylist.hasMediaPlaylist()) {
            // Master: caller should stitch each variant media playlist separately.
            return contentPlaylist;
        }
        if (breaks == null || breaks.isEmpty()) {
            return contentPlaylist;
        }
        if (options == null) {
            options = StitchOptions.defaults();
        }

        final MediaPlaylist media = contentPlaylist.getMediaPlaylist();
        final List<TrackData> contentTracks = media.getTracks();
        final List<ResolvedBreak> resolved = resolveBreaks(contentTracks, breaks, options);

        // Sort by insert-after index ascending; stable for same index (pod order preserved by sort stability).
        Collections.sort(resolved, new Comparator<ResolvedBreak>() {
            @Override
            public int compare(ResolvedBreak a, ResolvedBreak b) {
                int c = Integer.compare(a.insertAfterIndex, b.insertAfterIndex);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(a.order, b.order);
            }
        });

        // Group pods that share the same insert-after index (back-to-back mid-rolls).
        final List<TrackData> out = new ArrayList<TrackData>();
        final List<DateRangeData> dateRanges = new ArrayList<DateRangeData>();
        if (media.hasDateRanges()) {
            dateRanges.addAll(media.getDateRanges());
        }

        String basePdt = firstProgramDateTime(contentTracks);
        long basePdtMs = PlaylistRewriteUtil.parseIsoToMs(basePdt);
        if (basePdtMs < 0) {
            basePdt = options.synthProgramDateTime;
            basePdtMs = PlaylistRewriteUtil.parseIsoToMs(basePdt);
            if (basePdtMs < 0) {
                basePdt = PlaylistRewriteUtil.DEFAULT_SYNTH_PDT;
                basePdtMs = PlaylistRewriteUtil.DEFAULT_SYNTH_PDT_MS;
            }
        }

        int breakCursor = 0;
        int contentIndex = 0;
        // Pre-roll (insertAfterIndex == -1)
        while (breakCursor < resolved.size() && resolved.get(breakCursor).insertAfterIndex < 0) {
            ResolvedBreak rb = resolved.get(breakCursor++);
            appendAdPod(out, rb.breakDef, options, contentTracks);
            maybeAddDateRange(dateRanges, rb, basePdtMs, contentTracks, options);
        }

        while (contentIndex < contentTracks.size()) {
            TrackData contentTrack = contentTracks.get(contentIndex);
            // DISCONTINUITY on content resume is applied to this track when previous item was ads.
            if (!out.isEmpty() && lastWasAd(out)) {
                TrackData.Builder resume = contentTrack.buildUpon();
                // Written before this content URI: CUE-IN then DISCONTINUITY (MediaTailor).
                if (options.emitCueTags) {
                    resume.withCueIn(true);
                }
                if (options.discontinuityOutOfAd) {
                    resume.withDiscontinuity(true);
                }
                contentTrack = resume.build();
            }
            out.add(contentTrack);
            final int justAddedIndex = contentIndex;
            contentIndex++;

            while (breakCursor < resolved.size()
                    && resolved.get(breakCursor).insertAfterIndex == justAddedIndex) {
                ResolvedBreak rb = resolved.get(breakCursor++);
                appendAdPod(out, rb.breakDef, options, contentTracks);
                maybeAddDateRange(dateRanges, rb, basePdtMs, contentTracks, options);
            }
        }

        // Post-roll (insertAfterIndex >= content size was mapped to last index; also handle empty content)
        while (breakCursor < resolved.size()) {
            ResolvedBreak rb = resolved.get(breakCursor++);
            appendAdPod(out, rb.breakDef, options, contentTracks);
            maybeAddDateRange(dateRanges, rb, basePdtMs, contentTracks, options);
        }

        // Post-roll (or ads with no following content): CUE-IN after the last ad URI.
        if (options.emitCueTags && lastWasAd(out)) {
            int last = out.size() - 1;
            out.set(last, out.get(last).buildUpon().withCueIn(true).build());
        }

        int targetDuration = media.getTargetDuration();
        for (TrackData t : out) {
            if (t.hasTrackInfo()) {
                int ceil = (int) Math.ceil(t.getTrackInfo().duration);
                if (ceil > targetDuration) {
                    targetDuration = ceil;
                }
            }
        }

        MediaPlaylist stitchedMedia = media.buildUpon()
                .withTracks(out)
                .withDateRanges(dateRanges)
                .withTargetDuration(targetDuration)
                .build();

        return contentPlaylist.buildUpon()
                .withMediaPlaylist(stitchedMedia)
                .withExtended(true)
                .build();
    }

    /**
     * Copy tracks from an ad media playlist, stripping SSAI cue markers so stitch can re-apply them.
     */
    public static List<TrackData> segmentsFromAdPlaylist(Playlist adPlaylist) {
        if (adPlaylist == null || !adPlaylist.hasMediaPlaylist()) {
            throw new IllegalArgumentException("adPlaylist must be a media playlist");
        }
        return stripCueMarkers(adPlaylist.getMediaPlaylist().getTracks());
    }

    // ---------- internals ----------

    private static final class ResolvedBreak {
        final AdBreak breakDef;
        final int insertAfterIndex; // -1 = before first content track
        final int order;

        ResolvedBreak(AdBreak breakDef, int insertAfterIndex, int order) {
            this.breakDef = breakDef;
            this.insertAfterIndex = insertAfterIndex;
            this.order = order;
        }
    }

    private static List<ResolvedBreak> resolveBreaks(List<TrackData> contentTracks,
                                                     List<AdBreak> breaks,
                                                     StitchOptions options) {
        List<ResolvedBreak> resolved = new ArrayList<ResolvedBreak>();
        final int lastIndex = contentTracks.isEmpty() ? -1 : contentTracks.size() - 1;
        int order = 0;
        for (AdBreak br : breaks) {
            if (br == null) {
                continue;
            }
            int after;
            switch (br.placement) {
                case PRE_ROLL:
                    after = -1;
                    break;
                case POST_ROLL:
                    after = lastIndex;
                    break;
                case AFTER_TRACK:
                    after = br.afterTrackIndex;
                    if (after < -1) {
                        after = -1;
                    }
                    if (after > lastIndex) {
                        after = lastIndex;
                    }
                    break;
                case MID_ROLL_OFFSET:
                    after = indexAfterOffset(contentTracks, br.offsetSec, options.snapOffsetToSegment);
                    break;
                default:
                    after = br.afterTrackIndex;
            }
            resolved.add(new ResolvedBreak(br, after, order++));
        }
        return resolved;
    }

    /**
     * Returns the content track index after which to insert for a timeline offset.
     * Offset 0 → pre-roll (-1). Offset past end → after last track.
     */
    static int indexAfterOffset(List<TrackData> tracks, float offsetSec, boolean snap) {
        if (tracks == null || tracks.isEmpty()) {
            return -1;
        }
        if (offsetSec <= 0f) {
            return -1;
        }
        List<Float> starts = new ArrayList<Float>();
        starts.add(0f);
        float cumulative = 0f;
        for (TrackData t : tracks) {
            if (t.hasTrackInfo()) {
                cumulative += t.getTrackInfo().duration;
            }
            starts.add(cumulative);
        }
        float target = offsetSec;
        if (snap) {
            target = PlaylistRewriteUtil.snapOffset(offsetSec, starts);
        }
        // Find largest segment start <= target; insert after the track that ends at that start... 
        // Segment starts[i] is the start time of tracks[i]. Insert after track (i-1) when target == starts[i].
        int bestI = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= target + 1e-4f) {
                bestI = i;
            }
        }
        // bestI is index into starts; content track index after which to insert is bestI - 1
        // (starts[0]=0 → after=-1 pre-roll; starts[n]=total → after last track)
        return bestI - 1;
    }

    private static void appendAdPod(List<TrackData> out, AdBreak br, StitchOptions options,
                                    List<TrackData> contentTracks) {
        List<TrackData> ads = br.adTracks;
        float podDuration = br.totalDurationSec();
        float elapsed = 0f;

        for (int i = 0; i < ads.size(); i++) {
            TrackData src = ads.get(i);
            TrackData.Builder b = src.buildUpon()
                    .withCueOut(null)
                    .withCueOutCont(null)
                    .withCueIn(false)
                    .withDiscontinuity(false);

            // When content uses AES, emit METHOD=NONE so players stop applying the content key to ads.
            if (options.clearEncryptionForAds && contentHasEncryption(contentTracks)) {
                b.withEncryptionData(CLEAR_KEY);
            }

            if (i == 0) {
                if (options.discontinuityIntoAd) {
                    b.withDiscontinuity(true);
                }
                if (options.emitCueTags) {
                    b.withCueOut(new CueOutData(podDuration > 0 ? podDuration : null));
                }
            } else if (options.emitCueTags && options.emitCueOutCont) {
                b.withCueOutCont(new CueOutContData(elapsed, podDuration, null));
            }

            TrackData built = b.build();
            out.add(built);
            if (built.hasTrackInfo()) {
                elapsed += built.getTrackInfo().duration;
            }
        }
    }

    private static boolean contentHasEncryption(List<TrackData> contentTracks) {
        if (contentTracks == null) {
            return false;
        }
        for (TrackData t : contentTracks) {
            if (t.isEncrypted()) {
                return true;
            }
        }
        return false;
    }

    private static boolean lastWasAd(List<TrackData> out) {
        if (out.isEmpty()) {
            return false;
        }
        TrackData last = out.get(out.size() - 1);
        // Do not treat content-with-CUE-IN (MediaTailor resume) as an ad.
        return last.hasCueOut() || last.hasCueOutCont();
    }

    private static void maybeAddDateRange(List<DateRangeData> dateRanges, ResolvedBreak rb,
                                          long basePdtMs, List<TrackData> contentTracks,
                                          StitchOptions options) {
        AdBreak br = rb.breakDef;
        if (!br.emitDateRange) {
            return;
        }
        float contentOffset = contentOffsetBefore(contentTracks, rb.insertAfterIndex);
        long startMs = basePdtMs + Math.round(contentOffset * 1000.0);
        String startDate = PlaylistRewriteUtil.toIsoDate(startMs);
        float dur = br.totalDurationSec();

        DateRangeData.Builder b = new DateRangeData.Builder()
                .withId(br.id)
                .withStartDate(startDate)
                .withPlannedDuration(dur)
                .withDuration(dur);
        if (br.scte35Out != null && br.scte35Out.length() > 0) {
            b.withScte35Out(br.scte35Out);
        }
        // Drop any prior range with same id (re-stitch safety).
        for (int i = dateRanges.size() - 1; i >= 0; i--) {
            if (br.id.equals(dateRanges.get(i).getId())) {
                dateRanges.remove(i);
            }
        }
        dateRanges.add(b.build());
    }

    private static float contentOffsetBefore(List<TrackData> tracks, int insertAfterIndex) {
        if (tracks == null || insertAfterIndex < 0) {
            return 0f;
        }
        float sum = 0f;
        int limit = Math.min(insertAfterIndex + 1, tracks.size());
        for (int i = 0; i < limit; i++) {
            TrackData t = tracks.get(i);
            if (t.hasTrackInfo()) {
                sum += t.getTrackInfo().duration;
            }
        }
        return sum;
    }

    private static String firstProgramDateTime(List<TrackData> tracks) {
        if (tracks == null) {
            return null;
        }
        for (TrackData t : tracks) {
            if (t.hasProgramDateTime()) {
                return t.getProgramDateTime();
            }
        }
        return null;
    }

    private static List<TrackData> stripCueMarkers(List<TrackData> tracks) {
        List<TrackData> out = new ArrayList<TrackData>(tracks.size());
        for (TrackData t : tracks) {
            out.add(t.buildUpon()
                    .withCueOut(null)
                    .withCueOutCont(null)
                    .withCueIn(false)
                    .withDiscontinuity(false)
                    .build());
        }
        return out;
    }

    /**
     * Keep whole ad segments from the start of the list until cumulative duration would exceed
     * {@code maxDurationSec}. Does not re-encode or split media: if the first segment alone is
     * longer than the max, it is still kept so the pod is non-empty.
     *
     * @param tracks         ad segments (order preserved)
     * @param maxDurationSec maximum pod length in seconds; {@code <= 0} returns an unmodifiable copy
     * @return trimmed list (never null)
     */
    public static List<TrackData> trimTracksToMaxDuration(List<TrackData> tracks, float maxDurationSec) {
        if (tracks == null || tracks.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxDurationSec <= 0f) {
            return Collections.unmodifiableList(new ArrayList<TrackData>(tracks));
        }
        List<TrackData> out = new ArrayList<TrackData>();
        float cumulative = 0f;
        for (TrackData t : tracks) {
            float d = t.hasTrackInfo() ? t.getTrackInfo().duration : 0f;
            if (d < 0f) {
                d = 0f;
            }
            if (!out.isEmpty() && cumulative + d > maxDurationSec + 1e-4f) {
                break;
            }
            out.add(t);
            cumulative += d;
            if (cumulative >= maxDurationSec - 1e-4f) {
                break;
            }
        }
        return out;
    }
}
