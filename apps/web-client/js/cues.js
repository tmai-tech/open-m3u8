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

/**
 * InsertPresentation and ReplacePresentation events.
 * Each event id is the break id; the uri is that break's alternative MPD.
 */
export function parseDashAltPresentations(xml) {
  const events = [];
  if (!xml) return events;
  const re = /<Event\b([^>]*)>([\s\S]*?)<\/Event>/gi;
  let m;
  while ((m = re.exec(xml))) {
    const id = xmlAttr(m[1], "id");
    const tag = (m[2] || "").match(/<(?:Insert|Replace)Presentation\b([^>]*?)\/?>/i);
    if (!id || !tag) continue;
    const url = xmlAttr(tag[1], "uri") || xmlAttr(tag[1], "url");
    if (url) events.push({ id: id, url: htmlUnescape(url) });
  }
  return events;
}

/** Period start and duration on an alternative-MPD timeline, in seconds. */
export function parseDashPeriods(xml) {
  const periods = [];
  if (!xml) return periods;
  const re = /<Period\b([^>]*)>/gi;
  let m;
  let cursor = 0;
  while ((m = re.exec(xml))) {
    const attrs = m[1] || "";
    const duration = parseIsoDuration(xmlAttr(attrs, "duration"));
    if (!(duration > 0)) continue;
    const startRaw = xmlAttr(attrs, "start");
    const start = startRaw ? parseIsoDuration(startRaw) : cursor;
    periods.push({ start: start, duration: duration });
    cursor = start + duration;
  }
  return periods;
}

/**
 * Which period contains the ad playhead. A missing playhead is the first
 * period, still unstarted. index is 1-based.
 */
export function dashPeriodAt(periods, played) {
  const list = periods || [];
  if (!list.length) return { index: 1, total: 0, remaining: 0 };
  // null is "not ready", not time 0. Number(null) is 0, so check it first.
  if (played == null || !isFinite(Number(played))) {
    return { index: 1, total: list.length, remaining: list[0].duration };
  }
  const t = Number(played);
  let index = 0;
  for (let i = 0; i < list.length; i++) {
    if (t + 1e-4 >= list[i].start) index = i;
  }
  const period = list[index];
  const into = Math.max(0, t - period.start);
  return {
    index: index + 1,
    total: list.length,
    remaining: Math.max(0, period.duration - into),
  };
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

function xmlAttr(text, name) {
  const m = String(text || "").match(new RegExp("\\b" + name + "\\s*=\\s*\"([^\"]*)\"", "i"));
  return m ? m[1] : "";
}

function htmlUnescape(s) {
  return String(s)
    .replace(/&quot;/g, "\"")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}
