# playlist-service

HTTP demo that uses the `library` module to parse and write playlists, then applies ads:

- `com.iheartradio.m3u8.ads.PlaylistSsaiUtil` — SSAI stitch (`CUE-OUT` / `CUE-IN`)
- `com.iheartradio.m3u8.ads.PlaylistRewriteUtil` — SGAI interstitials (`EXT-X-DATERANGE`) plus URI rewrite
- `com.iheartradio.m3u8.http.DemoPlayerServer` — sessions and proxy

```bash
# from repo root
./gradlew :apps:playlist-service:runDemo
# http://127.0.0.1:8765/
```

Static UI is `apps/web-client`. This module does not own parse/write of raw HLS tags.
