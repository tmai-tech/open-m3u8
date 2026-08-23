package com.iheartradio.m3u8.data;

import java.util.List;
import java.util.Objects;

public class MediaPlaylist {
    private final List<TrackData> mTracks;
    private final List<String> mUnknownTags;
    private final List<DateRangeData> mDateRanges;
    private final List<DefineData> mDefines;
    private final int mTargetDuration;
    private final int mMediaSequenceNumber;
    private final boolean mIsIframesOnly;
    private final boolean mIsOngoing;
    private final PlaylistType mPlaylistType;
    private final StartData mStartData;
    private final ServerControlData mServerControlData;
    private final SkipData mSkipData;

    private MediaPlaylist(List<TrackData> tracks, List<String> unknownTags, List<DateRangeData> dateRanges, List<DefineData> defines, int targetDuration, StartData startData, int mediaSequenceNumber, boolean isIframesOnly, boolean isOngoing, PlaylistType playlistType, ServerControlData serverControlData, SkipData skipData) {
        mTracks = DataUtil.emptyOrUnmodifiable(tracks);
        mUnknownTags = DataUtil.emptyOrUnmodifiable(unknownTags);
        mDateRanges = DataUtil.emptyOrUnmodifiable(dateRanges);
        mDefines = DataUtil.emptyOrUnmodifiable(defines);
        mTargetDuration = targetDuration;
        mMediaSequenceNumber = mediaSequenceNumber;
        mIsIframesOnly = isIframesOnly;
        mIsOngoing = isOngoing;
        mStartData = startData;
        mPlaylistType = playlistType;
        mServerControlData = serverControlData;
        mSkipData = skipData;
    }

    public boolean hasTracks() {
        return !mTracks.isEmpty();
    }

    public List<TrackData> getTracks() {
        return mTracks;
    }

    public int getTargetDuration() {
        return mTargetDuration;
    }

    public int getMediaSequenceNumber() {
        return mMediaSequenceNumber;
    }
    
    public boolean isIframesOnly() {
        return mIsIframesOnly;
    }

    public boolean isOngoing() {
        return mIsOngoing;
    }

    public boolean hasUnknownTags() {
        return !mUnknownTags.isEmpty();
    }
    
    public List<String> getUnknownTags() {
        return mUnknownTags;
    }

    public boolean hasDateRanges() {
        return !mDateRanges.isEmpty();
    }

    public List<DateRangeData> getDateRanges() {
        return mDateRanges;
    }

    public boolean hasDefines() {
        return !mDefines.isEmpty();
    }

    public List<DefineData> getDefines() {
        return mDefines;
    }
    
    public StartData getStartData() {
        return mStartData;
    }
    
    public boolean hasStartData() {
        return mStartData != null;
    }

    public PlaylistType getPlaylistType() {
        return mPlaylistType;
    }
    
    public boolean hasPlaylistType() {
        return mPlaylistType != null;
    }

    public ServerControlData getServerControlData() {
        return mServerControlData;
    }

    public boolean hasServerControlData() {
        return mServerControlData != null;
    }

    public SkipData getSkipData() {
        return mSkipData;
    }

    public boolean hasSkipData() {
        return mSkipData != null;
    }

    /**
     * @return true if this media playlist is a Playlist Delta Update (contains EXT-X-SKIP)
     */
    public boolean isDeltaUpdate() {
        return mSkipData != null;
    }

    public int getDiscontinuitySequenceNumber(final int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= mTracks.size()) {
            throw new IndexOutOfBoundsException();
        }

        int discontinuitySequenceNumber = 0;

        for (int i = 0; i <= segmentIndex; ++i) {
            if (mTracks.get(i).hasDiscontinuity()) {
                ++discontinuitySequenceNumber;
            }
        }

