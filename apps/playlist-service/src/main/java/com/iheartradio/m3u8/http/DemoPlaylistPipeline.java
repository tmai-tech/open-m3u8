package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.InjectConfig;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.UriMapper;
import com.iheartradio.m3u8.ads.PlaylistSsaiUtil;
import com.iheartradio.m3u8.data.MasterPlaylist;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import com.iheartradio.m3u8.data.PlaylistType;
import com.iheartradio.m3u8.data.Resolution;
import com.iheartradio.m3u8.data.StreamInfo;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared demo rewrite: fetch → parse → apply(strategy) → rewrite URIs → write.
 */
public final class DemoPlaylistPipeline {

    public static final class Result {
        public final byte[] body;
        public final String kind;
        public final boolean fallback;

        public Result(byte[] body, String kind, boolean fallback) {
            this.body = body;
            this.kind = kind;
            this.fallback = fallback;
        }
    }

    private final DemoHttp.OriginFetch fetch;

    public DemoPlaylistPipeline() {
        this(new DemoHttp.OriginFetch() {
            @Override
            public DemoHttp.FetchResult fetch(String url) throws IOException {
                return DemoHttp.fetchRemote(url, null);
            }
        });
    }

    public DemoPlaylistPipeline(DemoHttp.OriginFetch fetch) {
        this.fetch = fetch;
    }

    /**
     * Apply the session strategy to a media playlist. Masters are returned unchanged.
     */
    public Playlist apply(DemoSession session, Playlist playlist) throws Exception {
        return apply(session, playlist, null);
    }

    /**
     * @param playlistUrl origin URL of {@code playlist}, used to match a content
     *                    {@code STREAM-INF} so SSAI can pick the closest ad variant
     */
    public Playlist apply(DemoSession session, Playlist playlist, String playlistUrl)
            throws Exception {
        return apply(session, playlist, playlistUrl, null);
    }

    public Playlist apply(DemoSession session, Playlist playlist, String playlistUrl,
                          String publicBase) throws Exception {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        // Never splice video ads into WebVTT. SSAI inserts cue-less empty.vtt
        // for the ad window so captions stay aligned and stay dark during ads.
        skipSilentAds(session);
        if (looksLikeSubtitles(playlist)) {
            if (session.strategy == DemoSession.Strategy.SGAI) {
                return playlist;
            }
            return stitchEmptySubtitleBreaks(session, playlist, publicBase);
        }
        if (session.strategy == DemoSession.Strategy.SGAI) {
            InjectConfig cfg = LiveCadence.shouldInject(session, playlist, playlistUrl)
                    ? LiveCadence.injectConfig(session, playlist, publicBase)
                    : session.toInjectConfig(publicBase);
            return PlaylistRewriteUtil.injectMediaTags(playlist, cfg);
        }
        Resolution target = resolveContentResolution(session, playlistUrl);
        java.util.Map<String, Playlist> ads = new java.util.HashMap<String, Playlist>();
        for (String url : session.allAdUrls()) {
            if (url == null || url.length() == 0 || ads.containsKey(url)
                    || session.isSkippedAd(url)) {
                continue;
            }
            ads.put(url, loadAdMediaPlaylist(session, url, target));
        }
        List<PlaylistSsaiUtil.AdBreak> breaks = session.toSsaiBreaks(ads);
        if (breaks.isEmpty()) {
            return playlist;
        }
        Playlist stitched = PlaylistSsaiUtil.stitch(
                playlist, breaks, PlaylistSsaiUtil.StitchOptions.defaults());
        return ensureVodHints(stitched);
    }

