# Demo backlog

Client-only ideas for a later pass. Library stitch/inject APIs stay unchanged.

## Custom player chrome (seek during ads)

The page currently keeps native `<video controls>` and **snaps seeks** so ads are unskippable (SSAI cue windows + SGAI primary/interstitial media).

Replace native controls with a custom bar:

- During an ad: progress is ad-only or locked — no drag
- During content: drag allowed, but dropping on/after an unwatched ad starts that ad
- Keep play/pause, volume, fullscreen
- Work in fullscreen and on mobile; do not rely on overlaying the native seek bar

Until then, seek blocking is `apps/web-client/js/seek-ssai.js` and `seek-sgai.js`.

## SSAI: resume at seek target after a forced mid-roll

Implemented in `apps/web-client/js/seek-ssai.js` (`ssaiSeekQueue`). Jumping past cue windows plays **every** crossed ad in order, then seeks to the scrub time. SGAI still tabled below.

## SGAI: resume at the user's seek target after a forced mid-roll

**Tabled.** Several client-only attempts failed (seek interceptor, `currentTime` setter, resume lock vs `attachPrimary`).

hls.js honors `X-RESUME-OFFSET=0` and re-attaches primary at the break (e.g. 30s). Desired: scrub to 5:00 → play unwatched ad at 0:30 → continue at 5:00.

Pick up with custom chrome (above) owning the timeline, or `interstitialsManager.primary` only after `hasPlayed`. Do not change library APIs or default `X-RESUME-OFFSET` for natural play-through.

## SSAI: `mediaError / bufferAppendError` on mismatched ads

hls.js appends SSAI ad segments into the **same** MSE `SourceBuffer` that opened on the content init. Tags on the stitched playlist can be valid (`CUE-OUT` / `DISCONTINUITY` / re-announced `EXT-X-MAP`) and the player still throws `mediaError / bufferAppendError`.

Reproduced against Summer on Mars (muxed fMP4, H.264 + AAC, 1280×720, 24 fps):

| Ad | Why the append fails |
|---|---|
| Unified Tears of Steel (MPEG-TS) | Container mix: `video/mp4` buffer cannot append `.ts` |
| Apple BipBop / Angel One | First variant is **video-only**; audio is a separate rendition. Content init is 2-track |
| GIFF Day 1 (library house ad at 10s) | Muxed fMP4, but 30 fps / different init than Mars |

Same-encode self-clips (copy of the title `init.m4s` + later `.m4s`) append. **SGAI** also plays the mismatched ads because the creative is a separate asset.

Do not change library stitch APIs for this. Later pass: reject or warn at session create when the ad media playlist is not the same container / track layout as content; or transcode house ads to the title encode. Until then, SSAI on the library rail may still error with GIFF as the default ad.

## Watch Library: delete from the rail

**Later.** Cancel / Delete is on the Uploads tab (`DELETE /api/ingest?id=`). The Watch rail still only plays ready titles.

## Library: display titles for uploaded MP4s

**Tabled.** Folder id is a slug of the filename (unique). Display title is cleaned from that name: `grok-video-<uuid>.mp4` → **Grok clip**, `My Holiday.mp4` → **My Holiday**. Two Grok exports therefore share the same rail label.

Leave it. Later pass: a title field on upload, or disambiguate (`Grok clip 2`, date, duration). Do not put the raw UUID back on the card.
