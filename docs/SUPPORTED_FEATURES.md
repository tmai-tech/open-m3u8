# Supported HLS tags and features

This document catalogs what **open-m3u8** can parse and write today, with sample playlists.

Library scope: **playlist text ↔ Java models only**. It does not download segments, decrypt media, or perform ABR.

Spec reference: [HTTP Live Streaming (pantos / RFC 8216 bis)](https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis).

---

## Formats and encodings

| Item | Support |
|------|---------|
| `Format.EXT_M3U` | Extended HLS (primary) |
| `Format.M3U` | Simple URI list + comments |
| `Encoding.UTF_8` | Typical `.m3u8` (BOM stripped on read) |
| `Encoding.WINDOWS_1252` | Legacy `.m3u` |
| `ParsingMode.STRICT` | Unknown EXT tags fail |
| `ParsingMode.LENIENT` | Unknown tags kept; negative numbers allowed |

Public entry points: `PlaylistParser`, `PlaylistWriter`, model `Builder` / `buildUpon()`.

---

## Tag support matrix

### Basic

| Tag | Parse | Write | Notes |
|-----|:-----:|:-----:|-------|
| `EXTM3U` | yes | yes | Required header for EXT_M3U |
| `EXT-X-VERSION` | yes | yes | Compatibility version on `Playlist` |

### Master (multivariant) playlist

| Tag | Parse | Write | Notes |
|-----|:-----:|:-----:|-------|
| `EXT-X-MEDIA` | yes | yes | AUDIO / VIDEO / SUBTITLES / CLOSED-CAPTIONS |
| `EXT-X-STREAM-INF` | yes | yes | Variant streams + URI line |
| `EXT-X-I-FRAME-STREAM-INF` | yes | yes | I-frame only variants |

**`EXT-X-MEDIA` attributes:** `TYPE`, `URI`, `GROUP-ID`, `LANGUAGE`, `ASSOC-LANGUAGE`, `NAME`, `DEFAULT`, `AUTOSELECT`, `FORCED`, `INSTREAM-ID`, `CHARACTERISTICS`, `CHANNELS`

**`EXT-X-STREAM-INF` / `I-FRAME-STREAM-INF` attributes:** `BANDWIDTH`, `AVERAGE-BANDWIDTH`, `CODECS`, `RESOLUTION`, `FRAME-RATE`, `VIDEO`, `AUDIO`, `SUBTITLES`, `CLOSED-CAPTIONS`, `PROGRAM-ID` (legacy), plus `URI` on I-frame tag

### Media playlist headers

| Tag | Parse | Write | Notes |
|-----|:-----:|:-----:|-------|
| `EXT-X-TARGETDURATION` | yes | yes | |
| `EXT-X-MEDIA-SEQUENCE` | yes | yes | |
| `EXT-X-PLAYLIST-TYPE` | yes | yes | `EVENT` \| `VOD` |
| `EXT-X-START` | yes | yes | `TIME-OFFSET`, `PRECISE` |
| `EXT-X-I-FRAMES-ONLY` | yes | yes | Requires version ≥ 4 |
| `EXT-X-ENDLIST` | yes | yes | Absence ⇒ ongoing / live |
| `EXT-X-ALLOW-CACHE` | yes* | no-op | Deprecated; accepted, not written |
| `EXT-X-SERVER-CONTROL` | yes | yes | Delta updates / LL-HLS delivery |
| `EXT-X-DEFINE` | yes | yes | SGAI / variable substitution names |
| `EXT-X-DATERANGE` | yes | yes | SSAI / SGAI metadata |
| `EXT-X-SKIP` | yes | yes | Playlist delta update body |

**`EXT-X-SERVER-CONTROL`:** `CAN-SKIP-UNTIL`, `CAN-SKIP-DATERANGES`, `HOLD-BACK`, `PART-HOLD-BACK`, `CAN-BLOCK-RELOAD`

**`EXT-X-SKIP`:** `SKIPPED-SEGMENTS`, `RECENTLY-REMOVED-DATERANGES` (tab-delimited IDs)

**`EXT-X-DEFINE`:** `NAME`+`VALUE`, or `IMPORT`, or `QUERYPARAM` (exactly one mode)

