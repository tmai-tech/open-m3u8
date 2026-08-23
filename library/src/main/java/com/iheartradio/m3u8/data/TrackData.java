package com.iheartradio.m3u8.data;

import java.util.Objects;

public class TrackData {
    private final String mUri;
    private final TrackInfo mTrackInfo;
    private final EncryptionData mEncryptionData;
    private final String mProgramDateTime;
    private final boolean mHasDiscontinuity;
    private final MapInfo mMapInfo;
    private final ByteRange mByteRange;
    private final CueOutData mCueOut;
    private final boolean mHasCueIn;
    private final CueOutContData mCueOutCont;

    private TrackData(String uri, TrackInfo trackInfo, EncryptionData encryptionData, String programDateTime,
                      boolean hasDiscontinuity, MapInfo mapInfo, ByteRange byteRange,
                      CueOutData cueOut, boolean hasCueIn, CueOutContData cueOutCont) {
        mUri = uri;
        mTrackInfo = trackInfo;
        mEncryptionData = encryptionData;
        mProgramDateTime = programDateTime;
        mHasDiscontinuity = hasDiscontinuity;
        mMapInfo = mapInfo;
        mByteRange = byteRange;
        mCueOut = cueOut;
        mHasCueIn = hasCueIn;
        mCueOutCont = cueOutCont;
    }

    public String getUri() { return mUri; }
    public boolean hasTrackInfo() { return mTrackInfo != null; }
    public TrackInfo getTrackInfo() { return mTrackInfo; }
    public boolean hasEncryptionData() { return mEncryptionData != null; }
    public boolean isEncrypted() {
        return hasEncryptionData() &&
               mEncryptionData.getMethod() != null &&
               mEncryptionData.getMethod() != EncryptionMethod.NONE;
    }
    public boolean hasProgramDateTime() { return mProgramDateTime != null && mProgramDateTime.length() > 0; }
    public String getProgramDateTime() { return mProgramDateTime; }
    public boolean hasDiscontinuity() { return mHasDiscontinuity; }
    public EncryptionData getEncryptionData() { return mEncryptionData; }
    public boolean hasMapInfo() { return mMapInfo != null; }
    public MapInfo getMapInfo() { return mMapInfo; }
    public boolean hasByteRange() { return mByteRange != null; }
    public ByteRange getByteRange() { return mByteRange; }
    public boolean hasCueOut() { return mCueOut != null; }
    public CueOutData getCueOut() { return mCueOut; }
    public boolean hasCueIn() { return mHasCueIn; }
    public boolean hasCueOutCont() { return mCueOutCont != null; }
    public CueOutContData getCueOutCont() { return mCueOutCont; }

    public Builder buildUpon() {
        return new Builder(getUri(), mTrackInfo, mEncryptionData, mProgramDateTime, mHasDiscontinuity,
                mMapInfo, mByteRange, mCueOut, mHasCueIn, mCueOutCont);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrackData trackData = (TrackData) o;
        return mHasDiscontinuity == trackData.mHasDiscontinuity &&
                mHasCueIn == trackData.mHasCueIn &&
                Objects.equals(mUri, trackData.mUri) &&
                Objects.equals(mTrackInfo, trackData.mTrackInfo) &&
                Objects.equals(mEncryptionData, trackData.mEncryptionData) &&
                Objects.equals(mProgramDateTime, trackData.mProgramDateTime) &&
                Objects.equals(mMapInfo, trackData.mMapInfo) &&
                Objects.equals(mByteRange, trackData.mByteRange) &&
                Objects.equals(mCueOut, trackData.mCueOut) &&
                Objects.equals(mCueOutCont, trackData.mCueOutCont);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUri, mTrackInfo, mEncryptionData, mProgramDateTime, mHasDiscontinuity,
                mMapInfo, mByteRange, mCueOut, mHasCueIn, mCueOutCont);
    }

    @Override
    public String toString() {
        return "TrackData{" +
                "mUri='" + mUri + '\'' +
                ", mTrackInfo=" + mTrackInfo +
                ", mEncryptionData=" + mEncryptionData +
                ", mProgramDateTime='" + mProgramDateTime + '\'' +
                ", mHasDiscontinuity=" + mHasDiscontinuity +
                ", mMapInfo=" + mMapInfo +
                ", mByteRange=" + mByteRange +
                ", mCueOut=" + mCueOut +
                ", mHasCueIn=" + mHasCueIn +
                ", mCueOutCont=" + mCueOutCont +
                '}';
    }

    public static class Builder {
        private String mUri;
        private TrackInfo mTrackInfo;
        private EncryptionData mEncryptionData;
        private String mProgramDateTime;
        private boolean mHasDiscontinuity;
        private MapInfo mMapInfo;
        private ByteRange mByteRange;
        private CueOutData mCueOut;
        private boolean mHasCueIn;
        private CueOutContData mCueOutCont;

        public Builder() {}

        private Builder(String uri, TrackInfo trackInfo, EncryptionData encryptionData, String programDateTime,
                        boolean hasDiscontinuity, MapInfo mapInfo, ByteRange byteRange,
                        CueOutData cueOut, boolean hasCueIn, CueOutContData cueOutCont) {
            mUri = uri;
            mTrackInfo = trackInfo;
            mEncryptionData = encryptionData;
            mProgramDateTime = programDateTime;
            mHasDiscontinuity = hasDiscontinuity;
            mMapInfo = mapInfo;
            mByteRange = byteRange;
            mCueOut = cueOut;
            mHasCueIn = hasCueIn;
            mCueOutCont = cueOutCont;
        }

        public Builder withUri(String url) { mUri = url; return this; }
        public Builder withTrackInfo(TrackInfo trackInfo) { mTrackInfo = trackInfo; return this; }
        public Builder withEncryptionData(EncryptionData encryptionData) { mEncryptionData = encryptionData; return this; }
        public Builder withProgramDateTime(String programDateTime) { mProgramDateTime = programDateTime; return this; }
        public Builder withDiscontinuity(boolean hasDiscontinuity) { mHasDiscontinuity = hasDiscontinuity; return this; }
        public Builder withMapInfo(MapInfo mapInfo) { mMapInfo = mapInfo; return this; }
        public Builder withByteRange(ByteRange byteRange) { mByteRange = byteRange; return this; }
        public Builder withCueOut(CueOutData cueOut) { mCueOut = cueOut; return this; }
        public Builder withCueIn(boolean hasCueIn) { mHasCueIn = hasCueIn; return this; }
        public Builder withCueOutCont(CueOutContData cueOutCont) { mCueOutCont = cueOutCont; return this; }

        public TrackData build() {
            return new TrackData(mUri, mTrackInfo, mEncryptionData, mProgramDateTime, mHasDiscontinuity,
                    mMapInfo, mByteRange, mCueOut, mHasCueIn, mCueOutCont);
        }
    }
}
