# open-m3u8

Java library for parsing and writing HLS M3U8 playlists (MIT).

Targets [HLS](https://datatracker.ietf.org/doc/html/draft-pantos-http-live-streaming-16). Public API may change until 1.0.

## Install

```gradle
implementation 'com.iheartradio.m3u8:open-m3u8:0.2.4'
```

```xml
<dependency>
  <groupId>com.iheartradio.m3u8</groupId>
  <artifactId>open-m3u8</artifactId>
  <version>0.2.4</version>
</dependency>
```

## Parse

```java
PlaylistParser parser = new PlaylistParser(inputStream, Format.EXT_M3U, Encoding.UTF_8);
Playlist playlist = parser.parse();
```

## Write

```java
PlaylistWriter writer = new PlaylistWriter(outputStream, Format.EXT_M3U, Encoding.UTF_8);
writer.write(playlist);
```

Build playlists with `Builder` / `buildUpon()` on `Playlist`, `MediaPlaylist`, `TrackData`, etc.

## Playlist Delta Updates

Parse/write `EXT-X-SERVER-CONTROL` and `EXT-X-SKIP`. Merge a delta refresh with a previous playlist:

```java
// Request only changes (client networking is out of scope)
String deltaUri = PlaylistDeltaUtil.appendSkipDirective(playlistUri, /* skipDateRanges */ false);

Playlist previous = /* last full media playlist */;
Playlist delta = new PlaylistParser(deltaStream, Format.EXT_M3U, Encoding.UTF_8).parse();
Playlist merged = PlaylistDeltaUtil.merge(previous, delta);
```

## Supported tags

Full tag/feature matrix and annotated samples: [docs/SUPPORTED_FEATURES.md](docs/SUPPORTED_FEATURES.md).

HLS Interstitial `EXT-X-DATERANGE` attributes include `X-ASSET-URI`, `X-ASSET-LIST`, `X-RESTRICT`,
`X-RESUME-OFFSET`, `X-PLAYOUT-LIMIT`, `X-SNAP`, `X-CONTENT-MAY-VARY`, `X-TIMELINE-OCCUPIES`,
`X-TIMELINE-STYLE`.

### Inject / rewrite helpers

```java
Playlist rewritten = PlaylistRewriteUtil.rewrite(
    playlist,
    playlistUrl,
    PlaylistRewriteUtil.InjectConfig.builder()
        .withStartOverride(new StartData(10f, true))
        .addBreak(new PlaylistRewriteUtil.InterstitialBreak(
            "user-ad-1", /* offsetSec */ 30f, /* durationSec */ 15f, adAssetUri))
        .build(),
    absoluteUri -> "http://127.0.0.1:8765/proxy?url=" + URLEncoder.encode(absoluteUri, "UTF-8"));
```

### Classic SSAI (stitch ads into the media playlist)

Inline ad segments with `EXT-X-CUE-OUT` / `CUE-OUT-CONT` / `CUE-IN` and discontinuities
(MediaTailor-style manifest stitching — not HLS Interstitials):

```java
PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
    .withId("mid-1")
    .atOffsetSec(30f)              // or afterTrackIndex / preRoll / postRoll
    .withAdPlaylist(adMediaPlaylist) // or addAdSegment(uri, duration)
    .build();

Playlist stitched = PlaylistSsaiUtil.stitch(contentMediaPlaylist, Arrays.asList(mid));
```

### Demo player (SSAI or SGAI)

One local UI. Pick **classic SSAI stitch** or **SGAI interstitials**; both go through
`DemoPlaylistPipeline` (parse → apply strategy → rewrite URIs → write).

Live demo (static GitHub Pages — ads rewritten in the browser):
**https://tmai-tech.github.io/open-m3u8/**

```bash
./gradlew runDemo
# open http://127.0.0.1:8765/
```

| Strategy | Library call | What the player sees |
|----------|--------------|----------------------|
| **SSAI** (default) | `PlaylistSsaiUtil.stitch` | Ad segments inlined with CUE-OUT / CUE-IN |
| **SGAI** | `PlaylistRewriteUtil.injectMediaTags` | `EXT-X-DATERANGE` + `X-ASSET-URI` |

```
# one-shot playable manifest
# /play?strategy=ssai&content=<content.m3u8>&ad=<ad.m3u8>&splices=0,30,90
```

See [demo/README.md](demo/README.md).

## Code coverage

Measured with **JaCoCo 0.8.7** over the full unit-test suite (**68 tests, all passing**).

| Metric | Coverage |
|--------|----------|
| **Line** | **74.0%** (2131 / 2881) |
| **Branch** | **55.9%** (617 / 1104) |
| **Method** | **78.5%** (852 / 1085) |
| **Instruction** | **71.7%** (10633 / 14829) |
| **Class** | **98.8%** (238 / 241) |

### By package

| Package | Line | Branch |
|---------|-----:|-------:|
| `com.iheartradio.m3u8` | 80.4% | 64.1% |
| `com.iheartradio.m3u8.data` | 61.5% | 43.0% |

Gaps are mostly data-model builders/`equals`/`toString`, simple M3U write path (`M3uWriter`), and some master/media attribute edge cases. After a local test run, HTML report path: `build/reports/jacoco/test/html/index.html`.

## Docs

- Spec: [draft-pantos-hls-rfc8216bis](https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis) (Playlist Delta Updates)
- Supported tags: [docs/SUPPORTED_FEATURES.md](docs/SUPPORTED_FEATURES.md)
- Issues / PRs welcome
