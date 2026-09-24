package com.iheartradio.m3u8.http;

import com.iheartradio.dash.MpdIo;
import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.MpdSgaiUtil;
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
import java.util.function.LongSupplier;

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
    private final LongSupplier clock;

    public DemoMpdPipeline() {
        this(url -> DemoHttp.fetchRemote(url, null), new MpdIo(), null);
    }

    public DemoMpdPipeline(DemoHttp.OriginFetch fetch, MpdIo io) {
        this(fetch, io, null);
    }

    public DemoMpdPipeline(DemoHttp.OriginFetch fetch, MpdIo io, LongSupplier clock) {
        this.fetch = fetch;
        this.io = io == null ? new MpdIo() : io;
        this.clock = clock == null ? new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        } : clock;
    }

    public Result process(DemoSession session, String mpdUrl, String publicBase) throws Exception {
        DemoHttp.FetchResult remote = fetch.fetch(mpdUrl);
        if (remote.status >= 400) {
            throw new DemoHttp.HttpException(remote.status > 0 ? remote.status : 502,
                    "origin returned HTTP " + remote.status + " for " + mpdUrl);
        }
        String xml = new String(remote.body == null ? new byte[0] : remote.body, StandardCharsets.UTF_8);
        MPD mpd = io.parse(xml);
        mpd = withPeriodBase(mpd, periodBase(mpdUrl));

        if (session != null && MpdLiveCadence.uses(session, mpd)) {
            return liveReplace(session, mpd);
        }
        if (session != null && session.strategy == DemoSession.Strategy.SGAI) {
            String written = new String(io.writeBytes(mpd), StandardCharsets.UTF_8);
            java.util.Map<String, String> alts = new java.util.LinkedHashMap<String, String>();
            List<MpdSgaiUtil.Event> events = sgaiEvents(session, alts);
            session.setDashAlt(alts);
            if (!events.isEmpty()) {
                written = MpdSgaiUtil.inject(written, events);
            }
            return new Result(written.getBytes(StandardCharsets.UTF_8));
        }
        if (session != null && session.strategy == DemoSession.Strategy.SSAI) {
            List<MpdSsaiUtil.AdBreak> ads = loadAdBreaks(session, publicBase);
            if (!ads.isEmpty()) {
                String vtt = fetchText(siblingFile(mpdUrl, "subs-dash.vtt"));
                String prefix = "/s/" + session.id + "/text/";
                MpdSsaiUtil.Stitched stitched = MpdSsaiUtil.stitch(mpd, ads, vtt, prefix);
                session.setDashText(stitched.text);
                mpd = stitched.mpd;
            }
        }
        return new Result(io.writeBytes(mpd));
    }

    private Result liveReplace(DemoSession session, MPD mpd) throws Exception {
        String written = new String(io.writeBytes(mpd), StandardCharsets.UTF_8);
        java.util.Map<String, String> alts = new java.util.LinkedHashMap<String, String>();
        MpdLiveCadence.Place place = MpdLiveCadence.place(mpd, session, clock.getAsLong());
        if (place != null && session.adUrl != null && session.adUrl.length() > 0) {
            Period ad = loadAdPeriod(session.adUrl);
            if (ad == null) {
                session.addWarning("DASH live SGAI skipped ad with no MPD/CMAF: " + session.adUrl);
            } else {
                float dur = session.livePlayoutLimit > 0f ? session.livePlayoutLimit : 15f;
                if (ad.getDuration() == null) {
                    ad = ad.buildUpon().withDuration(Duration.ofMillis(Math.round(dur * 1000f))).build();
                }
                alts.put(place.breakId, MpdSgaiUtil.altMpdString(Collections.singletonList(ad)));
                MpdSgaiUtil.Event ev = new MpdSgaiUtil.Event(place.breakId, place.offsetSec, dur,
                        "/s/" + session.id + "/alt/" + place.breakId + ".mpd");
                written = MpdSgaiUtil.injectReplace(written, place.periodId, ev, place.resolveMs);
            }
        }
        session.setDashAlt(alts);
        return new Result(written.getBytes(StandardCharsets.UTF_8));
    }

    List<MpdSgaiUtil.Event> sgaiEvents(DemoSession session, java.util.Map<String, String> alts) {
        List<MpdSgaiUtil.Event> events = new ArrayList<MpdSgaiUtil.Event>();
        if (session.breaks.isEmpty()) {
            return events;
        }
        int i = 0;
        for (DemoSession.Break br : session.breaks) {
            i++;
            String bid = br.id != null && br.id.length() > 0 ? br.id : ("ad-" + i);
            List<Period> periods = new ArrayList<Period>();
            float sum = 0f;
            int j = 0;
            for (DemoSession.Ad creative : session.liveAds(br)) {
                j++;
                if (session.isSkippedAd(creative.assetUri)) {
                    continue;
                }
                Period period = loadAdPeriod(creative.assetUri);
                if (period == null) {
                    session.addWarning("DASH SGAI skipped ad with no MPD/CMAF: " + creative.assetUri);
                    continue;
                }
                String pid = br.ads.size() > 1 ? (bid + "-" + j) : bid;
                float dur = creative.durationSec > 0f ? creative.durationSec
                        : (period.getDuration() != null ? period.getDuration().toMillis() / 1000f : 0f);
                if (period.getDuration() == null && dur > 0f) {
                    period = period.buildUpon().withDuration(Duration.ofMillis(Math.round(dur * 1000f))).build();
                }
                periods.add(period.buildUpon().withId(pid).build());
                sum += dur;
            }
            if (periods.isEmpty()) {
                continue;
            }
            try {
                alts.put(bid, MpdSgaiUtil.altMpdString(periods));
            } catch (Exception e) {
                session.addWarning("DASH SGAI alt MPD failed: " + e.getMessage());
                continue;
            }
            events.add(new MpdSgaiUtil.Event(bid, br.offsetSec, sum,
                    "/s/" + session.id + "/alt/" + bid + ".mpd"));
        }
        return events;
    }

    String fetchText(String url) {
        try {
            DemoHttp.FetchResult r = fetch.fetch(url);
            if (r.status >= 400 || r.body == null || r.body.length == 0) {
                return null;
            }
            return new String(r.body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
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

    static String siblingFile(String url, String name) {
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            if (path == null) {
                path = "/";
            }
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return new URI(u.getScheme(), u.getUserInfo(), u.getHost(), u.getPort(),
                    dir + name, null, null).toString();
        } catch (Exception e) {
            return url;
        }
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

    /**
     * Remote origins need an absolute base so segments stay on that host.
     * A path-only base is resolved against the session manifest host
     * (localhost or the tunnel). Local {@code /media} stays path-only so a
     * tunnel page still loads its own files.
     */
    static String periodBase(String url) {
        try {
            URI u = URI.create(url);
            String path = directoryPath(url);
            String host = u.getHost();
            if (host == null || isLoopback(host) || path.startsWith("/media/")) {
                return path;
            }
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            return new URI(scheme, u.getUserInfo(), host, u.getPort(), path, null, null).toString();
        } catch (Exception e) {
            return directoryPath(url);
        }
    }

    static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
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
