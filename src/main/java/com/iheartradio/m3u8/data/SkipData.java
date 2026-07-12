package com.iheartradio.m3u8.data;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Data for an EXT-X-SKIP tag present in a Playlist Delta Update.
 * Replaces older Media Segments (and optionally Date Ranges) that the client
 * already has from a previous playlist download.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-18#section-4.4.5.2">HLS EXT-X-SKIP</a>
 */
public class SkipData {
    private final int mSkippedSegments;
    private final List<String> mRecentlyRemovedDateranges;

    private SkipData(int skippedSegments, List<String> recentlyRemovedDateranges) {
        mSkippedSegments = skippedSegments;
        mRecentlyRemovedDateranges = recentlyRemovedDateranges == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(recentlyRemovedDateranges);
    }

    /**
     * @return number of Media Segments replaced by this EXT-X-SKIP tag
     */
    public int getSkippedSegments() {
        return mSkippedSegments;
    }

    /**
     * @return EXT-X-DATERANGE IDs removed from the playlist recently
     *         (from RECENTLY-REMOVED-DATERANGES), never null
     */
    public List<String> getRecentlyRemovedDateranges() {
        return mRecentlyRemovedDateranges;
    }

    public boolean hasRecentlyRemovedDateranges() {
        return !mRecentlyRemovedDateranges.isEmpty();
    }

    public Builder buildUpon() {
        return new Builder(mSkippedSegments, mRecentlyRemovedDateranges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSkippedSegments, mRecentlyRemovedDateranges);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SkipData)) {
            return false;
        }
        SkipData other = (SkipData) o;
        return mSkippedSegments == other.mSkippedSegments
                && Objects.equals(mRecentlyRemovedDateranges, other.mRecentlyRemovedDateranges);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("(SkipData")
                .append(" skippedSegments=").append(mSkippedSegments)
                .append(" recentlyRemovedDateranges=").append(mRecentlyRemovedDateranges)
                .append(")")
                .toString();
    }

    public static class Builder {
        private int mSkippedSegments = -1;
        private List<String> mRecentlyRemovedDateranges;

        public Builder() {
        }

        private Builder(int skippedSegments, List<String> recentlyRemovedDateranges) {
            mSkippedSegments = skippedSegments;
            mRecentlyRemovedDateranges = recentlyRemovedDateranges;
        }

        public Builder withSkippedSegments(int skippedSegments) {
            mSkippedSegments = skippedSegments;
            return this;
        }

        public Builder withRecentlyRemovedDateranges(List<String> recentlyRemovedDateranges) {
            mRecentlyRemovedDateranges = recentlyRemovedDateranges;
            return this;
        }

        public SkipData build() {
            return new SkipData(mSkippedSegments, mRecentlyRemovedDateranges);
        }
    }
}
