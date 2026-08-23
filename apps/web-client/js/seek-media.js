import { state } from "./state.js";

export function nativeCurrentTime(media) {
  const desc = state.mediaCurrentTime;
  return desc && desc.get ? desc.get.call(media) : media.currentTime;
}

export function nativeSetCurrentTime(media, t) {
  const desc = state.mediaCurrentTime;
  if (desc && desc.set) desc.set.call(media, t);
  else media.currentTime = t;
}

export function snapTime(media, t) {
  state.seekGuard = true;
  try { nativeSetCurrentTime(media, Math.max(0, t)); } catch (_) { /* ignore */ }
  state.lastGoodTime.set(media, nativeCurrentTime(media) || t);
  state.seekGuard = false;
}
