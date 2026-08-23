package com.iheartradio.m3u8.data;

import java.util.Objects;

/**
 * Data for an EXT-X-CUE-OUT tag marking the start of an SSAI ad break.
 * Duration is optional (seconds).
 */
public class CueOutData {
    private final Float mDuration;

    public CueOutData(Float duration) {
        mDuration = duration;
    }

    /** Cue-out with no duration value. */
    public static CueOutData withoutDuration() {
        return new CueOutData(null);
    }

    public Float getDuration() {
        return mDuration;
    }

    public boolean hasDuration() {
        return mDuration != null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDuration);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CueOutData)) {
            return false;
        }
        return Objects.equals(mDuration, ((CueOutData) o).mDuration);
    }

    @Override
    public String toString() {
        return "CueOutData{duration=" + mDuration + '}';
    }
}
