package com.iheartradio.m3u8.http;

import java.util.Locale;

/**
 * Library ingest / packager job state. {@link #wire} is what {@code catalog.json} and the UI use.
 */
public enum DemoJobStatus {
    QUEUED("queued"),
    PACKAGING("packaging"),
    READY("ready"),
    FAILED("failed"),
    DUPLICATE("duplicate");

    public final String wire;

    DemoJobStatus(String wire) {
        this.wire = wire;
    }

    public static DemoJobStatus fromWire(String raw) {
        if (raw == null) {
            return READY;
        }
        String n = raw.trim().toLowerCase(Locale.US);
        if (n.length() == 0) {
            return READY;
        }
        for (DemoJobStatus s : values()) {
            if (s.wire.equals(n)) {
                return s;
            }
        }
        return READY;
    }

    @Override
    public String toString() {
        return wire;
    }
}
