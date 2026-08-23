# HLS demo (SSAI / SGAI)

The playable UI is **`apps/web-client/`** (HTML shell + `css/` + `js/` modules). This folder keeps notes, `BACKLOG.md`, and a leftover `watch.html`.

One player. You pick the ad strategy.

**Hosted (static):** https://tmai-tech.github.io/open-m3u8/  
GitHub Pages has no Java proxy: `rewrite.js` patches playlists in the browser. Network stays the original CDN.

**Local proxy:** `./gradlew runDemo` → http://127.0.0.1:8765/  
Default ad is Tears of Steel (~4s segments). After Generate, **Copy** is localhost; **Copy HTTPS** appears when a Cloudflare tunnel has been used (for the official https hls.js demo). **Open in hls.js** is a same-origin player (`watch.html`).

Local Java proxy (shareable `/s/{id}/manifest` URLs):

```bash
./gradlew runDemo
# open http://127.0.0.1:8765/
```

`runHlsPlayer` and `runSsaiProxy` start the same server.

| Endpoint | Purpose |
|----------|---------|
| `GET /` | Player UI |
| `GET /api/health` | `{ "strategies": ["sgai","ssai"], "engine": "open-m3u8-demo" }` |
| `POST /api/session` | Create session (`strategy` required; omit → `ssai`) |
| `GET /api/session?id=` | Session + `manifestUrl` |
| `GET /play?strategy=&content=&ad=&splices=` | Create session + **302** to `/s/{id}/manifest` |
| `GET /s/{id}/manifest` | Entry playlist (transform + URI rewrite) |
| `GET /s/{id}/proxy?url=` | Child playlists (same transform) + segment passthrough |

## Strategies

| | SSAI (default) | SGAI |
|--|--|--|
| playlist-service | `PlaylistSsaiUtil.stitch` | `PlaylistRewriteUtil.injectMediaTags` |
| Player sees | Ad segments inlined with CUE-OUT / CUE-IN | Content only + `EXT-X-DATERANGE` / `X-ASSET-URI` |
| hls.js interstitials | Off | On |

Both share `DemoPlaylistPipeline`: fetch → parse → apply(strategy) → rewrite URIs through `/s/{id}/proxy` → write.

## Try it

1. Open http://127.0.0.1:8765/
2. **Fill content + ad demo** (Mux samples)
3. SSAI: splices `0, 30` → **Apply & play** — preview shows CUE tags
4. Switch to SGAI → **Apply & play** — preview shows `com.apple.hls.interstitial`, no CUE
5. Copy the manifest URL into VLC / another tab

One-shot:

```
http://127.0.0.1:8765/play?strategy=ssai&content=<content.m3u8>&ad=<ad.m3u8>&splices=0,30
```

Demo only: `http`/`https` origins, sessions expire after 2 hours, no auth.
