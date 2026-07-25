# HLS Player + open-m3u8 rewrite proxy

Paste a content `.m3u8`, mark interstitial ad breaks, and play. Tags are injected by a
**Java rewrite proxy** that uses the **open-m3u8** library (`PlaylistParser` → model builders
→ `PlaylistWriter`) so rewritten playlists appear in DevTools **Network**.

## Run

From the repository root:

```bash
./gradlew runHlsPlayer
# optional port: ./gradlew runHlsPlayer -Pport=8765
```

Open [http://127.0.0.1:8765/](http://127.0.0.1:8765/).

| Endpoint | Purpose |
|----------|---------|
| `GET /` | Player UI (`hls-player/index.html`) |
| `POST /api/rewrite-config` | Store start + ad break settings |
| `GET /api/rewrite-config` | Read settings |
| `GET /api/health` | `{ "proxy": true, "engine": "open-m3u8" }` |
| `GET /proxy?url=<encoded>` | Fetch remote URL; rewrite m3u8 via open-m3u8; proxy segments |

## What the library does

For each proxied `.m3u8`:

1. **Parse** with `PlaylistParser` (lenient)
2. **Inject** on media playlists via `PlaylistRewriteUtil.injectMediaTags`:
   - optional `EXT-X-START`
   - interstitial `EXT-X-DATERANGE` (`CLASS="com.apple.hls.interstitial"`, `X-ASSET-URI`, …)
   - synthetic `EXT-X-PROGRAM-DATE-TIME` when the playlist has none
3. **Rewrite URIs** with `PlaylistRewriteUtil.rewriteUris` so child playlists / segments / keys /
   maps / `X-ASSET-URI` go through `/proxy?url=…`
4. **Write** with `PlaylistWriter`

No Python string builders.

## Playback path

In the UI, **Manifest delivery**:

1. **Local rewrite proxy** (default) — Network shows rewritten bodies (`X-Rewrite-Engine: open-m3u8`).
2. **Direct CDN + client inject** — client-side fallback; Network stays original.

### Seeing tags in Network

1. DevTools → **Network** → filter `proxy`
2. Open a response with `X-Playlist-Kind: media` / `X-Playlist-Rewritten: 1`
3. Confirm `#EXT-X-DATERANGE` / `com.apple.hls.interstitial`

## Notes

- Demo tooling only; production hardening is out of scope.
- Proxy allows only `http`/`https` remote targets.
- Requires JDK 8+ (build uses Gradle 7.6+).
