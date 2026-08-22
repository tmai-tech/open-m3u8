# Demo backlog

Client-only ideas for a later pass. Library stitch/inject APIs stay unchanged.

## Custom player chrome (seek during ads)

The page currently keeps native `<video controls>` and **snaps seeks** so ads are unskippable (SSAI cue windows + SGAI primary/interstitial media).

Replace native controls with a custom bar:

- During an ad: progress is ad-only or locked — no drag
- During content: drag allowed, but dropping on/after an unwatched ad starts that ad
- Keep play/pause, volume, fullscreen
- Work in fullscreen and on mobile; do not rely on overlaying the native seek bar

Until then, seek blocking is `onSeekingBlockAds` in `demo/index.html`.

## SSAI: resume at seek target after a forced mid-roll

Implemented in `demo/index.html` (`ssaiPendingSeek`). Jumping past a cue window plays that ad, then seeks to the scrub time. SGAI still tabled below.

## SGAI: resume at the user's seek target after a forced mid-roll

**Tabled.** Several client-only attempts failed (seek interceptor, `currentTime` setter, resume lock vs `attachPrimary`).

hls.js honors `X-RESUME-OFFSET=0` and re-attaches primary at the break (e.g. 30s). Desired: scrub to 5:00 → play unwatched ad at 0:30 → continue at 5:00.

Pick up with custom chrome (above) owning the timeline, or `interstitialsManager.primary` only after `hasPlayed`. Do not change library APIs or default `X-RESUME-OFFSET` for natural play-through.
