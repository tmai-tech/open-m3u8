# playlist-service

HTTP demo that uses the `library` module to parse and write playlists, then applies ads:

- `PlaylistSsaiUtil` — SSAI stitch (`CUE-OUT` / `CUE-IN`)
- `PlaylistRewriteUtil` — SGAI interstitials (`EXT-X-DATERANGE`) plus URI rewrite for the proxy

```bash
# from repo root
./gradlew :apps:playlist-service:runDemo
# http://127.0.0.1:8765/
```

Static UI is `apps/web-client`. This module does not own parse/write of raw HLS tags.
