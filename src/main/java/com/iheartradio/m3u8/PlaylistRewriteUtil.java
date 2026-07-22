package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.DateRangeData;
import com.iheartradio.m3u8.data.EncryptionData;
import com.iheartradio.m3u8.data.IFrameStreamInfo;
import com.iheartradio.m3u8.data.MapInfo;
import com.iheartradio.m3u8.data.MasterPlaylist;
import com.iheartradio.m3u8.data.MediaData;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import com.iheartradio.m3u8.data.StartData;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * High-level helpers for rewriting HLS playlists using open-m3u8 models
 * (parse → mutate builders → write) rather than raw string edits.
 *
 * <p>Used by the demo HLS player proxy and available as a public API for
 * injecting EXT-X-START / interstitial EXT-X-DATERANGE tags and rewriting URIs.
 */
public final class PlaylistRewriteUtil {

    public static final String DEFAULT_SYNTH_PDT = "2020-01-01T00:00:00.000Z";
    public static final long DEFAULT_SYNTH_PDT_MS = 1577836800000L;
    public static final String USER_AD_ID_PREFIX = "user-ad-";
    /** Minimum EXT-X-VERSION for DATERANGE / interstitials. */
    public static final int INTERSTITIAL_MIN_VERSION = 7;

    private PlaylistRewriteUtil() {
    }

    /**
     * Configuration for injecting start + interstitial date ranges into a media playlist.
     */
    public static final class InjectConfig {
        public final StartData startOverride; // null = leave playlist start alone
        public final List<InterstitialBreak> breaks;
        public final boolean snapToSegment;
        public final Float defaultResumeOffset;
        public final String defaultRestrict; // e.g. "SKIP"
        public final String defaultSnap; // e.g. "IN" / "OUT"
        public final String synthProgramDateTime;

        public InjectConfig(StartData startOverride,
                            List<InterstitialBreak> breaks,
                            boolean snapToSegment,
                            Float defaultResumeOffset,
                            String defaultRestrict,
                            String defaultSnap,
                            String synthProgramDateTime) {
            this.startOverride = startOverride;
            this.breaks = breaks == null
                    ? Collections.<InterstitialBreak>emptyList()
                    : Collections.unmodifiableList(new ArrayList<InterstitialBreak>(breaks));
            this.snapToSegment = snapToSegment;
            this.defaultResumeOffset = defaultResumeOffset;
            this.defaultRestrict = defaultRestrict;
            this.defaultSnap = defaultSnap;
            this.synthProgramDateTime = synthProgramDateTime == null ? DEFAULT_SYNTH_PDT : synthProgramDateTime;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private StartData startOverride;
            private List<InterstitialBreak> breaks = new ArrayList<InterstitialBreak>();
            private boolean snapToSegment = true;
            private Float defaultResumeOffset = 0f;
            private String defaultRestrict;
            private String defaultSnap;
            private String synthProgramDateTime = DEFAULT_SYNTH_PDT;

            public Builder withStartOverride(StartData start) {
                this.startOverride = start;
                return this;
            }

            public Builder withBreaks(List<InterstitialBreak> breaks) {
                this.breaks = breaks == null ? new ArrayList<InterstitialBreak>() : new ArrayList<InterstitialBreak>(breaks);
                return this;
            }

            public Builder addBreak(InterstitialBreak br) {
                if (br != null) {
                    this.breaks.add(br);
                }
                return this;
            }

            public Builder withSnapToSegment(boolean snap) {
                this.snapToSegment = snap;
                return this;
            }

            public Builder withDefaultResumeOffset(Float resumeOffset) {
                this.defaultResumeOffset = resumeOffset;
                return this;
            }

            public Builder withDefaultRestrict(String restrict) {
                this.defaultRestrict = restrict;
                return this;
            }

            public Builder withDefaultSnap(String snap) {
                this.defaultSnap = snap;
                return this;
            }

            public Builder withSynthProgramDateTime(String iso) {
                this.synthProgramDateTime = iso;
                return this;
            }

            public InjectConfig build() {
                return new InjectConfig(startOverride, breaks, snapToSegment, defaultResumeOffset,
                        defaultRestrict, defaultSnap, synthProgramDateTime);
            }
        }
    }

