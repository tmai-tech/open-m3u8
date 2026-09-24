import { $, bind } from "./dom.js";
import { adsAllowed, DEFAULT_AD, HINTS, LENGTH_HINTS, isLiveCadenceSession, itemHasDash, LIVE_CADENCE, protocolForItem, SAMPLES, setProtocolPreference, state } from "./state.js";
import { setStatus } from "./status.js";
import { renderTimeline } from "./ad-markers.js";
import { fillPublicRow } from "./public-url.js";
import { findCatalogItem, loadSnapshotTimeline, refreshPoster, setLiveUi, syncSnapshotAdUi } from "./ott.js";

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
    const sid = $("sessionIdLabel");
    if (sid) sid.hidden = true;
    $("sessionMeta").textContent = "Strategy: " + state.strategy.toUpperCase() + " — apply to create a new session.";
    setStatus("Switched to " + state.strategy.toUpperCase() + ".");
  }
  syncSnapshotAdUi();
}

let breakSeq = 0;

function nextBreakId() {
  breakSeq += 1;
  return (state.strategy === "sgai" ? "user-ad-" : "ssai-") + breakSeq;
}

function lastCreativeFields() {
  const urls = document.querySelectorAll("#breakList .brk-url");
  const durs = document.querySelectorAll("#breakList .brk-dur");
  return {
    url: urls.length ? urls[urls.length - 1].value : defaultAdUrl(),
    dur: durs.length ? durs[durs.length - 1].value : "12",
  };
}

export function readBreaksFromDom() {
  const cards = document.querySelectorAll("#breakList .break-card");
  const out = [];
  cards.forEach((card, i) => {
    const offsetSec = Number(card.querySelector(".brk-off").value);
    if (Number.isNaN(offsetSec) || offsetSec < 0) throw new Error("Break " + (i + 1) + ": offset must be ≥ 0");
    const ads = [];
    card.querySelectorAll(".ad-row").forEach((row, j) => {
      const assetUri = (row.querySelector(".brk-url").value || "").trim();
      const durationSec = Number(row.querySelector(".brk-dur").value);
      if (!assetUri) return;
      if (Number.isNaN(durationSec) || durationSec < 0) {
        throw new Error("Break " + (i + 1) + " ad " + (j + 1) + ": duration must be ≥ 0");
      }
      ads.push({ assetUri, durationSec });
    });
    if (!ads.length) return;
    out.push({
      id: card.dataset.breakId || nextBreakId(),
      offsetSec,
      ads,
    });
  });
  return out;
}

export function addBreakRow(offsetSec, assetUri, durationSec) {
  addBreakCard(offsetSec, [{ assetUri, durationSec }]);
}

export function addBreakCard(offsetSec, ads) {
  const list = $("breakList");
  const inherit = lastCreativeFields();
  const card = document.createElement("div");
  card.className = "break-card";
  card.dataset.breakId = nextBreakId();
  card.innerHTML =
    '<div class="break-head">' +
      '<span class="break-label">Break</span>' +
      '<input class="brk-off" type="number" min="0" step="any" aria-label="Break time in seconds" />' +
      '<span class="col-label">s</span>' +
      '<button type="button" class="btn btn-ghost btn-sm brk-remove" title="Remove this break">Remove break</button>' +
    '</div>' +
    '<div class="ad-list"></div>' +
    '<button type="button" class="btn btn-ad btn-sm brk-add-ad">Add ad</button>';
  card.querySelector(".brk-off").value = offsetSec != null ? offsetSec : 0;
  card.querySelector(".brk-off").addEventListener("input", renderTimeline);
  card.querySelector(".brk-remove").onclick = () => {
    card.remove();
    renderTimeline();
  };
  card.querySelector(".brk-add-ad").onclick = () => {
    const last = card.querySelector(".ad-row:last-child");
    addCreativeRow(card, last ? last.querySelector(".brk-url").value : inherit.url,
        last ? Number(last.querySelector(".brk-dur").value) : Number(inherit.dur));
  };
  const seed = ads && ads.length ? ads : [{ assetUri: inherit.url || DEFAULT_AD, durationSec: inherit.dur }];
  seed.forEach((ad) => {
    addCreativeRow(card, ad && ad.assetUri, ad && ad.durationSec);
  });
  list.appendChild(card);
  renderTimeline();
  return card;
}

