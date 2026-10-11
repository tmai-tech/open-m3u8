package com.iheartradio.m3u8.http.ads;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.ads.AdInventory.Creative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Picks creatives for one slot whose time and length are already known.
 * A pinned URL is returned as given. An empty pin list is filled from inventory.
 */
public final class AdDecision {

    public static final String START_OFFSET = "start-offset";
    public static final String NONE_ELIGIBLE = "none-eligible";
    public static final String UNAVAILABLE = "unavailable";

    /** Media length is unknown. The slot cap is used. */
    public static final Probe DECLARED = new Probe() {
        @Override
        public float mediaSeconds(String url) {
            return Float.NaN;
        }
    };

    /** Reports how long a creative's media is. Throw {@link Unavailable} when it will not play. */
    public interface Probe {
        float mediaSeconds(String url) throws Unavailable;
    }

    public static final class Unavailable extends Exception {
        public Unavailable(String url) {
            super(url);
        }
    }

    public static final class Pin {
        public final String url;
        public final float durationSec;

        public Pin(String url, float durationSec) {
            this.url = url;
            this.durationSec = durationSec;
        }
    }

    public static final class Request {
        public final String sessionId;
        public final String mode;
        public final String format;
        public final String strategy;
        public final String contentUrl;
        public final String breakId;
        public final float offsetSec;
        public final float durationSec;
        public final int maxAds;
        public final double startOffset;
        public final List<Pin> pinned;
        public final List<String> skipped;

