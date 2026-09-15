import { $, bind } from "./dom.js";
import { state } from "./state.js";

function formatBitrate(bps) {
  const n = Number(bps);
  if (!isFinite(n) || n <= 0) return "";
  if (n >= 1e6) {
    const mb = n / 1e6;
    return (Math.round(mb * 10) / 10).toFixed(1).replace(/\.0$/, "") + " Mbps";
  }
  return Math.round(n / 1000) + " kbps";
}

function heightOf(level) {
  const h = Number(level && level.height);
  return isFinite(h) && h > 0 ? h : 0;
}

function baseLabel(level) {
  const name = level && level.name != null ? String(level.name).trim() : "";
  if (name) return /^\d+$/.test(name) ? name + "p" : name;
  const h = heightOf(level);
  return h ? h + "p" : "Variant";
}

export function levelLabel(level, withBitrate) {
  let base = baseLabel(level);
  if (withBitrate) {
    const rate = formatBitrate(level && level.bitrate);
    if (rate) base += " · " + rate;
  }
  return base;
}

function labelsFor(levels) {
  const list = levels || [];
  const bases = list.map(baseLabel);
  const counts = {};
  bases.forEach((b) => { counts[b] = (counts[b] || 0) + 1; });
  return list.map((lv, i) => levelLabel(lv, counts[bases[i]] > 1));
}

function selectEl() {
  return $("qualitySelect");
}

function hintEl() {
  return $("qualityHint");
}

function setHint(text) {
  const hint = hintEl();
  if (hint) hint.textContent = text || "";
}

function fillSelect(levels) {
  const sel = selectEl();
  if (!sel) return;
  const labels = labelsFor(levels);
  sel.innerHTML = "";
  const auto = document.createElement("option");
  auto.value = "-1";
  auto.textContent = "Auto";
  sel.appendChild(auto);
  labels.forEach((label, i) => {
    const opt = document.createElement("option");
    opt.value = String(i);
    opt.textContent = label;
    sel.appendChild(opt);
  });
  sel.value = "-1";
}

export function resetQualityMenu() {
  fillSelect([]);
  const sel = selectEl();
  if (sel) {
    sel.disabled = true;
    sel.title = "Play to load variants from the master playlist";
  }
  setHint("");
}

export function setQualityNative() {
  fillSelect([]);
  const sel = selectEl();
  if (sel) {
    sel.disabled = true;
    sel.title = "Native HLS — the browser picks the variant";
  }
  setHint("Browser picks the variant");
}

export function syncQualityMenu(hls) {
  const sel = selectEl();
  if (!sel) return;
  const levels = (hls && hls.levels) || [];
  fillSelect(levels);
  if (!levels.length) {
    sel.disabled = true;
    sel.title = "No video variants in this master";
    setHint("No variants");
    return;
  }
  sel.disabled = false;
  sel.title = "Auto uses bandwidth. Pick a resolution to pin it.";
  refreshQualityHint(hls);
}

export function refreshQualityHint(hls) {
  const sel = selectEl();
  if (!sel || !hls) return;
  const auto = sel.value === "-1";
  if (!auto) {
    setHint("");
    return;
  }
  const idx = Number(hls.currentLevel);
  const levels = hls.levels || [];
  if (!isFinite(idx) || idx < 0 || !levels[idx]) {
    setHint("");
    return;
  }
  setHint(levelLabel(levels[idx], false));
}

export function bindQuality() {
  resetQualityMenu();
  bind("qualitySelect", "change", () => {
    const hls = state.hls;
    const sel = selectEl();
    if (!hls || !sel) return;
    const next = Number(sel.value);
    if (!isFinite(next)) return;
    hls.currentLevel = next;
    refreshQualityHint(hls);
  });
}
