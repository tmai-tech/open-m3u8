package com.iheartradio.m3u8.demo;

import com.iheartradio.m3u8.PlaylistRewriteUtil;
import com.iheartradio.m3u8.PlaylistRewriteUtil.InjectConfig;
import com.iheartradio.m3u8.PlaylistRewriteUtil.InterstitialBreak;
import com.iheartradio.m3u8.PlaylistSsaiUtil;
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

    public static final class Break {
        public final String id;
        public final float offsetSec;
        public final float durationSec;
        public final String assetUri;

        public Break(String id, float offsetSec, float durationSec, String assetUri) {
            this.id = id;
            this.offsetSec = offsetSec;
            this.durationSec = durationSec;
            this.assetUri = assetUri;
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
    public final long createdAtMs;

    private volatile Playlist cachedAdMedia;
    private volatile String adLoadError;
    private final Object adLock = new Object();

    public DemoSession(String id, Strategy strategy, String contentUrl, String adUrl,
                       List<Break> breaks, float maxAdDurationSec, StartData startOverride,
                       boolean snapToSegment, Float defaultResumeOffset, String defaultRestrict,
                       String defaultSnap) {
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
        List<InterstitialBreak> injected = new ArrayList<InterstitialBreak>();
        int i = 0;
        for (Break br : breaks) {
            i++;
            String uri = br.assetUri;
            if (uri == null || uri.length() == 0) {
                uri = adUrl;
            }
            if (uri == null || uri.length() == 0) {
                continue;
            }
            String bid = br.id;
            if (bid == null || bid.length() == 0) {
                bid = PlaylistRewriteUtil.USER_AD_ID_PREFIX + i;
            }
            float dur = br.durationSec > 0 ? br.durationSec : 15f;
            injected.add(new InterstitialBreak(bid, br.offsetSec, dur, uri));
        }
        return InjectConfig.builder()
                .withStartOverride(startOverride)
                .withBreaks(injected)
                .withSnapToSegment(snapToSegment)
                .withDefaultResumeOffset(defaultResumeOffset)
                .withDefaultRestrict(defaultRestrict)
                .withDefaultSnap(defaultSnap)
                .build();
    }

    public List<PlaylistSsaiUtil.AdBreak> toSsaiBreaks(Playlist adMedia) {
        List<PlaylistSsaiUtil.AdBreak> out = new ArrayList<PlaylistSsaiUtil.AdBreak>();
        if (adMedia == null || breaks.isEmpty()) {
            return out;
        }
        int i = 0;
        for (Break br : breaks) {
            i++;
            PlaylistSsaiUtil.AdBreak.Builder b = PlaylistSsaiUtil.AdBreak.builder()
                    .withId(br.id != null && br.id.length() > 0 ? br.id : ("ssai-" + i))
                    .withAdPlaylist(adMedia, maxAdDurationSec)
                    .withEmitDateRange(true);
            if (br.offsetSec <= 0f) {
                b.preRoll();
            } else {
                b.atOffsetSec(br.offsetSec);
            }
            out.add(b.build());
        }
        return out;
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
                    .append("\"durationSec\":").append(b.durationSec).append(',')
                    .append("\"assetUri\":").append(DemoHttp.jsonString(b.assetUri))
                    .append('}');
        }
        sb.append("],");
        sb.append("\"maxAdDurationSec\":").append(maxAdDurationSec).append(',');
        String localManifest = publicBase + "/s/" + id + "/manifest";
        sb.append("\"manifestUrl\":").append(DemoHttp.jsonString(localManifest));
        String advertised = DemoHttp.advertisedPublicBase();
        if (advertised != null && advertised.length() > 0) {
            sb.append(",\"publicManifestUrl\":")
                    .append(DemoHttp.jsonString(advertised + "/s/" + id + "/manifest"));
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
        if (strategy == Strategy.SSAI && (ad == null || ad.isEmpty())) {
            throw new IllegalArgumentException("adUrl is required for ssai");
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
        boolean restrictSkip = DemoHttp.jsonBool(sgaiSrc, "restrictSkip",
                DemoHttp.jsonBool(json, "restrictSkip", false));
        String restrict = restrictSkip ? "SKIP" : null;
        String snap = DemoHttp.jsonStringValue(sgaiSrc, "snap");
        if (snap == null) {
            snap = DemoHttp.jsonStringValue(json, "snap");
        }

        return new DemoSession(id, strategy, content, ad, breaks, maxAd, start,
                snapSeg, resume, restrict, snap);
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
                true, 0f, null, null);
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
                String uri = DemoHttp.jsonStringValue(item, "assetUri");
                if (uri == null || uri.isEmpty()) {
                    uri = DemoHttp.jsonStringValue(item, "url");
                }
                if (uri == null || uri.isEmpty()) {
                    uri = defaultAd;
                }
                String bid = DemoHttp.jsonStringValue(item, "id");
                if (bid == null || bid.isEmpty()) {
                    bid = "break-" + i;
                }
                float off = (float) DemoHttp.jsonNumber(item, "offsetSec",
                        DemoHttp.jsonNumber(item, "offset", 0));
                float dur = (float) DemoHttp.jsonNumber(item, "durationSec", 15);
                breaks.add(new Break(bid, off, dur, uri));
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
