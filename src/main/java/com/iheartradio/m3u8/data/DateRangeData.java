package com.iheartradio.m3u8.data;

import java.util.Objects;

/**
 * Data for an EXT-X-DATERANGE tag used by SSAI and SGAI to carry ad-break
 * timing and rich metadata (e.g. SCTE-35 markers, ad break IDs).
 */
public class DateRangeData {
    private final String mId;
    private final String mClassAttribute;
    private final String mStartDate;
    private final String mEndDate;
    private final Float mDuration;
    private final Float mPlannedDuration;
    private final String mScte35Out;
    private final String mScte35In;
    private final String mScte35Cmd;
    private final String mAssetUri;
    private final String mRestrictions;
    private final Float mResumeOffset;

    private DateRangeData(String id, String classAttribute, String startDate, String endDate,
                          Float duration, Float plannedDuration,
                          String scte35Out, String scte35In, String scte35Cmd,
                          String assetUri, String restrictions, Float resumeOffset) {
        mId = id;
        mClassAttribute = classAttribute;
        mStartDate = startDate;
        mEndDate = endDate;
        mDuration = duration;
        mPlannedDuration = plannedDuration;
        mScte35Out = scte35Out;
        mScte35In = scte35In;
        mScte35Cmd = scte35Cmd;
        mAssetUri = assetUri;
        mRestrictions = restrictions;
        mResumeOffset = resumeOffset;
    }

    public String getId() { return mId; }
    public String getClassAttribute() { return mClassAttribute; }
    public boolean hasClassAttribute() { return mClassAttribute != null; }
    public boolean isInterstitial() { return "com.apple.hls.interstitial".equals(mClassAttribute); }
    public String getStartDate() { return mStartDate; }
    public boolean hasStartDate() { return mStartDate != null; }
    public String getEndDate() { return mEndDate; }
    public boolean hasEndDate() { return mEndDate != null; }
    public Float getDuration() { return mDuration; }
    public boolean hasDuration() { return mDuration != null; }
    public Float getPlannedDuration() { return mPlannedDuration; }
    public boolean hasPlannedDuration() { return mPlannedDuration != null; }
    public String getScte35Out() { return mScte35Out; }
    public boolean hasScte35Out() { return mScte35Out != null; }
    public String getScte35In() { return mScte35In; }
    public boolean hasScte35In() { return mScte35In != null; }
    public String getScte35Cmd() { return mScte35Cmd; }
    public boolean hasScte35Cmd() { return mScte35Cmd != null; }
    public String getAssetUri() { return mAssetUri; }
    public boolean hasAssetUri() { return mAssetUri != null; }
    public String getRestrictions() { return mRestrictions; }
    public boolean hasRestrictions() { return mRestrictions != null; }
    public Float getResumeOffset() { return mResumeOffset; }
    public boolean hasResumeOffset() { return mResumeOffset != null; }

    public Builder buildUpon() {
        return new Builder(mId, mClassAttribute, mStartDate, mEndDate, mDuration, mPlannedDuration,
                mScte35Out, mScte35In, mScte35Cmd, mAssetUri, mRestrictions, mResumeOffset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mClassAttribute, mStartDate, mEndDate, mDuration, mPlannedDuration,
                mScte35Out, mScte35In, mScte35Cmd, mAssetUri, mRestrictions, mResumeOffset);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DateRangeData)) {
            return false;
        }
        DateRangeData other = (DateRangeData) o;
        return Objects.equals(mId, other.mId) &&
                Objects.equals(mClassAttribute, other.mClassAttribute) &&
                Objects.equals(mStartDate, other.mStartDate) &&
                Objects.equals(mEndDate, other.mEndDate) &&
                Objects.equals(mDuration, other.mDuration) &&
                Objects.equals(mPlannedDuration, other.mPlannedDuration) &&
                Objects.equals(mScte35Out, other.mScte35Out) &&
                Objects.equals(mScte35In, other.mScte35In) &&
                Objects.equals(mScte35Cmd, other.mScte35Cmd) &&
                Objects.equals(mAssetUri, other.mAssetUri) &&
                Objects.equals(mRestrictions, other.mRestrictions) &&
                Objects.equals(mResumeOffset, other.mResumeOffset);
    }

    @Override
    public String toString() {
        return "DateRangeData{id='" + mId + "', classAttribute='" + mClassAttribute +
                "', startDate='" + mStartDate + "', endDate='" + mEndDate +
                "', duration=" + mDuration + ", plannedDuration=" + mPlannedDuration +
                ", scte35Out='" + mScte35Out + "', scte35In='" + mScte35In +
                "', scte35Cmd='" + mScte35Cmd + "', assetUri='" + mAssetUri +
                "', restrictions='" + mRestrictions + "', resumeOffset=" + mResumeOffset + '}';
    }

    public static class Builder {
        private String mId;
        private String mClassAttribute;
        private String mStartDate;
        private String mEndDate;
        private Float mDuration;
        private Float mPlannedDuration;
        private String mScte35Out;
        private String mScte35In;
        private String mScte35Cmd;
        private String mAssetUri;
        private String mRestrictions;
        private Float mResumeOffset;

        public Builder() {}

        private Builder(String id, String classAttribute, String startDate, String endDate,
                        Float duration, Float plannedDuration,
                        String scte35Out, String scte35In, String scte35Cmd,
                        String assetUri, String restrictions, Float resumeOffset) {
            mId = id;
            mClassAttribute = classAttribute;
            mStartDate = startDate;
            mEndDate = endDate;
            mDuration = duration;
            mPlannedDuration = plannedDuration;
            mScte35Out = scte35Out;
            mScte35In = scte35In;
            mScte35Cmd = scte35Cmd;
            mAssetUri = assetUri;
            mRestrictions = restrictions;
            mResumeOffset = resumeOffset;
        }

        public Builder withId(String id) { mId = id; return this; }
        public Builder withClassAttribute(String classAttribute) { mClassAttribute = classAttribute; return this; }
        public Builder withStartDate(String startDate) { mStartDate = startDate; return this; }
        public Builder withEndDate(String endDate) { mEndDate = endDate; return this; }
        public Builder withDuration(Float duration) { mDuration = duration; return this; }
        public Builder withPlannedDuration(Float plannedDuration) { mPlannedDuration = plannedDuration; return this; }
        public Builder withScte35Out(String scte35Out) { mScte35Out = scte35Out; return this; }
        public Builder withScte35In(String scte35In) { mScte35In = scte35In; return this; }
        public Builder withScte35Cmd(String scte35Cmd) { mScte35Cmd = scte35Cmd; return this; }
        public Builder withAssetUri(String assetUri) { mAssetUri = assetUri; return this; }
        public Builder withRestrictions(String restrictions) { mRestrictions = restrictions; return this; }
        public Builder withResumeOffset(Float resumeOffset) { mResumeOffset = resumeOffset; return this; }

        public DateRangeData build() {
            return new DateRangeData(mId, mClassAttribute, mStartDate, mEndDate, mDuration, mPlannedDuration,
                    mScte35Out, mScte35In, mScte35Cmd, mAssetUri, mRestrictions, mResumeOffset);
        }
    }
}