    public Result process(DemoSession session, String playlistUrl, String publicBase)
            throws Exception {
        long t0 = System.currentTimeMillis();
        DemoHttp.FetchResult remote = fetch.fetch(playlistUrl);
        if (DemoHttp.looksLikePlaylist(playlistUrl, remote.contentType, remote.body)) {
            DemoLog.event("origin")
                    .sid(session != null ? session.id : null)
                    .put("url", playlistUrl)
                    .put("status", remote.status)
                    .put("bytes", remote.body == null ? 0 : remote.body.length)
                    .put("ms", System.currentTimeMillis() - t0)
                    .write();
        }
        if (remote.status >= 400) {
            DemoLog.event("error")
                    .sid(session != null ? session.id : null)
                    .put("url", playlistUrl)
                    .put("status", remote.status)
                    .put("msg", "origin returned HTTP " + remote.status)
                    .write();
            throw new DemoHttp.HttpException(remote.status > 0 ? remote.status : 502,
                    "origin returned HTTP " + remote.status + " for " + playlistUrl);
        }
        if (!DemoHttp.looksLikePlaylist(playlistUrl, remote.contentType, remote.body)) {
            return new Result(remote.body, "passthrough", false);
        }

        if (session.forceVod) {
            byte[] pinned = session.getForcedVodSnapshot(playlistUrl);
            if (pinned != null) {
                DemoLog.Event pin = DemoLog.event("rewrite")
                        .sid(session.id)
                        .put("url", playlistUrl)
                        .put("kind", "media")
                        .put("strategy", session.strategy.wireName())
                        .put("forceVod", true)
                        .put("pin", true)
                        .put("fallback", false);
                DemoLog.summarizePlaylist(pin, pinned);
                pin.write();
                return new Result(pinned, "media", false);
            }
        }

        final String proxyBase = publicBase + "/s/" + session.id;
        UriMapper mapper = new UriMapper() {
            @Override
            public String map(String absoluteUri) {
                return mapOriginUri(absoluteUri, proxyBase, publicBase);
            }
        };

        try {
            Playlist playlist = PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
            if (session.forceVod) {
                playlist = snapshotAsVod(playlist);
            }
            Playlist transformed = apply(session, playlist, playlistUrl, publicBase);
            Playlist rewritten = PlaylistRewriteUtil.rewriteUris(transformed, playlistUrl, mapper);
            rewritten = stripStartTag(rewritten);
            rewritten = applyDeliveryDirectives(rewritten, playlistUrl, session);
            byte[] out = PlaylistRewriteUtil.write(rewritten, Encoding.UTF_8);
            String kind = rewritten.hasMasterPlaylist() ? "master"
                    : rewritten.hasMediaPlaylist() ? "media" : "playlist";
            Result result = pinIfForcedVod(session, playlistUrl, new Result(out, kind, false));
            logRewrite(session, playlistUrl, result, false, null);
            return result;
        } catch (ParseException e) {
            Result result = pinIfForcedVod(session, playlistUrl,
                    fallbackUriRewrite(session, remote.body, playlistUrl, mapper, e));
            logRewrite(session, playlistUrl, result, true, e);
            return result;
        } catch (PlaylistException e) {
            Result result = pinIfForcedVod(session, playlistUrl,
                    fallbackUriRewrite(session, remote.body, playlistUrl, mapper, e));
            logRewrite(session, playlistUrl, result, true, e);
            return result;
        }
    }

    private static void logRewrite(DemoSession session, String playlistUrl, Result result,
                                   boolean fallback, Exception err) {
        DemoLog.Event ev = DemoLog.event("rewrite")
                .sid(session != null ? session.id : null)
                .put("url", playlistUrl)
                .put("kind", result != null ? result.kind : "unknown")
                .put("strategy", session != null ? session.strategy.wireName() : null)
                .put("forceVod", session != null && session.forceVod)
                .put("pin", false)
                .put("fallback", fallback || (result != null && result.fallback));
        if (result != null) {
            DemoLog.summarizePlaylist(ev, result.body);
        }
        if (err != null) {
            ev.put("msg", err.getClass().getSimpleName() + ": " + err.getMessage());
        }
        ev.write();
    }

    private Result fallbackUriRewrite(DemoSession session, byte[] body, String playlistUrl,
                                      UriMapper mapper, Exception e)
            throws DemoHttp.HttpException {
        System.err.println("open-m3u8 parse/rewrite failed for " + playlistUrl + ": " + e
                + " — falling back to URI rewrite only");
        try {
            String text = new String(body, StandardCharsets.UTF_8);
            String fixed = DemoHttp.rewritePlaylistUrisText(text, playlistUrl, mapper);
            fixed = stripStartTagText(fixed);
            if (session != null && session.forceVod) {
                fixed = snapshotAsVodText(fixed);
            }
            return new Result(fixed.getBytes(StandardCharsets.UTF_8), "fallback", true);
        } catch (Exception fallbackEx) {
            throw new DemoHttp.HttpException(502,
                    "failed to parse playlist: " + e.getMessage());
        }
    }

    private static Result pinIfForcedVod(DemoSession session, String playlistUrl, Result result) {
        if (session != null && session.forceVod && result != null && result.body != null
                && !"master".equals(result.kind)) {
            session.putForcedVodSnapshot(playlistUrl, result.body);
        }
        return result;
    }

    Playlist loadAdMediaPlaylist(DemoSession session) throws Exception {
        return loadAdMediaPlaylist(session, session.adUrl, null);
    }

    Playlist loadAdMediaPlaylist(DemoSession session, String adUrl) throws Exception {
        return loadAdMediaPlaylist(session, adUrl, null);
    }

