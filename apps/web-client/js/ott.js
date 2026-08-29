import { $, bind } from "./dom.js";
import { adsAllowed, SAMPLES, state } from "./state.js";
import { dummyPosterDataUrl, loadPoster, probeMediaDuration } from "./thumbnail.js";
import { renderTimeline } from "./ad-markers.js";
import { setStatus } from "./status.js";

export const CATALOG = [
  { id: "mars", title: "Summer on Mars", sub: "Local · 73s · 720p", kicker: "Your library", url: SAMPLES.mars, library: true, adUrl: SAMPLES.giff, adOffset: 10, adDuration: 12 },
  { id: "giff", title: "GIFF Day 1", sub: "Local · 12s · 720p", kicker: "Your library", url: SAMPLES.giff, library: true, adUrl: SAMPLES.giff, adOffset: 10, adDuration: 12 },
  { id: "grok", title: "Grok clip", sub: "Local · 15s · 720p", kicker: "Your library", url: SAMPLES.grok, library: true, adUrl: SAMPLES.giff, adOffset: 10, adDuration: 12 },
  { id: "mux", title: "Big Buck Bunny", sub: "Mux · HLS", kicker: "Featured · Blender Foundation", url: SAMPLES.mux },
  { id: "apple", title: "BipBop", sub: "Apple · I-frame", kicker: "Featured · Apple HLS", url: SAMPLES.apple },
  { id: "tos", title: "Tears of Steel", sub: "Unified · 4s", kicker: "Featured · Blender Institute", url: SAMPLES.tos },
  { id: "demo", title: "BBB + ads", sub: "Mux · breaks at 30s / 90s", kicker: "Featured · Blender Foundation", url: SAMPLES.mux, fillAds: true },
  { id: "elephants", title: "Elephants Dream", sub: "Longtail · multi-audio", kicker: "Blender Foundation", url: SAMPLES.elephants },
  { id: "angel", title: "Angel One", sub: "Shaka · multi-audio", kicker: "Shaka Player demo", url: SAMPLES.angel },
  { id: "skate", title: "Skate Phantom Flex", sub: "Vodobox · up to 4K", kicker: "Vodobox sample", url: SAMPLES.skate },
  { id: "vinn", title: "VINN", sub: "Eyevinn · HLS", kicker: "Eyevinn sample", url: SAMPLES.vinn },
  { id: "arte", title: "ARTE China", sub: "Mux · ABR", kicker: "Mux test stream", url: SAMPLES.arte },
  { id: "atmos", title: "Apple TV Trailer", sub: "Apple · Dolby Vision / Atmos", kicker: "Apple HLS examples", url: SAMPLES.atmos },
  { id: "av1", title: "Apple AV1 Trailer", sub: "Apple · AV1", kicker: "Apple HLS examples", url: SAMPLES.av1 },
  { id: "fdr", title: "FDR", sub: "JW Player · 4s", kicker: "JW Player CDN", url: SAMPLES.fdr },
  { id: "blender", title: "Blender 24/7", sub: "Ireplay · I-frame", kicker: "Live · Ireplay", url: SAMPLES.blender, live: true, asVod: true },
  { id: "unifiedLive", title: "Channel 1", sub: "Unified · low-latency", kicker: "Live · Unified Streaming", url: SAMPLES.unifiedLive, live: true },
];

let playFn = () => {};
let applyPreset = () => {};
let posterToken = 0;

export function setOttPlay(fn) {
  playFn = fn || (() => {});
}

export function setCatalogApply(fn) {
  applyPreset = fn || (() => {});
}

export function setLiveUi(on) {
  state.liveMode = !!on;
  document.body.classList.toggle("live-mode", state.liveMode);
  const bar = $("livePlayBar");
  if (bar) bar.hidden = !state.liveMode;
  syncSnapshotAdUi();
  syncLivePlayButtons();
}

export function syncSnapshotAdUi() {
  const snapshot = !!(state.liveMode && state.forceVod);
  document.body.classList.toggle("snapshot-mode", snapshot);
  const btn = $("btnStudio");
  const studio = $("studio");
  if (!state.liveMode) {
    if (btn) btn.hidden = false;
    return;
  }
  if (snapshot) {
    if (btn) btn.hidden = false;
    return;
  }
  if (studio) studio.hidden = true;
  if (btn) {
    btn.hidden = true;
    btn.setAttribute("aria-expanded", "false");
    btn.textContent = "Ad setup";
  }
}