**`EXT-X-DATERANGE`:** `ID`, `CLASS`, `START-DATE`, `END-DATE`, `DURATION`, `PLANNED-DURATION`, `SCTE35-OUT` / `IN` / `CMD`, `X-ASSET-URI`, `X-RESTRICTIONS`, `X-RESUME-OFFSET`

### Media segment tags

| Tag | Parse | Write | Notes |
|-----|:-----:|:-----:|-------|
| `EXTINF` | yes | yes | Duration + optional title |
| URI line | yes | yes | Segment / playlist location |
| `EXT-X-KEY` | yes | yes | AES-128 / SAMPLE-AES / NONE |
| `EXT-X-MAP` | yes | yes | Init section (fMP4) |
| `EXT-X-BYTERANGE` | yes | yes | Sub-range of resource |
| `EXT-X-DISCONTINUITY` | yes | yes | |
| `EXT-X-PROGRAM-DATE-TIME` | yes | limited | Parsed onto next track; not always re-emitted as standalone history |
| `EXT-X-CUE-OUT` | yes | yes | SSAI ad break start (+ optional duration) |
| `EXT-X-CUE-OUT-CONT` | yes | yes | Slash form or attribute form |
| `EXT-X-CUE-IN` | yes | yes | SSAI ad break end |

**`EXT-X-KEY` attributes:** `METHOD`, `URI`, `IV`, `KEYFORMAT`, `KEYFORMATVERSIONS`

**`EXT-X-MAP` attributes:** `URI`, `BYTERANGE`

### Not implemented (examples)

These appear in modern HLS / LL-HLS but are **not** first-class tags in this library (STRICT mode will reject them; LENIENT keeps the raw lines as unknown tags):

- `EXT-X-PART`, `EXT-X-PART-INF`, `EXT-X-PRELOAD-HINT`, `EXT-X-RENDITION-REPORT`
- `EXT-X-GAP`, `EXT-X-BITRATE`, `EXT-X-DISCONTINUITY-SEQUENCE`
- `EXT-X-SESSION-DATA`, `EXT-X-SESSION-KEY`, `EXT-X-CONTENT-STEERING`
- `EXT-X-INDEPENDENT-SEGMENTS` (as a dedicated model)

---

## Feature helpers

| Feature | API |
|---------|-----|
| Playlist delta merge | `PlaylistDeltaUtil.merge(previous, delta)` |
| Delta request URI | `PlaylistDeltaUtil.appendSkipDirective(uri, skipDateRanges)` |
| Model validation | `PlaylistValidation.from(playlist)` |
| Build / copy | `*.Builder`, `buildUpon()` on data classes |

---

## Sample: master (multivariant) playlist

```m3u8
#EXTM3U
#EXT-X-VERSION:6
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",CHANNELS="2",URI="audio/en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Español",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="es",URI="audio/es.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",FORCED=NO,URI="subs/en.m3u8"
#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1",DEFAULT=YES,AUTOSELECT=YES,INSTREAM-ID="CC1"
#EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1000000,RESOLUTION=640x360,FRAME-RATE=30.000,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aac",SUBTITLES="subs",CLOSED-CAPTIONS="cc"
low/prog.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2560000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1280x720,FRAME-RATE=30.000,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aac",SUBTITLES="subs",CLOSED-CAPTIONS="cc"
mid/prog.m3u8
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=163286,RESOLUTION=640x360,CODECS="avc1.4d401e",URI="low/iframe.m3u8"
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=394234,RESOLUTION=1280x720,CODECS="avc1.4d401f",URI="mid/iframe.m3u8"
```

---

## Sample: VOD media playlist (encryption, MAP, byterange, discontinuity)

```m3u8
#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-START:TIME-OFFSET=-4.5,PRECISE=YES
#EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/key0",IV=0x0123456789ABCDEF0123456789ABCDEF,KEYFORMAT="identity",KEYFORMATVERSIONS="1"
#EXTINF:9.009,First
#EXT-X-BYTERANGE:10000@0
https://cdn.example.com/seg0.mp4
#EXTINF:9.009,Second
#EXT-X-BYTERANGE:10000@10000
https://cdn.example.com/seg0.mp4
#EXT-X-DISCONTINUITY
#EXT-X-KEY:METHOD=NONE
#EXTINF:3.003,
https://cdn.example.com/seg1.ts
#EXT-X-ENDLIST
```

---

## Sample: live media playlist with delta-update advertising

