import { state } from "./state.js";
import { renderSeekMarks } from "./ad-markers.js";

export function parseIsoDuration(iso) {
  if (!iso) return 0;
  const m = String(iso).match(/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/i);
  if (!m) return 0;
  return (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0);
}

/** Periods whose id looks like an SSAI ad (ad-*, ssai-*, *-ad-*). */
export function parseDashAdWindows(xml) {
  const windows = [];
  if (!xml) return windows;
  let t = 0;
  const re = /<Period\b([^>]*)>/gi;
  let m;
  while ((m = re.exec(xml))) {
    const attrs = m[1] || "";
    const id = ((attrs.match(/\bid\s*=\s*"([^"]*)"/i) || [])[1]) || "";
    const dur = parseIsoDuration((attrs.match(/\bduration\s*=\s*"([^"]*)"/i) || [])[1] || "");
    const isAd = /^(ad|ssai)[-_]/i.test(id) || /[-_]ad[-_]/i.test(id) || /^ssai-/i.test(id);
    if (isAd && dur > 0) windows.push({ start: t, end: t + dur });
    t += dur;
  }
  return windows;
}

export function parseCueWindows(playlistText) {
  const windows = [];
  if (!playlistText) return windows;
  let t = 0;
  let adStart = null;
  let adEnd = null;
  String(playlistText).replace(/\r/g, "").split("\n").forEach((raw) => {
    const line = raw.trim();
    if (/^#EXT-X-CUE-OUT(?!-CONT)/i.test(line)) {
      // Back-to-back pods (multi-ad break) emit a new CUE-OUT with no CUE-IN
      // in between. Close the previous window so the badge counts one creative.
      if (adStart != null) {
        windows.push({ start: adStart, end: Math.max(adStart, t, adEnd || t) });
      }
      adStart = t;
      const dm = line.match(/:([0-9.]+)/) || line.match(/DURATION=([0-9.]+)/i);
      adEnd = dm ? t + (Number(dm[1]) || 0) : null;
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

/** Interstitial date ranges that name an asset list. */
export function parseInterstitialAssetLists(playlistText) {
  const lists = [];
  if (!playlistText) return lists;
  String(playlistText).replace(/\r/g, "").split("\n").forEach((raw) => {
    const line = raw.trim();
    if (line.indexOf("#EXT-X-DATERANGE:") !== 0) return;
    const id = dateRangeAttr(line, "ID");
    const url = dateRangeAttr(line, "X-ASSET-LIST");
    if (id && url) lists.push({ id: id, url: url });
  });
  return lists;
}

function dateRangeAttr(line, name) {
  const quoted = line.match(new RegExp("(?:^|[,:])" + name + "=\"([^\"]*)\"", "i"));
  if (quoted) return quoted[1];
  const bare = line.match(new RegExp("(?:^|[,:])" + name + "=([^,]*)", "i"));
  return bare ? bare[1] : "";
}
