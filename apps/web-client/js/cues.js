import { state } from "./state.js";
import { renderSeekMarks } from "./ad-markers.js";

export function parseCueWindows(playlistText) {
  const windows = [];
  if (!playlistText) return windows;
  let t = 0;
  let adStart = null;
  let adEnd = null;
  String(playlistText).replace(/\r/g, "").split("\n").forEach((raw) => {
    const line = raw.trim();
    if (/^#EXT-X-CUE-OUT(?!-CONT)/i.test(line)) {
      if (adStart == null) adStart = t;
      const dm = line.match(/:([0-9.]+)/) || line.match(/DURATION=([0-9.]+)/i);
      if (dm) adEnd = t + (Number(dm[1]) || 0);
      return;
    }
    if (/^#EXT-X-CUE-IN/i.test(line)) {
      if (adStart != null) {
        windows.push({ start: adStart, end: Math.max(adStart, t, adEnd || t) });
        adStart = null;
        adEnd = null;
      }
      return;
    }
    const m = line.match(/^#EXTINF\s*:\s*([0-9.]+)/i);
    if (m) t += Number(m[1]) || 0;
  });
  if (adStart != null) {
    windows.push({ start: adStart, end: Math.max(adStart, adEnd != null ? adEnd : t) });
  }
  return windows;
}

export function rememberPlaylist(text) {
  const wins = parseCueWindows(text);
  if (wins.length) {
    state.adWindows = wins;
    renderSeekMarks();
  }
}
