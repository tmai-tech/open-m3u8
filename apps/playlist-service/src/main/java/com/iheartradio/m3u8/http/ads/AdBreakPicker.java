package com.iheartradio.m3u8.http.ads;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoLog;
import com.iheartradio.m3u8.http.DemoSession;
import com.iheartradio.m3u8.http.ads.AdDecision.Pin;
import com.iheartradio.m3u8.http.ads.AdDecision.Request;
import com.iheartradio.m3u8.http.ads.AdDecision.Result;
import com.iheartradio.m3u8.http.catalog.CatalogStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Asks {@link AdDecision} for one slot, then hands the URLs back to the
 * playlist path that already fetches ads. The player does not call this.
 */
public final class AdBreakPicker {

    private final CatalogStore catalog;
    private final File mediaRoot;
    private final AdDecision decision = new AdDecision();

    public AdBreakPicker(CatalogStore catalog, File mediaRoot) {
        this.catalog = catalog;
        this.mediaRoot = mediaRoot;
    }

    public List<DemoSession.Ad> chooseBreak(DemoSession session, DemoSession.Break br,
                                            String format, String publicBase) {
        if (session == null || br == null) {
            return Collections.emptyList();
        }
        String bid = br.id != null && br.id.length() > 0 ? br.id : "break";
        List<Pin> pins = new ArrayList<Pin>();
        // Pinned cards keep every listed creative. The budget is their durations
        // added together, so a nested durationSec cannot clip the later ads.
        float slot = br.fill ? br.slotDurationSec : 0f;
        if (!br.fill) {
            for (DemoSession.Ad ad : br.ads) {
                String uri = ad.assetUri != null && ad.assetUri.length() > 0
                        ? ad.assetUri : session.adUrl;
                if (uri == null || uri.length() == 0) {
                    continue;
                }
                float dur = ad.durationSec > 0f ? ad.durationSec : 15f;
                pins.add(new Pin(uri, dur));
                slot += dur;
            }
            if (pins.isEmpty()) {
                return Collections.emptyList();
            }
        }
        if (slot <= 0f) {
            slot = 15f;
        }
        int maxAds = br.fill ? br.maxAds : pins.size();
        if (maxAds < 1) {
            maxAds = 1;
        }
        return choose(session, bid, format, slot, maxAds, pins, 0d, publicBase);
    }

    /** Minute pod or preroll. A selected URL is pinned. {@code fill} walks inventory. */
    public List<DemoSession.Ad> chooseLive(DemoSession session, String breakId, boolean preroll,
                                           double startOffset, String publicBase) {
        if (session == null) {
            return Collections.emptyList();
        }
        String uri = preroll ? session.prerollAdUrl : session.adUrl;
        boolean fill = preroll ? session.prerollFill : session.liveFill;
        boolean hasUri = uri != null && uri.length() > 0;
        if (!hasUri && !fill) {
            return Collections.emptyList();
        }
        float limit = preroll
                ? (session.prerollPlayoutLimit > 0f ? session.prerollPlayoutLimit : 15f)
                : (session.livePlayoutLimit > 0f ? session.livePlayoutLimit : 15f);
        List<Pin> pins = new ArrayList<Pin>();
        if (hasUri) {
            pins.add(new Pin(uri, limit));
        }
        String bid = breakId != null && breakId.length() > 0 ? breakId : (preroll ? "live-preroll" : "live");
        return choose(session, bid, session.formatName(), limit, 1, pins, startOffset, publicBase);
    }

    public List<DemoSession.Ad> choose(DemoSession session, String breakId, String format,
                                       float durationSec, int maxAds, List<Pin> pinned,
                                       double startOffset, String publicBase) {
        String mode = session.modeName();
        String strategy = session.strategy == null ? "sgai" : session.strategy.wireName();
        String fmt = format == null || format.length() == 0 ? session.formatName() : format;
        Request request = new Request(session.id, mode, fmt, strategy, session.contentUrl,
                breakId, 0f, durationSec, maxAds < 1 ? 1 : maxAds, startOffset,
                pinned, session.skippedAdUrls());
        Result result = decision.decide(request, AdInventory.load(catalog),
                new MediaFileProbe(mediaRoot));
        log(session, breakId, fmt, durationSec, result);
        if (!result.fill) {
            return Collections.emptyList();
        }
        List<DemoSession.Ad> ads = new ArrayList<DemoSession.Ad>();
        for (AdDecision.Ad ad : result.ads) {
            ads.add(new DemoSession.Ad(absolute(ad.url, publicBase), ad.durationSec));
        }
        return ads;
    }

    private static void log(DemoSession session, String breakId, String format,
                            float durationSec, Result result) {
        StringBuilder urls = new StringBuilder("[");
        for (int i = 0; i < result.ads.size(); i++) {
            if (i > 0) {
                urls.append(',');
            }
            urls.append(DemoHttp.jsonString(result.ads.get(i).url));
        }
        urls.append(']');
        DemoLog.Event ev = DemoLog.event("decision")
                .sid(session.id)
                .play(session)
                .put("break", breakId)
                .put("strategy", session.strategy == null ? null : session.strategy.wireName())
                .put("durationSec", durationSec)
                .put("fill", result.fill)
                .putRaw("ads", urls.toString());
        if (!result.fill && result.reason != null) {
            ev.put("reason", result.reason);
        }
        ev.write();
    }

    static String absolute(String url, String publicBase) {
        if (url == null || url.length() == 0) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (!url.startsWith("/")) {
            return url;
        }
        String base = publicBase;
        if (base == null || base.length() == 0) {
            base = DemoHttp.advertisedPublicBase();
        }
        if (base == null || base.length() == 0) {
            return url;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + url;
    }
}
