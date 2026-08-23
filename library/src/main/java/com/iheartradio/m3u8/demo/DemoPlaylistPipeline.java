package com.iheartradio.m3u8.demo;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.PlaylistRewriteUtil;
import com.iheartradio.m3u8.PlaylistRewriteUtil.UriMapper;
import com.iheartradio.m3u8.PlaylistSsaiUtil;
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
        DemoHttp.FetchResult remote = fetch.fetch(playlistUrl);
        if (remote.status >= 400) {
            throw new DemoHttp.HttpException(remote.status > 0 ? remote.status : 502,
                    "origin returned HTTP " + remote.status + " for " + playlistUrl);
        }
        if (!DemoHttp.looksLikePlaylist(playlistUrl, remote.contentType, remote.body)) {
            return new Result(remote.body, "passthrough", false);
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
            Playlist transformed = apply(session, playlist);
            Playlist rewritten = PlaylistRewriteUtil.rewriteUris(transformed, playlistUrl, mapper);
            byte[] out = PlaylistRewriteUtil.write(rewritten, Encoding.UTF_8);
            String kind = rewritten.hasMasterPlaylist() ? "master"
                    : rewritten.hasMediaPlaylist() ? "media" : "playlist";
            return new Result(out, kind, false);
        } catch (ParseException e) {
            return fallbackUriRewrite(remote.body, playlistUrl, mapper, e);
        } catch (PlaylistException e) {
            return fallbackUriRewrite(remote.body, playlistUrl, mapper, e);
        }
    }

    private Result fallbackUriRewrite(byte[] body, String playlistUrl, UriMapper mapper, Exception e)
            throws DemoHttp.HttpException {
        System.err.println("open-m3u8 parse/rewrite failed for " + playlistUrl + ": " + e
                + " — falling back to URI rewrite only");
        try {
            String text = new String(body, StandardCharsets.UTF_8);
            String fixed = DemoHttp.rewritePlaylistUrisText(text, playlistUrl, mapper);
            return new Result(fixed.getBytes(StandardCharsets.UTF_8), "fallback", true);
        } catch (Exception fallbackEx) {
            throw new DemoHttp.HttpException(502,
                    "failed to parse playlist: " + e.getMessage());
        }
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
