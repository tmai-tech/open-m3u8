package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.ServerControlData;
import com.iheartradio.m3u8.data.SkipData;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeltaUpdatePlaylistTest {

    @Test
    public void parseServerControlAndSkip() throws Exception {
        final Playlist playlist = TestUtil.parsePlaylistFromResource("deltaUpdatePlaylist.m3u8");
        assertTrue(playlist.hasMediaPlaylist());

        final MediaPlaylist media = playlist.getMediaPlaylist();
        assertTrue(media.hasServerControlData());
        assertTrue(media.isDeltaUpdate());
        assertTrue(media.hasSkipData());

        final ServerControlData serverControl = media.getServerControlData();
        assertTrue(serverControl.hasCanSkipUntil());
        assertEquals(24.0f, serverControl.getCanSkipUntil(), 1e-6);
        assertTrue(serverControl.canProduceDeltaUpdates());
        assertTrue(serverControl.canSkipDateranges());
        assertTrue(serverControl.hasHoldBack());
        assertEquals(12.0f, serverControl.getHoldBack(), 1e-6);
        assertTrue(serverControl.canBlockReload());

        final SkipData skip = media.getSkipData();
        assertEquals(3, skip.getSkippedSegments());
        assertTrue(skip.hasRecentlyRemovedDateranges());
        assertEquals(Arrays.asList("ad-1", "ad-2"), skip.getRecentlyRemovedDateranges());

        assertEquals(10, media.getMediaSequenceNumber());
        assertEquals(3, media.getTracks().size());
        assertEquals("http://media.example.com/seg13.ts", media.getTracks().get(0).getUri());
        assertEquals("http://media.example.com/seg15.ts", media.getTracks().get(2).getUri());
    }

    @Test
    public void writeRoundTripDeltaPlaylist() throws Exception {
        final Playlist original = TestUtil.parsePlaylistFromResource("deltaUpdatePlaylist.m3u8");

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PlaylistWriter(out, Format.EXT_M3U, Encoding.UTF_8).write(original);
        final String written = out.toString(Encoding.UTF_8.getValue());

        assertTrue(written.contains("#EXT-X-SERVER-CONTROL:"));
        assertTrue(written.contains("CAN-SKIP-UNTIL=24.0"));
        assertTrue(written.contains("CAN-SKIP-DATERANGES=YES"));
        assertTrue(written.contains("CAN-BLOCK-RELOAD=YES"));
        assertTrue(written.contains("#EXT-X-SKIP:"));
        assertTrue(written.contains("SKIPPED-SEGMENTS=3"));
        assertTrue(written.contains("RECENTLY-REMOVED-DATERANGES="));

        final Playlist reparsed = new PlaylistParser(
                new ByteArrayInputStream(written.getBytes(Encoding.UTF_8.getValue())),
                Format.EXT_M3U,
                Encoding.UTF_8).parse();

        assertEquals(original.getMediaPlaylist().getSkipData(), reparsed.getMediaPlaylist().getSkipData());
        assertEquals(original.getMediaPlaylist().getServerControlData(),
                reparsed.getMediaPlaylist().getServerControlData());
        assertEquals(original.getMediaPlaylist().getTracks().size(),
                reparsed.getMediaPlaylist().getTracks().size());
    }

    @Test
    public void appendSkipDirective() {
        assertEquals(
                "https://example.com/live.m3u8?_HLS_skip=YES",
                PlaylistDeltaUtil.appendSkipDirective("https://example.com/live.m3u8", false));
        assertEquals(
                "https://example.com/live.m3u8?cdn=1&_HLS_skip=v2",
                PlaylistDeltaUtil.appendSkipDirective("https://example.com/live.m3u8?cdn=1", true));
    }

    @Test
    public void mergeDeltaUpdateWithPreviousPlaylist() throws Exception {
        final Playlist previous = TestUtil.parsePlaylistFromResource("fullLivePlaylistForDelta.m3u8");
        final Playlist delta = TestUtil.parsePlaylistFromResource("deltaUpdatePlaylist.m3u8");

        final Playlist merged = PlaylistDeltaUtil.merge(previous, delta);
        assertTrue(merged.hasMediaPlaylist());
        assertFalse(merged.getMediaPlaylist().isDeltaUpdate());
        assertFalse(merged.getMediaPlaylist().hasSkipData());

        final MediaPlaylist media = merged.getMediaPlaylist();
        assertEquals(10, media.getMediaSequenceNumber());
        assertEquals(6, media.getTracks().size());
        assertEquals("http://media.example.com/seg10.ts", media.getTracks().get(0).getUri());
        assertEquals("http://media.example.com/seg11.ts", media.getTracks().get(1).getUri());
        assertEquals("http://media.example.com/seg12.ts", media.getTracks().get(2).getUri());
        assertEquals("http://media.example.com/seg13.ts", media.getTracks().get(3).getUri());
        assertEquals("http://media.example.com/seg14.ts", media.getTracks().get(4).getUri());
        assertEquals("http://media.example.com/seg15.ts", media.getTracks().get(5).getUri());

        // ad-1 and ad-2 removed; ad-3 retained from previous
        assertTrue(media.hasDateRanges());
        assertEquals(1, media.getDateRanges().size());
        assertEquals("ad-3", media.getDateRanges().get(0).getId());

        assertTrue(media.hasServerControlData());
        assertTrue(media.getServerControlData().canProduceDeltaUpdates());
    }

    @Test
    public void mergeFailsWhenSkippedSegmentsMissing() throws Exception {
        final Playlist previous = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(9)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(4)
                        .withMediaSequenceNumber(20)
                        .withIsOngoing(true)
                        .withTracks(Arrays.asList(track("http://example.com/only.ts", 4f)))
                        .build())
                .build();

        final Playlist delta = TestUtil.parsePlaylistFromResource("deltaUpdatePlaylist.m3u8");

        try {
            PlaylistDeltaUtil.merge(previous, delta);
            fail("expected PlaylistException");
        } catch (PlaylistException e) {
            assertTrue(e.getErrors().contains(PlaylistError.DELTA_UPDATE_MISSING_SEGMENTS));
        }
    }

    @Test
    public void mergeFailsWhenNotDelta() throws Exception {
        final Playlist previous = TestUtil.parsePlaylistFromResource("fullLivePlaylistForDelta.m3u8");
        final Playlist notDelta = TestUtil.parsePlaylistFromResource("fullLivePlaylistForDelta.m3u8");

        try {
            PlaylistDeltaUtil.merge(previous, notDelta);
            fail("expected PlaylistException");
        } catch (PlaylistException e) {
            assertTrue(e.getErrors().contains(PlaylistError.DELTA_UPDATE_WITHOUT_SKIP));
        }
    }

    @Test
    public void buildAndWriteServerControlOnly() throws Exception {
        final Playlist playlist = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(9)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(4)
                        .withMediaSequenceNumber(1)
                        .withIsOngoing(true)
                        .withServerControlData(new ServerControlData.Builder()
                                .withCanSkipUntil(24.0f)
                                .withCanBlockReload(true)
                                .build())
                        .withTracks(Arrays.asList(track("http://example.com/a.ts", 4f)))
                        .build())
                .build();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PlaylistWriter(out, Format.EXT_M3U, Encoding.UTF_8).write(playlist);
        final String written = out.toString(Encoding.UTF_8.getValue());
        assertTrue(written.contains("#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0,CAN-BLOCK-RELOAD=YES"));
        assertFalse(written.contains("EXT-X-SKIP"));
    }

    @Test
    public void validationRejectsCanSkipUntilTooSmall() {
        final Playlist playlist = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(9)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(4)
                        .withMediaSequenceNumber(1)
                        .withIsOngoing(true)
                        .withServerControlData(new ServerControlData.Builder()
                                .withCanSkipUntil(10.0f) // < 6 * 4 = 24
                                .build())
                        .withTracks(Arrays.asList(track("http://example.com/a.ts", 4f)))
                        .build())
                .build();

        final PlaylistValidation validation = PlaylistValidation.from(playlist);
        assertFalse(validation.isValid());
        assertTrue(validation.getErrors().contains(PlaylistError.SERVER_CONTROL_CAN_SKIP_UNTIL_TOO_SMALL));
    }

    private static TrackData track(String uri, float duration) {
        return new TrackData.Builder()
                .withUri(uri)
                .withTrackInfo(new TrackInfo(duration, null))
                .build();
    }
}
