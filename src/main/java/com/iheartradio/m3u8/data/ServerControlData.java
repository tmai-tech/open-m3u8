package com.iheartradio.m3u8.data;

import java.util.Objects;

/**
 * Data for an EXT-X-SERVER-CONTROL tag. Advertises Delivery Directives
 * including Playlist Delta Updates (CAN-SKIP-UNTIL / CAN-SKIP-DATERANGES).
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-18#section-4.4.3.8">HLS EXT-X-SERVER-CONTROL</a>
 */
public class ServerControlData {
    private final Float mCanSkipUntil;
    private final boolean mCanSkipDateranges;
    private final Float mHoldBack;
    private final Float mPartHoldBack;
    private final boolean mCanBlockReload;

    private ServerControlData(Float canSkipUntil, boolean canSkipDateranges, Float holdBack,
                              Float partHoldBack, boolean canBlockReload) {
        mCanSkipUntil = canSkipUntil;
        mCanSkipDateranges = canSkipDateranges;
        mHoldBack = holdBack;
        mPartHoldBack = partHoldBack;
        mCanBlockReload = canBlockReload;
    }

    /**
     * @return Skip Boundary in seconds, or null if CAN-SKIP-UNTIL is absent
     */
    public Float getCanSkipUntil() {
        return mCanSkipUntil;
    }

    public boolean hasCanSkipUntil() {
        return mCanSkipUntil != null;
    }

    /**
     * @return true if the server can produce delta updates that skip Media Segments
     */
    public boolean canProduceDeltaUpdates() {
        return hasCanSkipUntil();
    }

    public boolean canSkipDateranges() {
        return mCanSkipDateranges;
    }

    public Float getHoldBack() {
        return mHoldBack;
    }

    public boolean hasHoldBack() {
        return mHoldBack != null;
    }

    public Float getPartHoldBack() {
        return mPartHoldBack;
    }

    public boolean hasPartHoldBack() {
        return mPartHoldBack != null;
    }

    public boolean canBlockReload() {
        return mCanBlockReload;
    }

    public Builder buildUpon() {
        return new Builder(mCanSkipUntil, mCanSkipDateranges, mHoldBack, mPartHoldBack, mCanBlockReload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCanSkipUntil, mCanSkipDateranges, mHoldBack, mPartHoldBack, mCanBlockReload);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ServerControlData)) {
            return false;
        }
        ServerControlData other = (ServerControlData) o;
        return Objects.equals(mCanSkipUntil, other.mCanSkipUntil)
                && mCanSkipDateranges == other.mCanSkipDateranges
                && Objects.equals(mHoldBack, other.mHoldBack)
                && Objects.equals(mPartHoldBack, other.mPartHoldBack)
                && mCanBlockReload == other.mCanBlockReload;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("(ServerControlData")
                .append(" canSkipUntil=").append(mCanSkipUntil)
                .append(" canSkipDateranges=").append(mCanSkipDateranges)
                .append(" holdBack=").append(mHoldBack)
                .append(" partHoldBack=").append(mPartHoldBack)
                .append(" canBlockReload=").append(mCanBlockReload)
                .append(")")
                .toString();
    }

    public static class Builder {
        private Float mCanSkipUntil;
        private boolean mCanSkipDateranges;
        private Float mHoldBack;
        private Float mPartHoldBack;
        private boolean mCanBlockReload;

        public Builder() {
        }

        private Builder(Float canSkipUntil, boolean canSkipDateranges, Float holdBack,
                        Float partHoldBack, boolean canBlockReload) {
            mCanSkipUntil = canSkipUntil;
            mCanSkipDateranges = canSkipDateranges;
            mHoldBack = holdBack;
            mPartHoldBack = partHoldBack;
            mCanBlockReload = canBlockReload;
        }

        public Builder withCanSkipUntil(float canSkipUntil) {
            mCanSkipUntil = canSkipUntil;
            return this;
        }

        public Builder withCanSkipDateranges(boolean canSkipDateranges) {
            mCanSkipDateranges = canSkipDateranges;
            return this;
        }

        public Builder withHoldBack(float holdBack) {
            mHoldBack = holdBack;
            return this;
        }

        public Builder withPartHoldBack(float partHoldBack) {
            mPartHoldBack = partHoldBack;
            return this;
        }

        public Builder withCanBlockReload(boolean canBlockReload) {
            mCanBlockReload = canBlockReload;
            return this;
        }

        public ServerControlData build() {
            return new ServerControlData(mCanSkipUntil, mCanSkipDateranges, mHoldBack, mPartHoldBack, mCanBlockReload);
        }
    }
}
