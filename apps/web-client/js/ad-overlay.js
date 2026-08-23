import { $ } from "./dom.js";
import { state } from "./state.js";
import { advanceSsaiSeekQueue, isSsaiAdPlaying, ssaiRemaining } from "./seek-ssai.js";
import { isSgaiAdPlaying, sgaiRemaining } from "./seek-sgai.js";

export function formatRemain(sec) {
  const s = Math.max(0, Math.ceil(sec));
  if (s < 60) return s + "s";
  const m = Math.floor(s / 60);
  return m + ":" + String(s % 60).padStart(2, "0");
}

export function setAdLabel(on, remainSec) {
  const badge = $("adBadge");
  const count = $("adCount");
  const text = on ? formatRemain(remainSec || 0) : "";
  if (badge && badge.hidden !== !on) badge.hidden = !on;
  if (count && count.textContent !== text) count.textContent = text;
}

export function refreshAdLabel() {
  if (!state.watchingAds) {
    setAdLabel(false);
    return;
  }
  if (state.strategy === "sgai") {
    const on = isSgaiAdPlaying();
    setAdLabel(on, on ? sgaiRemaining() : 0);
  } else {
    const on = isSsaiAdPlaying();
    if (state.ssaiWasInAd && !on) advanceSsaiSeekQueue();
    state.ssaiWasInAd = on;
    setAdLabel(on, on ? ssaiRemaining() : 0);
  }
}
