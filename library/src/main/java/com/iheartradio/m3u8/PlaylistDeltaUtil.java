package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.DateRangeData;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.SkipData;
import com.iheartradio.m3u8.data.TrackData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for HLS Playlist Delta Updates.
 * <p>
 * Servers advertise support via {@code EXT-X-SERVER-CONTROL} with {@code CAN-SKIP-UNTIL}.
 * Clients request a delta by adding {@code _HLS_skip=YES} or {@code _HLS_skip=v2} to the
 * playlist URI. The response replaces older segments with {@code EXT-X-SKIP}.
 * Clients must merge the delta with their previous full playlist copy.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-18#section-6.2.5.1">Playlist Delta Updates</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-18#section-6.3.7">Requesting Playlist Delta Updates</a>
 */
public final class PlaylistDeltaUtil {
    private PlaylistDeltaUtil() {
    }

    /**
     * Appends the {@code _HLS_skip} Delivery Directive to a playlist URI.
     *
     * @param playlistUri     media playlist URI (may already contain query parameters)
     * @param skipDateRanges  if true, uses {@code v2} (skip segments and older Date Ranges);
     *                        if false, uses {@code YES} (skip segments only)
     * @return URI with the {@code _HLS_skip} query parameter
     */
    public static String appendSkipDirective(String playlistUri, boolean skipDateRanges) {
        if (playlistUri == null) {
            throw new IllegalArgumentException("playlistUri is null");
        }
        final String value = skipDateRanges ? Constants.HLS_SKIP_V2 : Constants.HLS_SKIP_YES;
        final String separator = playlistUri.contains("?") ? "&" : "?";
        return playlistUri + separator + Constants.HLS_SKIP_QUERY_PARAM + "=" + value;
    }

    /**
     * Merges a Playlist Delta Update with the client's previous media playlist to form a
     * complete up-to-date media playlist (without {@code EXT-X-SKIP}).
     * <p>
     * Per the HLS specification, if the client does not already have all of the information
     * that was skipped, it must re-request the playlist without {@code _HLS_skip}.
     *
     * @param previous previously held full (or previously merged) playlist
     * @param delta    newly downloaded playlist that contains {@code EXT-X-SKIP}
     * @return merged playlist with continuous media segments and no skip data
     * @throws IllegalArgumentException if either playlist is missing a media playlist
     * @throws PlaylistException        if the delta is not a delta update or skipped
     *                                  segments cannot be recovered from {@code previous}
     */
    public static Playlist merge(Playlist previous, Playlist delta) throws PlaylistException {
        if (previous == null || !previous.hasMediaPlaylist()) {
            throw new IllegalArgumentException("previous must be a media playlist");
        }
        if (delta == null || !delta.hasMediaPlaylist()) {
            throw new IllegalArgumentException("delta must be a media playlist");
        }

        final MediaPlaylist prevMedia = previous.getMediaPlaylist();
        final MediaPlaylist deltaMedia = delta.getMediaPlaylist();

        if (!deltaMedia.hasSkipData()) {
            throw new PlaylistException("", java.util.Collections.singleton(PlaylistError.DELTA_UPDATE_WITHOUT_SKIP));
        }

        final SkipData skipData = deltaMedia.getSkipData();
        final int skipped = skipData.getSkippedSegments();
        final int deltaMsn = deltaMedia.getMediaSequenceNumber();
        final int prevMsn = prevMedia.getMediaSequenceNumber();

        final Map<Integer, TrackData> previousBySequence = new HashMap<Integer, TrackData>();
        final List<TrackData> prevTracks = prevMedia.getTracks();
        for (int i = 0; i < prevTracks.size(); i++) {
            previousBySequence.put(prevMsn + i, prevTracks.get(i));
        }

        final List<TrackData> mergedTracks = new ArrayList<TrackData>();
        for (int i = 0; i < skipped; i++) {
            final int sequence = deltaMsn + i;
            final TrackData track = previousBySequence.get(sequence);
            if (track == null) {
                throw new PlaylistException("", java.util.Collections.singleton(PlaylistError.DELTA_UPDATE_MISSING_SEGMENTS));
            }
            mergedTracks.add(track);
        }
        mergedTracks.addAll(deltaMedia.getTracks());

        final List<DateRangeData> mergedDateRanges = mergeDateRanges(
                prevMedia.getDateRanges(),
                deltaMedia.getDateRanges(),
                skipData.getRecentlyRemovedDateranges());

        final MediaPlaylist.Builder mediaBuilder = new MediaPlaylist.Builder()
                .withTracks(mergedTracks)
                .withUnknownTags(deltaMedia.getUnknownTags())
                .withDateRanges(mergedDateRanges)
                .withDefines(deltaMedia.getDefines())
                .withTargetDuration(deltaMedia.getTargetDuration())
                .withMediaSequenceNumber(deltaMsn)
                .withIsIframesOnly(deltaMedia.isIframesOnly())
                .withIsOngoing(deltaMedia.isOngoing())
                .withPlaylistType(deltaMedia.getPlaylistType())
                .withStartData(deltaMedia.hasStartData() ? deltaMedia.getStartData() : prevMedia.getStartData())
                .withServerControlData(deltaMedia.hasServerControlData()
                        ? deltaMedia.getServerControlData()
                        : prevMedia.getServerControlData())
                .withSkipData(null);

        return new Playlist.Builder()
                .withMediaPlaylist(mediaBuilder.build())
                .withExtended(true)
                .withCompatibilityVersion(Math.max(previous.getCompatibilityVersion(), delta.getCompatibilityVersion()))
                .build();
    }

    private static List<DateRangeData> mergeDateRanges(List<DateRangeData> previous,
                                                       List<DateRangeData> deltaRanges,
                                                       List<String> removedIds) {
        final Set<String> removed = new HashSet<String>();
        if (removedIds != null) {
            removed.addAll(removedIds);
        }

        final Map<String, DateRangeData> byId = new HashMap<String, DateRangeData>();
        final List<String> order = new ArrayList<String>();

        if (previous != null) {
            for (DateRangeData dr : previous) {
                if (dr.getId() == null || removed.contains(dr.getId())) {
                    continue;
                }
                if (!byId.containsKey(dr.getId())) {
                    order.add(dr.getId());
                }
                byId.put(dr.getId(), dr);
            }
        }

        if (deltaRanges != null) {
            for (DateRangeData dr : deltaRanges) {
                if (dr.getId() == null) {
                    continue;
                }
                if (!byId.containsKey(dr.getId())) {
                    order.add(dr.getId());
                }
                byId.put(dr.getId(), dr);
            }
        }

        final List<DateRangeData> result = new ArrayList<DateRangeData>();
        for (String id : order) {
            result.add(byId.get(id));
        }
        return result;
    }
}
