package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.UriMapper;
import com.iheartradio.m3u8.ads.PlaylistSsaiUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import com.iheartradio.m3u8.data.PlaylistType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        if (session.strategy == DemoSession.Strategy.SGAI) {
            return PlaylistRewriteUtil.injectMediaTags(playlist, session.toInjectConfig());
        }
        java.util.Map<String, Playlist> ads = new java.util.HashMap<String, Playlist>();
        for (DemoSession.Break br : session.breaks) {
            String url = session.resolveAdUrl(br);
            if (url == null || url.length() == 0 || ads.containsKey(url)) {
                continue;
            }
            ads.put(url, loadAdMediaPlaylist(session, url));
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
                return PlaylistRewriteUtil.toProxyUrl(proxyBase, absoluteUri);
            }
        };

        try {
            Playlist playlist = PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
            if (session.forceVod) {
                playlist = snapshotAsVod(playlist);
            }
            Playlist transformed = apply(session, playlist);
            Playlist rewritten = PlaylistRewriteUtil.rewriteUris(transformed, playlistUrl, mapper);
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
        return loadAdMediaPlaylist(session, session.adUrl);
    }

    Playlist loadAdMediaPlaylist(DemoSession session, String adUrl) throws Exception {
        if (adUrl == null || adUrl.length() == 0) {
            throw new DemoHttp.HttpException(400, "ad URL is required for ssai");
        }
        Playlist cached = session.getCachedAd(adUrl);
        if (cached != null) {
            return cached;
        }
        synchronized (session.adLock()) {
            cached = session.getCachedAd(adUrl);
            if (cached != null) {
                return cached;
            }
            if (session.getAdLoadError() != null) {
                throw new DemoHttp.HttpException(502, session.getAdLoadError());
            }
            try {
                Playlist ad = fetchAndParse(adUrl);
                Playlist resolved;
                if (ad.hasMediaPlaylist()) {
                    resolved = PlaylistRewriteUtil.absolutizeUris(ad, adUrl);
                } else if (ad.hasMasterPlaylist()) {
                    List<PlaylistData> variants = ad.getMasterPlaylist().getPlaylists();
                    if (variants == null || variants.isEmpty()) {
                        throw new IllegalStateException("ad master playlist has no variants");
                    }
                    String child = PlaylistRewriteUtil.resolveUri(adUrl, variants.get(0).getUri());
                    Playlist media = fetchAndParse(child);
                    if (!media.hasMediaPlaylist()) {
                        throw new IllegalStateException("ad variant is not a media playlist: " + child);
                    }
                    resolved = PlaylistRewriteUtil.absolutizeUris(media, child);
                } else {
                    throw new IllegalStateException("ad URL is neither master nor media playlist");
                }
                session.putCachedAd(adUrl, resolved);
                return resolved;
            } catch (DemoHttp.HttpException e) {
                session.setAdLoadError(e.getMessage());
                throw e;
            } catch (Exception e) {
                String msg = "failed to load ad playlist: " + e.getMessage();
                session.setAdLoadError(msg);
                DemoLog.event("error")
                        .sid(session.id)
                        .put("url", adUrl)
                        .put("msg", msg)
                        .write();
                throw new DemoHttp.HttpException(502, msg);
            }
        }
    }

    private Playlist fetchAndParse(String url) throws Exception {
        DemoHttp.FetchResult remote = fetch.fetch(url);
        if (remote.status >= 400) {
            throw new IOException("HTTP " + remote.status + " for " + url);
        }
        return PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
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
}
