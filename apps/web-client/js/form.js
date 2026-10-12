import { $, bind } from "./dom.js";
import { adsAllowed, DEFAULT_AD, HINTS, LENGTH_HINTS, isLiveCadenceSession, itemHasDash, LIVE_CADENCE, protocolForItem, SAMPLES, setProtocolPreference, state } from "./state.js";
import { setStatus } from "./status.js";
import { renderTimeline } from "./ad-markers.js";
import { fillPublicRow } from "./public-url.js";
import { findCatalogItem, liveAdCandidates, loadSnapshotTimeline, refreshPoster, setLiveUi, syncSnapshotAdUi } from "./ott.js";

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
    const source = card.querySelector(".brk-source");
    if (source && source.value === "fill") {
      const durationSec = Number(card.querySelector(".brk-slot").value);
      const maxAds = Number(card.querySelector(".brk-max").value);
      if (Number.isNaN(durationSec) || durationSec <= 0) {
        throw new Error("Break " + (i + 1) + ": slot length must be greater than 0");
      }
      if (!Number.isInteger(maxAds) || (maxAds !== -1 && maxAds < 1)) {
        throw new Error("Break " + (i + 1) + ": ad count must be at least 1, or -1 for no limit");
      }
      out.push({
        id: card.dataset.breakId || nextBreakId(),
        offsetSec,
        fill: true,
        durationSec,
        maxAds,
        ads: [],
      });
      return;
    }
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
    '<div class="break-source">' +
      '<select class="brk-source" aria-label="How this break picks ads">' +
        '<option value="pinned">These ads</option>' +
        '<option value="fill">Server chooses</option>' +
      '</select>' +
      '<span class="brk-fill-fields hidden">' +
        '<input class="brk-slot" type="number" min="1" step="any" value="15" aria-label="Slot length in seconds" />' +
        '<span class="col-label">s</span>' +
        '<input class="brk-max" type="number" min="-1" step="1" value="1" aria-label="Maximum ads, -1 for no limit" title="-1 uses as many ads as fit the slot" />' +
        '<span class="col-label">ads</span>' +
      '</span>' +
    '</div>' +
    '<div class="ad-list"></div>' +
    '<button type="button" class="btn btn-ad btn-sm brk-add-ad">Add ad</button>';
  card.querySelector(".brk-off").value = offsetSec != null ? offsetSec : 0;
  card.querySelector(".brk-off").addEventListener("input", renderTimeline);
  card.querySelector(".brk-remove").onclick = () => {
    card.remove();
    renderTimeline();
  };
  card.querySelector(".brk-source").addEventListener("change", () => {
    const fill = card.querySelector(".brk-source").value === "fill";
    card.querySelector(".brk-fill-fields").classList.toggle("hidden", !fill);
    card.querySelector(".ad-list").classList.toggle("hidden", fill);
    card.querySelector(".brk-add-ad").classList.toggle("hidden", fill);
  });
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
    const ad = selectedLiveAd();
    if (!ad) throw new Error("Pick a minute ad");
    const preroll = selectedAdFrom("livePrerollSelect");
    const restrictSkip = $("restrictSkip").checked;
    const restrictJump = $("restrictJump").checked;
    const playlistDelta = $("playlistDelta").checked;
    return {
      strategy: "sgai",
      contentUrl,
      adUrl: ad.any ? "" : ad.url,
      breaks: [],
      splices: [],
      offsets: [],
      adLength: ad.durationSec,
      maxAdDurationSec: ad.durationSec,
      liveCadenceSec: LIVE_CADENCE.intervalSec,
      livePlayoutLimit: ad.durationSec,
      liveFill: !!ad.any,
      prerollAdUrl: preroll && !preroll.any ? preroll.url : "",
      prerollPlayoutLimit: preroll ? preroll.durationSec : 0,
      prerollFill: !!(preroll && preroll.any),
      snapToSegment: true,
      restrictSkip,
      restrictJump,
      cueOnce: false,
      playlistDelta,
      forceVod: false,
      sgai: {
        snapToSegment: true,
        restrictSkip,
        restrictJump,
        cueOnce: false,
        playlistDelta,
        liveCadenceSec: LIVE_CADENCE.intervalSec,
        livePlayoutLimit: ad.durationSec,
        liveFill: !!ad.any,
        prerollAdUrl: preroll && !preroll.any ? preroll.url : "",
        prerollPlayoutLimit: preroll ? preroll.durationSec : 0,
        prerollFill: !!(preroll && preroll.any),
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
    playlistDelta: $("playlistDelta").checked,
    forceVod: !!state.forceVod,
    sgai: {
      snapToSegment: $("snapSegment").checked,
      restrictSkip: $("restrictSkip").checked,
      restrictJump: $("restrictJump").checked,
      cueOnce: $("cueOnce").checked,
      playlistDelta: $("playlistDelta").checked,
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

function selectedAdFrom(selectId) {
  const sel = $(selectId);
  if (!sel || !sel.value) return null;
  if (sel.value === "any") return { url: "", durationSec: 15, any: true };
  const opt = sel.selectedOptions && sel.selectedOptions[0];
  const dur = opt ? Number(opt.dataset.duration) : NaN;
  if (!isFinite(dur) || dur <= 0) return null;
  const content = findCatalogItem(($("contentUrl") && $("contentUrl").value) || "");
  const item = findCatalogItem(sel.value);
  const dash = content && protocolForItem(content) === "dash" && item && item.dashUrl;
  const played = Math.min(dur, LIVE_CADENCE.playoutLimit);
  return { url: toAbsoluteUrl(dash || sel.value), durationSec: played };
}

function selectedLiveAd() {
  const pick = $("liveAdPick");
  if (!pick || pick.hidden) return null;
  return selectedAdFrom("liveAdSelect");
}

function fillAdSelect(sel, ads, allowNone) {
  const previous = sel.value;
  sel.innerHTML = "";
  if (!ads.length) {
    sel.disabled = false;
    if (allowNone) {
      const none = document.createElement("option");
      none.value = "";
      none.textContent = "None";
      sel.appendChild(none);
    }
    const any = document.createElement("option");
    any.value = "any";
    any.textContent = "Any eligible ad";
    sel.appendChild(any);
    if (!allowNone) sel.value = "any";
    return;
  }
  sel.disabled = false;
  if (allowNone) {
    const none = document.createElement("option");
    none.value = "";
    none.textContent = "None";
    sel.appendChild(none);
  }
  const any = document.createElement("option");
  any.value = "any";
  any.textContent = "Any eligible ad";
  sel.appendChild(any);
  const nameCount = {};
  ads.forEach((t) => {
    const name = t.title || t.id;
    nameCount[name] = (nameCount[name] || 0) + 1;
  });
  ads.forEach((t) => {
    const opt = document.createElement("option");
    opt.value = t.url;
    opt.dataset.duration = String(t.durationSec);
    const shown = Math.round(t.durationSec * 10) / 10;
    let name = t.title || t.id;
    if (nameCount[name] > 1) name += " · " + String(t.id).replace(/^grok-video-/, "").slice(0, 8);
    opt.textContent = t.durationSec > LIVE_CADENCE.playoutLimit
      ? name + " · " + shown + "s, play " + LIVE_CADENCE.playoutLimit + "s"
      : name + " · " + shown + "s";
    sel.appendChild(opt);
  });
  if (previous && (previous === "any" || ads.some((t) => t.url === previous))) sel.value = previous;
  else if (allowNone) sel.value = "";
}

function showLiveAdSelect() {
  const pick = $("liveAdPick");
  const sel = $("liveAdSelect");
  const prerollPick = $("livePrerollPick");
  const prerollSel = $("livePrerollSelect");
  if (!pick || !sel) return;
  const ads = liveAdCandidates();
  fillAdSelect(sel, ads, false);
  if (prerollSel) fillAdSelect(prerollSel, ads, true);
  pick.hidden = false;
  if (prerollPick) prerollPick.hidden = false;
}

export function refreshLiveAdSelect() {
  if (!isLiveCadenceSession()) return;
  showLiveAdSelect();
}

export function bindForm() {
  bind("strategy", "change", () => setStrategy($("strategy").value, true));
  bind("btnAddBreak", "click", () => {
    if (isLiveCadenceSession()) {
      if ($("liveAdPick") && !$("liveAdPick").hidden) {
        setStatus("Live takes one ad, under 15s.");
        return;
      }
      showLiveAdSelect();
      const sel = $("liveAdSelect");
      setStatus(sel && sel.value
        ? "One ad under 15s, every minute. Play to apply."
        : "No ready titles in your library.", sel && sel.value ? "ok" : "err");
      return;
    }
    addBreakRow();
  });
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
      hint.textContent = "DASH live SGAI: one ad under 15s every 1 min. Add break to choose it.";
    }
    setStatus("DASH live — pick one ad, then Play.");
    refreshLiveAdSelect();
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
          hint.textContent = "Live SGAI: one ad under 15s every 1 min. Add break to choose it.";
        }
        setStatus(state.hlsLiveAds === false
          ? "Live — playlist only, no ads. Play."
          : "Live SGAI — pick one ad, then Play.");
        refreshLiveAdSelect();
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
