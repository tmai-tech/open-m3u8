package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.CueOutContData;
import com.iheartradio.m3u8.data.CueOutData;
import com.iheartradio.m3u8.data.DateRangeData;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SsaiPlaylistTest {

    @Test
    public void parseSsaiCuePlaylist() throws Exception {
        final Playlist playlist = TestUtil.parsePlaylistFromResource("ssaiCuePlaylist.m3u8");
        final MediaPlaylist mediaPlaylist = playlist.getMediaPlaylist();

        assertTrue(mediaPlaylist.hasDateRanges());
        final DateRangeData range = mediaPlaylist.getDateRanges().get(0);
        assertEquals("break-1", range.getId());
        assertEquals("2024-06-15T12:00:00.000Z", range.getStartDate());
        assertEquals(30.0f, range.getPlannedDuration(), 1e-6);
        assertTrue(range.hasScte35Out());

        final List<TrackData> tracks = mediaPlaylist.getTracks();
        assertEquals(5, tracks.size());

        assertFalse(tracks.get(0).hasCueOut());
        assertTrue(tracks.get(1).hasCueOut());
        assertEquals(30.0f, tracks.get(1).getCueOut().getDuration(), 1e-6);

        assertTrue(tracks.get(2).hasCueOutCont());
        assertEquals(10.0f, tracks.get(2).getCueOutCont().getElapsedTime(), 1e-6);
        assertEquals(30.0f, tracks.get(2).getCueOutCont().getDuration(), 1e-6);

        assertTrue(tracks.get(3).hasCueOutCont());
        assertEquals(20.0f, tracks.get(3).getCueOutCont().getElapsedTime(), 1e-6);

        assertTrue(tracks.get(4).hasCueIn());
        assertTrue(tracks.get(4).hasDiscontinuity());
    }

    @Test
    public void writeAndRoundTripSsaiCues() throws Exception {
        final DateRangeData dateRange = new DateRangeData.Builder()
                .withId("pod-ssai")
                .withStartDate("2025-01-01T00:00:00.000Z")
                .withDuration(15f)
                .withScte35Out("/DAlAAAA")
                .build();

        final TrackData content = new TrackData.Builder()
                .withUri("http://example.com/c0.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .build();

        final TrackData adStart = new TrackData.Builder()
                .withUri("http://example.com/a0.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .withCueOut(new CueOutData(20f))
                .build();

        final TrackData adMid = new TrackData.Builder()
                .withUri("http://example.com/a1.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .withCueOutCont(new CueOutContData(10f, 20f, null))
                .build();

        final TrackData back = new TrackData.Builder()
                .withUri("http://example.com/c1.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .withCueIn(true)
                .withDiscontinuity(true)
                .build();

        final MediaPlaylist mediaPlaylist = new MediaPlaylist.Builder()
                .withTargetDuration(10)
                .withMediaSequenceNumber(0)
                .withIsOngoing(false)
                .withDateRanges(Arrays.asList(dateRange))
                .withTracks(Arrays.asList(content, adStart, adMid, back))
                .build();

        final Playlist playlist = new Playlist.Builder()
                .withCompatibilityVersion(3)
                .withExtended(true)
                .withMediaPlaylist(mediaPlaylist)
                .build();

        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        new PlaylistWriter(os, Format.EXT_M3U, Encoding.UTF_8).write(playlist);
        final String written = os.toString(Encoding.UTF_8.value);

        assertTrue(written.contains("#EXT-X-CUE-OUT:20.0") || written.contains("#EXT-X-CUE-OUT:20"));
        assertTrue(written.contains("#EXT-X-CUE-OUT-CONT:10.0/20.0") || written.contains("10.0/20.0"));
        assertTrue(written.contains("#EXT-X-CUE-IN"));
        assertTrue(written.contains("#EXT-X-DATERANGE:"));
        assertTrue(written.contains("SCTE35-OUT="));
        assertTrue(written.contains("#EXT-X-DISCONTINUITY"));

        final Playlist reparsed = new PlaylistParser(
                new ByteArrayInputStream(written.getBytes(Encoding.UTF_8.value)),
                Format.EXT_M3U,
                Encoding.UTF_8).parse();

        assertEquals(1, reparsed.getMediaPlaylist().getDateRanges().size());
        assertTrue(reparsed.getMediaPlaylist().getTracks().get(1).hasCueOut());
        assertTrue(reparsed.getMediaPlaylist().getTracks().get(2).hasCueOutCont());
        assertTrue(reparsed.getMediaPlaylist().getTracks().get(3).hasCueIn());
    }
}
