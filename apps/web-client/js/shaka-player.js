import { $ } from "./dom.js";
import { currentPlaybackEpoch, isMpdUrl, state } from "./state.js";
import { setStatus } from "./status.js";
import { showSessionWarnings } from "./api.js";
import { hidePoster } from "./ott.js";
import { renderTimeline } from "./ad-markers.js";
import { resetQualityMenu, syncShakaQuality } from "./quality.js";
import { parseInterstitialAssetLists, rememberPlaylist } from "./cues.js";
import { refreshAdLabel, startAdLabelWatch, stopAdLabelWatch } from "./ad-overlay.js";

const HLS_MIME = "application/vnd.apple.mpegurl";

export function shakaAvailable() {
  return !!(window.shaka && shaka.Player && shaka.ui && shaka.ui.Overlay);
}

export function destroyShaka() {
  const ui = state.shakaUi;
  const player = state.shaka;
  state.shakaUi = null;
  state.shaka = null;
  state.shakaAd = null;
  state.shakaAdPlaying = false;
  state.shakaAssetDurations = {};
  stopAdLabelWatch();
  document.body.classList.remove("shaka-mode");
  const video = $("video");
  restoreVideoMount(video);
  if (ui && ui.destroy) {
    try {
      const p = ui.destroy();
      if (p && typeof p.then === "function") {
        p.catch(() => {}).finally(() => restoreVideoMount($("video")));
      }
    } catch (e) { /* ignore */ }
  }
  if (player && player.destroy) {
    try {
      const p = player.destroy();
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* ignore */ }
  }
  restoreVideoMount($("video"));
}

/** Put #video back under #shakaMount; drop leftover Shaka chrome/cursor. */
function restoreVideoMount(video) {
  const mount = $("shakaMount");
  if (!video) return;
  video.controls = true;
  video.style.cursor = "";
  if (!mount) return;
  mount.style.cursor = "";
  if (video.parentElement && video.parentElement !== mount) {
    mount.insertBefore(video, mount.firstChild);
  }
  Array.from(mount.children).forEach((el) => {
    if (el !== video) el.remove();
  });
}

export async function playShaka(manifestUrl) {
  const epoch = currentPlaybackEpoch();
  const stale = () => epoch !== currentPlaybackEpoch();
  if (!shakaAvailable()) {
    throw new Error("Shaka UI is not loaded");
  }
  if (shaka.polyfill && shaka.polyfill.installAll) {
    shaka.polyfill.installAll();
  }
  if (!shaka.Player.isBrowserSupported()) {
    throw new Error("Shaka is not supported in this browser");
  }

  destroyShaka();
  const video = $("video");
  const mount = $("shakaMount") || $("heroStage");
  video.controls = false;
  document.body.classList.add("shaka-mode");
  resetQualityMenu();

  const player = new shaka.Player();
  await player.attach(video);
  if (stale()) {
    try { player.destroy(); } catch (e) { /* format changed */ }
    return;
  }
  try {
    player.configure({
      preferredTextLanguage: "en",
      streaming: { alwaysStreamText: true },
    });
  } catch (e) { /* ignore older Shaka */ }
  const ui = new shaka.ui.Overlay(player, mount, video);
  state.shaka = player;
  state.shakaUi = ui;

  player.addEventListener("error", (ev) => {
    if (stale() || state.shaka !== player) return;
    const err = ev && ev.detail;
    const code = err && (err.code != null) ? err.code : "";
    const msg = err && err.message ? err.message : String(err || "unknown");
    setStatus("Shaka error " + code + " — " + msg, "err");
  });
  player.addEventListener("loaded", () => {
    if (stale() || state.shaka !== player) return;
    const dur = Number(video.duration);
    if (isFinite(dur) && dur > state.mediaDuration) state.mediaDuration = dur;
    if (state.liveMode && state.forceVod) state.windowDuration = state.mediaDuration;
    const hint = $("tlHint");
    if (hint) hint.textContent = "Duration " + Math.round(state.mediaDuration) + "s — click to add a break.";
    renderTimeline();
    syncShakaQuality(player);
  });
  player.addEventListener("adaptation", () => syncShakaQuality(player));
  player.addEventListener("variantchanged", () => syncShakaQuality(player));

  hookShakaAds(player);
  startAdLabelWatch();
  const mime = isMpdUrl(manifestUrl) ? "application/dash+xml" : HLS_MIME;
  await player.load(manifestUrl, /* startTime= */ null, mime);
  if (stale()) return;
  if (!isMpdUrl(manifestUrl)) {
    await loadCueWindowsFromMaster(manifestUrl);
    if (stale()) return;
  }
  try { player.setTextTrackVisibility(true); } catch (e) { /* ignore */ }
  if (stale()) return;
  syncShakaQuality(player);
  hidePoster();
  video.play().catch(() => {});
  setStatus("Playing", "ok");
  showSessionWarnings(state.lastSession && state.lastSession.id, setStatus);
}

