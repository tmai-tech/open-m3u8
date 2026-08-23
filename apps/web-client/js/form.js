import { $, bind } from "./dom.js";
import { DEFAULT_AD, HINTS, LENGTH_HINTS, state } from "./state.js";
import { setStatus } from "./status.js";
import { renderTimeline } from "./ad-markers.js";
import { fillPublicRow } from "./public-url.js";
import { refreshPoster, setLiveUi } from "./ott.js";

let destroyPlayerFn = () => {};

export function setFormPlayer(fn) {
  destroyPlayerFn = fn || (() => {});
}

export function setStrategy(next, announce) {
  state.strategy = next === "sgai" ? "sgai" : "ssai";
  localStorage.setItem("hls-demo-strategy", state.strategy);
  $("strategy").value = state.strategy;
  $("app-card").classList.toggle("mode-ssai", state.strategy === "ssai");
  $("app-card").classList.toggle("mode-sgai", state.strategy === "sgai");
  $("strategyHint").textContent = HINTS[state.strategy];
  $("adLengthHint").textContent = LENGTH_HINTS[state.strategy];
  if (announce) {
    destroyPlayerFn();
    state.lastSession = null;
    $("manifestUrl").value = "";
    $("btnCopy").disabled = true;
    $("sessionMeta").textContent = "Strategy: " + state.strategy.toUpperCase() + " — apply to create a new session.";
    setStatus("Switched to " + state.strategy.toUpperCase() + ". Ad points are kept.");
  }
}

export function readBreaksFromDom() {
  const rows = document.querySelectorAll("#breakList .break-row");
  const out = [];
  rows.forEach((row, i) => {
    const offsetSec = Number(row.querySelector(".brk-off").value);
    const assetUri = (row.querySelector(".brk-url").value || "").trim();
    const durationSec = Number(row.querySelector(".brk-dur").value);
    if (!assetUri) return;
    if (Number.isNaN(offsetSec) || offsetSec < 0) throw new Error("Break " + (i + 1) + ": offset must be ≥ 0");
    if (Number.isNaN(durationSec) || durationSec < 0) throw new Error("Break " + (i + 1) + ": duration must be ≥ 0");
    out.push({
      id: (state.strategy === "sgai" ? "user-ad-" : "ssai-") + (i + 1),
      offsetSec,
      durationSec,
      assetUri,
    });
  });
  return out;
}

export function addBreakRow(offsetSec, assetUri, durationSec) {
  const list = $("breakList");
  const last = list.querySelector(".break-row:last-child");
  const inheritUrl = last ? last.querySelector(".brk-url").value : DEFAULT_AD;
  const inheritDur = last ? last.querySelector(".brk-dur").value : "12";
  const row = document.createElement("div");
  row.className = "break-row";
  row.innerHTML =
    '<input class="brk-off" type="number" min="0" step="any" />' +
    '<input class="brk-url" type="url" placeholder="https://…/ad.m3u8" />' +
    '<input class="brk-dur" type="number" min="0" step="any" />' +
    '<button type="button" class="btn btn-icon" title="Remove">×</button>';
  row.querySelector(".brk-off").value = offsetSec != null ? offsetSec : 0;
  row.querySelector(".brk-url").value = assetUri || inheritUrl || DEFAULT_AD;
  row.querySelector(".brk-dur").value = durationSec != null ? durationSec : inheritDur;
  row.querySelector(".btn-icon").onclick = () => {
    row.remove();
    renderTimeline();
  };
  row.querySelector(".brk-off").addEventListener("input", renderTimeline);
  list.appendChild(row);
  renderTimeline();
}

export function buildLocalSession() {
  const contentUrl = $("contentUrl").value.trim();
  if (!contentUrl) throw new Error("Content URL is required");
  const breaks = state.liveMode ? [] : readBreaksFromDom();
  const splices = breaks.map((b) => b.offsetSec);
  const first = breaks[0];
  return {
    strategy: state.strategy,
    contentUrl,
    adUrl: first ? first.assetUri : "",
    breaks,
    splices,
    offsets: splices,
    adLength: first ? first.durationSec : 0,
    maxAdDurationSec: first ? first.durationSec : 0,
    snapToSegment: $("snapSegment").checked,
    restrictSkip: $("restrictSkip").checked,
    sgai: {
      snapToSegment: $("snapSegment").checked,
      restrictSkip: $("restrictSkip").checked,
      resumeOffset: 0,
    },
  };
}

export function configuredAdLength() {
  const first = document.querySelector("#breakList .brk-dur");
  const n = first ? Number(first.value) : NaN;
  if (isFinite(n) && n > 0) return n;
  if (state.lastSession && state.lastSession.breaks && state.lastSession.breaks[0]) {
    const d = Number(state.lastSession.breaks[0].durationSec);
    if (isFinite(d) && d > 0) return d;
  }
  return 12;
}

export function setManifestButtons(enabled) {
  $("btnCopy").disabled = !enabled;
  fillPublicRow();
}

export function bindForm() {
  bind("strategy", "change", () => setStrategy($("strategy").value, true));
  bind("btnAddBreak", "click", () => addBreakRow());
  bind("timeline", "click", (ev) => {
    if (!state.mediaDuration) {
      addBreakRow(0);
      setStatus("Added a break at 0s. Play to place on the timeline.", "ok");
      return;
    }
    const rect = ev.currentTarget.getBoundingClientRect();
    const sec = Math.max(0, ((ev.clientX - rect.left) / rect.width) * state.mediaDuration);
    addBreakRow(Math.round(sec * 10) / 10);
  });
}

let savedBreaks = null;

export function applyCatalogPreset(item) {
  if (!item) return;
  $("contentUrl").value = item.url;
  if (item.live) {
    if (!state.liveMode) {
      try { savedBreaks = readBreaksFromDom(); } catch (_) { savedBreaks = []; }
    }
    if ($("breakList")) $("breakList").innerHTML = "";
    renderTimeline();
    setLiveUi(true);
    setStatus("Live — ads are not available on this rail.");
  } else {
    const fromLive = state.liveMode;
    setLiveUi(false);
    if (item.fillAds) {
      savedBreaks = null;
      $("breakList").innerHTML = "";
      addBreakRow(30, DEFAULT_AD, 12);
      addBreakRow(90, DEFAULT_AD, 12);
      setStatus("BBB with two 12s Tears of Steel breaks. Click Play on the hero.", "ok");
    } else if (fromLive) {
      $("breakList").innerHTML = "";
      (savedBreaks || []).forEach((b) => addBreakRow(b.offsetSec, b.assetUri, b.durationSec));
      savedBreaks = null;
    } else if ($("breakList")) {
      $("breakList").innerHTML = "";
    }
  }
  refreshPoster();
}
