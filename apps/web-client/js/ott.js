import { $, bind } from "./dom.js";
import { adsAllowed, SAMPLES, state } from "./state.js";
import { dummyPosterDataUrl, loadPoster, probeMediaDuration } from "./thumbnail.js";
import { renderTimeline } from "./ad-markers.js";
import { setStatus } from "./status.js";

function featuredPoster(id) {
  return "/media/posters/" + id + ".jpg";
}

export const CATALOG = [
  { id: "mux", title: "Big Buck Bunny", sub: "Mux · HLS", kicker: "Featured · Blender Foundation", url: SAMPLES.mux, poster: featuredPoster("mux") },
  { id: "apple", title: "BipBop", sub: "Apple · I-frame", kicker: "Featured · Apple HLS", url: SAMPLES.apple, poster: featuredPoster("apple") },
  { id: "tos", title: "Tears of Steel", sub: "Unified · 4s", kicker: "Featured · Blender Institute", url: SAMPLES.tos, poster: featuredPoster("tos") },
  { id: "elephants", title: "Elephants Dream", sub: "Longtail · multi-audio", kicker: "Blender Foundation", url: SAMPLES.elephants, poster: featuredPoster("elephants") },
  { id: "angel", title: "Angel One", sub: "Shaka · multi-audio", kicker: "Shaka Player demo", url: SAMPLES.angel, poster: featuredPoster("angel") },
  { id: "skate", title: "Skate Phantom Flex", sub: "Vodobox · up to 4K", kicker: "Vodobox sample", url: SAMPLES.skate, poster: featuredPoster("skate") },
  { id: "vinn", title: "VINN", sub: "Eyevinn · HLS", kicker: "Eyevinn sample", url: SAMPLES.vinn, poster: featuredPoster("vinn") },
  { id: "arte", title: "ARTE China", sub: "Mux · ABR", kicker: "Mux test stream", url: SAMPLES.arte, poster: featuredPoster("arte") },
  { id: "atmos", title: "Apple TV Trailer", sub: "Apple · Dolby Vision / Atmos", kicker: "Apple HLS examples", url: SAMPLES.atmos, poster: featuredPoster("atmos") },
  { id: "av1", title: "Apple AV1 Trailer", sub: "Apple · AV1", kicker: "Apple HLS examples", url: SAMPLES.av1, poster: featuredPoster("av1") },
  { id: "fdr", title: "FDR", sub: "JW Player · 4s", kicker: "JW Player CDN", url: SAMPLES.fdr, poster: featuredPoster("fdr") },
  { id: "blender", title: "Blender 24/7", sub: "Ireplay · I-frame", kicker: "Live · Ireplay", url: SAMPLES.blender, live: true, poster: featuredPoster("blender") },
  { id: "unifiedLive", title: "Channel 1", sub: "Unified · low-latency", kicker: "Live · Unified Streaming", url: SAMPLES.unifiedLive, live: true, poster: featuredPoster("unifiedLive") },
  { id: "dashTimeline", title: "Segment timeline", sub: "DASH-IF · 2s + patch", kicker: "DASH live · livesim2", url: SAMPLES.dashTimeline, dashUrl: SAMPLES.dashTimeline, dashLive: true, poster: featuredPoster("dashTimeline") },
  { id: "dashPeriods", title: "Period every minute", sub: "DASH-IF · continuous", kicker: "DASH live · livesim2", url: SAMPLES.dashPeriods, dashUrl: SAMPLES.dashPeriods, dashLive: true, poster: featuredPoster("dashPeriods") },
];

let playFn = () => {};
let applyPreset = () => {};
let posterToken = 0;
let libraryTitles = [];


export function setOttPlay(fn) {
  playFn = fn || (() => {});
}

export function setCatalogApply(fn) {
  applyPreset = fn || (() => {});
}

let catalogReadyFn = () => {};
export function setCatalogReady(fn) {
  catalogReadyFn = fn || (() => {});
}

export function setLiveUi(on) {
  state.liveMode = !!on;
  document.body.classList.toggle("live-mode", state.liveMode);
  const bar = $("livePlayBar");
  if (bar) bar.hidden = !state.liveMode;
  syncSnapshotAdUi();
  syncLivePlayButtons();
}

function syncAdsLayout() {
  const row = $("watchRow");
  const studio = $("studio");
  if (row) row.classList.toggle("ads-off", !studio || studio.hidden);
}