function hookShakaAds(player) {
  const onStart = (ev) => {
    state.shakaAdPlaying = true;
    const ad = ev && (ev.ad || (ev.detail && ev.detail.ad));
    if (ad) state.shakaAd = ad;
    refreshAdLabel();
  };
  // Shaka sends ad-complete after the next creative's ad-started, and ad-skipped
  // before it drops the rest of an asset-list pod. The break is over on
  // ad-break-ended, which is also the return to content after a skip.
  const onBreakEnd = () => {
    state.shakaAdPlaying = false;
    state.shakaAd = null;
    refreshAdLabel();
  };
  const bind = (target) => {
    if (!target || !target.addEventListener) return;
    const AM = window.shaka && shaka.ads && shaka.ads.AdManager;
    const starts = ["ad-started", "ad-impression"];
    const ends = ["ad-break-ended"];
    if (AM) {
      if (AM.AD_STARTED) starts.push(AM.AD_STARTED);
      if (AM.AD_IMPRESSION) starts.push(AM.AD_IMPRESSION);
      if (AM.AD_BREAK_ENDED) ends.push(AM.AD_BREAK_ENDED);
    }
    listen(target, starts, onStart);
    listen(target, ends, onBreakEnd);
  };
  bind(player);
  if (typeof player.getAdManager === "function") {
    try { bind(player.getAdManager()); } catch (e) { /* ignore */ }
  }
}

function listen(target, names, fn) {
  const seen = Object.create(null);
  for (let i = 0; i < names.length; i++) {
    const name = names[i];
    if (!name || seen[name]) continue;
    seen[name] = true;
    target.addEventListener(name, fn);
  }
}

async function rememberShakaAssetDurations(playlistText, mediaUrl) {
  const lists = parseInterstitialAssetLists(playlistText);
  const durations = {};
  for (let i = 0; i < lists.length; i++) {
    const item = lists[i];
    try {
      const href = new URL(item.url, mediaUrl).href;
      const body = await (await fetch(href, { cache: "no-store" })).json();
      const assets = (body && (body.ASSETS || body.assets)) || [];
      const durs = [];
      for (let j = 0; j < assets.length; j++) {
        const raw = assets[j].DURATION != null ? assets[j].DURATION : assets[j].duration;
        const n = Number(raw);
        if (isFinite(n) && n > 0) durs.push(n);
      }
      if (durs.length) durations[item.id] = durs;
    } catch (e) { /* this break keeps the file length */ }
  }
  state.shakaAssetDurations = durations;
}

async function loadCueWindowsFromMaster(masterUrl) {
  try {
    const master = await (await fetch(masterUrl, { cache: "no-store" })).text();
    const lines = master.replace(/\r/g, "").split("\n");
    let child = "";
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].indexOf("#EXT-X-STREAM-INF") === 0) {
        const next = (lines[i + 1] || "").trim();
        if (next && next.charAt(0) !== "#") {
          child = next;
          break;
        }
      }
    }
    if (!child) return;
    const url = new URL(child, masterUrl).href;
    const media = await (await fetch(url, { cache: "no-store" })).text();
    rememberPlaylist(media);
    await rememberShakaAssetDurations(media, url);
    refreshAdLabel();
  } catch (e) {
    /* cue windows stay empty; badge stays off */
  }
}