    Playlist loadAdMediaPlaylist(DemoSession session, String adUrl, Resolution target)
            throws Exception {
        if (adUrl == null || adUrl.length() == 0) {
            throw new DemoHttp.HttpException(400, "ad URL is required for ssai");
        }
        Playlist cached = session.getCachedAd(adUrl);
        if (cached != null && cached.hasMediaPlaylist()) {
            return cached;
        }
        synchronized (session.adLock()) {
            cached = session.getCachedAd(adUrl);
            if (cached != null && cached.hasMediaPlaylist()) {
                return cached;
            }
            try {
                Playlist ad = cached;
                if (ad == null) {
                    if (session.getAdLoadError() != null) {
                        throw new DemoHttp.HttpException(502, session.getAdLoadError());
                    }
                    ad = fetchAndParse(adUrl);
                    session.putCachedAd(adUrl, ad);
                }
                if (ad.hasMediaPlaylist()) {
                    Playlist resolved = PlaylistRewriteUtil.absolutizeUris(ad, adUrl);
                    session.putCachedAd(adUrl, resolved);
                    return resolved;
                }
                if (!ad.hasMasterPlaylist()) {
                    throw new IllegalStateException("ad URL is neither master nor media playlist");
                }
                List<PlaylistData> variants = ad.getMasterPlaylist().getPlaylists();
                if (variants == null || variants.isEmpty()) {
                    throw new IllegalStateException("ad master playlist has no variants");
                }
                PlaylistData chosen = pickClosestVariant(variants, target);
                String child = PlaylistRewriteUtil.resolveUri(adUrl, chosen.getUri());
                Playlist mediaCached = session.getCachedAd(child);
                if (mediaCached != null && mediaCached.hasMediaPlaylist()) {
                    return mediaCached;
                }
                Playlist media = fetchAndParse(child);
                if (!media.hasMediaPlaylist()) {
                    throw new IllegalStateException("ad variant is not a media playlist: " + child);
                }
                Playlist resolved = PlaylistRewriteUtil.absolutizeUris(media, child);
                session.putCachedAd(child, resolved);
                return resolved;
            } catch (DemoHttp.HttpException e) {
                throw e;
            } catch (Exception e) {
                String msg = "failed to load ad playlist: " + e.getMessage();
                if (cached == null) {
                    session.setAdLoadError(msg);
                }
                DemoLog.event("error")
                        .sid(session.id)
                        .put("url", adUrl)
                        .put("msg", msg)
                        .write();
                throw new DemoHttp.HttpException(502, msg);
            }
        }
    }

    void skipSilentAds(DemoSession session) {
        if (session == null || session.breaks == null) {
            return;
        }
        Resolution target = firstContentResolution(session);
        String kind = session.strategy == DemoSession.Strategy.SGAI ? "SGAI" : "SSAI";
        for (String url : session.allAdUrls()) {
            if (url == null || url.length() == 0 || session.isSkippedAd(url)) {
                continue;
            }
            try {
                Playlist adMedia = loadAdMediaPlaylist(session, url, target);
                if (!playlistHasAudio(adMedia, url)) {
                    session.skipAd(url);
                    String warn = kind + " skipped ad (no audio): " + url;
                    session.addWarning(warn);
                    DemoLog.event("warn").sid(session.id).put("url", url)
                            .put("msg", warn).write();
                }
            } catch (Exception e) {
                DemoLog.event("warn").sid(session.id).put("url", url)
                        .put("msg", "ad audio check failed: " + e.getMessage()).write();
            }
        }
    }

    /**
     * Muxed fMP4 inits contain an audio {@code soun}/{@code mp4a} handler.
     * MPEG-TS ads and unknown inits are treated as having audio (do not skip).
     */
    boolean playlistHasAudio(Playlist media, String mediaUrl) {
        if (media == null || !media.hasMediaPlaylist()) {
            return true;
        }
        String mapUri = null;
        List<TrackData> tracks = media.getMediaPlaylist().getTracks();
        if (tracks != null) {
            for (TrackData t : tracks) {
                if (t != null && t.hasMapInfo() && t.getMapInfo().getUri() != null) {
                    mapUri = t.getMapInfo().getUri();
                    break;
                }
            }
        }
        if (mapUri == null || mapUri.length() == 0) {
            return true;
        }
        String abs = PlaylistRewriteUtil.resolveUri(mediaUrl, mapUri);
        try {
            DemoHttp.FetchResult remote = fetch.fetch(abs);
            if (remote == null || remote.status >= 400 || remote.body == null) {
                return true;
            }
            return initLooksLikeItHasAudio(remote.body);
        } catch (Exception e) {
            return true;
        }
    }

    static boolean initLooksLikeItHasAudio(byte[] init) {
        if (init == null || init.length < 8) {
            return true;
        }
        String ascii = new String(init, java.nio.charset.StandardCharsets.ISO_8859_1);
        return ascii.contains("soun") || ascii.contains("mp4a");
    }

    private Playlist fetchAndParse(String url) throws Exception {
        DemoHttp.FetchResult remote = fetch.fetch(url);
        if (remote.status >= 400) {
            throw new IOException("HTTP " + remote.status + " for " + url);
        }
        return PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
    }

    Resolution resolveContentResolution(DemoSession session, String playlistUrl) {
        if (session == null || playlistUrl == null || playlistUrl.length() == 0) {
            return null;
        }
        String contentUrl = session.contentUrl;
        if (contentUrl == null || contentUrl.length() == 0) {
            return null;
        }
        Playlist master = session.getCachedContentMaster();
        if (master == null || !master.hasMasterPlaylist()) {
            try {
                Playlist parsed = fetchAndParse(contentUrl);
                if (parsed == null || !parsed.hasMasterPlaylist()) {
                    return null;
                }
                session.setCachedContentMaster(parsed);
                master = parsed;
            } catch (Exception e) {
                return null;
            }
        }
        return resolutionForPlaylistUrl(master, contentUrl, playlistUrl);
    }