function applyWindowDuration(sec) {
  const n = Number(sec);
  if (!isFinite(n) || n <= 0) return;
  state.windowDuration = n;
  state.mediaDuration = n;
  const hint = $("tlHint");
  if (hint) hint.textContent = "Window " + Math.round(n) + "s — click to add a break.";
  renderTimeline();
}

function clearWindowDuration() {
  state.windowDuration = 0;
  if (!state.hls) state.mediaDuration = 0;
  renderTimeline();
}

let durationToken = 0;

export async function loadSnapshotTimeline(url) {
  const token = ++durationToken;
  const target = url || ($("contentUrl") && $("contentUrl").value.trim()) || "";
  if (!target || !adsAllowed() || !state.liveMode) return 0;
  try {
    const sec = await probeMediaDuration(target);
    if (token !== durationToken) return 0;
    applyWindowDuration(sec);
    return sec;
  } catch (_) {
    return 0;
  }
}

function syncLivePlayButtons() {
  const live = $("btnForceLive");
  const vod = $("btnForceVod");
  if (live) live.classList.toggle("is-on", !state.forceVod);
  if (vod) vod.classList.toggle("is-on", !!state.forceVod);
}

function isVideoPlaying() {
  if (state.hls) return true;
  const v = $("video");
  return !!(v && !v.paused && v.readyState > 1);
}

function setForceVod(on) {
  const next = !!on;
  const changed = next !== !!state.forceVod;
  state.forceVod = next;
  syncLivePlayButtons();
  syncSnapshotAdUi();
  setPosterHint(state.forceVod
    ? "Snapshot of the current live window. Ads use this window’s timeline."
    : "Rolling live — the player keeps polling the window.");
  if (!changed) return;
  if (next) {
    const playing = isVideoPlaying();
    loadSnapshotTimeline().then(() => {
      setStatus("Snapshot — add breaks on the window timeline, then Apply.");
      if (playing) playFn();
    });
    return;
  }
  durationToken++;
  clearWindowDuration();
  setStatus("Live — ads are not available on this rail.");
  if (isVideoPlaying()) playFn();
}

export function catalogMeta(url) {
  const u = url || "";
  const hit = CATALOG.find((c) => !c.fillAds && c.url === u);
  if (hit) return { title: hit.title, kicker: hit.kicker || hit.sub };
  if (u.indexOf("tears-of-steel") >= 0) return { title: "Tears of Steel", kicker: "Featured · Blender Institute" };
  let host = "HLS title";
  try { host = new URL(u).hostname.replace(/^www\./, ""); } catch (_) { /* ignore */ }
  return { title: host || "Untitled stream", kicker: "Your stream" };
}

export function showPoster() {
  const layer = $("posterLayer");
  if (layer) layer.hidden = false;
}

export function hidePoster() {
  const layer = $("posterLayer");
  if (layer) layer.hidden = true;
}

export function setPosterHint(text) {
  const hint = $("posterHint");
  if (hint && text != null) hint.textContent = text;
}

export function setPosterImage(src) {
  const img = $("posterImg");
  if (img && src) img.src = src;
}

export function refreshHeroCopy() {
  const url = ($("contentUrl") && $("contentUrl").value.trim()) || SAMPLES.mux;
  const meta = catalogMeta(url);
  const title = $("heroTitle");
  const kicker = $("heroKicker");
  if (title) title.textContent = meta.title;
  if (kicker) kicker.textContent = meta.kicker;
}

export async function refreshPoster() {
  const url = ($("contentUrl") && $("contentUrl").value.trim()) || "";
  refreshHeroCopy();
  if (!url) return;
  const token = ++posterToken;
  const hint = $("posterHint");
  if (hint) hint.textContent = "Checking the master for an I-frame variant…";
  const meta = catalogMeta(url);
  try {
    const { dataUrl, kind } = await loadPoster(url);
    if (token !== posterToken) return;
    if (dataUrl && (kind === "iframe" || kind === "image" || kind === "variant")) {
      setPosterImage(dataUrl);
      if (hint) {
        hint.textContent = state.liveMode
          ? (state.forceVod
            ? "Snapshot of the current live window. Switch to Live below."
            : "Rolling live. Switch to Snapshot below to freeze the window.")
          : kind === "iframe"
            ? "Poster from I-frame variant (via local proxy)"
            : kind === "image"
              ? "Poster from image playlist"
              : "Poster from lowest video variant.";
      }
      return;
    }
    setPosterImage(dummyPosterDataUrl(meta.title, "No I-frame or video variant in this master."));
    if (hint) {
      hint.textContent = state.liveMode
        ? (state.forceVod
          ? "Snapshot of the current live window. Switch to Live below."
          : "Rolling live. Switch to Snapshot below to freeze the window.")
        : "Could not grab a poster frame — placeholder art. Play starts the real stream.";
    }
  } catch (_) {
    if (token !== posterToken) return;
    setPosterImage(dummyPosterDataUrl(meta.title, "No I-frame variant in this master."));
    if (hint) {
      hint.textContent = state.liveMode
        ? (state.forceVod
          ? "Snapshot of the current live window. Switch to Live below."
          : "Rolling live. Switch to Snapshot below to freeze the window.")
        : "No I-frame variant — placeholder art. Play starts the real stream.";
    }
  }
}

