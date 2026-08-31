package com.iheartradio.m3u8.http.catalog;

import com.iheartradio.m3u8.http.DemoJobStatus;

import java.util.UUID;

/**
 * One catalog row. Disk and {@code /api/catalog} wire format.
 */
public final class Title {
    public static final String DEFAULT_AD_URL = "/media/titles/giff-day-1/master.m3u8";
    public static final float DEFAULT_AD_OFFSET = 10f;
    public static final float DEFAULT_AD_DURATION = 12f;

    public String id;
    public String jobId;
    public String title;
    public String sub;
    public String summary;
    public String url;
    public String poster;
    public String adUrl;
    public float adOffset;
    public float adDuration;
    public DemoJobStatus status;
    public float durationSec;
    public String error;
    public String contentHash;
    public String duplicateOf;

    public Title() {
        this.adUrl = DEFAULT_AD_URL;
        this.adOffset = DEFAULT_AD_OFFSET;
        this.adDuration = DEFAULT_AD_DURATION;
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