    static Resolution resolutionForPlaylistUrl(Playlist master, String masterUrl, String playlistUrl) {
        if (master == null || !master.hasMasterPlaylist() || playlistUrl == null) {
            return null;
        }
        List<PlaylistData> variants = master.getMasterPlaylist().getPlaylists();
        if (variants == null) {
            return null;
        }
        for (PlaylistData pd : variants) {
            if (pd == null || pd.getUri() == null) {
                continue;
            }
            String abs = PlaylistRewriteUtil.resolveUri(masterUrl, pd.getUri());
            if (samePlaylistUrl(abs, playlistUrl)) {
                return resolutionOf(pd);
            }
        }
        return null;
    }

    static PlaylistData pickClosestVariant(List<PlaylistData> variants, Resolution target) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        if (target == null) {
            return variants.get(0);
        }
        PlaylistData best = null;
        long bestDist = Long.MAX_VALUE;
        int bestBw = Integer.MAX_VALUE;
        for (PlaylistData pd : variants) {
            Resolution res = resolutionOf(pd);
            if (res == null) {
                continue;
            }
            long dx = (long) res.width - target.width;
            long dy = (long) res.height - target.height;
            long dist = dx * dx + dy * dy;
            int bw = Integer.MAX_VALUE;
            if (pd.hasStreamInfo()) {
                int raw = pd.getStreamInfo().getBandwidth();
                if (raw > 0) {
                    bw = raw;
                }
            }
            if (best == null || dist < bestDist || (dist == bestDist && bw < bestBw)) {
                best = pd;
                bestDist = dist;
                bestBw = bw;
            }
        }
        return best != null ? best : variants.get(0);
    }

    static Resolution resolutionOf(PlaylistData pd) {
        if (pd == null || !pd.hasStreamInfo()) {
            return null;
        }
        StreamInfo info = pd.getStreamInfo();
        return info.hasResolution() ? info.getResolution() : null;
    }

    static boolean samePlaylistUrl(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return normalizePlaylistUrl(a).equals(normalizePlaylistUrl(b));
    }

    static String normalizePlaylistUrl(String url) {
        String s = url.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Drop {@code EXT-X-START} so live players join the edge instead of
     * {@code TIME-OFFSET} into the DVR (Blender uses {@code TIME-OFFSET=36}).
     */
    /**
     * Live media playlists advertise {@code CAN-SKIP-UNTIL} even when the origin
     * does not. A request carrying {@code _HLS_skip} returns only the tail plus
     * {@code EXT-X-SKIP}. Media sequence stays the sequence of the oldest segment.
     */
    static Playlist applyDeliveryDirectives(Playlist playlist, String playlistUrl) {
        return applyDeliveryDirectives(playlist, playlistUrl, null);
    }

    static Playlist applyDeliveryDirectives(Playlist playlist, String playlistUrl, DemoSession session) {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        com.iheartradio.m3u8.data.MediaPlaylist media = playlist.getMediaPlaylist();
        if (!media.isOngoing()) {
            return playlist;
        }
        if (session == null || !session.playlistDelta) {
            return stripDeltaAdvertisement(playlist);
        }
        if (media.hasSkipData()) {
            return playlist;
        }
        int target = media.getTargetDuration() > 0 ? media.getTargetDuration() : 6;
        float holdBack = LiveCadence.HOLD_BACK_TARGETS * target;
        float playout = session != null && session.livePlayoutLimit > 0f
                ? session.livePlayoutLimit : LiveCadence.DEFAULT_PLAYOUT_SEC;
        // Six target durations is the spec minimum. The tail also has to
        // contain the interstitial (one hold-back behind the edge) for the
        // whole pod, with another hold-back of slack so it is not on the boundary.
        float skipUntil = Math.max(6f * target, holdBack + playout + holdBack);
        com.iheartradio.m3u8.data.ServerControlData existing = media.hasServerControlData()
                ? media.getServerControlData() : null;
        if (existing != null && existing.hasCanSkipUntil() && existing.getCanSkipUntil() > skipUntil) {
            skipUntil = existing.getCanSkipUntil();
        }
        com.iheartradio.m3u8.data.ServerControlData.Builder control = existing != null
                ? existing.buildUpon() : new com.iheartradio.m3u8.data.ServerControlData.Builder();
        control.withCanSkipUntil(skipUntil);
        com.iheartradio.m3u8.data.MediaPlaylist.Builder mb = media.buildUpon()
                .withServerControlData(control.build());
        boolean skip = playlistUrl != null && (playlistUrl.contains("_HLS_skip=YES")
                || playlistUrl.contains("_HLS_skip=v2"));
        if (skip) {
            java.util.List<com.iheartradio.m3u8.data.TrackData> tracks = media.getTracks();
            double end = 0;
            double[] ends = new double[tracks.size()];
            for (int i = 0; i < tracks.size(); i++) {
                float dur = tracks.get(i).hasTrackInfo() ? tracks.get(i).getTrackInfo().duration : target;
                end += dur;
                ends[i] = end;
            }
            int skipped = 0;
            for (int i = 0; i < tracks.size(); i++) {
                if (end - ends[i] > skipUntil) {
                    skipped++;
                } else {
                    break;
                }
            }
            if (skipped > 0 && skipped < tracks.size()) {
                com.iheartradio.m3u8.data.MapInfo map = null;
                for (int i = 0; i < skipped; i++) {
                    if (tracks.get(i).hasMapInfo()) {
                        map = tracks.get(i).getMapInfo();
                    }
                }
                java.util.ArrayList<com.iheartradio.m3u8.data.TrackData> tail =
                        new java.util.ArrayList<com.iheartradio.m3u8.data.TrackData>(
                                tracks.subList(skipped, tracks.size()));
                if (map != null && !tail.get(0).hasMapInfo()) {
                    tail.set(0, tail.get(0).buildUpon().withMapInfo(map).build());
                }
                if (!tail.get(0).hasProgramDateTime()) {
                    String pdt = programDateAt(tracks, skipped);
                    if (pdt != null) {
                        tail.set(0, tail.get(0).buildUpon().withProgramDateTime(pdt).build());
                    }
                }
                mb.withTracks(tail);
                mb.withSkipData(new com.iheartradio.m3u8.data.SkipData.Builder()
                        .withSkippedSegments(skipped).build());
            }
        }
        int version = playlist.getCompatibilityVersion();
        if (version < 9) {
            version = 9;
        }
        return playlist.buildUpon()
                .withCompatibilityVersion(version)
                .withMediaPlaylist(mb.build())
                .build();
    }

    /** Drop CAN-SKIP-UNTIL so the player refetches the full live window. */
    static Playlist stripDeltaAdvertisement(Playlist playlist) {
        com.iheartradio.m3u8.data.MediaPlaylist media = playlist.getMediaPlaylist();
        if (!media.hasServerControlData()) {
            return playlist;
        }
        com.iheartradio.m3u8.data.ServerControlData existing = media.getServerControlData();
        if (!existing.hasCanSkipUntil() && !existing.canSkipDateranges()) {
            return playlist;
        }
        com.iheartradio.m3u8.data.ServerControlData stripped = existing.buildUpon()
                .withoutCanSkipUntil()
                .build();
        boolean empty = !stripped.hasCanSkipUntil()
                && !stripped.canSkipDateranges()
                && !stripped.hasHoldBack()
                && !stripped.hasPartHoldBack()
                && !stripped.canBlockReload();
        return playlist.buildUpon()
                .withMediaPlaylist(media.buildUpon()
                        .withServerControlData(empty ? null : stripped)
                        .build())
                .build();
    }

    /** Start time of {@code index}, carried forward from the first program date. */
    static String programDateAt(java.util.List<com.iheartradio.m3u8.data.TrackData> tracks, int index) {
        long clock = -1L;
        for (int i = 0; i <= index && i < tracks.size(); i++) {
            com.iheartradio.m3u8.data.TrackData track = tracks.get(i);
            if (track.hasProgramDateTime()) {
                long parsed = PlaylistRewriteUtil.parseIsoToMs(track.getProgramDateTime());
                if (parsed >= 0) {
                    clock = parsed;
                }
            }
            if (i == index) {
                return clock >= 0 ? PlaylistRewriteUtil.toIsoDate(clock) : null;
            }
            float dur = track.hasTrackInfo() ? track.getTrackInfo().duration : 0f;
            if (clock >= 0) {
                clock += Math.round(dur * 1000f);
            }
        }
        return null;
    }

    static Playlist stripStartTag(Playlist playlist) {
        if (playlist == null) {
            return playlist;
        }
        Playlist.Builder b = playlist.buildUpon();
        boolean changed = false;
        if (playlist.hasMediaPlaylist() && playlist.getMediaPlaylist().hasStartData()) {
            b.withMediaPlaylist(playlist.getMediaPlaylist().buildUpon().withStartData(null).build());
            changed = true;
        }
        if (playlist.hasMasterPlaylist() && playlist.getMasterPlaylist().hasStartData()) {
            b.withMasterPlaylist(playlist.getMasterPlaylist().buildUpon().withStartData(null).build());
            changed = true;
        }
        return changed ? b.build() : playlist;
    }

    static String stripStartTagText(String text) {
        if (text == null || text.indexOf("#EXT-X-START") < 0) {
            return text;
        }
        boolean crlf = text.contains("\r\n");
        String nl = crlf ? "\r\n" : "\n";
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("#EXT-X-START")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(nl);
            }
            out.append(lines[i]);
        }
        return out.toString();
    }

    /**
     * Freeze a live media playlist into a VOD snapshot of the current window:
     * {@code EXT-X-ENDLIST} + {@code PLAYLIST-TYPE:VOD}, and drop {@code EXT-X-START}
     * so playback begins at the first listed segment.
     */
    static Playlist snapshotAsVod(Playlist playlist) {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        MediaPlaylist media = playlist.getMediaPlaylist();
        MediaPlaylist.Builder b = media.buildUpon()
                .withIsOngoing(false)
                .withPlaylistType(PlaylistType.VOD);
        if (media.hasStartData()) {
            b.withStartData(null);
        }
        return playlist.buildUpon().withMediaPlaylist(b.build()).build();
    }

    /**
     * Text-level snapshot used when parse/write cannot run (unknown tags, huge
     * media-sequence, etc.). Masters are left unchanged.
     */
    static String snapshotAsVodText(String text) {
        if (text == null || text.indexOf("#EXTM3U") < 0) {
            return text;
        }
        if (text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-I-FRAME-STREAM-INF")) {
            return text;
        }
        boolean crlf = text.contains("\r\n");
        String nl = crlf ? "\r\n" : "\n";
        String[] lines = text.split("\\r?\\n", -1);
        List<String> out = new java.util.ArrayList<String>(lines.length + 2);
        boolean sawType = false;
        boolean sawEnd = false;
        int insertAfter = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#EXT-X-START")) {
                continue;
            }
            if (trimmed.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                if (!sawType) {
                    out.add("#EXT-X-PLAYLIST-TYPE:VOD");
                    sawType = true;
                }
                continue;
            }
            if (trimmed.equals("#EXT-X-ENDLIST")) {
                sawEnd = true;
            }
            out.add(line);
            if (trimmed.equals("#EXTM3U") || trimmed.startsWith("#EXT-X-VERSION:")) {
                insertAfter = out.size();
            }
        }
        if (!sawType) {
            out.add(insertAfter, "#EXT-X-PLAYLIST-TYPE:VOD");
        }
        if (!sawEnd) {
            if (!out.isEmpty() && out.get(out.size() - 1).length() == 0) {
                out.add(out.size() - 1, "#EXT-X-ENDLIST");
            } else {
                out.add("#EXT-X-ENDLIST");
            }
        }
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append(nl);
            }
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    static final String EMPTY_VTT_PATH = "/media/empty.vtt";
    static final float EMPTY_VTT_SEGMENT_SEC = 1f;

    Playlist stitchEmptySubtitleBreaks(DemoSession session, Playlist playlist, String publicBase)
            throws Exception {
        if (session == null || session.breaks == null || session.breaks.isEmpty()) {
            return playlist;
        }
        String emptyUri = emptyVttUri(publicBase);
        Map<String, Float> pods = new LinkedHashMap<String, Float>();
        for (DemoSession.Break br : session.breaks) {
            for (DemoSession.Ad ad : session.liveAds(br)) {
                String url = ad.assetUri;
                if (url == null || url.length() == 0) {
                    continue;
                }
                float maxDur = ad.durationSec > 0f ? ad.durationSec : session.maxAdDurationSec;
                if (maxDur <= 0f) {
                    maxDur = 15f;
                }
                float pod = actualAdPodDuration(session, url, maxDur);
                Float prev = pods.get(url);
                if (prev == null || pod > prev) {
                    pods.put(url, pod);
                }
            }
        }
        if (pods.isEmpty()) {
            return playlist;
        }
        Map<String, Playlist> ads = new LinkedHashMap<String, Playlist>();
        for (Map.Entry<String, Float> e : pods.entrySet()) {
            ads.put(e.getKey(), emptySubtitleAdPlaylist(emptyUri, e.getValue(), EMPTY_VTT_SEGMENT_SEC));
        }
        List<PlaylistSsaiUtil.AdBreak> breaks = session.toSsaiBreaks(ads);
        if (breaks.isEmpty()) {
            return playlist;
        }
        Playlist stitched = PlaylistSsaiUtil.stitch(
                playlist, breaks, PlaylistSsaiUtil.StitchOptions.defaults());
        // Do not shift X-TIMESTAMP-MAP. After CUE-IN the video remounts
        // content init and PTS is content time again; the empty.vtt hole
        // already keeps captions dark during the ad. Adding ssaiVttShift
        // double-counts the pod and cues resume ~ad-length late.
        return ensureVodHints(stitched);
    }

    static final String SSAI_VTT_SHIFT = "ssaiVttShift";
    private static final Pattern MPEGTS_ATTR =
            Pattern.compile("(?i)(MPEGTS\\s*[=:]\\s*)(\\d+)");

    /**
     * Dummy / sidecar VTT uses X-TIMESTAMP-MAP on the content clock. After an SSAI
     * hole those MPEGTS values still sit inside the ad window, so hls.js paints
     * cues during the break. Shift post-break VTT maps by the ad pod duration.
     */
    static Playlist applyPostBreakVttShift(Playlist playlist) {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        List<TrackData> tracks = playlist.getMediaPlaylist().getTracks();
        if (tracks == null || tracks.isEmpty()) {
            return playlist;
        }
        List<TrackData> out = new ArrayList<TrackData>(tracks.size());
        float shift = 0f;
        float adAcc = 0f;
        boolean inAd = false;
        for (TrackData t : tracks) {
            boolean empty = t != null && isEmptyVttUri(t.getUri());
            if (empty) {
                inAd = true;
                if (t.hasTrackInfo() && t.getTrackInfo().duration > 0f) {
                    adAcc += t.getTrackInfo().duration;
                }
                out.add(t);
                continue;
            }
            if (inAd) {
                shift += adAcc;
                adAcc = 0f;
                inAd = false;
            }
            if (shift > 1e-4f && t != null && isWebVttUri(t.getUri())) {
                out.add(t.buildUpon().withUri(withVttShift(t.getUri(), shift)).build());
            } else {
                out.add(t);
            }
        }
        return playlist.buildUpon()
                .withMediaPlaylist(playlist.getMediaPlaylist().buildUpon().withTracks(out).build())
                .build();
    }

    static boolean isEmptyVttUri(String uri) {
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.US);
        return lower.contains("empty.vtt") || lower.contains(EMPTY_VTT_PATH);
    }

    static String withVttShift(String uri, float shiftSec) {
        if (uri == null || uri.length() == 0 || shiftSec <= 1e-4f) {
            return uri;
        }
        if (uri.contains(SSAI_VTT_SHIFT + "=")) {
            return uri;
        }
        String q = SSAI_VTT_SHIFT + "=" + String.format(Locale.US, "%.6f", shiftSec);
        return uri + (uri.indexOf('?') >= 0 ? "&" : "?") + q;
    }

    public static float parseVttShift(String uri) {
        if (uri == null) {
            return 0f;
        }
        try {
            String query = URI.create(uri).getRawQuery();
            String raw = DemoHttp.queryParam(query, SSAI_VTT_SHIFT);
            if (raw == null || raw.length() == 0) {
                return 0f;
            }
            return Float.parseFloat(raw);
        } catch (Exception e) {
            return 0f;
        }
    }

    public static String stripVttShift(String uri) {
        if (uri == null || !uri.contains(SSAI_VTT_SHIFT)) {
            return uri;
        }
        int q = uri.indexOf('?');
        if (q < 0) {
            return uri;
        }
        String base = uri.substring(0, q);
        String[] parts = uri.substring(q + 1).split("&");
        StringBuilder keep = new StringBuilder();
        for (String part : parts) {
            if (part.startsWith(SSAI_VTT_SHIFT + "=") || part.isEmpty()) {
                continue;
            }
            if (keep.length() > 0) {
                keep.append('&');
            }
            keep.append(part);
        }
        return keep.length() == 0 ? base : base + "?" + keep;
    }

    public static String shiftWebVttMpegts(String text, float shiftSec) {
        if (text == null || shiftSec == 0f) {
            return text;
        }
        long delta = Math.round(shiftSec * 90000.0);
        if (delta == 0L) {
            return text;
        }
        Matcher m = MPEGTS_ATTR.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long next = Long.parseLong(m.group(2)) + delta;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + next));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    float actualAdPodDuration(DemoSession session, String adUrl, float maxDur) {
        try {
            Playlist ad = loadAdMediaPlaylist(session, adUrl, firstContentResolution(session));
            if (ad == null || !ad.hasMediaPlaylist()) {
                return maxDur;
            }
            List<TrackData> trimmed = PlaylistSsaiUtil.trimTracksToMaxDuration(
                    ad.getMediaPlaylist().getTracks(), maxDur);
            float sum = 0f;
            for (TrackData t : trimmed) {
                if (t != null && t.hasTrackInfo() && t.getTrackInfo().duration > 0f) {
                    sum += t.getTrackInfo().duration;
                }
            }
            return sum > 0f ? sum : maxDur;
        } catch (Exception e) {
            return maxDur;
        }
    }

    Resolution firstContentResolution(DemoSession session) {
        if (session == null || session.contentUrl == null || session.contentUrl.length() == 0) {
            return null;
        }
        Playlist master = session.getCachedContentMaster();
        if (master == null || !master.hasMasterPlaylist()) {
            try {
                Playlist parsed = fetchAndParse(session.contentUrl);
                if (parsed != null && parsed.hasMasterPlaylist()) {
                    session.setCachedContentMaster(parsed);
                    master = parsed;
                }
            } catch (Exception e) {
                return null;
            }
        }
        if (master == null || !master.hasMasterPlaylist()) {
            return null;
        }
        List<PlaylistData> variants = master.getMasterPlaylist().getPlaylists();
        if (variants == null) {
            return null;
        }
        for (PlaylistData pd : variants) {
            Resolution res = resolutionOf(pd);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    static String emptyVttUri(String publicBase) {
        if (publicBase == null || publicBase.length() == 0) {
            return "empty.vtt";
        }
        String base = publicBase.endsWith("/")
                ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
        return base + EMPTY_VTT_PATH;
    }

    static Playlist emptySubtitleAdPlaylist(String uri, float durationSec, float segmentSec) {
        float seg = segmentSec > 0.05f ? segmentSec : EMPTY_VTT_SEGMENT_SEC;
        float left = durationSec > 0f ? durationSec : seg;
        List<TrackData> tracks = new ArrayList<TrackData>();
        while (left > 1e-3f) {
            float d = Math.min(seg, left);
            tracks.add(new TrackData.Builder()
                    .withUri(uri)
                    .withTrackInfo(new TrackInfo(d, null))
                    .build());
            left -= d;
        }
        if (tracks.isEmpty()) {
            tracks.add(new TrackData.Builder()
                    .withUri(uri)
                    .withTrackInfo(new TrackInfo(seg, null))
                    .build());
        }
        int target = Math.max(1, (int) Math.ceil(seg));
        return new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(6)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(target)
                        .withMediaSequenceNumber(0)
                        .withIsOngoing(false)
                        .withTracks(tracks)
                        .build())
                .build();
    }

    static boolean looksLikeSubtitles(Playlist playlist) {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return false;
        }
        List<TrackData> tracks = playlist.getMediaPlaylist().getTracks();
        if (tracks == null || tracks.isEmpty()) {
            return false;
        }
        for (TrackData track : tracks) {
            if (track != null && isWebVttUri(track.getUri())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWebVttUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        if (path.endsWith(".vtt") || path.endsWith(".webvtt")) {
            return true;
        }
        // Rewritten proxy URIs keep the .vtt suffix on the encoded origin URL.
        return lower.contains(".vtt") || lower.contains(".webvtt");
    }

    static Playlist ensureVodHints(Playlist playlist) {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        MediaPlaylist media = playlist.getMediaPlaylist();
        if (!media.isOngoing() && !media.hasPlaylistType()) {
            MediaPlaylist m2 = media.buildUpon()
                    .withPlaylistType(PlaylistType.VOD)
                    .build();
            return playlist.buildUpon().withMediaPlaylist(m2).build();
        }
        return playlist;
    }

    static boolean isOwnSessionUrl(String uri, String sessionBase) {
        if (uri == null || sessionBase == null || sessionBase.length() == 0) {
            return false;
        }
        return uri.equals(sessionBase) || uri.startsWith(sessionBase + "/");
    }

    /**
     * Playlists stay on {@code /s/{id}/proxy} so SSAI/SGAI rewrite still runs.
     * Local {@code /media/} segments (m4s, init, vtt, …) are emitted as same-origin
     * paths so the player does not round-trip them through the session proxy.
     * Remote and live origins are unchanged.
     */
    static String mapOriginUri(String absoluteUri, String proxyBase, String publicBase) {
        if (absoluteUri == null || absoluteUri.length() == 0) {
            return absoluteUri;
        }
        if (isOwnSessionUrl(absoluteUri, proxyBase)) {
            return absoluteUri;
        }
        if (isLocalMediaAsset(absoluteUri, publicBase)) {
            String href = toLocalMediaHref(absoluteUri);
            if (href != null && href.length() > 0) {
                return href;
            }
        }
        return PlaylistRewriteUtil.toProxyUrl(proxyBase, absoluteUri);
    }

    static boolean isLocalMediaAsset(String absoluteUri, String publicBase) {
        if (absoluteUri == null || DemoHttp.urlLooksLikePlaylist(absoluteUri)) {
            return false;
        }
        URI u;
        try {
            u = URI.create(absoluteUri);
        } catch (Exception e) {
            return false;
        }
        String path = u.getPath();
        if (path == null || !path.startsWith("/media/")) {
            return false;
        }
        String host = u.getHost();
        if (host == null || host.length() == 0) {
            return true;
        }
        String h = host.toLowerCase(Locale.US);
        if ("127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)) {
            return true;
        }
        if (publicBase != null && publicBase.length() > 0) {
            try {
                String pbHost = URI.create(publicBase).getHost();
                if (pbHost != null && pbHost.equalsIgnoreCase(host)) {
                    return true;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return false;
    }

    static String toLocalMediaHref(String absoluteUri) {
        try {
            URI u = URI.create(absoluteUri);
            String path = u.getPath();
            if (path == null || path.length() == 0) {
                return null;
            }
            String q = u.getRawQuery();
            if (q != null && q.length() > 0) {
                return path + "?" + q;
            }
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Apple interstitial asset list for one break. Unknown break → {@code null} (404).
     * Skipped creatives are omitted; an empty {@code ASSETS} array is still 200.
     */
    public String assetListJson(DemoSession session, String breakId, String publicBase) {
        return assetListJson(session, breakId, publicBase, 0d);
    }

    public String assetListJson(DemoSession session, String breakId, String publicBase,
                                double hlsStartOffset) {
        return assetListJson(session, breakId, publicBase, hlsStartOffset, null);
    }

    public String assetListJson(DemoSession session, String breakId, String publicBase,
                                double hlsStartOffset, String hlsPrimaryId) {
        if (session == null || breakId == null) {
            return null;
        }
        skipSilentAds(session);
        if (LiveCadence.isBreak(session, breakId)) {
            return LiveCadence.assetListJson(this, session, publicBase, hlsStartOffset, hlsPrimaryId, breakId);
        }
        DemoSession.Break found = session.findBreak(breakId);
        if (found == null) {
            return null;
        }
        String base = publicBase == null ? "" : publicBase;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String proxyBase = base + "/s/" + session.id;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ASSETS\":[");
        List<DemoSession.Ad> live = session.liveAds(found);
        for (int i = 0; i < live.size(); i++) {
            DemoSession.Ad ad = live.get(i);
            float maxDur = ad.durationSec > 0f ? ad.durationSec : session.maxAdDurationSec;
            if (maxDur <= 0f) {
                maxDur = 15f;
            }
            float dur = actualAdPodDuration(session, ad.assetUri, maxDur);
            String uri = PlaylistRewriteUtil.toProxyUrl(proxyBase, ad.assetUri);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"URI\":").append(DemoHttp.jsonString(uri))
                    .append(",\"DURATION\":").append(dur)
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}
