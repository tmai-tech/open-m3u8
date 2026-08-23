import { $ } from "./dom.js";
import { state } from "./state.js";
import { nativeCurrentTime } from "./seek-media.js";
import { onSsaiSeeking, resetSsaiSeek } from "./seek-ssai.js";
import {
  isSgaiAdPlaying,
  onSgaiEnded,
  onSgaiSeeking,
  onSgaiTimeUpdate,
  resetAdClock,
  sgaiPlayoutCap,
  shouldClampSgaiSeek,
  sgaiSafeTime,
} from "./seek-sgai.js";
import { refreshAdLabel } from "./ad-overlay.js";

function markGoodTime(media) {
  if (!media || media.seeking) return;
  const t = nativeCurrentTime(media) || 0;
  if (state.strategy === "sgai" && isSgaiAdPlaying() && t >= sgaiPlayoutCap() - 0.05) return;
  state.lastGoodTime.set(media, t);
}

function installTimeGuard(media) {
  const desc = state.mediaCurrentTime;
  if (!media || !desc || media._hlsTimeGuard) return;
  media._hlsTimeGuard = true;
  Object.defineProperty(media, "currentTime", {
    configurable: true,
    enumerable: true,
    get() { return desc.get.call(media); },
    set(v) {
      let next = Number(v);
      if (shouldClampSgaiSeek(media, next)) next = sgaiSafeTime(media);
      desc.set.call(media, next);
    },
  });
}

function uninstallTimeGuard(media) {
  if (!media || !media._hlsTimeGuard) return;
  delete media._hlsTimeGuard;
  delete media.currentTime;
}

function onSeekingBlockAds(ev) {
  if (state.seekGuard || !state.watchingAds) return;
  const media = ev.target;
  const to = media.currentTime || 0;
  const from = state.lastGoodTime.has(media) ? state.lastGoodTime.get(media) : to;
  if (state.strategy === "sgai") {
    onSgaiSeeking(media, from, to);
    return;
  }
  onSsaiSeeking(media, from, to);
}

function onGuardedTimeUpdate(ev) {
  const media = ev.target;
  if (onSgaiTimeUpdate(media)) return;
  if (!state.seekGuard) markGoodTime(media);
}

export function guardMedia(media) {
  if (!media || state.guardedMedia.indexOf(media) >= 0) return;
  state.guardedMedia.push(media);
  installTimeGuard(media);
  media.addEventListener("seeking", onSeekingBlockAds, true);
  media.addEventListener("timeupdate", onGuardedTimeUpdate, true);
  media.addEventListener("ended", onSgaiEnded, true);
}

export function guardInterstitialMedia() {
  const v = $("video");
  if (v) {
    installTimeGuard(v);
    if (isSgaiAdPlaying()) {
      const t = nativeCurrentTime(v) || 0;
      const cap = sgaiPlayoutCap();
      const prev = state.lastGoodTime.get(v);
      if (prev == null || (prev > cap + 0.5 && t < cap)) resetAdClock(v);
    }
    if (state.guardedMedia.indexOf(v) < 0) guardMedia(v);
  }
  const im = state.hls && state.hls.interstitialsManager;
  const q = (im && im.playerQueue) || [];
  for (let i = 0; i < q.length; i++) {
    if (q[i] && q[i].media) guardMedia(q[i].media);
  }
}

export function unguardAllMedia() {
  state.guardedMedia.forEach((media) => {
    media.removeEventListener("seeking", onSeekingBlockAds, true);
    media.removeEventListener("timeupdate", onGuardedTimeUpdate, true);
    media.removeEventListener("ended", onSgaiEnded, true);
    uninstallTimeGuard(media);
  });
  state.guardedMedia = [];
}

export function startAdWatch() {
  state.watchingAds = true;
  if (state.adPollTimer) clearInterval(state.adPollTimer);
  state.adPollTimer = setInterval(() => {
    refreshAdLabel();
    guardInterstitialMedia();
  }, 500);
  const v = $("video");
  v.addEventListener("timeupdate", refreshAdLabel);
  guardMedia(v);
}

export function stopAdWatch() {
  state.watchingAds = false;
  if (state.adPollTimer) {
    clearInterval(state.adPollTimer);
    state.adPollTimer = null;
  }
  unguardAllMedia();
  resetSsaiSeek();
  const v = $("video");
  if (v) v.removeEventListener("timeupdate", refreshAdLabel);
}