function addCreativeRow(card, assetUri, durationSec) {
  const inherit = lastCreativeFields();
  const row = document.createElement("div");
  row.className = "ad-row";
  row.innerHTML =
    '<span class="ad-label">Ad</span>' +
    '<input class="brk-url" type="url" placeholder="Ad playlist URL" aria-label="Ad playlist URL" />' +
    '<input class="brk-dur" type="number" min="0" step="any" title="Length (s)" aria-label="Ad length in seconds" />' +
    '<span class="col-label">s</span>' +
    '<button type="button" class="btn btn-icon ad-remove" title="Remove this ad" aria-label="Remove ad">×</button>';
  row.querySelector(".brk-url").value = assetUri || inherit.url || DEFAULT_AD;
  row.querySelector(".brk-dur").value = durationSec != null && durationSec !== "" ? durationSec : inherit.dur;
  row.querySelector(".ad-remove").onclick = () => {
    row.remove();
    numberAds(card);
    const left = card.querySelectorAll(".ad-row");
    if (!left.length) card.remove();
    renderTimeline();
  };
  card.querySelector(".ad-list").appendChild(row);
  numberAds(card);
}

function numberAds(card) {
  card.querySelectorAll(".ad-row .ad-label").forEach((el, i) => {
    el.textContent = "Ad " + (i + 1);
  });
}

export function defaultAdUrl() {
  const u = $("contentUrl") && $("contentUrl").value.trim();
  const item = findCatalogItem(u);
  if (item && item.library) return "";
  return DEFAULT_AD;
}

function toAbsoluteUrl(url) {
  const u = (url || "").trim();
  if (!u) return u;
  try { return new URL(u, location.origin).href; } catch (_) { return u; }
}

export function buildLocalSession() {
  const item = findCatalogItem(($("contentUrl") && $("contentUrl").value) || "");
  let contentUrl = toAbsoluteUrl($("contentUrl").value);
  if (protocolForItem(item) === "dash" && item && item.dashUrl) {
    contentUrl = toAbsoluteUrl(item.dashUrl);
  }
  if (!contentUrl) throw new Error("Content URL is required");
  if (isLiveCadenceSession()) {
    const grok = toAbsoluteUrl(SAMPLES.grok8e30);
    const restrictSkip = $("restrictSkip").checked;
    const restrictJump = $("restrictJump").checked;
    return {
      strategy: "sgai",
      contentUrl,
      adUrl: grok,
      breaks: [],
      splices: [],
      offsets: [],
      adLength: LIVE_CADENCE.playoutLimit,
      maxAdDurationSec: LIVE_CADENCE.playoutLimit,
      liveCadenceSec: LIVE_CADENCE.intervalSec,
      livePlayoutLimit: LIVE_CADENCE.playoutLimit,
      snapToSegment: true,
      restrictSkip,
      restrictJump,
      cueOnce: false,
      forceVod: false,
      sgai: {
        snapToSegment: true,
        restrictSkip,
        restrictJump,
        cueOnce: false,
        liveCadenceSec: LIVE_CADENCE.intervalSec,
        livePlayoutLimit: LIVE_CADENCE.playoutLimit,
      },
    };
  }
  const breaks = adsAllowed() ? readBreaksFromDom() : [];
  breaks.forEach((b) => {
    b.ads.forEach((a) => {
      a.assetUri = toAbsoluteUrl(a.assetUri);
    });
  });
  const splices = breaks.map((b) => b.offsetSec);
  const firstAd = breaks[0] && breaks[0].ads && breaks[0].ads[0];
  return {
    strategy: state.strategy,
    contentUrl,
    adUrl: firstAd ? firstAd.assetUri : "",
    breaks,
    splices,
    offsets: splices,
    adLength: firstAd ? firstAd.durationSec : 0,
    maxAdDurationSec: firstAd ? firstAd.durationSec : 0,
    snapToSegment: $("snapSegment").checked,
    restrictSkip: $("restrictSkip").checked,
    restrictJump: $("restrictJump").checked,
    cueOnce: $("cueOnce").checked,
    forceVod: !!state.forceVod,
    sgai: {
      snapToSegment: $("snapSegment").checked,
      restrictSkip: $("restrictSkip").checked,
      restrictJump: $("restrictJump").checked,
      cueOnce: $("cueOnce").checked,
      resumeOffset: 0,
    },
  };
}

