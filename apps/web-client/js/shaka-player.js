import { $ } from "./dom.js";
import { isMpdUrl, state } from "./state.js";
import { setStatus } from "./status.js";
import { showSessionWarnings } from "./api.js";
import { hidePoster } from "./ott.js";
import { renderTimeline } from "./ad-markers.js";
import { resetQualityMenu, syncShakaQuality } from "./quality.js";
import { rememberPlaylist } from "./cues.js";
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
  stopAdLabelWatch();
  document.body.classList.remove("shaka-mode");
  const video = $("video");
  if (video) video.controls = true;
  if (ui && ui.destroy) {
    try { ui.destroy(); } catch (e) { /* ignore */ }
  }
  if (player && player.destroy) {
    try {
      const p = player.destroy();
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* ignore */ }
  }
}

export async function playShaka(manifestUrl) {
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
  const ui = new shaka.ui.Overlay(player, mount, video);
  state.shaka = player;
  state.shakaUi = ui;

  player.addEventListener("error", (ev) => {
    const err = ev && ev.detail;
    const code = err && (err.code != null) ? err.code : "";
    const msg = err && err.message ? err.message : String(err || "unknown");
    setStatus("Shaka error " + code + " — " + msg, "err");
  });
  player.addEventListener("loaded", () => {
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
  if (!isMpdUrl(manifestUrl)) {
    await loadCueWindowsFromMaster(manifestUrl);
  }
  syncShakaQuality(player);
  showSessionWarnings(state.lastSession && state.lastSession.id, setStatus);
  hidePoster();
  video.play().catch(() => {});
  setStatus("Playing", "ok");
}

function hookShakaAds(player) {
  const onStart = (ev) => {
    state.shakaAdPlaying = true;
    state.shakaAd = (ev && (ev.ad || (ev.detail && ev.detail.ad))) || null;
    refreshAdLabel();
  };
  const onEnd = () => {
    state.shakaAdPlaying = false;
    state.shakaAd = null;
    refreshAdLabel();
  };
  const bind = (target) => {
    if (!target || !target.addEventListener) return;
    const AM = window.shaka && shaka.ads && shaka.ads.AdManager;
    const starts = ["ad-started", "ad-impression"];
    const ends = ["ad-complete", "ad-skipped", "ad-stopped", "ad-ended"];
    if (AM) {
      if (AM.AD_STARTED) starts.push(AM.AD_STARTED);
      if (AM.AD_COMPLETE) ends.push(AM.AD_COMPLETE);
      if (AM.AD_SKIPPED) ends.push(AM.AD_SKIPPED);
      if (AM.AD_STOPPED) ends.push(AM.AD_STOPPED);
    }
    starts.forEach((n) => target.addEventListener(n, onStart));
    ends.forEach((n) => target.addEventListener(n, onEnd));
  };
  bind(player);
  if (typeof player.getAdManager === "function") {
    try { bind(player.getAdManager()); } catch (e) { /* ignore */ }
  }
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
    refreshAdLabel();
  } catch (e) {
    /* cue windows stay empty; badge stays off */
  }
}
