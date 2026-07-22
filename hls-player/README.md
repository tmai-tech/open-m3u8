# HLS Player + local rewrite proxy

Paste a content `.m3u8`, mark interstitial ad breaks, and play. Tags are injected by a **local Python proxy** so they appear in DevTools **Network**.

## Run (important)

Use the rewrite proxy, not plain `http.server`:

```bash
cd hls-player
python3 proxy_server.py
```

Open [http://127.0.0.1:8765/](http://127.0.0.1:8765/).

| Endpoint | Purpose |
|----------|---------|
| `GET /` | Player UI |
| `POST /api/rewrite-config` | Store start + ad break settings |
| `GET /api/rewrite-config` | Read settings |
| `GET /api/health` | `{ "proxy": true }` |
| `GET /proxy?url=<encoded>` | Fetch remote URL; rewrite m3u8; proxy segments |

## Playback path

In the UI, **Manifest delivery**:

1. **Local rewrite proxy** (default) — player POSTs config, then loads `/proxy?url=…`. Network shows rewritten bodies.
2. **Direct CDN + client inject** — old behavior; Network shows original CDN text.

### Seeing tags in Network

1. Open DevTools → **Network**.
2. Filter: `proxy`
3. Click a request with `X-Playlist-Kind: media` (or Response header `X-Playlist-Rewritten: 1`).
4. **Response** tab — you should see e.g.:

```m3u8
#EXT-X-VERSION:7
#EXT-X-DATERANGE:ID="user-ad-1",CLASS="com.apple.hls.interstitial",START-DATE=2020-01-01T00:00:30.000Z,DURATION=15,X-ASSET-URI="http://127.0.0.1:8765/proxy?url=…",X-RESUME-OFFSET=0
#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:00.000Z
```

Master playlists only get **URI rewriting** (children point at `/proxy?url=…`). Interstitial tags are injected on **media** playlists.

## Ad interstitials

1. Play content (or load so the timeline has duration).
2. Set interstitial URL, click timeline / add at playhead.
3. **Apply ads & reload** (or Play) — config is pushed to the proxy automatically.

## Notes

- Proxy allows only `http`/`https` remote targets.
- All segment + child playlist URIs are rewritten through the proxy (avoids browser CORS on many CDNs).
- For local use only — not hardened as a public open proxy.
