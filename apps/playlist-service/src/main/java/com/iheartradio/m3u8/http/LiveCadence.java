package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.InjectConfig;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.InterstitialBreak;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling-live SGAI: one interstitial per cadence interval on video media
 * playlists. START-DATE is the end of the last segment plus {@link #LEAD_MS},
 * frozen for the interval so live reloads do not slide it. The tag stays
 * while that pod can still be playing, even if the window has slid past it.
 */
final class LiveCadence {

    static final int HOLD_BACK_TARGETS = 3;
    /** Break is this far after the live edge, so playback runs before the pod. */
    static final long LEAD_MS = 10_000L;
    static final float DEFAULT_INTERVAL_SEC = 60f;
    static final float DEFAULT_PLAYOUT_SEC = 15f;

    private LiveCadence() {}

    static boolean uses(DemoSession session) {
        return session != null
                && session.strategy == DemoSession.Strategy.SGAI
                && !session.forceVod
                && session.liveCadenceSec > 0f;
    }

    static boolean isBreak(DemoSession session, String breakId) {
        return uses(session)
                && breakId != null
                && breakId.startsWith(PlaylistRewriteUtil.LIVE_BREAK_ID_PREFIX);
    }

    static boolean shouldInject(DemoSession session, Playlist playlist, String playlistUrl) {
        if (!uses(session) || playlist == null || !playlist.hasMediaPlaylist()) {
            return false;
        }
        MediaPlaylist media = playlist.getMediaPlaylist();
        if (!media.isOngoing() || media.isIframesOnly() || isAudioOrKeyframeUrl(playlistUrl)) {
            return false;
        }
        if (playlistUrl != null && session.adUrl != null && session.adUrl.length() > 0) {
            if (playlistUrl.equals(session.adUrl) || playlistUrl.startsWith(session.adUrl)) {
                return false;
            }
            String adDir = session.adUrl.replaceAll("/[^/]+$", "/");
            if (adDir.length() > 8 && playlistUrl.startsWith(adDir)) {
                return false;
            }
        }
        return true;
    }

    static InjectConfig injectConfig(DemoSession session, Playlist playlist, String publicBase) {
        List<InterstitialBreak> injected = new ArrayList<InterstitialBreak>();
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return InjectConfig.builder().withBreaks(injected).withDefaultResumeOffset(null).build();
        }
        MediaPlaylist media = playlist.getMediaPlaylist();
        PlaylistRewriteUtil.Timeline timeline = PlaylistRewriteUtil.parseTimeline(media.getTracks());
        long baseMs;
        String synth = null;
        if (timeline.firstProgramDateTime != null) {
            baseMs = timeline.firstProgramDateTimeMs;
        } else {
            long edge = System.currentTimeMillis();
            baseMs = edge - Math.round(timeline.duration * 1000.0);
            synth = PlaylistRewriteUtil.toIsoDate(baseMs);
        }
        long intervalMs = Math.round(session.liveCadenceSec * 1000.0);
        if (intervalMs < 1000L) {
            intervalMs = Math.round(DEFAULT_INTERVAL_SEC * 1000.0);
        }
        float limit = session.livePlayoutLimit > 0f ? session.livePlayoutLimit : DEFAULT_PLAYOUT_SEC;
        long lastPdtMs = timeline.lastSegmentPdtMs;
        int target = media.getTargetDuration();
        if (target <= 0) {
            target = 6;
        }
        long lastDurMs = target * 1000L;
        java.util.List<com.iheartradio.m3u8.data.TrackData> tracks = media.getTracks();
        if (tracks != null && !tracks.isEmpty()) {
            com.iheartradio.m3u8.data.TrackData last = tracks.get(tracks.size() - 1);
            if (last.hasTrackInfo()) {
                lastDurMs = Math.round(last.getTrackInfo().duration * 1000.0);
            }
        }
        long startMs = lastPdtMs + lastDurMs + LEAD_MS;
        long idSec = (lastPdtMs / intervalMs) * (intervalMs / 1000L);
        String id = PlaylistRewriteUtil.LIVE_BREAK_ID_PREFIX + idSec;
        startMs = session.freezeLiveCadenceStart(id, startMs);
        float off = (startMs - baseMs) / 1000f;
        // Keep the tag while the pod can still be playing. Dropping it as soon
        // as the window start passes START-DATE makes hls.js cancel the ad.
        boolean podStillCurrent = startMs + Math.round(limit * 1000f) > baseMs;
        String host = publicBase == null ? "" : publicBase;
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (off >= -0.01f || podStillCurrent) {
            String list = host + "/s/" + session.id + "/assets/" + id + ".json";
            injected.add(new InterstitialBreak(id, off, limit, null, list,
                    null, session.defaultRestrict, session.defaultSnap,
                    limit, null, null, null, null));
        }
        InjectConfig.Builder b = InjectConfig.builder()
                .withBreaks(injected)
                .withSnapToSegment(false)
                .withDefaultResumeOffset(null)
                .withDefaultRestrict(session.defaultRestrict)
                .withDefaultSnap(session.defaultSnap)
                .withDefaultCue(null);
        if (synth != null) {
            b.withSynthProgramDateTime(synth);
        }
        return b.build();
    }

    static final String EMPTY_ASSETS = "{\"ASSETS\":[]}";

    static String assetListJson(DemoPlaylistPipeline pipeline, DemoSession session, String publicBase) {
        return assetListJson(pipeline, session, publicBase, 0d);
    }

    static String assetListJson(DemoPlaylistPipeline pipeline, DemoSession session, String publicBase,
                                double hlsStartOffset) {
        return assetListJson(pipeline, session, publicBase, hlsStartOffset, null);
    }

    static String assetListJson(DemoPlaylistPipeline pipeline, DemoSession session, String publicBase,
                                double hlsStartOffset, String hlsPrimaryId) {
        if (hlsStartOffset > 0d || Double.isNaN(hlsStartOffset)) {
            DemoLog.event("assets").sid(session.id)
                    .put("break", "live")
                    .put("primaryId", hlsPrimaryId)
                    .put("startOffset", hlsStartOffset)
                    .put("skipAds", true)
                    .put("msg", "_HLS_start_offset greater than 0; returning empty ASSETS")
                    .write();
            return EMPTY_ASSETS;
        }
        String uri = session.adUrl;
        if (uri == null || uri.length() == 0 || session.isSkippedAd(uri)) {
            return EMPTY_ASSETS;
        }
        String host = publicBase == null ? "" : publicBase;
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        String proxyBase = host + "/s/" + session.id;
        float maxDur = session.livePlayoutLimit > 0f ? session.livePlayoutLimit : DEFAULT_PLAYOUT_SEC;
        float dur = pipeline.actualAdPodDuration(session, uri, maxDur);
        String proxied = PlaylistRewriteUtil.toProxyUrl(proxyBase, uri);
        return "{\"ASSETS\":[{\"URI\":" + DemoHttp.jsonString(proxied)
                + ",\"DURATION\":" + dur + "}]}";
    }

    static boolean isAudioOrKeyframeUrl(String playlistUrl) {
        if (playlistUrl == null) {
            return false;
        }
        String u = playlistUrl.toLowerCase();
        return u.contains("audio_") || u.contains("audio-") || u.contains("/audio")
                || u.contains("keyframes/") || u.contains("iframe");
    }
}
