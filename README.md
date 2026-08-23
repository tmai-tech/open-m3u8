# open-m3u8

Java library for **parsing and writing HLS `.m3u8` playlists**, plus a local demo that inserts ads two ways:

Java parse/write lives in `library/`. SSAI/SGAI and the HTTP demo live in `apps/playlist-service/` (`./gradlew test`, `./gradlew runDemo` from the repo root).

- **SSAI** — classic server-side stitch (MediaTailor-style `CUE-OUT` / `CUE-IN` + `DISCONTINUITY`)
- **SGAI** — HLS Interstitials (`EXT-X-DATERANGE` + `X-ASSET-URI`)

Fork of [iheartradio/open-m3u8](https://github.com/iheartradio/open-m3u8). This repo adds SSAI stitch, SGAI inject, playlist delta updates, and the demo player.

Library scope is **playlist text ↔ Java models**. It does not transcode, decrypt, or host a production CDN.

- **Repo:** [tmai-tech/open-m3u8](https://github.com/tmai-tech/open-m3u8)
- **Static UI (GitHub Pages):** https://tmai-tech.github.io/open-m3u8/  
  Pages is static: ads are rewritten in the browser. Network will not show generated tags. For real `/s/{id}/manifest` URLs, run the local demo.

---

## Architecture

```
                    POST /api/session  { strategy, contentUrl, adUrl, breaks }
                                      │
                                      ▼
                               DemoSession
                          strategy = SSAI | SGAI
                                      │
         GET /s/{id}/manifest  ───────┴───────  GET /s/{id}/proxy?url=
                                      │
                          DemoPlaylistPipeline
              fetch origin → parse → apply(strategy) → rewrite URIs → write
                                      │
                 SSAI: PlaylistSsaiUtil.stitch
                 SGAI: PlaylistRewriteUtil.injectMediaTags
```

| | SSAI | SGAI |
|--|--|--|
| playlist-service | `PlaylistSsaiUtil.stitch` | `PlaylistRewriteUtil.injectMediaTags` |
| Player sees | Ad **segments inlined** in the media playlist | Content playlist + **DATERANGE**; player loads the ad |
| Tags | `DISCONTINUITY`, `CUE-OUT` / `CUE-OUT-CONT`, `CUE-IN` (MediaTailor order) | `EXT-X-DATERANGE` `CLASS=com.apple.hls.interstitial`, `X-PLAYOUT-LIMIT` |
| Ad length | Whole-segment trim (4s ads + max 12 → three segments) | Player stops the creative at `X-PLAYOUT-LIMIT` |

Master playlists are not stitched/injected. Child media playlists are transformed when fetched through `/s/{id}/proxy`.

**Local Java demo** (`DemoPlayerServer` on `:8765`) serves rewritten manifests so DevTools Network shows the tags. Optional Cloudflare Quick Tunnel exposes the same process as **https** so [hlsjs.video-dev.org](https://hlsjs.video-dev.org/demo/) can load it (a public HTTPS page cannot load `http://127.0.0.1`).

**GitHub Pages** has no Java process. `demo/rewrite.js` patches playlists inside hls.js (`pLoader`).

---

## How to use the demo

Needs **JDK 8+** (17 is fine).

```bash
git clone https://github.com/tmai-tech/open-m3u8.git
cd open-m3u8
./gradlew runDemo
# open http://127.0.0.1:8765/
```

`runHlsPlayer` and `runSsaiProxy` start the same server.

1. Choose **SSAI** or **SGAI**.
2. Content defaults can use Mux BBB; default ad is **Tears of Steel** (~4s segments).
3. Set **ad points** (`30, 90` or click the timeline) and **ad length**.
4. **Apply & play** creates a session and starts the player. **Generate only** writes the playlist without playing.
5. **AD 12s** on the video is a client overlay (cue windows for SSAI, `interstitialsManager` for SGAI).

| Button | Effect |
|--|--|
| Apply & play | Session + rewrite + start hls.js |
| Generate only | Session + rewrite, no playback |
| Copy | Local `http://127.0.0.1:8765/s/{id}/manifest` (VLC / this origin) |
| Copy HTTPS | Public `https://…trycloudflare.com/s/{id}/manifest` when a tunnel is up |
| Open in hls.js | Same-origin player (`/watch.html`) — no CORS/mixed-content |

### Official hls.js website

`https://hlsjs.video-dev.org` is HTTPS. It can play Mux (`https://test-streams.mux.dev/…`) but **not** `http://127.0.0.1`. That shows up as CORS / HTTP 0.

While the local demo is running:

```bash
# Windows / from this repo
build/tools/cloudflared.exe tunnel --url http://127.0.0.1:8765
```

Open the printed `https://….trycloudflare.com/` UI (or Generate on localhost and use **Copy HTTPS**). Paste that `https://…/s/{id}/manifest` into the official demo.

One-shot (no UI):

```
http://127.0.0.1:8765/play?strategy=ssai&content=<content.m3u8>&ad=<ad.m3u8>&splices=0,30&maxAd=12
```

---

## Library

Published artifact is still `0.2.4`. This tree is `0.2.7-SNAPSHOT`.

```gradle
implementation 'com.iheartradio.m3u8:open-m3u8:0.2.4'
```

```java
Playlist p = new PlaylistParser(in, Format.EXT_M3U, Encoding.UTF_8).parse();
new PlaylistWriter(out, Format.EXT_M3U, Encoding.UTF_8).write(p);
```

**SSAI stitch**

```java
Playlist stitched = PlaylistSsaiUtil.stitch(contentMedia, Arrays.asList(
    PlaylistSsaiUtil.AdBreak.builder()
        .withId("mid-1")
        .atOffsetSec(30f)
        .withAdPlaylist(adMedia, /* maxAdDurationSec */ 12f)
        .build()));
```

**SGAI inject**

```java
Playlist rewritten = PlaylistRewriteUtil.injectMediaTags(
    media,
    PlaylistRewriteUtil.InjectConfig.builder()
        .addBreak(new PlaylistRewriteUtil.InterstitialBreak(
            "user-ad-1", 30f, 15f, adAssetUri))
        .build());
```

**Delta updates:** `PlaylistDeltaUtil.merge(previous, delta)`.

Tag matrix: [docs/SUPPORTED_FEATURES.md](docs/SUPPORTED_FEATURES.md). Demo details: [demo/README.md](demo/README.md).

```bash
./gradlew test
```

---

## Docs

- Spec: [draft-pantos-hls-rfc8216bis](https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis)
- Supported tags: [docs/SUPPORTED_FEATURES.md](docs/SUPPORTED_FEATURES.md)
