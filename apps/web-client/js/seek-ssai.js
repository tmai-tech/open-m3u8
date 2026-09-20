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

/** Adjacent CUE-OUT pods (no content between) count as one break: 1 of 2. */
export function ssaiAdOrdinal() {
  const t = $("video").currentTime || 0;
  const wins = state.adWindows;
  let i = -1;
  for (let k = 0; k < wins.length; k++) {
    if (t >= wins[k].start && t < wins[k].end + 0.2) {
      i = k;
      break;
    }
  }
  if (i < 0) return null;
  let lo = i;
  let hi = i;
  while (lo > 0 && wins[lo].start - wins[lo - 1].end < 0.5) lo--;
  while (hi < wins.length - 1 && wins[hi + 1].start - wins[hi].end < 0.5) hi++;
  const total = hi - lo + 1;
  if (total < 2) return null;
  return { index: i - lo + 1, total };
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

export function onSsaiTimeUpdate(media) {
  if (state.strategy !== "ssai" || Date.now() < state.ssaiIgnoreSnapUntil) return false;
  const t = nativeCurrentTime(media) || 0;
  const prev = state.lastGoodTime.has(media) ? state.lastGoodTime.get(media) : t;
  const w = ssaiWindowAt(prev);
  if (w && prev > w.start + 1 && t <= w.start + 0.6) {
    state.ssaiIgnoreSnapUntil = Date.now() + 1500;
    snapTime(media, w.end + 0.05);
    return true;
  }
  return false;
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
    const naturalExit = to >= inAd.end && to <= inAd.end + 2 && from >= inAd.end - 2;
    // Shaka remounts content MAP at CUE-IN; currentTime can jump to content
    // PTS, which is the playlist time of the ad start. Resume after the ad.
    const remountToStart = to <= inAd.start + 0.6 && from > inAd.start + 1;
    if (naturalExit) {
      state.lastGoodTime.set(media, to);
      return true;
    }
    if (remountToStart) {
      state.ssaiIgnoreSnapUntil = Date.now() + 1500;
      snapTime(media, inAd.end + 0.05);
      return true;
    }
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