export function syncSnapshotAdUi() {
  const snapshot = !!(state.liveMode && state.forceVod);
  document.body.classList.toggle("snapshot-mode", snapshot);
  const btn = $("btnStudio");
  const studio = $("studio");
  const cadence = state.liveMode && !state.forceVod && adsAllowed();
  const list = $("breakList");
  const toolbar = document.querySelector(".breaks-toolbar");
  const tl = $("timeline");
  const hint = $("liveCadenceHint");
  if (toolbar) toolbar.hidden = !!cadence;
  if (list) list.hidden = !!cadence;
  if (tl) tl.hidden = !!cadence;
  if (hint) hint.hidden = !cadence;
  if (!state.liveMode || snapshot || cadence) {
    if (btn) btn.hidden = false;
    if (studio && cadence) studio.hidden = false;
    syncAdsLayout();
    return;
  }
  if (studio) studio.hidden = true;
  if (btn) {
    btn.hidden = true;
    btn.setAttribute("aria-expanded", "false");
    btn.textContent = "Ad setup";
  }
  syncAdsLayout();
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
  setStatus(adsAllowed()
    ? "Live SGAI — 15s grok ad every 1 min. Play."
    : "Live — ads are not available on this rail.");
  if (isVideoPlaying()) playFn();
}

function catalogKey(url) {
  const raw = (url || "").trim();
  if (!raw) return "";
  try {
    const parsed = new URL(raw, typeof location !== "undefined" ? location.origin : "http://localhost");
    return parsed.pathname.replace(/\/+$/, "") || parsed.href;
  } catch (_) {
    return raw.replace(/[?#].*$/, "").replace(/\/+$/, "");
  }
}

export function findCatalogItem(url) {
  const key = catalogKey(url);
  if (!key) return null;
  const match = (c) => {
    const ck = catalogKey(c.url);
    if (ck && ck === key) return true;
    return !!(c.id && key.indexOf("/titles/" + c.id + "/") >= 0);
  };
  return libraryTitles.find(match) || CATALOG.find(match) || null;
}

export function catalogMeta(url) {
  const u = url || "";
  const hit = findCatalogItem(u);
  if (hit) return { title: hit.title, kicker: hit.summary || hit.kicker || hit.sub };
  if (u.indexOf("tears-of-steel") >= 0) return { title: "Tears of Steel", kicker: "Featured · Blender Institute" };
  let host = "HLS title";
  try {
    host = new URL(u, typeof location !== "undefined" ? location.origin : "http://localhost")
      .hostname.replace(/^www\./, "");
  } catch (_) { /* ignore */ }
  return { title: host && host !== "HLS title" ? host : "Untitled", kicker: "" };
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
  const now = $("nowPlayingTitle");
  const nowK = $("nowPlayingKicker");
  if (now) now.textContent = meta.title;
  if (nowK) nowK.textContent = meta.kicker || "";
}

export async function refreshPoster() {
  const url = ($("contentUrl") && $("contentUrl").value.trim()) || "";
  refreshHeroCopy();
  if (!url) return;
  const token = ++posterToken;
  const hint = $("posterHint");
  const packed = findCatalogItem(url);
  if (packed && packed.poster) {
    setPosterImage(packed.poster);
    if (hint) hint.textContent = packed.summary || "";
    return;
  }
  if (hint) hint.textContent = packed && packed.summary ? packed.summary : "";
  const meta = catalogMeta(url);
  try {
    const { dataUrl, kind } = await loadPoster(url);
    if (token !== posterToken) return;
    if (dataUrl && (kind === "iframe" || kind === "image" || kind === "variant")) {
      setPosterImage(dataUrl);
      if (hint) {
        hint.textContent = state.liveMode
          ? (state.forceVod ? "Live snapshot." : "Live.")
          : (meta.kicker || "");
      }
      return;
    }
    setPosterImage(dummyPosterDataUrl(meta.title, meta.kicker || ""));
    if (hint) {
      hint.textContent = state.liveMode
        ? (state.forceVod ? "Live snapshot." : "Live.")
        : (meta.kicker || "");
    }
  } catch (_) {
    if (token !== posterToken) return;
    setPosterImage(dummyPosterDataUrl(meta.title, meta.kicker || ""));
    if (hint) {
      hint.textContent = state.liveMode
        ? (state.forceVod ? "Live snapshot." : "Live.")
        : (meta.kicker || "");
    }
  }
}

export function bindOtt() {
  syncAdsLayout();
  bind("btnStudio", "click", () => {
    if (state.liveMode && !state.forceVod) return;
    const studio = $("studio");
    if (!studio) return;
    studio.hidden = !studio.hidden;
    syncAdsLayout();
    const btn = $("btnStudio");
    if (btn) {
      btn.setAttribute("aria-expanded", studio.hidden ? "false" : "true");
      btn.textContent = studio.hidden ? "Ads" : "Hide ads";
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
  renderCatalog().then(() => {
    const q = new URLSearchParams(location.search).get("play");
    if (q) selectCatalog(q);
  });
}

function allItems() {
  return libraryTitles.concat(CATALOG);
}

function selectCatalog(id) {
  const item = allItems().find((c) => c.id === id);
  if (!item) return;
  if (item.library && item.status && item.status !== "ready") {
    setStatus(item.status === "failed"
      ? ("Packaging failed: " + (item.error || item.title))
      : (item.title + " is still " + item.status + "."));
    return;
  }
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
    if (item.status && item.status !== "ready") btn.classList.add("is-busy");
    if (item.status === "failed") btn.classList.add("is-fail");
    btn.setAttribute("data-id", item.id);
    btn.innerHTML = "<img alt=\"\" /><div class=\"thumb-meta\"><strong></strong><span></span><em></em></div>";
    btn.querySelector("strong").textContent = item.title;
    btn.querySelector("span").textContent = item.sub || item.status || "";
    const sum = btn.querySelector("em");
    if (item.summary) sum.textContent = item.summary;
    else sum.hidden = true;
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
  if (item.poster) {
    img.onerror = () => {
      img.onerror = null;
      img.src = dummyPosterDataUrl(item.title, item.sub);
    };
    img.src = item.poster;
    return;
  }
  if (item.status && item.status !== "ready") return;
  try {
    const { dataUrl, kind } = await loadPoster(item.url);
    if (dataUrl && (kind === "iframe" || kind === "image" || kind === "variant")) img.src = dataUrl;
  } catch (_) { /* keep dummy */ }
}

function toLibraryItem(t) {
  const status = t.status || "ready";
  const dur = Number(t.durationSec);
  const sub = t.sub || (status === "ready" && isFinite(dur) && dur > 0
    ? ("Local · " + Math.round(dur) + "s · 720p")
    : (status === "ready" ? "Local · 720p" : status.charAt(0).toUpperCase() + status.slice(1)));
  return {
    id: t.id,
    title: t.title || t.id,
    sub,
    summary: t.summary || "",
    kicker: "Your library",
    url: t.url,
    dashUrl: t.dashUrl || "",
    poster: t.poster,
    library: true,
    adUrl: "",
    adOffset: 0,
    adDuration: 0,
    status,
    error: t.error,
  };
}

async function fetchLibraryCatalog() {
  try {
    const res = await fetch("/api/catalog", { cache: "no-store" });
    if (!res.ok) return null;
    const data = await res.json();
    const titles = (data && data.titles) || [];
    return titles
      .filter((t) => t.status !== "duplicate")
      .map(toLibraryItem);
  } catch (_) {
    return null;
  }
}

export async function renderCatalog() {
  const remote = await fetchLibraryCatalog();
  const hasLocal = remote != null;
  libraryTitles = remote || [];
  const rail = $("libraryRail");
  if (rail) rail.hidden = !hasLocal;
  paintRail($("libraryCatalog"), libraryTitles);
  paintRail($("catalog"), CATALOG.filter((c) => !c.live && !c.dashLive && !c.library));
  paintRail($("liveCatalog"), CATALOG.filter((c) => c.live && !c.dashLive));
  paintRail($("dashLiveCatalog"), CATALOG.filter((c) => c.dashLive));
  const current = ($("contentUrl") && $("contentUrl").value) || SAMPLES.mux;
  const visible = allItems().filter((c) => !c.library || c.status === "ready");
  const match = visible.find((c) => catalogKey(c.url) === catalogKey(current)) || visible[0];
  if (match) {
    document.querySelectorAll(".thumb-card").forEach((el) => {
      el.classList.toggle("is-on", el.getAttribute("data-id") === match.id);
    });
  }
  refreshHeroCopy();
  catalogReadyFn();
}