        public Request(String sessionId, String mode, String format, String strategy,
                       String contentUrl, String breakId, float offsetSec, float durationSec,
                       int maxAds, double startOffset, List<Pin> pinned, List<String> skipped) {
            this.sessionId = sessionId;
            this.mode = mode;
            this.format = format;
            this.strategy = strategy;
            this.contentUrl = contentUrl;
            this.breakId = breakId;
            this.offsetSec = offsetSec;
            this.durationSec = durationSec;
            this.maxAds = maxAds;
            this.startOffset = startOffset;
            this.pinned = pinned == null
                    ? Collections.<Pin>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Pin>(pinned));
            this.skipped = skipped == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(skipped));
        }

        public static Request parse(String json) {
            if (json == null || json.indexOf('{') < 0) {
                throw new IllegalArgumentException("decision body must be a JSON object");
            }
            String breakId = DemoHttp.jsonStringValue(json, "breakId");
            if (breakId == null || breakId.length() == 0) {
                throw new IllegalArgumentException("breakId is required");
            }
            int maxAds = (int) DemoHttp.jsonNumber(json, "maxAds", 1);
            if (maxAds < 1) {
                maxAds = 1;
            }
            float duration = (float) DemoHttp.jsonNumber(json, "durationSec", 15);
            if (duration < 0f) {
                duration = 0f;
            }
            List<Pin> pinned = new ArrayList<Pin>();
            String arr = DemoHttp.jsonArray(json, "pinned");
            if (arr != null) {
                for (String item : DemoHttp.splitJsonObjects(arr)) {
                    String url = DemoHttp.jsonStringValue(item, "url");
                    if (url == null || url.length() == 0) {
                        url = DemoHttp.jsonStringValue(item, "assetUri");
                    }
                    if (url == null || url.length() == 0) {
                        continue;
                    }
                    float dur = (float) DemoHttp.jsonNumber(item, "durationSec", duration);
                    pinned.add(new Pin(url, dur));
                }
            }
            return new Request(
                    DemoHttp.jsonStringValue(json, "sessionId"),
                    textOr(json, "mode", "vod"),
                    textOr(json, "format", "hls"),
                    textOr(json, "strategy", "sgai"),
                    DemoHttp.jsonStringValue(json, "contentUrl"),
                    breakId,
                    (float) DemoHttp.jsonNumber(json, "offsetSec", 0),
                    duration,
                    maxAds,
                    startOffsetOf(json),
                    pinned,
                    jsonStrings(json, "skipped"));
        }
    }

    public static final class Ad {
        public final String url;
        public final float durationSec;

        public Ad(String url, float durationSec) {
            this.url = url;
            this.durationSec = durationSec;
        }
    }

    public static final class Result {
        public final boolean fill;
        public final String breakId;
        public final List<Ad> ads;
        public final String reason;

        private Result(boolean fill, String breakId, List<Ad> ads, String reason) {
            this.fill = fill;
            this.breakId = breakId;
            this.ads = ads == null
                    ? Collections.<Ad>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Ad>(ads));
            this.reason = reason;
        }

        public static Result filled(String breakId, List<Ad> ads) {
            return new Result(true, breakId, ads, null);
        }

        public static Result none(String breakId, String reason) {
            return new Result(false, breakId, Collections.<Ad>emptyList(), reason);
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"fill\":").append(fill ? "true" : "false");
            sb.append(",\"breakId\":").append(DemoHttp.jsonString(breakId));
            sb.append(",\"ads\":[");
            for (int i = 0; i < ads.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Ad ad = ads.get(i);
                sb.append("{\"url\":").append(DemoHttp.jsonString(ad.url));
                sb.append(",\"durationSec\":").append(trimFloat(ad.durationSec));
                sb.append('}');
            }
            sb.append(']');
            if (!fill && reason != null) {
                sb.append(",\"reason\":").append(DemoHttp.jsonString(reason));
            }
            sb.append('}');
            return sb.toString();
        }
    }

    public Result decide(Request request) {
        return decide(request, Collections.<Creative>emptyList(), DECLARED);
    }

    public Result decide(Request request, List<Creative> inventory, Probe probe) {
        if (request == null || request.breakId == null) {
            throw new IllegalArgumentException("breakId is required");
        }
        if (startOffsetBlocks(request.startOffset)) {
            return Result.none(request.breakId, START_OFFSET);
        }
        Probe used = probe == null ? DECLARED : probe;
        List<Pin> slots = candidates(request, inventory);
        if (slots.isEmpty()) {
            return Result.none(request.breakId, NONE_ELIGIBLE);
        }
        int start = request.pinned.isEmpty() ? startIndex(request, slots.size()) : 0;
        float budget = request.durationSec > 0f ? request.durationSec : 15f;
        int capCount = request.maxAds < 1 ? 1 : request.maxAds;
        List<Ad> chosen = new ArrayList<Ad>();
        List<String> usedUrls = new ArrayList<String>();
        boolean sawFailure = false;
        for (int n = 0; n < slots.size() && chosen.size() < capCount && budget > 0.05f; n++) {
            Pin pin = slots.get((start + n) % slots.size());
            if (pin.url == null || pin.url.length() == 0 || contains(usedUrls, pin.url)
                    || contains(request.skipped, pin.url)) {
                continue;
            }
            float media;
            try {
                media = used.mediaSeconds(pin.url);
            } catch (Unavailable e) {
                sawFailure = true;
                continue;
            } catch (RuntimeException e) {
                sawFailure = true;
                continue;
            }
            float own = pin.durationSec > 0f ? pin.durationSec : budget;
            float cap = Math.min(own, budget);
            float dur = media > 0f ? Math.min(media, cap) : cap;
            if (dur <= 0f) {
                sawFailure = true;
                continue;
            }
            chosen.add(new Ad(pin.url, dur));
            usedUrls.add(pin.url);
            budget -= dur;
        }
        if (!chosen.isEmpty()) {
            return Result.filled(request.breakId, chosen);
        }
        return Result.none(request.breakId, sawFailure ? UNAVAILABLE : NONE_ELIGIBLE);
    }

    private static List<Pin> candidates(Request request, List<Creative> inventory) {
        if (!request.pinned.isEmpty()) {
            return request.pinned;
        }
        boolean dash = "dash".equalsIgnoreCase(request.format);
        List<Pin> out = new ArrayList<Pin>();
        if (inventory == null) {
            return out;
        }
        for (Creative c : inventory) {
            if (c == null || !c.audio) {
                continue;
            }
            String url = dash ? c.dashUrl : c.hlsUrl;
            if (url == null || url.length() == 0) {
                continue;
            }
            out.add(new Pin(url, c.durationSec));
        }
        return out;
    }

    private static int startIndex(Request request, int size) {
        if (size <= 1) {
            return 0;
        }
        String key = (request.sessionId == null ? "" : request.sessionId)
                + "\n" + request.breakId;
        int mod = key.hashCode() % size;
        return mod < 0 ? mod + size : mod;
    }

    static boolean startOffsetBlocks(double startOffset) {
        return Double.isNaN(startOffset) || startOffset > 0d;
    }

    private static boolean contains(List<String> urls, String url) {
        for (String u : urls) {
            if (url.equals(u)) {
                return true;
            }
        }
        return false;
    }

    private static String textOr(String json, String key, String def) {
        String v = DemoHttp.jsonStringValue(json, key);
        return v == null || v.length() == 0 ? def : v;
    }

    static double startOffsetOf(String json) {
        int idx = json.indexOf("\"startOffset\"");
        if (idx < 0) {
            return 0d;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return 0d;
        }
        int i = colon + 1;
        while (i < json.length() && json.charAt(i) <= ' ') {
            i++;
        }
        if (i >= json.length()) {
            return Double.NaN;
        }
        char c = json.charAt(i);
        if (c == '-' || (c >= '0' && c <= '9')) {
            return DemoHttp.jsonNumber(json, "startOffset", Double.NaN);
        }
        return Double.NaN;
    }

    static List<String> jsonStrings(String json, String key) {
        List<String> out = new ArrayList<String>();
        String arr = DemoHttp.jsonArray(json, key);
        if (arr == null || arr.length() < 2) {
            return out;
        }
        String inner = arr.substring(1, arr.length() - 1);
        int i = 0;
        while (i < inner.length()) {
            char c = inner.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') {
                i++;
                continue;
            }
            if (c != '"') {
                break;
            }
            StringBuilder sb = new StringBuilder();
            boolean esc = false;
            for (i = i + 1; i < inner.length(); i++) {
                char ch = inner.charAt(i);
                if (esc) {
                    if (ch == 'n') {
                        sb.append('\n');
                    } else if (ch == 'r') {
                        sb.append('\r');
                    } else if (ch == 't') {
                        sb.append('\t');
                    } else {
                        sb.append(ch);
                    }
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    i++;
                    break;
                } else {
                    sb.append(ch);
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    static String trimFloat(float n) {
        if (n == (long) n) {
            return Long.toString((long) n);
        }
        return Float.toString(n);
    }
}
