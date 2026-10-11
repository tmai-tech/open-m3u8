package com.iheartradio.m3u8.http;

import com.iheartradio.dash.MpdIo;
import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.MpdSgaiUtil;
import com.iheartradio.m3u8.ads.MpdSsaiUtil;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.http.ads.AdBreakPicker;
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
    private AdBreakPicker picker;

    public void setPicker(AdBreakPicker picker) {
        this.picker = picker;
    }

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
        if (xml.contains("PatchLocation")) {
            MPD parsed = io.parse(xml);
            String kept = keepPatch(xml, mpdUrl);
            if (session != null && MpdLiveCadence.uses(session, parsed)) {
                session.dashPatchUpstream = upstreamPatchBase(xml, mpdUrl);
                String owned = retargetPatchLocations(kept, "/s/" + session.id + "/patch.mpp?publishTime=");
                return liveReplaceInto(session, parsed, owned, true, publicBase);
            }
            return new Result(kept.getBytes(StandardCharsets.UTF_8));
        }
        MPD mpd = io.parse(xml);
        mpd = withPeriodBase(mpd, directoryPath(mpdUrl));
        String hostBase = mpdHostBase(mpdUrl);
        if (hostBase != null) {
            mpd = mpd.buildUpon()
                    .withBaseURLs(Collections.singletonList(BaseURL.builder().withValue(hostBase).build()))
                    .build();
        }

        if (session != null && MpdLiveCadence.uses(session, mpd)) {
            return liveReplace(session, mpd, publicBase);
        }
        if (session != null && session.strategy == DemoSession.Strategy.SGAI) {
            String written = new String(io.writeBytes(mpd), StandardCharsets.UTF_8);
            java.util.Map<String, String> alts = new java.util.LinkedHashMap<String, String>();
            List<MpdSgaiUtil.Event> events = sgaiEvents(session, alts, publicBase);
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

    private Result liveReplace(DemoSession session, MPD mpd, String publicBase) throws Exception {
        return liveReplaceInto(session, mpd,
                new String(io.writeBytes(mpd), StandardCharsets.UTF_8), false, publicBase);
    }

    /**
     * Livesim's patch, with {@code PatchLocation} kept on this session.
     * The event edit is added only when {@code live-*} changes.
     */
    public byte[] adPatch(DemoSession session, String publishTime) throws Exception {
        if (session == null || session.dashPatchUpstream == null) {
            throw new DemoHttp.HttpException(404, "session has no patch upstream");
        }
        String upstream = session.dashPatchUpstream + publishTime;
        DemoHttp.FetchResult remote = fetch.fetch(upstream);
        if (remote.status >= 400) {
            throw new DemoHttp.HttpException(remote.status > 0 ? remote.status : 502,
                    "origin patch HTTP " + remote.status);
        }
        String xml = new String(remote.body == null ? new byte[0] : remote.body, StandardCharsets.UTF_8);
        xml = retargetPatchLocations(xml, "/s/" + session.id + "/patch.mpp?publishTime=");
        String published = patchPublishTime(xml);
        MpdLiveCadence.Place place = MpdLiveCadence.placeAheadFromPublish(
                session, published != null ? published : publishTime);
        if (place != null && !place.breakId.equals(session.dashPatchEventId)) {
            xml = appendEventReplace(xml, session, place, null);
            session.dashPatchEventId = place.breakId;
        }
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /** Inject the replacement into {@code xml} without rewriting the rest of the document. */
    private Result liveReplaceInto(DemoSession session, MPD mpd, String xml, boolean ahead,
                                    String publicBase) throws Exception {
        String written = xml;
        java.util.Map<String, String> alts = new java.util.LinkedHashMap<String, String>();
        MpdLiveCadence.Place place = ahead
                ? MpdLiveCadence.placeAhead(mpd, session, clock.getAsLong())
                : MpdLiveCadence.place(mpd, session, clock.getAsLong());
        String creative = liveCreative(session, place, publicBase);
        if (creative != null) {
            Period ad = loadAdPeriod(creative);
            if (ad == null) {
                session.addWarning("DASH live SGAI skipped ad with no MPD/CMAF: " + creative);
            } else {
                float dur = session.livePlayoutLimit > 0f ? session.livePlayoutLimit : 15f;
                if (ad.getDuration() == null) {
                    ad = ad.buildUpon().withDuration(Duration.ofMillis(Math.round(dur * 1000f))).build();
                }
                alts.put(place.breakId, MpdSgaiUtil.altMpdString(Collections.singletonList(ad)));
                MpdSgaiUtil.Event ev = new MpdSgaiUtil.Event(place.breakId, place.offsetSec, dur,
                        "/s/" + session.id + "/alt/" + place.breakId + ".mpd");
                written = MpdSgaiUtil.injectReplace(written, place.periodId, ev, place.resolveMs);
                if (ahead) {
                    session.dashPatchEventId = place.breakId;
                    session.dashPatchPeriodId = place.periodId;
                    session.dashPatchPeriodStartSec = place.periodStartSec;
                }
            }
        }
        session.setDashAlt(alts);
        return new Result(written.getBytes(StandardCharsets.UTF_8));
    }

    private String appendEventReplace(String xml, DemoSession session, MpdLiveCadence.Place place,
                                      String publicBase) throws Exception {
        String creative = liveCreative(session, place, publicBase);
        Period ad = creative == null ? null : loadAdPeriod(creative);
        if (ad == null) {
            return xml;
        }
        float dur = session.livePlayoutLimit > 0f ? session.livePlayoutLimit : 15f;
        if (ad.getDuration() == null) {
            ad = ad.buildUpon().withDuration(Duration.ofMillis(Math.round(dur * 1000f))).build();
        }
        session.putDashAlt(place.breakId, MpdSgaiUtil.altMpdString(Collections.singletonList(ad)));
        long at = Math.round(place.offsetSec * 1000.0);
        long max = Math.round(dur * 1000f);
        String block = "<replace sel=\"/MPD/Period[@id='" + place.periodId + "']/EventStream[1]\">"
                + "<EventStream schemeIdUri=\"" + MpdSgaiUtil.REPLACE_SCHEME + "\" timescale=\"1000\">"
                + "<Event presentationTime=\"" + at + "\" duration=\"" + max
                + "\" id=\"" + place.breakId + "\">"
                + "<ReplacePresentation uri=\"/s/" + session.id + "/alt/" + place.breakId + ".mpd\""
                + " maxDuration=\"" + max + "\" earliestResolutionTimeOffset=\"" + place.resolveMs + "\"/>"
                + "</Event></EventStream></replace>";
        int end = xml.lastIndexOf("</Patch>");
        if (end < 0) {
            return xml;
        }
        return xml.substring(0, end) + block + xml.substring(end);
    }

    /** Upstream URL through {@code publishTime=}, taken from the origin document. */
    static String upstreamPatchBase(String xml, String mpdUrl) {
        String url = firstPatchLocation(xml);
        if (url == null) {
            return null;
        }
        if (url.startsWith("/")) {
            String host = mpdHostBase(mpdUrl);
            if (host == null) {
                return null;
            }
            url = host + url;
        }
        int q = url.indexOf("publishTime=");
        if (q < 0) {
            return url;
        }
        return url.substring(0, q + "publishTime=".length());
    }

    /** Point every PatchLocation at this session, keeping its publishTime value. */
    static String retargetPatchLocations(String xml, String sessionBase) {
        if (xml == null || sessionBase == null) {
            return xml;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(<PatchLocation\\b[^>]*>)([^<]*)(</PatchLocation>)")
                .matcher(xml);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String url = m.group(2).trim();
            int q = url.indexOf("publishTime=");
            String value = q >= 0 ? url.substring(q + "publishTime=".length()) : "";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    m.group(1) + sessionBase + value + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String firstPatchLocation(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<PatchLocation\\b[^>]*>([^<]*)</PatchLocation>")
                .matcher(xml == null ? "" : xml);
        return m.find() ? m.group(1).trim() : null;
    }

    static String patchPublishTime(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<Patch\\b[^>]*\\bpublishTime=\"([^\"]*)\"")
                .matcher(xml == null ? "" : xml);
        return m.find() ? m.group(1) : null;
    }

    private String liveCreative(DemoSession session, MpdLiveCadence.Place place, String publicBase) {
        if (place == null) {
            return null;
        }
        if (picker != null) {
            List<DemoSession.Ad> chosen = picker.chooseLive(session, place.breakId, false, 0d, publicBase);
            if (chosen.isEmpty()) {
                return null;
            }
            return chosen.get(0).assetUri;
        }
        if (session.adUrl != null && session.adUrl.length() > 0) {
            return session.adUrl;
        }
        return null;
    }

    List<MpdSgaiUtil.Event> sgaiEvents(DemoSession session, java.util.Map<String, String> alts) {
        return sgaiEvents(session, alts, null);
    }

    List<MpdSgaiUtil.Event> sgaiEvents(DemoSession session, java.util.Map<String, String> alts,
                                       String publicBase) {
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
            List<DemoSession.Ad> creatives = picker != null
                    ? picker.chooseBreak(session, br, "dash", publicBase)
                    : session.liveAds(br);
            for (DemoSession.Ad creative : creatives) {
                j++;
                if (session.isSkippedAd(creative.assetUri)) {
                    continue;
                }
                Period period = loadAdPeriod(creative.assetUri);
                if (period == null) {
                    session.skipAd(creative.assetUri, "no MPD/CMAF");
                    continue;
                }
                String pid = creatives.size() > 1 ? (bid + "-" + j) : bid;
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
        DemoPlaylistPipeline.publishDiscardedAds(session);
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
            List<DemoSession.Ad> creatives = picker != null
                    ? picker.chooseBreak(session, br, "dash", publicBase)
                    : session.liveAds(br);
            for (DemoSession.Ad creative : creatives) {
                i++;
                j++;
                if (session.isSkippedAd(creative.assetUri)) {
                    continue;
                }
                Period period = loadAdPeriod(creative.assetUri);
                if (period == null) {
                    session.skipAd(creative.assetUri, "no MPD/CMAF");
                    continue;
                }
                String bid = br.id != null && br.id.length() > 0 ? br.id : ("ad-" + i);
                if (creatives.size() > 1) {
                    bid = bid + "-" + j;
                }
                out.add(new MpdSsaiUtil.AdBreak(bid, br.offsetSec, period));
            }
        }
        DemoPlaylistPipeline.publishDiscardedAds(session);
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
     * Host only, for an MPD-level BaseURL. The period keeps the path
     * ({@code /livesim2/.../}). A leading slash on that path replaces the
     * host base's path, then the segment name is appended. Local
     * {@code /media} stays path-only so a tunnel page still loads its own files.
     */
    /**
     * Leave a patch manifest as the origin wrote it, plus the host and period
     * bases. A parse/write would drop {@code PatchLocation}, and an ad event
     * would make the origin {@code .mpp} a diff against the wrong document.
     */
    static String keepPatch(String xml, String mpdUrl) {
        String host = mpdHostBase(mpdUrl);
        String path = directoryPath(mpdUrl);
        if (host != null && !xml.contains("<BaseURL>" + host + "</BaseURL>")) {
            int open = xml.indexOf("<MPD");
            int gt = open < 0 ? -1 : xml.indexOf('>', open);
            if (gt >= 0) {
                xml = xml.substring(0, gt + 1) + "<BaseURL>" + host + "</BaseURL>" + xml.substring(gt + 1);
            }
        }
        if (path != null && path.length() > 1 && !xml.contains("<BaseURL>" + path + "</BaseURL>")) {
            StringBuilder out = new StringBuilder();
            int from = 0;
            while (from < xml.length()) {
                int open = xml.indexOf("<Period", from);
                if (open < 0) {
                    out.append(xml.substring(from));
                    break;
                }
                int gt = xml.indexOf('>', open);
                if (gt < 0) {
                    out.append(xml.substring(from));
                    break;
                }
                out.append(xml.substring(from, gt + 1));
                out.append("<BaseURL>").append(path).append("</BaseURL>");
                from = gt + 1;
            }
            xml = out.toString();
        }
        return absolutizePatchLocations(xml, host);
    }

    /**
     * Shaka resolves {@code PatchLocation} against the manifest URL, not
     * {@code BaseURL}. A path that starts with {@code /} therefore stays on
     * localhost. Prefix the origin host so the {@code .mpp} is requested from
     * the same host as the segments.
     */
    static String absolutizePatchLocations(String xml, String host) {
        if (xml == null || host == null || host.length() == 0) {
            return xml;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(<PatchLocation\\b[^>]*>)([^<]*)(</PatchLocation>)")
                .matcher(xml);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String url = m.group(2).trim();
            if (url.startsWith("/") && !url.startsWith("//")) {
                url = host + url;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    m.group(1) + url + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String mpdHostBase(String url) {
        try {
            URI u = URI.create(url);
            String path = directoryPath(url);
            String host = u.getHost();
            if (host == null || isLoopback(host) || path.startsWith("/media/")) {
                return null;
            }
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            if (u.getPort() > 0) {
                return scheme + "://" + host + ":" + u.getPort();
            }
            return scheme + "://" + host;
        } catch (Exception e) {
            return null;
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
