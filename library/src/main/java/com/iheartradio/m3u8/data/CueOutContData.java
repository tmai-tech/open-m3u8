package com.iheartradio.m3u8.data;

import java.util.Objects;

/**
 * Data for an EXT-X-CUE-OUT-CONT tag during an ongoing SSAI ad break.
 * Supports elapsed/duration progress (e.g. 10/30) and optional SCTE-35 payload.
 */
public class CueOutContData {
    private final Float mElapsedTime;
    private final Float mDuration;
    private final String mScte35;

    public CueOutContData(Float elapsedTime, Float duration, String scte35) {
        mElapsedTime = elapsedTime;
        mDuration = duration;
        mScte35 = scte35;
    }

    public Float getElapsedTime() {
        return mElapsedTime;
    }

    public boolean hasElapsedTime() {
        return mElapsedTime != null;
    }

    public Float getDuration() {
        return mDuration;
    }

    public boolean hasDuration() {
        return mDuration != null;
    }

    public String getScte35() {
        return mScte35;
    }

    public boolean hasScte35() {
        return mScte35 != null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mElapsedTime, mDuration, mScte35);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CueOutContData)) {
            return false;
        }
        CueOutContData other = (CueOutContData) o;
        return Objects.equals(mElapsedTime, other.mElapsedTime) &&
                Objects.equals(mDuration, other.mDuration) &&
                Objects.equals(mScte35, other.mScte35);
    }

    @Override
    public String toString() {
        return "CueOutContData{elapsedTime=" + mElapsedTime +
                ", duration=" + mDuration +
                ", scte35='" + mScte35 + "'}";
    }

    public static class Builder {
        private Float mElapsedTime;
        private Float mDuration;
        private String mScte35;

        public Builder withElapsedTime(Float elapsedTime) {
            mElapsedTime = elapsedTime;
            return this;
        }

        public Builder withDuration(Float duration) {
            mDuration = duration;
            return this;
        }

        public Builder withScte35(String scte35) {
            mScte35 = scte35;
            return this;
        }

        public CueOutContData build() {
            return new CueOutContData(mElapsedTime, mDuration, mScte35);
        }
    }
}
