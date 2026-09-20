import { $ } from "./dom.js";
import { state } from "./state.js";
import { configuredAdLength } from "./form.js";
import { nativeCurrentTime, snapTime } from "./seek-media.js";

export function isSgaiAdPlaying() {
  if (state.shakaAdPlaying) return true;
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
  if (state.shakaAdPlaying) {
    const ad = state.shakaAd;
    if (ad && typeof ad.getRemainingTime === "function") {
      const left = Number(ad.getRemainingTime());
      if (isFinite(left)) return Math.max(0, left);
    }
    if (ad && typeof ad.getDuration === "function") {
      const d = Number(ad.getDuration());
      const t = Number(($("video") && $("video").currentTime) || 0);
      if (isFinite(d) && d > 0) return Math.max(0, d - t);
    }
    return configuredAdLength();
  }
  const im = state.hls && state.hls.interstitialsManager;
  if (!im) return configuredAdLength();
  const q = im.playerQueue || [];
  for (let i = 0; i < q.length; i++) {
    const media = q[i] && q[i].media;
    if (!media || media.paused || media.ended) continue;
    const d = Number(media.duration);
    const t = Number(media.currentTime) || 0;
    if (isFinite(d) && d > 0) return Math.max(0, d - t);
    return Math.max(0, sgaiPlayoutCap() - t);
  }
  const player = im.interstitialPlayer;
  if (player) {
    const d = Number(player.duration);
    const t = Number(player.currentTime) || 0;
    if (isFinite(d) && d > 0 && d < sgaiPlayoutCap() + 0.5) return Math.max(0, d - t);
  }
  return Math.max(0, sgaiPlayoutCap());
}

export function sgaiAdOrdinal() {
  if (state.shakaAdPlaying && state.shakaAd) {
    const ad = state.shakaAd;
    const pos = typeof ad.getPositionInSequence === "function" ? Number(ad.getPositionInSequence()) : NaN;
    const len = typeof ad.getSequenceLength === "function" ? Number(ad.getSequenceLength()) : NaN;
    if (len >= 2 && pos >= 1) return { index: pos, total: len };
  }
  const im = state.hls && state.hls.interstitialsManager;
  if (!im) return null;
  const item = im.playingItem;
  const ev = item && (item.event || item.interstitial);
  let total = 0;
  const list = ev && (ev.assetList || ev.assets
      || (ev.assetListResponse && (ev.assetListResponse.ASSETS || ev.assetListResponse.assets)));
  if (Array.isArray(list)) total = list.length;
  const player = im.interstitialPlayer;
  if (!total && player && Number.isFinite(player.assetCount)) total = player.assetCount;
  let idx = 0;
  if (player && Number.isFinite(player.playingIndex)) idx = player.playingIndex;
  else if (im.playingAsset && Number.isFinite(im.playingAsset.index)) idx = im.playingAsset.index;
  if (total < 2) return null;
  return { index: Math.max(1, idx + 1), total };
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

export function resetSgaiOnce() {
  if (state.sgaiPlayedOnce) state.sgaiPlayedOnce.clear();
}

function interstitialEvent(data) {
  if (!data) return null;
  return data.event || data.interstitial || data;
}

function interstitialId(ev) {
  if (!ev) return "";
  if (ev.identifier) return String(ev.identifier);
  if (ev.id) return String(ev.id);
  const dr = ev.dateRange;
  if (dr && (dr.id || dr.ID)) return String(dr.id || dr.ID);
  return "";
}

function isCueOnce(ev) {
  const cue = ev && ev.cue;
  if (cue && (cue.once === true || cue.once === "ONCE")) return true;
  if (typeof cue === "string" && /ONCE/i.test(cue)) return true;
  const dr = ev && ev.dateRange;
  const attr = dr && (dr.cue || (dr.attr && (dr.attr.CUE || dr.attr.cue)));
  if (typeof attr === "string" && /ONCE/i.test(attr)) return true;
  const s = state.lastSession;
  return !!(s && (s.cueOnce || (s.sgai && s.sgai.cueOnce) || s.cue === "ONCE"
      || (s.sgai && s.sgai.cue === "ONCE")));
}

/** After a CUE=ONCE interstitial finishes, do not play it again (native seek / Shaka). */
export function onSgaiInterstitialEnded(data) {
  const ev = interstitialEvent(data);
  const id = interstitialId(ev);
  if (id && isCueOnce(ev)) state.sgaiPlayedOnce.add(id);
}

/** If hls.js starts a once-played break again, skip it. */
export function onSgaiInterstitialStarted(data) {
  const ev = interstitialEvent(data);
  const id = interstitialId(ev);
  if (!id || !isCueOnce(ev) || !state.sgaiPlayedOnce.has(id)) return;
  const im = state.hls && state.hls.interstitialsManager;
  if (im && typeof im.skip === "function") {
    try { im.skip(); } catch (e) { /* ignore */ }
  }
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