export function bindOtt() {
  bind("btnStudio", "click", () => {
    if (state.liveMode && !state.forceVod) return;
    const studio = $("studio");
    if (!studio) return;
    studio.hidden = !studio.hidden;
    const btn = $("btnStudio");
    if (btn) {
      btn.setAttribute("aria-expanded", studio.hidden ? "false" : "true");
      btn.textContent = studio.hidden ? "Ad setup" : "Hide setup";
    }
  });
  const watch = () => {
    setPosterHint("Starting playback…");
    playFn();
  };
  bind("btnWatchNow", "click", (ev) => { ev.stopPropagation(); watch(); });
  bind("btnPlayOrb", "click", (ev) => { ev.stopPropagation(); watch(); });
  bind("posterLayer", "click", watch);
  bind("btnForceLive", "click", () => setForceVod(false));
  bind("btnForceVod", "click", () => setForceVod(true));
  const input = $("contentUrl");
  if (input) {
    let t = null;
    input.addEventListener("change", refreshPoster);
    input.addEventListener("input", () => {
      clearTimeout(t);
      t = setTimeout(refreshPoster, 600);
    });
  }
  renderCatalog();
}

function selectCatalog(id) {
  const item = CATALOG.find((c) => c.id === id);
  if (!item) return;
  document.querySelectorAll(".thumb-card").forEach((el) => {
    el.classList.toggle("is-on", el.getAttribute("data-id") === id);
  });
  applyPreset(item);
}

function paintRail(host, items) {
  if (!host) return;
  host.innerHTML = "";
  items.forEach((item) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "thumb-card";
    btn.setAttribute("data-id", item.id);
    btn.innerHTML = "<img alt=\"\" /><div class=\"thumb-meta\"><strong></strong><span></span></div>";
    btn.querySelector("strong").textContent = item.title;
    btn.querySelector("span").textContent = item.sub;
    btn.addEventListener("click", () => selectCatalog(item.id));
    host.appendChild(btn);
    fillCatalogThumb(btn, item);
  });
}

async function fillCatalogThumb(card, item) {
  const img = card.querySelector("img");
  if (!img) return;
  img.alt = item.title;
  img.src = dummyPosterDataUrl(item.title, item.sub);
  try {
    const { dataUrl, kind } = await loadPoster(item.url);
    if (dataUrl && (kind === "iframe" || kind === "image" || kind === "variant")) img.src = dataUrl;
  } catch (_) { /* keep dummy */ }
}

async function localMediaAvailable() {
  try {
    const res = await fetch(SAMPLES.mars, { cache: "no-store" });
    return res.ok;
  } catch (_) {
    return false;
  }
}

export async function renderCatalog() {
  const hasLocal = await localMediaAvailable();
  const rail = $("libraryRail");
  if (rail) rail.hidden = !hasLocal;
  paintRail($("libraryCatalog"), hasLocal ? CATALOG.filter((c) => c.library && !c.live) : []);
  paintRail($("catalog"), CATALOG.filter((c) => !c.live && !c.library));
  paintRail($("liveCatalog"), CATALOG.filter((c) => c.live));
  const current = ($("contentUrl") && $("contentUrl").value) || SAMPLES.mux;
  const visible = CATALOG.filter((c) => !c.fillAds && (!c.library || hasLocal));
  const match = visible.find((c) => c.url === current) || visible[0];
  if (!match) return;
  document.querySelectorAll(".thumb-card").forEach((el) => {
    el.classList.toggle("is-on", el.getAttribute("data-id") === match.id);
  });
}