export function configuredAdLength() {
  const first = document.querySelector("#breakList .brk-dur");
  const n = first ? Number(first.value) : NaN;
  if (isFinite(n) && n > 0) return n;
  if (state.lastSession && state.lastSession.breaks && state.lastSession.breaks[0]) {
    const b = state.lastSession.breaks[0];
    const raw = b.ads && b.ads[0] ? b.ads[0].durationSec : b.durationSec;
    const d = Number(raw);
    if (isFinite(d) && d > 0) return d;
  }
  return 12;
}

export function setManifestButtons(enabled) {
  $("btnCopy").disabled = !enabled;
  fillPublicRow();
}

export function syncProtocolSelect() {
  const sel = $("protocol");
  if (!sel) return;
  const item = findCatalogItem(($("contentUrl") && $("contentUrl").value) || "");
  const dashOk = itemHasDash(item);
  const dashOpt = sel.querySelector('option[value="dash"]');
  if (dashOpt) {
    dashOpt.disabled = !dashOk;
    dashOpt.title = dashOk ? "" : "No DASH pack for this title";
  }
  sel.value = protocolForItem(item);
  const eng = $("playerEngine");
  if (eng) {
    const dash = sel.value === "dash";
    eng.disabled = dash;
    if (dash) eng.value = "shaka";
    else eng.value = state.playerEngine === "shaka" ? "shaka" : "hls";
  }
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
  state.forceVod = !!item.asVod;
  if (item.dashLive) {
    if (savedBreaks == null) {
      try { savedBreaks = readBreaksFromDom(); } catch (_) { savedBreaks = []; }
    }
    state.windowDuration = 0;
    state.mediaDuration = 0;
    setProtocolPreference("dash");
    if ($("breakList")) $("breakList").innerHTML = "";
    renderTimeline();
    setLiveUi(true);
    setStrategy("sgai", false);
    const hint = $("liveCadenceHint");
    if (hint) {
      hint.textContent = "DASH live SGAI: 15s grok every 1 min. One replace event on the newest period, frozen for that minute.";
    }
    setStatus("DASH live — 15s grok every 1 min. Play.");
  } else if (item.live) {
    if (!state.liveMode) {
      try { savedBreaks = readBreaksFromDom(); } catch (_) { savedBreaks = []; }
    }
    if ($("breakList")) $("breakList").innerHTML = "";
    renderTimeline();
    setLiveUi(true);
    if (item.asVod) {
      loadSnapshotTimeline(item.url);
      setStatus("Snapshot — add breaks on the window timeline, then Play.");
    } else {
      state.windowDuration = 0;
      state.mediaDuration = 0;
      renderTimeline();
      if (item.live && !item.asVod) {
        setStrategy("sgai", false);
        const hint = $("liveCadenceHint");
        if (hint) {
          hint.textContent = "Live SGAI: 15s grok every 1 min (START-DATE frozen per minute, hold-back 3× target duration).";
        }
        setStatus("Live SGAI — 15s grok ad every 1 min. Play.");
      } else {
        setStatus("Live — ads are not available on this rail.");
      }
    }
  } else {
    const fromLive = state.liveMode;
    state.windowDuration = 0;
    setLiveUi(false);
    if (item.library) {
      savedBreaks = null;
      if ($("breakList")) $("breakList").innerHTML = "";
      const proto = protocolForItem(item);
      setStatus(proto === "dash"
        ? "Library title — DASH, no ads."
        : (itemHasDash(item)
          ? "Library title — HLS. Switch protocol to DASH, or add a break."
          : "Library title — no house ad. Add a break if you want one."));
    } else if (fromLive || savedBreaks) {
      $("breakList").innerHTML = "";
      (savedBreaks || []).forEach((b) => {
        if (b.ads && b.ads.length) addBreakCard(b.offsetSec, b.ads);
        else addBreakRow(b.offsetSec, b.assetUri, b.durationSec);
      });
      savedBreaks = null;
    } else if ($("breakList")) {
      $("breakList").innerHTML = "";
    }
  }
  refreshPoster();
  syncProtocolSelect();
}
