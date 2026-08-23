import { state } from "./state.js";
import { configuredAdLength } from "./form.js";
import { nativeCurrentTime, snapTime } from "./seek-media.js";

export function isSgaiAdPlaying() {
  const im = state.hls && state.hls.interstitialsManager;
  if (!im) return false;
  if (im.playingAsset) return true;
  const item = im.playingItem;
  if (item && (item.event || item.interstitial)) return true;
  const q = im.playerQueue || [];
  for (let i = 0; i < q.length; i++) {
    const p = q[i];
    if (p && p.media && !p.media.paused && !p.media.ended && p.media.currentTime > 0) return true;
  }
  return false;
}

export function sgaiPlayoutCap() {
  const im = state.hls && state.hls.interstitialsManager;
  const item = im && im.playingItem;
  const ev = item && (item.event || item.interstitial);
  const player = im && im.interstitialPlayer;
  const cap = Number(
    (ev && (ev.playoutLimit || ev.duration))
    || (player && player.duration)
    || configuredAdLength()
  );
  return isFinite(cap) && cap > 0 ? cap : 12;
}

export function sgaiRemaining() {
  const im = state.hls && state.hls.interstitialsManager;
  if (!im) return configuredAdLength();
  const ev = im.playingItem && (im.playingItem.event || im.playingItem.interstitial);
  const cap = (ev && (ev.playoutLimit || ev.duration)) || configuredAdLength();
  const q = im.playerQueue || [];
  for (let i = 0; i < q.length; i++) {
    const media = q[i] && q[i].media;
    if (!media || media.paused || media.ended) continue;
    return Math.max(0, cap - (media.currentTime || 0));
  }
  return Math.max(0, cap);
}

export function sgaiSafeTime(media) {
  const cap = sgaiPlayoutCap();
  const from = state.lastGoodTime.has(media) ? state.lastGoodTime.get(media) : 0;
  const usable = (from > cap + 0.5) ? 0 : from;
  return Math.max(0, Math.min(usable, cap - 0.25));
}

export function shouldClampSgaiSeek(media, requested) {
  if (!state.watchingAds || state.strategy !== "sgai" || !isFinite(requested)) return false;
  if (!isSgaiAdPlaying()) return false;
  const cap = sgaiPlayoutCap();
  const from = state.lastGoodTime.has(media) ? state.lastGoodTime.get(media) : 0;
  const usable = (from > cap + 0.5) ? 0 : from;
  return requested >= cap - 0.05 || requested > usable + 0.4;
}

export function resetAdClock(media) {
  if (!media) return;
  state.lastGoodTime.set(media, 0);
}

export function onSgaiSeeking(media, from, to) {
  if (isSgaiAdPlaying()) {
    if (shouldClampSgaiSeek(media, to)) snapTime(media, sgaiSafeTime(media));
    return true;
  }
  state.lastGoodTime.set(media, to);
  return true;
}

export function onSgaiTimeUpdate(media) {
  if (state.strategy !== "sgai" || !isSgaiAdPlaying()) return false;
  const t = nativeCurrentTime(media) || 0;
  const cap = sgaiPlayoutCap();
  const prev = state.lastGoodTime.get(media);
  if (prev != null && prev > cap + 0.5 && t < cap) {
    state.lastGoodTime.set(media, t);
    return true;
  }
  if (t + 0.01 >= cap && (prev == null || prev < cap - 0.25)) {
    snapTime(media, sgaiSafeTime(media));
    return true;
  }
  return false;
}

export function onSgaiEnded(ev) {
  if (state.strategy !== "sgai" || !isSgaiAdPlaying()) return;
  ev.stopImmediatePropagation();
  const media = ev.target;
  snapTime(media, sgaiSafeTime(media));
  if (media.play) media.play().catch(() => {});
}
