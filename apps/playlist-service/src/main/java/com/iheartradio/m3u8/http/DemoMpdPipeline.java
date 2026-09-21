package com.iheartradio.m3u8.http;

import com.iheartradio.dash.MpdIo;
import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.MpdSsaiUtil;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MapInfo;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import io.lindstrom.mpd.data.AdaptationSet;
import io.lindstrom.mpd.data.BaseURL;
import io.lindstrom.mpd.data.MPD;
import io.lindstrom.mpd.data.Period;
import io.lindstrom.mpd.data.Representation;
import io.lindstrom.mpd.data.Segment;
import io.lindstrom.mpd.data.SegmentTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetch origin MPD → SSAI Period stitch → BaseURL rewrite. Same role as
 * {@link DemoPlaylistPipeline} for HLS.
 */
public final class DemoMpdPipeline {

    public static final class Result {
        public final byte[] body;

        public Result(byte[] body) {
            this.body = body;
        }
    }

    private final DemoHttp.OriginFetch fetch;
    private final MpdIo io;

    public DemoMpdPipeline() {
        this(url -> DemoHttp.fetchRemote(url, null), new MpdIo());
    }

    public DemoMpdPipeline(DemoHttp.OriginFetch fetch, MpdIo io) {
        this.fetch = fetch;
        this.io = io == null ? new MpdIo() : io;
    }

    public Result process(DemoSession session, String mpdUrl, String publicBase) throws Exception {
        DemoHttp.FetchResult remote = fetch.fetch(mpdUrl);
        if (remote.status >= 400) {
            throw new DemoHttp.HttpException(remote.status > 0 ? remote.status : 502,
                    "origin returned HTTP " + remote.status + " for " + mpdUrl);
        }
        String xml = new String(remote.body == null ? new byte[0] : remote.body, StandardCharsets.UTF_8);
        MPD mpd = io.parse(xml);
        String contentDir = directoryPath(mpdUrl);
        mpd = withPeriodBase(mpd, contentDir);

        if (session != null && session.strategy == DemoSession.Strategy.SSAI) {
            List<MpdSsaiUtil.AdBreak> ads = loadAdBreaks(session, publicBase);
            if (!ads.isEmpty()) {
                mpd = MpdSsaiUtil.stitch(mpd, ads);
            }
        }
        return new Result(io.writeBytes(mpd));
    }

    List<MpdSsaiUtil.AdBreak> loadAdBreaks(DemoSession session, String publicBase) {
        List<MpdSsaiUtil.AdBreak> out = new ArrayList<MpdSsaiUtil.AdBreak>();
        if (session.breaks.isEmpty()) {
            return out;
        }
        int i = 0;
        for (DemoSession.Break br : session.breaks) {
            int j = 0;
            for (DemoSession.Ad creative : session.liveAds(br)) {
                i++;
                j++;
                if (session.isSkippedAd(creative.assetUri)) {
                    continue;
                }
                Period period = loadAdPeriod(creative.assetUri);
                if (period == null) {
                    session.addWarning("DASH SSAI skipped ad with no MPD/CMAF: " + creative.assetUri);
                    continue;
                }
                String bid = br.id != null && br.id.length() > 0 ? br.id : ("ad-" + i);
                if (br.ads.size() > 1) {
                    bid = bid + "-" + j;
                }
                out.add(new MpdSsaiUtil.AdBreak(bid, br.offsetSec, period));
            }
        }
        return out;
    }

    Period loadAdPeriod(String assetUri) {
        if (assetUri == null || assetUri.length() == 0) {
            return null;
        }
        String mpdUrl = siblingMpd(assetUri);
        try {
            DemoHttp.FetchResult r = fetch.fetch(mpdUrl);
            if (r.status < 400 && r.body != null && r.body.length > 0) {
                MPD ad = io.parse(new String(r.body, StandardCharsets.UTF_8));
                if (ad.getPeriods() != null && !ad.getPeriods().isEmpty()) {
                    return withBase(ad.getPeriods().get(0), directoryPath(mpdUrl));
                }
            }
        } catch (Exception ignored) {
            // fall through to HLS CMAF
        }
        return periodFromHls(assetUri);
    }

