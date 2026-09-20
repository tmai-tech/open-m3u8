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
 * playlists. START-DATE is last-segment PDT minus {@link #HOLD_BACK_TARGETS}
 * target durations, frozen for the interval so live reloads do not slide it.
 */
final class LiveCadence {

    static final int HOLD_BACK_TARGETS = 3;
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
        long startMs = lastPdtMs - (long) HOLD_BACK_TARGETS * target * 1000L;
        if (startMs < baseMs) {
            startMs = baseMs;
        }
        long idSec = (lastPdtMs / intervalMs) * (intervalMs / 1000L);
        String id = PlaylistRewriteUtil.LIVE_BREAK_ID_PREFIX + idSec;
        startMs = session.freezeLiveCadenceStart(id, startMs);
        float off = (startMs - baseMs) / 1000f;
        String host = publicBase == null ? "" : publicBase;
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (off >= -0.01f) {
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

    static String assetListJson(DemoPlaylistPipeline pipeline, DemoSession session, String publicBase) {
        String uri = session.adUrl;
        if (uri == null || uri.length() == 0 || session.isSkippedAd(uri)) {
            return "{\"ASSETS\":[]}";
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
