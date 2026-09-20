package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.InjectConfig;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil.InterstitialBreak;
import com.iheartradio.m3u8.ads.PlaylistSsaiUtil;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.StartData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One demo session. {@link #strategy} selects SGAI inject vs classic SSAI stitch.
 */
public final class DemoSession {

    public enum Strategy {
        SGAI,
        SSAI;

        public String wireName() {
            return name().toLowerCase();
        }

        public static Strategy fromWire(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return SSAI;
            }
            String n = raw.trim().toLowerCase();
            if ("sgai".equals(n) || "interstitial".equals(n) || "interstitials".equals(n)) {
                return SGAI;
            }
            if ("ssai".equals(n) || "stitch".equals(n) || "classic".equals(n)) {
                return SSAI;
            }
            throw new IllegalArgumentException("unknown strategy: " + raw + " (use sgai or ssai)");
        }
    }

    public static final class Ad {
        public final String assetUri;
        public final float durationSec;

        public Ad(String assetUri, float durationSec) {
            this.assetUri = assetUri;
            this.durationSec = durationSec;
        }
    }

    public static final class Break {
        public final String id;
        public final float offsetSec;
        public final List<Ad> ads;

        public Break(String id, float offsetSec, float durationSec, String assetUri) {
            this(id, offsetSec, Arrays.asList(new Ad(assetUri, durationSec)));
        }

        public Break(String id, float offsetSec, List<Ad> ads) {
            this.id = id;
            this.offsetSec = offsetSec;
            this.ads = ads == null ? new ArrayList<Ad>() : new ArrayList<Ad>(ads);
        }

        public String firstAssetUri() {
            return ads.isEmpty() ? null : ads.get(0).assetUri;
        }

        public float firstDurationSec() {
            return ads.isEmpty() ? 0f : ads.get(0).durationSec;
        }
    }

    public final String id;
    public final Strategy strategy;
    public final String contentUrl;
    public final String adUrl;
    public final List<Break> breaks;
    public final float maxAdDurationSec;
    public final StartData startOverride;
    public final boolean snapToSegment;
    public final Float defaultResumeOffset;
    public final String defaultRestrict;
    public final String defaultSnap;
    public final String defaultCue;
    public final long createdAtMs;
    public boolean forceVod;
    /** Rolling-live SGAI: place a pod every N seconds of program time. 0 = off. */
    public float liveCadenceSec;
    public float livePlayoutLimit = 15f;

    private volatile Playlist cachedAdMedia;
    private volatile Playlist cachedContentMaster;
    private volatile String adLoadError;
    private final Object adLock = new Object();
    private final java.util.Map<String, Playlist> adCache =
            new java.util.concurrent.ConcurrentHashMap<String, Playlist>();
    private final java.util.Map<String, byte[]> forcedVodSnapshots =
            new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
    private final java.util.List<String> warnings =
            new java.util.concurrent.CopyOnWriteArrayList<String>();
    private final java.util.Set<String> skippedAds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DemoSession(String id, Strategy strategy, String contentUrl, String adUrl,
                       List<Break> breaks, float maxAdDurationSec, StartData startOverride,
                       boolean snapToSegment, Float defaultResumeOffset, String defaultRestrict,
                       String defaultSnap, String defaultCue) {
        this.id = id;
        this.strategy = strategy == null ? Strategy.SSAI : strategy;
        this.contentUrl = contentUrl;
        this.adUrl = adUrl;
        this.breaks = breaks == null
                ? new ArrayList<Break>()
                : new ArrayList<Break>(breaks);
        this.maxAdDurationSec = maxAdDurationSec;
        this.startOverride = startOverride;
        this.snapToSegment = snapToSegment;
        this.defaultResumeOffset = defaultResumeOffset;
        this.defaultRestrict = defaultRestrict;
        this.defaultSnap = defaultSnap;
        this.defaultCue = defaultCue;
        this.createdAtMs = System.currentTimeMillis();
    }

    public boolean isExpired(long ttlMs) {
        return System.currentTimeMillis() - createdAtMs > ttlMs;
    }

    public Playlist getCachedAdMedia() {
        return cachedAdMedia;
    }

    public void setCachedAdMedia(Playlist playlist) {
        this.cachedAdMedia = playlist;
        if (adUrl != null && playlist != null) {
            adCache.put(adUrl, playlist);
        }
    }

    public Playlist getCachedAd(String url) {
        if (url == null) {
            return cachedAdMedia;
        }
        Playlist hit = adCache.get(url);
        return hit != null ? hit : (url.equals(adUrl) ? cachedAdMedia : null);
    }

    public void putCachedAd(String url, Playlist playlist) {
        if (url != null && playlist != null) {
            adCache.put(url, playlist);
        }
    }

    public Playlist getCachedContentMaster() {
        return cachedContentMaster;
    }

    public void addWarning(String msg) {
        if (msg != null && msg.length() > 0 && !warnings.contains(msg)) {
            warnings.add(msg);
        }
    }

    public java.util.List<String> getWarnings() {
        return warnings;
    }

    public void skipAd(String url) {
        if (url != null && url.length() > 0) {
            skippedAds.add(url);
        }
    }

    public boolean isSkippedAd(String url) {
        return url != null && skippedAds.contains(url);
    }

    public void setCachedContentMaster(Playlist playlist) {
        if (playlist != null && playlist.hasMasterPlaylist()) {
            this.cachedContentMaster = playlist;
        }
    }

    public byte[] getForcedVodSnapshot(String url) {
        return url == null ? null : forcedVodSnapshots.get(url);
    }

    public void putForcedVodSnapshot(String url, byte[] body) {
        if (url != null && body != null) {
            forcedVodSnapshots.putIfAbsent(url, body);
        }
    }

    public String getAdLoadError() {
        return adLoadError;
    }

    public void setAdLoadError(String error) {
        this.adLoadError = error;
    }

    public Object adLock() {
        return adLock;
    }

    public InjectConfig toInjectConfig() {
        return toInjectConfig(null);
    }

    /**
     * One DATERANGE per break. {@code publicBase} builds {@code X-ASSET-LIST}
     * ({@code /s/{id}/assets/{breakId}.json}). Never emits {@code X-ASSET-URI}.
     */
    public InjectConfig toInjectConfig(String publicBase) {
        List<InterstitialBreak> injected = new ArrayList<InterstitialBreak>();
        String base = publicBase == null ? "" : publicBase;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int i = 0;
        for (Break br : breaks) {
            i++;
            List<Ad> live = liveAds(br);
            if (live.isEmpty()) {
                continue;
            }
            String bid = br.id;
            if (bid == null || bid.length() == 0) {
                bid = PlaylistRewriteUtil.USER_AD_ID_PREFIX + i;
            }
            float dur = 0f;
            for (Ad ad : live) {
                dur += ad.durationSec > 0f ? ad.durationSec : 15f;
            }
            String list = base + "/s/" + id + "/assets/" + bid + ".json";
            injected.add(new InterstitialBreak(bid, br.offsetSec, dur, null, list,
                    defaultResumeOffset, defaultRestrict, defaultSnap,
                    dur, null, null, null, defaultCue));
        }
        return InjectConfig.builder()
                .withStartOverride(startOverride)
                .withBreaks(injected)
                .withSnapToSegment(snapToSegment)
                .withDefaultResumeOffset(defaultResumeOffset)
                .withDefaultRestrict(defaultRestrict)
                .withDefaultSnap(defaultSnap)
                .withDefaultCue(defaultCue)
                .build();
    }

    public List<Ad> liveAds(Break br) {
        List<Ad> live = new ArrayList<Ad>();
        if (br == null) {
            return live;
        }
        for (Ad ad : br.ads) {
            String uri = ad.assetUri != null && ad.assetUri.length() > 0 ? ad.assetUri : adUrl;
            if (uri == null || uri.length() == 0 || isSkippedAd(uri)) {
                continue;
            }
            live.add(new Ad(uri, ad.durationSec));
        }
        return live;
    }

    public Break findBreak(String breakId) {
        if (breakId == null || breakId.length() == 0) {
            return null;
        }
        int i = 0;
        for (Break br : breaks) {
            i++;
            String bid = br.id;
            if (bid == null || bid.length() == 0) {
                bid = PlaylistRewriteUtil.USER_AD_ID_PREFIX + i;
            }
            if (breakId.equals(bid)) {
                return br;
            }
        }
        return null;
    }

    public List<PlaylistSsaiUtil.AdBreak> toSsaiBreaks(Playlist adMedia) {
        java.util.Map<String, Playlist> one = new java.util.HashMap<String, Playlist>();
        if (adMedia != null && adUrl != null) {
            one.put(adUrl, adMedia);
        }
        return toSsaiBreaks(one);
    }

    public List<PlaylistSsaiUtil.AdBreak> toSsaiBreaks(java.util.Map<String, Playlist> adsByUrl) {
        List<PlaylistSsaiUtil.AdBreak> out = new ArrayList<PlaylistSsaiUtil.AdBreak>();
        if (breaks.isEmpty()) {
            return out;
        }
        int i = 0;
        for (Break br : breaks) {
            int j = 0;
            for (Ad creative : liveAds(br)) {
                i++;
                j++;
                String uri = creative.assetUri;
                Playlist ad = adsByUrl != null ? adsByUrl.get(uri) : null;
                if (ad == null) {
                    ad = getCachedAd(uri);
                }
                if (ad == null || !ad.hasMediaPlaylist()) {
                    continue;
                }
                float maxDur = creative.durationSec > 0f ? creative.durationSec : maxAdDurationSec;
                String bid = br.id != null && br.id.length() > 0 ? br.id : ("ssai-" + i);
                if (br.ads.size() > 1) {
                    bid = bid + "-" + j;
                }
                PlaylistSsaiUtil.AdBreak.Builder b = PlaylistSsaiUtil.AdBreak.builder()
                        .withId(bid)
                        .withAdPlaylist(ad, maxDur)
                        .withEmitDateRange(true);
                if (br.offsetSec <= 0f) {
                    b.preRoll();
                } else {
                    b.atOffsetSec(br.offsetSec);
                }
                out.add(b.build());
            }
        }
        return out;
    }

    public String resolveAdUrl(Break br) {
        if (br != null) {
            String u = br.firstAssetUri();
            if (u != null && u.length() > 0) {
                return u;
            }
        }
        return adUrl;
    }

    public java.util.List<String> allAdUrls() {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<String>();
        for (Break br : breaks) {
            for (Ad ad : br.ads) {
                String u = ad.assetUri != null && ad.assetUri.length() > 0 ? ad.assetUri : adUrl;
                if (u != null && u.length() > 0) {
                    urls.add(u);
                }
            }
        }
        if (urls.isEmpty() && adUrl != null && adUrl.length() > 0) {
            urls.add(adUrl);
        }
        return new ArrayList<String>(urls);
    }

    public float[] spliceOffsets() {
        float[] out = new float[breaks.size()];
        for (int i = 0; i < breaks.size(); i++) {
            out[i] = breaks.get(i).offsetSec;
        }
        return out;
    }

    public String toJson(String publicBase) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(DemoHttp.jsonString(id)).append(',');
        sb.append("\"strategy\":").append(DemoHttp.jsonString(strategy.wireName())).append(',');
        sb.append("\"contentUrl\":").append(DemoHttp.jsonString(contentUrl)).append(',');
        sb.append("\"adUrl\":").append(DemoHttp.jsonString(adUrl)).append(',');
        sb.append("\"splices\":[");
        for (int i = 0; i < breaks.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(breaks.get(i).offsetSec);
        }
        sb.append("],\"breaks\":[");
        for (int i = 0; i < breaks.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Break b = breaks.get(i);
            sb.append('{')
                    .append("\"id\":").append(DemoHttp.jsonString(b.id)).append(',')
                    .append("\"offsetSec\":").append(b.offsetSec).append(',')
                    .append("\"ads\":[");
            for (int j = 0; j < b.ads.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                Ad a = b.ads.get(j);
                sb.append('{')
                        .append("\"assetUri\":").append(DemoHttp.jsonString(a.assetUri)).append(',')
                        .append("\"durationSec\":").append(a.durationSec)
                        .append('}');
            }
            sb.append("]}");
        }
        sb.append("],");
        sb.append("\"maxAdDurationSec\":").append(maxAdDurationSec).append(',');
        sb.append("\"forceVod\":").append(forceVod).append(',');
        if (liveCadenceSec > 0f) {
            sb.append("\"liveCadenceSec\":").append(liveCadenceSec).append(',');
            sb.append("\"livePlayoutLimit\":").append(livePlayoutLimit).append(',');
        }
        String localManifest = publicBase + "/s/" + id + "/manifest.m3u8";
        sb.append("\"manifestUrl\":").append(DemoHttp.jsonString(localManifest));
        String advertised = DemoHttp.advertisedPublicBase();
        if (advertised != null && advertised.length() > 0) {
            sb.append(",\"publicManifestUrl\":")
                    .append(DemoHttp.jsonString(advertised + "/s/" + id + "/manifest.m3u8"));
        }
        if (!warnings.isEmpty()) {
            sb.append(",\"warnings\":[");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(DemoHttp.jsonString(warnings.get(i)));
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    public static DemoSession fromJson(String id, String json) {
        Strategy strategy = Strategy.fromWire(DemoHttp.jsonStringValue(json, "strategy"));

        String content = DemoHttp.jsonStringValue(json, "contentUrl");
        if (content == null || content.isEmpty()) {
            content = DemoHttp.jsonStringValue(json, "content");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("contentUrl is required");
        }
        DemoHttp.requireHttpUrl(content);

        String ad = DemoHttp.jsonStringValue(json, "adUrl");
        if (ad == null || ad.isEmpty()) {
            ad = DemoHttp.jsonStringValue(json, "ad");
        }
        if (ad != null && !ad.isEmpty()) {
            DemoHttp.requireHttpUrl(ad);
        } else {
            ad = null;
        }

        List<Break> breaks = parseBreaks(json, ad);
        if (strategy == Strategy.SSAI && !breaks.isEmpty()) {
            boolean hasAd = ad != null && ad.length() > 0;
            for (int i = 0; !hasAd && i < breaks.size(); i++) {
                String u = breaks.get(i).firstAssetUri();
                hasAd = u != null && u.length() > 0;
            }
            if (!hasAd) {
                throw new IllegalArgumentException("each SSAI break needs an ad URL");
            }
        }

        float maxAd = (float) DemoHttp.jsonNumber(json, "maxAdDurationSec", Double.NaN);
        if (Double.isNaN(maxAd)) {
            maxAd = (float) DemoHttp.jsonNumber(json, "maxAd", Double.NaN);
        }
        if (Double.isNaN(maxAd)) {
            maxAd = DemoPlayerServer.DEFAULT_MAX_AD_DURATION_SEC;
        }
        if (maxAd < 0f) {
            throw new IllegalArgumentException("maxAdDurationSec must be >= 0 (0 = no trim)");
        }

        StartData start = null;
        String startObj = DemoHttp.jsonObject(json, "start");
        if (startObj != null && DemoHttp.jsonBool(startObj, "override", false)) {
            start = new StartData(
                    (float) DemoHttp.jsonNumber(startObj, "timeOffset", 0),
                    DemoHttp.jsonBool(startObj, "precise", false));
        }

        String sgai = DemoHttp.jsonObject(json, "sgai");
        String sgaiSrc = sgai != null ? sgai : json;
        boolean snapSeg = DemoHttp.jsonBool(sgaiSrc, "snapToSegment",
                DemoHttp.jsonBool(json, "snapSegment", true));
        Float resume = (float) DemoHttp.jsonNumber(sgaiSrc, "resumeOffset",
                DemoHttp.jsonNumber(json, "resumeOffset", 0));
        String restrict = DemoHttp.jsonStringValue(sgaiSrc, "restrict");
        if (restrict == null) {
            restrict = DemoHttp.jsonStringValue(json, "restrict");
        }
        if (restrict == null || restrict.length() == 0) {
            boolean restrictSkip = DemoHttp.jsonBool(sgaiSrc, "restrictSkip",
                    DemoHttp.jsonBool(json, "restrictSkip", false));
            boolean restrictJump = DemoHttp.jsonBool(sgaiSrc, "restrictJump",
                    DemoHttp.jsonBool(json, "restrictJump", false));
            restrict = composeRestrict(restrictSkip, restrictJump);
        }
        String snap = DemoHttp.jsonStringValue(sgaiSrc, "snap");
        if (snap == null) {
            snap = DemoHttp.jsonStringValue(json, "snap");
        }
        String cue = DemoHttp.jsonStringValue(sgaiSrc, "cue");
        if (cue == null || cue.length() == 0) {
            cue = DemoHttp.jsonStringValue(json, "cue");
        }
        if (cue == null || cue.length() == 0) {
            boolean cueOnce = DemoHttp.jsonBool(sgaiSrc, "cueOnce",
                    DemoHttp.jsonBool(json, "cueOnce", false));
            cue = cueOnce ? "ONCE" : null;
        }

        DemoSession session = new DemoSession(id, strategy, content, ad, breaks, maxAd, start,
                snapSeg, resume, restrict, snap, cue);
        session.forceVod = DemoHttp.jsonBool(json, "forceVod", false);
        float cadence = (float) DemoHttp.jsonNumber(sgaiSrc, "liveCadenceSec",
                DemoHttp.jsonNumber(json, "liveCadenceSec", 0));
        if (cadence > 0f) {
            session.liveCadenceSec = cadence;
        }
        float liveLimit = (float) DemoHttp.jsonNumber(sgaiSrc, "livePlayoutLimit",
                DemoHttp.jsonNumber(json, "livePlayoutLimit", Double.NaN));
        if (!Double.isNaN(liveLimit) && liveLimit > 0f) {
            session.livePlayoutLimit = liveLimit;
        }
        return session;
    }

    static String composeRestrict(boolean skip, boolean jump) {
        if (skip && jump) {
            return "SKIP,JUMP";
        }
        if (skip) {
            return "SKIP";
        }
        if (jump) {
            return "JUMP";
        }
        return null;
    }

    public static DemoSession fromPlayQuery(String id, Strategy strategy, String content, String ad,
                                            float[] splices, float maxAd) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("missing content query parameter");
        }
        DemoHttp.requireHttpUrl(content);
        if (strategy == Strategy.SSAI) {
            if (ad == null || ad.isEmpty()) {
                throw new IllegalArgumentException("missing ad query parameter");
            }
            DemoHttp.requireHttpUrl(ad);
        } else if (ad != null && !ad.isEmpty()) {
            DemoHttp.requireHttpUrl(ad);
        }
        List<Break> breaks = new ArrayList<Break>();
        if (splices != null) {
            for (int i = 0; i < splices.length; i++) {
                breaks.add(new Break("ssai-" + (i + 1), splices[i], 15f, ad));
            }
        }
        return new DemoSession(id, strategy, content, ad, breaks, maxAd, null,
                true, 0f, null, null, null);
    }

    private static List<Break> parseBreaks(String json, String defaultAd) {
        List<Break> breaks = new ArrayList<Break>();
        String arr = DemoHttp.jsonArray(json, "breaks");
        if (arr == null) {
            arr = DemoHttp.jsonArray(json, "ads");
        }
        if (arr != null && arr.indexOf('{') >= 0) {
            List<String> items = DemoHttp.splitJsonObjects(arr);
            int i = 0;
            for (String item : items) {
                i++;
                String bid = DemoHttp.jsonStringValue(item, "id");
                if (bid == null || bid.isEmpty()) {
                    bid = "break-" + i;
                }
                float off = (float) DemoHttp.jsonNumber(item, "offsetSec",
                        DemoHttp.jsonNumber(item, "offset", 0));
                List<Ad> ads = parseAds(item, defaultAd);
                if (ads.isEmpty()) {
                    continue;
                }
                breaks.add(new Break(bid, off, ads));
            }
            return breaks;
        }

        float[] splices;
        String splicesArr = DemoHttp.jsonArray(json, "splices");
        if (splicesArr != null) {
            splices = parseSplicesLiteral(splicesArr);
        } else {
            splices = parseSplices(DemoHttp.jsonStringValue(json, "splices"));
        }
        for (int i = 0; i < splices.length; i++) {
            breaks.add(new Break("ssai-" + (i + 1), splices[i], 15f, defaultAd));
        }
        return breaks;
    }

    private static List<Ad> parseAds(String breakJson, String defaultAd) {
        List<Ad> ads = new ArrayList<Ad>();
        String arr = DemoHttp.jsonArray(breakJson, "ads");
        if (arr != null && arr.indexOf('{') >= 0) {
            for (String item : DemoHttp.splitJsonObjects(arr)) {
                String uri = DemoHttp.jsonStringValue(item, "assetUri");
                if (uri == null || uri.isEmpty()) {
                    uri = DemoHttp.jsonStringValue(item, "url");
                }
                if (uri == null || uri.isEmpty()) {
                    uri = defaultAd;
                }
                if (uri != null && uri.length() > 0) {
                    DemoHttp.requireHttpUrl(uri);
                }
                float dur = (float) DemoHttp.jsonNumber(item, "durationSec", 15);
                ads.add(new Ad(uri, dur));
            }
            return ads;
        }
        String uri = DemoHttp.jsonStringValue(breakJson, "assetUri");
        if (uri == null || uri.isEmpty()) {
            uri = DemoHttp.jsonStringValue(breakJson, "url");
        }
        if (uri == null || uri.isEmpty()) {
            uri = defaultAd;
        }
        if (uri != null && uri.length() > 0) {
            DemoHttp.requireHttpUrl(uri);
        }
        if (uri == null || uri.length() == 0) {
            return ads;
        }
        float dur = (float) DemoHttp.jsonNumber(breakJson, "durationSec", 15);
        ads.add(new Ad(uri, dur));
        return ads;
    }

    public static float[] parseSplices(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new float[0];
        }
        String[] parts = raw.split("[,\\s]+");
        List<Float> list = new ArrayList<Float>();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            try {
                list.add(Float.parseFloat(p.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid splice point: " + p);
            }
        }
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    public static float[] parseSplicesLiteral(String arrayLiteral) {
        String inner = arrayLiteral.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        return parseSplices(inner);
    }

    public static float parseMaxAdDuration(String raw, float defaultSec) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultSec;
        }
        try {
            float v = Float.parseFloat(raw.trim());
            if (v < 0f) {
                throw new IllegalArgumentException("maxAdDurationSec must be >= 0 (0 = no trim)");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid maxAdDurationSec: " + raw);
        }
    }
}