    /**
     * A timeline-placed interstitial break (content offset → START-DATE).
     */
    public static final class InterstitialBreak {
        public final String id;
        public final float offsetSec;
        public final float durationSec;
        public final String assetUri;
        public final String assetList;
        public final Float resumeOffset;
        public final String restrict;
        public final String snap;
        public final Float playoutLimit;
        public final Boolean contentMayVary;
        public final String timelineOccupies;
        public final String timelineStyle;

        public InterstitialBreak(String id, float offsetSec, float durationSec, String assetUri) {
            this(id, offsetSec, durationSec, assetUri, null, null, null, null, null, null, null, null);
        }

        public InterstitialBreak(String id, float offsetSec, float durationSec, String assetUri,
                                 String assetList, Float resumeOffset, String restrict, String snap,
                                 Float playoutLimit, Boolean contentMayVary,
                                 String timelineOccupies, String timelineStyle) {
            this.id = id;
            this.offsetSec = offsetSec;
            this.durationSec = durationSec;
            this.assetUri = assetUri;
            this.assetList = assetList;
            this.resumeOffset = resumeOffset;
            this.restrict = restrict;
            this.snap = snap;
            this.playoutLimit = playoutLimit;
            this.contentMayVary = contentMayVary;
            this.timelineOccupies = timelineOccupies;
            this.timelineStyle = timelineStyle;
        }
    }

    /** Callback that maps an absolute remote URI to a proxied / rewritten URI. */
    public interface UriMapper {
        String map(String absoluteUri);
    }

    public static Playlist parse(byte[] body, Encoding encoding) throws IOException, ParseException, PlaylistException {
        return parse(new ByteArrayInputStream(body), encoding);
    }

    public static Playlist parse(InputStream in, Encoding encoding) throws IOException, ParseException, PlaylistException {
        return new PlaylistParser(in, Format.EXT_M3U, encoding, ParsingMode.LENIENT).parse();
    }

