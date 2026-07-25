# SSAI VOD Proxy (open-m3u8)

Classic server-side ad insertion for **VOD**: one content URL, one ad URL, and splice
points (seconds). The proxy stitches the **same ad pod** at every splice into media
playlists using `PlaylistSsaiUtil`, then rewrites all segment/key/map URIs through the
proxy so any HLS player (Safari, hls.js, ExoPlayer, AVPlayer) can play the result.

## Run

```bash
./gradlew runSsaiProxy
# optional: ./gradlew runSsaiProxy -Pport=8766
```

Open [http://127.0.0.1:8766/](http://127.0.0.1:8766/).

## API

| Endpoint | Purpose |
|----------|---------|
| `GET /` | Player UI (`ssai-player/index.html`) |
| `GET /api/health` | Health JSON |
| `POST /api/session` | Create session: `{ "contentUrl", "adUrl", "splices": [30, 90], "maxAdDurationSec": 30 }` |
| `GET /api/session?id=` | Session info + manifest URL |
| `GET /play?content=&ad=&splices=30,90` | Create session + **302** to stitched entry |
| `GET /s/{id}/manifest` | Entry playlist (stitch if media; rewrite master children) |
| `GET /s/{id}/proxy?url=` | Child playlists (stitched) + media segment passthrough |

### One-shot play URL

```
http://127.0.0.1:8766/play?content=https%3A%2F%2Fcdn.example%2Fvod.m3u8&ad=https%3A%2F%2Fads.example%2Fpod.m3u8&splices=0,30,90
```

- `splices=0` → pre-roll  
- Offsets snap to the nearest content segment boundary  
- Same ad creative is inserted at every splice  
- `maxAdDurationSec` (default **30**) trims each ad pod to at most that many seconds (whole segments). Use `0` for no trim. Optional query: `&maxAd=30` on `/play`  


### Session JSON

```bash
curl -s -X POST http://127.0.0.1:8766/api/session \
  -H 'Content-Type: application/json' \
  -d '{
    "contentUrl": "https://cdn.example/content.m3u8",
    "adUrl": "https://ads.example/ad.m3u8",
    "splices": [30, 90]
  }'
```

Response includes `manifestUrl` — point any HLS player at that URL.

## Pipeline (per media playlist)

1. Fetch content `.m3u8`
2. Parse with `PlaylistParser` (lenient)
3. Fetch/cache ad media playlist (if ad is master → first variant)
4. `PlaylistSsaiUtil.stitch(content, breaks)` — CUE-OUT / CONT / CUE-IN + DISCONTINUITY
5. `PlaylistRewriteUtil.rewriteUris` → `/s/{id}/proxy?url=…`
6. `PlaylistWriter` → response (`Content-Type: application/vnd.apple.mpegurl`)

Master playlists are **not** stitched; child media URIs are rewritten so each variant is
stitched on demand (same offsets / same ad).

## Headers of interest

| Header | Meaning |
|--------|---------|
| `X-Rewrite-Engine: open-m3u8-ssai` | Stitch path |
| `X-Ssai-Mode: classic-stitch` | Inline segment SSAI (not interstitials) |
| `X-Playlist-Rewritten: 1` | Body is library-generated |

## Notes

- **VOD first** — expects content with `#EXT-X-ENDLIST` (or non-ongoing). Live is out of scope here.
- Ad and content should be compatible (TS vs fMP4, codecs). Transcoding is not performed.
- Demo only: no auth, sessions expire after 2 hours, `http`/`https` origins only.
- For interstitial-style ads (DATERANGE only), use `./gradlew runHlsPlayer` instead.