        return discontinuitySequenceNumber;
    }

    public Builder buildUpon() {
        return new Builder(mTracks, mUnknownTags, mDateRanges, mDefines, mTargetDuration, mMediaSequenceNumber, mIsIframesOnly, mIsOngoing, mPlaylistType, mStartData, mServerControlData, mSkipData);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(
                mTracks,
                mUnknownTags,
                mDateRanges,
                mDefines,
                mTargetDuration,
                mMediaSequenceNumber,
                mIsIframesOnly,
                mIsOngoing,
                mPlaylistType,
                mStartData,
                mServerControlData,
                mSkipData);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MediaPlaylist)) {
            return false;
        }

        MediaPlaylist other = (MediaPlaylist) o;

        return Objects.equals(mTracks, other.mTracks) &&
               Objects.equals(mUnknownTags, other.mUnknownTags) &&
               Objects.equals(mDateRanges, other.mDateRanges) &&
               Objects.equals(mDefines, other.mDefines) &&
               mTargetDuration == other.mTargetDuration &&
               mMediaSequenceNumber == other.mMediaSequenceNumber &&
               mIsIframesOnly == other.mIsIframesOnly &&
               mIsOngoing == other.mIsOngoing &&
               Objects.equals(mPlaylistType, other.mPlaylistType) &&
               Objects.equals(mStartData, other.mStartData) &&
               Objects.equals(mServerControlData, other.mServerControlData) &&
               Objects.equals(mSkipData, other.mSkipData);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("(MediaPlaylist")
                .append(" mTracks=").append(mTracks)
                .append(" mUnknownTags=").append(mUnknownTags)
                .append(" mDateRanges=").append(mDateRanges)
                .append(" mDefines=").append(mDefines)
                .append(" mTargetDuration=").append(mTargetDuration)
                .append(" mMediaSequenceNumber=").append(mMediaSequenceNumber)
                .append(" mIsIframesOnly=").append(mIsIframesOnly)
                .append(" mIsOngoing=").append(mIsOngoing)
                .append(" mPlaylistType=").append(mPlaylistType)
                .append(" mStartData=").append(mStartData)
                .append(" mServerControlData=").append(mServerControlData)
                .append(" mSkipData=").append(mSkipData)
                .append(")")
                .toString();
    }

    public static class Builder {
        private List<TrackData> mTracks;
        private List<String> mUnknownTags;
        private List<DateRangeData> mDateRanges;
        private List<DefineData> mDefines;
        private int mTargetDuration;
        private int mMediaSequenceNumber;
        private boolean mIsIframesOnly;
        private boolean mIsOngoing;
        private PlaylistType mPlaylistType;
        private StartData mStartData;
        private ServerControlData mServerControlData;
        private SkipData mSkipData;

        public Builder() {
        }

        private Builder(List<TrackData> tracks, List<String> unknownTags, List<DateRangeData> dateRanges, List<DefineData> defines, int targetDuration, int mediaSequenceNumber, boolean isIframesOnly, boolean isOngoing, PlaylistType playlistType, StartData startData, ServerControlData serverControlData, SkipData skipData) {
            mTracks = tracks;
            mUnknownTags = unknownTags;
            mDateRanges = dateRanges;
            mDefines = defines;
            mTargetDuration = targetDuration;
            mMediaSequenceNumber = mediaSequenceNumber;
            mIsIframesOnly = isIframesOnly;
            mIsOngoing = isOngoing;
            mPlaylistType = playlistType;
            mStartData = startData;
            mServerControlData = serverControlData;
            mSkipData = skipData;
        }

        public Builder withTracks(List<TrackData> tracks) {
            mTracks = tracks;
            return this;
        }
        
        public Builder withUnknownTags(List<String> unknownTags) {
            mUnknownTags = unknownTags;
            return this;
        }

        public Builder withDateRanges(List<DateRangeData> dateRanges) {
            mDateRanges = dateRanges;
            return this;
        }

        public Builder withDefines(List<DefineData> defines) {
            mDefines = defines;
            return this;
        }

        public Builder withTargetDuration(int targetDuration) {
            mTargetDuration = targetDuration;
            return this;
        }

        public Builder withStartData(StartData startData) {
            mStartData = startData;
            return this;
        }
        
        public Builder withMediaSequenceNumber(int mediaSequenceNumber) {
            mMediaSequenceNumber = mediaSequenceNumber;
            return this;
        }
        
        public Builder withIsIframesOnly(boolean isIframesOnly) {
            mIsIframesOnly = isIframesOnly;
            return this;
        }

        public Builder withIsOngoing(boolean isOngoing) {
            mIsOngoing = isOngoing;
            return this;
        }

        public Builder withPlaylistType(PlaylistType playlistType) {
            mPlaylistType = playlistType;
            return this;
        }

        public Builder withServerControlData(ServerControlData serverControlData) {
            mServerControlData = serverControlData;
            return this;
        }

        public Builder withSkipData(SkipData skipData) {
            mSkipData = skipData;
            return this;
        }

        public MediaPlaylist build() {
            return new MediaPlaylist(mTracks, mUnknownTags, mDateRanges, mDefines, mTargetDuration, mStartData, mMediaSequenceNumber, mIsIframesOnly, mIsOngoing, mPlaylistType, mServerControlData, mSkipData);
        }
    }
}