    public static byte[] write(Playlist playlist, Encoding encoding)
            throws IOException, ParseException, PlaylistException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PlaylistWriter(out, Format.EXT_M3U, encoding).write(playlist);
        return out.toByteArray();
    }

    public static String writeToString(Playlist playlist, Encoding encoding)
            throws IOException, ParseException, PlaylistException {
        try {
            return new String(write(playlist, encoding), encoding.value);
        } catch (UnsupportedEncodingException e) {
            throw new IOException(e);
        }
    }

    /**
     * Inject EXT-X-START and interstitial EXT-X-DATERANGE tags into a media playlist.
     * Master playlists are returned unchanged.
     */
    public static Playlist injectMediaTags(Playlist playlist, InjectConfig config) {
        if (playlist == null || !playlist.hasMediaPlaylist() || config == null) {
            return playlist;
        }
        if (config.startOverride == null && config.breaks.isEmpty()) {
            return playlist;
        }

        MediaPlaylist media = playlist.getMediaPlaylist();
        List<TrackData> tracks = media.getTracks();
        Timeline timeline = parseTimeline(tracks);

        String basePdt = timeline.firstProgramDateTime;
        long basePdtMs = timeline.firstProgramDateTimeMs;
        boolean needSynthPdt = !config.breaks.isEmpty() && basePdt == null;
        if (needSynthPdt) {
            basePdt = config.synthProgramDateTime;
            basePdtMs = parseIsoToMs(basePdt);
            if (basePdtMs < 0) {
                basePdt = DEFAULT_SYNTH_PDT;
                basePdtMs = DEFAULT_SYNTH_PDT_MS;
            }
        }

        List<DateRangeData> dateRanges = new ArrayList<DateRangeData>();
        // Keep non-user-ad date ranges from the original playlist
        if (media.hasDateRanges()) {
            for (DateRangeData dr : media.getDateRanges()) {
                if (dr.getId() != null && dr.getId().startsWith(USER_AD_ID_PREFIX)) {
                    continue;
                }
                dateRanges.add(dr);
            }
        }

        List<InterstitialBreak> sorted = new ArrayList<InterstitialBreak>(config.breaks);
        Collections.sort(sorted, new Comparator<InterstitialBreak>() {
            @Override
            public int compare(InterstitialBreak a, InterstitialBreak b) {
                return Float.compare(a.offsetSec, b.offsetSec);
            }
        });

        int i = 0;
        for (InterstitialBreak br : sorted) {
            i++;
            float off = br.offsetSec;
            if (config.snapToSegment) {
                off = snapOffset(off, timeline.segmentStarts);
            }
            String id = br.id != null && br.id.length() > 0 ? br.id : (USER_AD_ID_PREFIX + i);
            long startMs = basePdtMs + Math.round(off * 1000.0);
            String startDate = toIsoDate(startMs);

            Float resume = br.resumeOffset != null ? br.resumeOffset : config.defaultResumeOffset;
            String restrict = br.restrict != null ? br.restrict : config.defaultRestrict;
            String snap = br.snap != null ? br.snap : config.defaultSnap;

            DateRangeData.Builder b = new DateRangeData.Builder()
                    .withId(id)
                    .withClassAttribute(Constants.INTERSTITIAL_CLASS)
                    .withStartDate(startDate);
            if (br.durationSec > 0) {
                b.withDuration(br.durationSec);
            }
            if (br.assetUri != null && br.assetUri.length() > 0) {
                b.withAssetUri(br.assetUri);
            }
            if (br.assetList != null && br.assetList.length() > 0) {
                b.withAssetList(br.assetList);
            }
            if (resume != null) {
                b.withResumeOffset(resume);
            }
            if (restrict != null && restrict.length() > 0) {
                b.withRestrict(restrict);
            }
            if (snap != null && snap.length() > 0) {
                b.withSnap(snap);
            }
            if (br.playoutLimit != null) {
                b.withPlayoutLimit(br.playoutLimit);
            }
            if (br.contentMayVary != null) {
                b.withContentMayVary(br.contentMayVary);
            }
            if (br.timelineOccupies != null) {
                b.withTimelineOccupies(br.timelineOccupies);
            }
            if (br.timelineStyle != null) {
                b.withTimelineStyle(br.timelineStyle);
            }
            dateRanges.add(b.build());
        }

        List<TrackData> newTracks = tracks;
        if (needSynthPdt && !tracks.isEmpty()) {
            newTracks = new ArrayList<TrackData>(tracks.size());
            boolean applied = false;
            for (TrackData t : tracks) {
                if (!applied && !t.hasProgramDateTime()) {
                    newTracks.add(t.buildUpon().withProgramDateTime(basePdt).build());
                    applied = true;
                } else {
                    newTracks.add(t);
                }
            }
        }

        MediaPlaylist.Builder mediaBuilder = media.buildUpon()
                .withDateRanges(dateRanges)
                .withTracks(newTracks);
        if (config.startOverride != null) {
            mediaBuilder.withStartData(config.startOverride);
        }

        int version = playlist.getCompatibilityVersion();
        if (!config.breaks.isEmpty() && version < INTERSTITIAL_MIN_VERSION) {
            version = INTERSTITIAL_MIN_VERSION;
        }

        return playlist.buildUpon()
                .withCompatibilityVersion(version)
                .withMediaPlaylist(mediaBuilder.build())
                .build();
    }

    /**
     * Rewrite all playlist / segment / key / map / interstitial URIs through {@code mapper}.
     * Relative references are resolved against {@code playlistUrl} first.
     */
    public static Playlist rewriteUris(Playlist playlist, String playlistUrl, UriMapper mapper) {
        if (playlist == null || mapper == null) {
            return playlist;
        }
        if (playlist.hasMediaPlaylist()) {
            return rewriteMediaUris(playlist, playlistUrl, mapper);
        }
        if (playlist.hasMasterPlaylist()) {
            return rewriteMasterUris(playlist, playlistUrl, mapper);
        }
        return playlist;
    }

    /**
     * Full media rewrite: inject tags (if media) then rewrite URIs.
     * Master playlists only get URI rewriting.
     */
    public static Playlist rewrite(Playlist playlist, String playlistUrl, InjectConfig config, UriMapper mapper) {
        Playlist p = playlist;
        if (p != null && p.hasMediaPlaylist() && config != null) {
            p = injectMediaTags(p, config);
        }
        if (mapper != null) {
            p = rewriteUris(p, playlistUrl, mapper);
        }
        return p;
    }

    private static Playlist rewriteMediaUris(Playlist playlist, String playlistUrl, UriMapper mapper) {
        MediaPlaylist media = playlist.getMediaPlaylist();
        List<TrackData> tracks = new ArrayList<TrackData>();
        for (TrackData t : media.getTracks()) {
            TrackData.Builder b = t.buildUpon();
            if (t.getUri() != null) {
                b.withUri(mapRef(playlistUrl, t.getUri(), mapper));
            }
            if (t.hasEncryptionData() && t.getEncryptionData().hasUri()) {
                EncryptionData e = t.getEncryptionData();
                b.withEncryptionData(e.buildUpon()
                        .withUri(mapRef(playlistUrl, e.getUri(), mapper))
                        .build());
            }
            if (t.hasMapInfo() && t.getMapInfo().getUri() != null) {
                MapInfo m = t.getMapInfo();
                b.withMapInfo(m.buildUpon()
                        .withUri(mapRef(playlistUrl, m.getUri(), mapper))
                        .build());
            }
            tracks.add(b.build());
        }

        List<DateRangeData> dateRanges = new ArrayList<DateRangeData>();
        if (media.hasDateRanges()) {
            for (DateRangeData dr : media.getDateRanges()) {
                DateRangeData.Builder b = dr.buildUpon();
                if (dr.hasAssetUri()) {
                    b.withAssetUri(mapRef(playlistUrl, dr.getAssetUri(), mapper));
                }
                if (dr.hasAssetList()) {
                    b.withAssetList(mapRef(playlistUrl, dr.getAssetList(), mapper));
                }
                dateRanges.add(b.build());
            }
        }

        MediaPlaylist.Builder mb = media.buildUpon()
                .withTracks(tracks)
                .withDateRanges(dateRanges);

        return playlist.buildUpon().withMediaPlaylist(mb.build()).build();
    }

    private static Playlist rewriteMasterUris(Playlist playlist, String playlistUrl, UriMapper mapper) {
        MasterPlaylist master = playlist.getMasterPlaylist();

        List<PlaylistData> playlists = new ArrayList<PlaylistData>();
        for (PlaylistData pd : master.getPlaylists()) {
            playlists.add(pd.buildUpon()
                    .withUri(mapRef(playlistUrl, pd.getUri(), mapper))
                    .build());
        }

        List<IFrameStreamInfo> iframes = new ArrayList<IFrameStreamInfo>();
        for (IFrameStreamInfo info : master.getIFramePlaylists()) {
            iframes.add(info.buildUpon()
                    .withUri(mapRef(playlistUrl, info.getUri(), mapper))
                    .build());
        }

        List<MediaData> mediaData = new ArrayList<MediaData>();
        for (MediaData md : master.getMediaData()) {
            if (md.hasUri()) {
                mediaData.add(md.buildUpon()
                        .withUri(mapRef(playlistUrl, md.getUri(), mapper))
                        .build());
            } else {
                mediaData.add(md);
            }
        }

        MasterPlaylist.Builder mb = master.buildUpon()
                .withPlaylists(playlists)
                .withIFramePlaylists(iframes)
                .withMediaData(mediaData);
        if (master.hasStartData()) {
            mb.withStartData(master.getStartData());
        }

        return playlist.buildUpon().withMasterPlaylist(mb.build()).build();
    }

    public static String mapRef(String playlistUrl, String ref, UriMapper mapper) {
        if (ref == null || ref.length() == 0) {
            return ref;
        }
        if (ref.regionMatches(true, 0, "data:", 0, 5)) {
            return ref;
        }
        String absolute = resolveUri(playlistUrl, ref);
        return mapper.map(absolute);
    }

    public static String resolveUri(String base, String ref) {
        if (ref == null) {
            return null;
        }
        try {
            URI refUri = new URI(ref);
            if (refUri.isAbsolute()) {
                return ref;
            }
            if (base == null || base.length() == 0) {
                return ref;
            }
            return new URI(base).resolve(refUri).toString();
        } catch (URISyntaxException e) {
            // Fallback: simple join
            if (base == null) {
                return ref;
            }
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                return ref;
            }
            if (base.endsWith("/")) {
                return base + ref;
            }
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                return base.substring(0, slash + 1) + ref;
            }
            return ref;
        }
    }

    public static String toProxyUrl(String proxyBase, String absoluteTarget) {
        String base = proxyBase.endsWith("/") ? proxyBase.substring(0, proxyBase.length() - 1) : proxyBase;
        return base + "/proxy?url=" + urlEncode(absoluteTarget);
    }

    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- timeline helpers ----------

    static final class Timeline {
        final float duration;
        final List<Float> segmentStarts;
        final String firstProgramDateTime;
        final long firstProgramDateTimeMs;

        Timeline(float duration, List<Float> segmentStarts, String firstProgramDateTime, long firstProgramDateTimeMs) {
            this.duration = duration;
            this.segmentStarts = segmentStarts;
            this.firstProgramDateTime = firstProgramDateTime;
            this.firstProgramDateTimeMs = firstProgramDateTimeMs;
        }
    }

    static Timeline parseTimeline(List<TrackData> tracks) {
        List<Float> starts = new ArrayList<Float>();
        starts.add(0f);
        float cumulative = 0f;
        String firstPdt = null;
        long firstPdtMs = -1;
        if (tracks != null) {
            for (TrackData t : tracks) {
                if (firstPdt == null && t.hasProgramDateTime()) {
                    firstPdt = t.getProgramDateTime();
                    firstPdtMs = parseIsoToMs(firstPdt);
                }
                if (t.hasTrackInfo()) {
                    TrackInfo info = t.getTrackInfo();
                    cumulative += info.duration;
                    starts.add(cumulative);
                }
            }
        }
        return new Timeline(cumulative, starts, firstPdt, firstPdtMs >= 0 ? firstPdtMs : DEFAULT_SYNTH_PDT_MS);
    }

    static float snapOffset(float offset, List<Float> segmentStarts) {
        if (segmentStarts == null || segmentStarts.isEmpty()) {
            return offset;
        }
        float best = segmentStarts.get(0);
        float bestDist = Math.abs(offset - best);
        for (Float s : segmentStarts) {
            float d = Math.abs(offset - s);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    static String toIsoDate(long ms) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(ms));
    }

    static long parseIsoToMs(String iso) {
        if (iso == null || iso.length() == 0) {
            return -1;
        }
        try {
            String s = iso.trim();
            // Support trailing Z
            if (s.endsWith("Z") || s.endsWith("z")) {
                s = s.substring(0, s.length() - 1) + "+0000";
            }
            // Normalize +00:00 → +0000 for SimpleDateFormat
            if (s.length() > 5 && (s.charAt(s.length() - 3) == ':') &&
                    (s.charAt(s.length() - 6) == '+' || s.charAt(s.length() - 6) == '-')) {
                s = s.substring(0, s.length() - 3) + s.substring(s.length() - 2);
            }
            String[] patterns = new String[] {
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "yyyy-MM-dd'T'HH:mm:ssZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS",
                    "yyyy-MM-dd'T'HH:mm:ss"
            };
            for (String p : patterns) {
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat(p, Locale.US);
                    if (!p.endsWith("Z")) {
                        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                    }
                    Date d = fmt.parse(s);
                    if (d != null) {
                        return d.getTime();
                    }
                } catch (Exception ignored) {
                    // try next
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return -1;
    }
}