    Period periodFromHls(String playlistUrl) {
        try {
            DemoHttp.FetchResult r = fetch.fetch(playlistUrl);
            if (r.status >= 400 || r.body == null) {
                return null;
            }
            Playlist parsed = PlaylistRewriteUtil.parse(r.body, Encoding.UTF_8);
            if (parsed == null || !parsed.hasMediaPlaylist()) {
                if (parsed != null && parsed.hasMasterPlaylist()
                        && parsed.getMasterPlaylist().getPlaylists() != null
                        && !parsed.getMasterPlaylist().getPlaylists().isEmpty()) {
                    String child = parsed.getMasterPlaylist().getPlaylists().get(0).getUri();
                    return periodFromHls(resolve(playlistUrl, child));
                }
                return null;
            }
            MediaPlaylist media = parsed.getMediaPlaylist();
            List<TrackData> tracks = media.getTracks();
            if (tracks == null || tracks.isEmpty()) {
                return null;
            }
            String init = "init.m4s";
            String pattern = null;
            long startNumber = 0L;
            List<Segment> timeline = new ArrayList<Segment>();
            long t = 0L;
            boolean first = true;
            for (TrackData tr : tracks) {
                if (tr.hasMapInfo()) {
                    MapInfo map = tr.getMapInfo();
                    if (map != null && map.getUri() != null) {
                        init = map.getUri();
                    }
                }
                if (pattern == null && tr.getUri() != null) {
                    String name = tr.getUri();
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) {
                        name = name.substring(slash + 1);
                    }
                    pattern = numberTemplate(name);
                    startNumber = numberFromName(name);
                }
                float dur = tr.hasTrackInfo() ? tr.getTrackInfo().duration : 4f;
                long d = Math.max(1L, Math.round(dur * 1000.0));
                Segment.Builder sb = Segment.builder().withD(d);
                if (first) {
                    sb.withT(0L);
                    first = false;
                }
                timeline.add(sb.build());
                t += d;
            }
            if (pattern == null) {
                pattern = "ad_$Number$.m4s";
            }
            SegmentTemplate st = SegmentTemplate.builder()
                    .withTimescale(1000L)
                    .withInitialization(init)
                    .withMedia(pattern)
                    .withStartNumber(startNumber)
                    .withSegmentTimeline(compressSame(timeline))
                    .build();
            Representation rep = Representation.builder()
                    .withId("ad")
                    .withBandwidth(500_000L)
                    .withSegmentTemplate(st)
                    .build();
            AdaptationSet as = AdaptationSet.builder()
                    .withId(0L)
                    .withContentType("video")
                    .withMimeType("video/mp4")
                    .withRepresentations(Collections.singletonList(rep))
                    .build();
            return withBase(Period.builder()
                    .withId("ad")
                    .withDuration(Duration.ofMillis(t))
                    .withAdaptationSets(Collections.singletonList(as))
                    .build(), directoryPath(playlistUrl));
        } catch (Exception e) {
            return null;
        }
    }

    static List<Segment> compressSame(List<Segment> raw) {
        List<Segment> out = new ArrayList<Segment>();
        int i = 0;
        boolean first = true;
        while (i < raw.size()) {
            long d = raw.get(i).getD();
            long r = 0L;
            int j = i + 1;
            while (j < raw.size() && raw.get(j).getD() == d) {
                r++;
                j++;
            }
            Segment.Builder b = Segment.builder().withD(d);
            if (first) {
                b.withT(0L);
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

    static MPD withPeriodBase(MPD mpd, String dir) {
        if (dir == null || dir.length() == 0 || mpd.getPeriods() == null) {
            return mpd;
        }
        List<Period> periods = new ArrayList<Period>();
        for (Period p : mpd.getPeriods()) {
            periods.add(withBase(p, dir));
        }
        return mpd.buildUpon().withPeriods(periods).build();
    }

    static Period withBase(Period period, String dir) {
        if (dir == null || dir.length() == 0) {
            return period;
        }
        return period.buildUpon()
                .withBaseURLs(Collections.singletonList(BaseURL.builder().withValue(dir).build()))
                .build();
    }

    static String siblingMpd(String url) {
        if (url == null) {
            return url;
        }
        if (url.contains(".mpd")) {
            return url;
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        int slash = base.lastIndexOf('/');
        String dir = slash >= 0 ? base.substring(0, slash + 1) : "";
        return dir + "manifest.mpd";
    }

    static String directoryPath(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            if (path == null || path.length() == 0) {
                return "/";
            }
            int slash = path.lastIndexOf('/');
            if (slash < 0) {
                return "/";
            }
            return path.substring(0, slash + 1);
        } catch (Exception e) {
            return "/";
        }
    }

    static String resolve(String base, String rel) {
        try {
            return URI.create(base).resolve(rel).toString();
        } catch (Exception e) {
            return rel;
        }
    }

    static String numberTemplate(String file) {
        int under = file.lastIndexOf('_');
        int dot = file.lastIndexOf('.');
        if (under >= 0 && dot > under) {
            String mid = file.substring(under + 1, dot);
            if (mid.matches("\\d+")) {
                return file.substring(0, under + 1) + "$Number$" + file.substring(dot);
            }
        }
        return file;
    }

    static long numberFromName(String file) {
        int under = file.lastIndexOf('_');
        int dot = file.lastIndexOf('.');
        if (under >= 0 && dot > under) {
            try {
                return Long.parseLong(file.substring(under + 1, dot));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
