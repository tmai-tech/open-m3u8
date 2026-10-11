package com.iheartradio.m3u8.http.catalog;

import com.iheartradio.m3u8.http.DemoJobStatus;

import java.util.UUID;

/**
 * One catalog row. Disk and {@code /api/catalog} wire format.
 */
public final class Title {
    /** Library titles do not get a house ad unless the user adds a break. */
    public static final String DEFAULT_AD_URL = null;
    public static final float DEFAULT_AD_OFFSET = 10f;
    public static final float DEFAULT_AD_DURATION = 12f;

    public String id;
    public String jobId;
    public String title;
    public String sub;
    public String summary;
    public String url;
    /** Static DASH MPD when packed; null if the title is HLS-only. */
    public String dashUrl;
    public String poster;
    public String adUrl;
    public float adOffset;
    public float adDuration;
    public DemoJobStatus status;
    public float durationSec;
    public String error;
    public String contentHash;
    public String duplicateOf;
    /** Null = unknown. False = source had no audio (silent AAC may still be muxed). */
    public Boolean hasAudio;
    /** True when this ready title may be chosen to fill an ad slot. */
    public boolean adEligible;

    public Title() {
        this.adUrl = null;
        this.adOffset = 0f;
        this.adDuration = 0f;
        this.status = DemoJobStatus.READY;
    }

    public static String newJobId() {
        return UUID.randomUUID().toString();
    }

    public boolean ensureJobId() {
        if (jobId != null && jobId.length() > 0) {
            return false;
        }
        jobId = newJobId();
        return true;
    }

    public boolean pointsAt(String otherId) {
        if (otherId == null || adUrl == null) {
            return false;
        }
        return adUrl.equals("/media/titles/" + otherId + "/master.m3u8")
                || adUrl.endsWith("/media/titles/" + otherId + "/master.m3u8")
                || adUrl.endsWith("/" + otherId + "/master.m3u8");
    }
}
