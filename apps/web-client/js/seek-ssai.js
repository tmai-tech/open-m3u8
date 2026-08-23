import { $ } from "./dom.js";
import { state } from "./state.js";
import { nativeCurrentTime, snapTime } from "./seek-media.js";

export function isSsaiAdPlaying() {
  if (!state.adWindows.length) return false;
  const t = $("video").currentTime || 0;
  return state.adWindows.some((w) => t >= w.start && t < w.end + 0.2);
}

export function ssaiRemaining() {
  const t = $("video").currentTime || 0;
  const w = state.adWindows.find((x) => t >= x.start && t < x.end + 0.2);
  if (!w) return 0;
  return Math.max(0, w.end - t);
}

export function ssaiWindowAt(t) {
  for (let i = 0; i < state.adWindows.length; i++) {
    const w = state.adWindows[i];
    if (t >= w.start && t < w.end + 0.2) return w;
  }
  return null;
}

export function ssaiAdsOnSeek(from, to) {
  const out = [];
  for (let i = 0; i < state.adWindows.length; i++) {
    const w = state.adWindows[i];
    if (w.start > from && w.start <= to) out.push(w);
  }
  out.sort((a, b) => a.start - b.start);
  return out;
}

export function startSsaiSeekQueue(from, to) {
  const ads = ssaiAdsOnSeek(from, to);
  if (!ads.length) return false;
  const last = ads[ads.length - 1];
  state.ssaiFinalTarget = (to > last.end + 0.5) ? to : null;
  state.ssaiSeekQueue = ads.slice();
  advanceSsaiSeekQueue();
  return true;
}

export function advanceSsaiSeekQueue() {
  if (state.strategy !== "ssai" || isSsaiAdPlaying()) return;
  const video = $("video");
  if (!video) return;
  if (state.ssaiSeekQueue.length) {
    const next = state.ssaiSeekQueue.shift();
    state.ssaiIgnoreSnapUntil = Date.now() + 1500;
    snapTime(video, next.start);
    video.play().catch(() => {});
    return;
  }
  const target = state.ssaiFinalTarget;
  state.ssaiFinalTarget = null;
  if (target == null || !isFinite(target)) return;
  const now = nativeCurrentTime(video) || 0;
  if (target <= now + 1) return;
  state.ssaiIgnoreSnapUntil = Date.now() + 2000;
  snapTime(video, target);
  video.play().catch(() => {});
}

export function resetSsaiSeek() {
  state.ssaiSeekQueue = [];
  state.ssaiFinalTarget = null;
  state.ssaiWasInAd = false;
  state.ssaiIgnoreSnapUntil = 0;
}

export function onSsaiSeeking(media, from, to) {
  if (Date.now() < state.ssaiIgnoreSnapUntil) {
    state.lastGoodTime.set(media, to);
    return true;
  }
  if (!state.adWindows.length) {
    state.lastGoodTime.set(media, to);
    return true;
  }
  const inAd = ssaiWindowAt(from);
  if (inAd) {
    if (to < inAd.start - 0.05 || to >= inAd.end || to > from + 0.4) {
      snapTime(media, Math.min(Math.max(from, inAd.start), Math.max(inAd.start, inAd.end - 0.05)));
    } else {
      state.lastGoodTime.set(media, to);
    }
    return true;
  }
  if (to > from && startSsaiSeekQueue(from, to)) return true;
  state.lastGoodTime.set(media, to);
  return true;
}
