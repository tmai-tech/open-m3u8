import { $ } from "./dom.js";
import { state } from "./state.js";
import { advanceSsaiSeekQueue, isSsaiAdPlaying, ssaiAdOrdinal, ssaiRemaining } from "./seek-ssai.js";
import { isSgaiAdPlaying, sgaiAdOrdinal, sgaiRemaining } from "./seek-sgai.js";

export function formatRemain(sec) {
  const s = Math.max(0, Math.ceil(sec));
  if (s < 60) return s + "s";
  const m = Math.floor(s / 60);
  return m + ":" + String(s % 60).padStart(2, "0");
}

export function setAdLabel(on, remainSec, ordinal) {
  const badge = $("adBadge");
  const count = $("adCount");
  const idx = $("adIndex");
  const text = on ? formatRemain(remainSec || 0) : "";
  if (badge && badge.hidden !== !on) badge.hidden = !on;
  if (count && count.textContent !== text) count.textContent = text;
  if (idx) {
    const show = !!(on && ordinal && ordinal.total > 1);
    const label = show ? " " + ordinal.index + " of " + ordinal.total : "";
    if (idx.hidden !== !show) idx.hidden = !show;
    if (idx.textContent !== label) idx.textContent = label;
  }
}

export function refreshAdLabel() {
  if (!state.watchingAds) {
    setAdLabel(false);
    return;
  }
  if (state.strategy === "sgai") {
    const on = isSgaiAdPlaying();
    setAdLabel(on, on ? sgaiRemaining() : 0, on ? sgaiAdOrdinal() : null);
  } else {
    const on = isSsaiAdPlaying();
    // hls.js only: advancing the seek queue on cue-out exit. Shaka owns the
    // playhead; snapping here replays the SSAI pod (official demo does not).
    if (!state.shaka && state.ssaiWasInAd && !on) advanceSsaiSeekQueue();
    state.ssaiWasInAd = on;
    setAdLabel(on, on ? ssaiRemaining() : 0, on ? ssaiAdOrdinal() : null);
  }
}

export function startAdLabelWatch() {
  state.watchingAds = true;
  if (state.adPollTimer) clearInterval(state.adPollTimer);
  state.adPollTimer = setInterval(refreshAdLabel, 250);
  const v = $("video");
  if (v) v.addEventListener("timeupdate", refreshAdLabel);
}

export function stopAdLabelWatch() {
  if (state.adPollTimer) {
    clearInterval(state.adPollTimer);
    state.adPollTimer = null;
  }
  const v = $("video");
  if (v) v.removeEventListener("timeupdate", refreshAdLabel);
}