Server advertises that clients may request `_HLS_skip=YES` / `v2`.

```m3u8
#EXTM3U
#EXT-X-VERSION:9
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:100
#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0,CAN-SKIP-DATERANGES=YES,HOLD-BACK=12.0,CAN-BLOCK-RELOAD=YES
#EXTINF:4.0,
https://cdn.example.com/seg100.ts
#EXTINF:4.0,
https://cdn.example.com/seg101.ts
#EXTINF:4.0,
https://cdn.example.com/seg102.ts
#EXTINF:4.0,
https://cdn.example.com/seg103.ts
#EXTINF:4.0,
https://cdn.example.com/seg104.ts
#EXTINF:4.0,
https://cdn.example.com/seg105.ts
```

### Delta response (after `_HLS_skip=v2`)

Older segments replaced by `EXT-X-SKIP`; client merges with previous copy via `PlaylistDeltaUtil.merge`.

```m3u8
#EXTM3U
#EXT-X-VERSION:9
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:100
#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0,CAN-SKIP-DATERANGES=YES,HOLD-BACK=12.0,CAN-BLOCK-RELOAD=YES
#EXT-X-SKIP:SKIPPED-SEGMENTS=3,RECENTLY-REMOVED-DATERANGES="ad-1	ad-2"
#EXTINF:4.0,
https://cdn.example.com/seg103.ts
#EXTINF:4.0,
https://cdn.example.com/seg104.ts
#EXTINF:4.0,
https://cdn.example.com/seg105.ts
#EXTINF:4.0,
https://cdn.example.com/seg106.ts
```

---

## Sample: SSAI (cue tags + SCTE-35 daterange)

```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-DATERANGE:ID="break-1",START-DATE=2024-06-15T12:00:00.000Z,PLANNED-DURATION=30.0,SCTE35-OUT="/DAlAAAAAAAAAP/wFAUAAAABf+//wpiWkjAACgAIAAAAAAD8AEw="
#EXTINF:10.0,
http://example.com/content/seg0.ts
#EXT-X-CUE-OUT:30.0
#EXTINF:10.0,
http://example.com/ads/ad0.ts
#EXT-X-CUE-OUT-CONT:10.0/30.0
#EXTINF:10.0,
http://example.com/ads/ad1.ts
#EXT-X-CUE-OUT-CONT:ElapsedTime=20.0,Duration=30.0
#EXTINF:10.0,
http://example.com/ads/ad2.ts
#EXT-X-CUE-IN
#EXT-X-DISCONTINUITY
#EXTINF:10.0,
http://example.com/content/seg1.ts
#EXT-X-ENDLIST
```

---

## Sample: SGAI (DEFINE + interstitial DATERANGE)

```m3u8
#EXTM3U
#EXT-X-VERSION:11
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-DEFINE:NAME="userId",VALUE="user-12345"
#EXT-X-DEFINE:NAME="sessionToken",VALUE="tok-abc-xyz"
#EXT-X-DATERANGE:ID="adbreak-1",CLASS="com.apple.hls.interstitial",START-DATE=2024-06-15T12:00:00.000Z,DURATION=30.0,X-ASSET-URI="https://ads.example.com/vast?user={$userId}&session={$sessionToken}",X-RESTRICTIONS="SKIP,JUMP",X-RESUME-OFFSET=0.0
#EXTINF:10.0,
http://example.com/content/seg0.ts
#EXTINF:10.0,
http://example.com/content/seg1.ts
#EXT-X-ENDLIST
```

---

## Sample: simple M3U (non-extended)

```m3u
# comment
http://example.com/track1.mp3
http://example.com/track2.mp3
```

Parsed with `Format.M3U` into a non-extended `MediaPlaylist` of track URIs only.

---

## Quick parse / write / merge

```java
// Parse
Playlist p = new PlaylistParser(in, Format.EXT_M3U, Encoding.UTF_8).parse();

// Write
new PlaylistWriter(out, Format.EXT_M3U, Encoding.UTF_8).write(p);

// Delta update merge
String deltaUri = PlaylistDeltaUtil.appendSkipDirective(mediaPlaylistUri, true); // _HLS_skip=v2
Playlist merged = PlaylistDeltaUtil.merge(previous, delta);
```

For fixture-backed examples used in tests, see `src/test/resources/*.m3u8`.
