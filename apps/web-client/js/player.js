import { $, bind } from "./dom.js";
import { adsAllowed, isMpdUrl, protocolForItem, setPlayerEngine, setProtocolPreference, state } from "./state.js";
import { highlight, setStatus } from "./status.js";
import { createSession, showSessionWarnings } from "./api.js";
import { setManifestButtons, syncProtocolSelect } from "./form.js";
import { parseCueWindows, rememberPlaylist } from "./cues.js";
import { renderTimeline } from "./ad-markers.js";
import { refreshAdLabel, setAdLabel, stopAdLabelWatch } from "./ad-overlay.js";
import { onSgaiInterstitialEnded, onSgaiInterstitialStarted, resetAdClock, resetSgaiOnce } from "./seek-sgai.js";
import { startAdWatch, stopAdWatch, guardInterstitialMedia } from "./seek-guard.js";
import { findCatalogItem, hidePoster, setPosterHint, showPoster } from "./ott.js";
import { startSeekThumbs, stopSeekThumbs } from "./seek-thumbs.js";
import { bindQuality, refreshQualityHint, resetQualityMenu, setQualityNative, syncQualityMenu } from "./quality.js";
import { destroyShaka, playShaka, shakaAvailable } from "./shaka-player.js";

let onDestroyed = () => {};
export function setOnDestroyed(fn) { onDestroyed = fn || (() => {}); }

export function showSessionId(id) {
  const label = $("sessionIdLabel");
  const link = $("sessionIdLink");
  if (!label || !link) return;
  if (!id || id === "static") {
    label.hidden = true;
    link.textContent = "—";
    link.href = "/api/logs";
    return;
  }
  label.hidden = false;
  link.textContent = id;
  link.href = "/api/logs?session=" + encodeURIComponent(id);
}

export function destroyPlayer() {
  stopSeekThumbs();
  stopAdWatch();
  stopAdLabelWatch();
  resetSgaiOnce();
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  destroyShaka();
  const v = $("video");
  v.removeAttribute("src");
  v.load();
  state.mediaDuration = (state.liveMode && state.forceVod && state.windowDuration)
    ? state.windowDuration
    : 0;
  setAdLabel(false);
  resetQualityMenu();
  onDestroyed();
}

export function playUrl(manifestUrl, enableInterstitials, staticSession) {
  destroyPlayer();
  const video = $("video");
  const dash = isMpdUrl(manifestUrl);
  if (dash || state.playerEngine === "shaka") {
    playShaka(manifestUrl).catch((e) => {
      if (dash) {
        setStatus("Shaka failed — DASH needs Shaka. " + (e && e.message ? e.message : e), "err");
        showPoster();
        return;
      }
      setStatus("Shaka failed — falling back to hls.js. " + (e && e.message ? e.message : e), "err");
      setPlayerEngine("hls");
      const sel = $("playerEngine");
      if (sel) sel.value = "hls";
      playUrl(manifestUrl, enableInterstitials, staticSession);
    });
    return;
  }
  if (window.Hls && Hls.isSupported()) {
    const cfg = { enableWorker: true, lowLatencyMode: false };
    if ("enableInterstitialPlayback" in Hls.DefaultConfig) {
      cfg.enableInterstitialPlayback = !!enableInterstitials;
    }
    if ("interstitialAppendInPlace" in Hls.DefaultConfig) {
      cfg.interstitialAppendInPlace = false;
    }
    if (state.liveMode && !state.forceVod && "interstitialLiveLookAhead" in Hls.DefaultConfig) {
      cfg.interstitialLiveLookAhead = 60;
    }
    if (staticSession && window.HlsDemoRewrite) {
      cfg.pLoader = window.HlsDemoRewrite.createLoader(Hls, staticSession, {
        onRewritten: (text) => {
          $("preview").innerHTML = highlight(text);
          rememberPlaylist(text);
        },
      });
    }
    state.hls = new Hls(cfg);
    state.hls.loadSource(manifestUrl);
    state.hls.attachMedia(video);
    startSeekThumbs(manifestUrl);
    startAdWatch();
    const hookInterstitial = () => {
      const im = state.hls && state.hls.interstitialsManager;
      const q = (im && im.playerQueue) || [];
      for (let i = 0; i < q.length; i++) {
        if (q[i] && q[i].media) resetAdClock(q[i].media);
      }
      guardInterstitialMedia();
    };
    if (Hls.Events.INTERSTITIAL_STARTED) {
      state.hls.on(Hls.Events.INTERSTITIAL_STARTED, (_, data) => {
        onSgaiInterstitialStarted(data);
        hookInterstitial();
      });
    }
    if (Hls.Events.INTERSTITIAL_ENDED) {
      state.hls.on(Hls.Events.INTERSTITIAL_ENDED, (_, data) => onSgaiInterstitialEnded(data));
    }
    if (Hls.Events.INTERSTITIAL_ASSET_STARTED) {
      state.hls.on(Hls.Events.INTERSTITIAL_ASSET_STARTED, hookInterstitial);
    }
    if (Hls.Events.INTERSTITIAL_ASSET_PLAYER_CREATED) {
      state.hls.on(Hls.Events.INTERSTITIAL_ASSET_PLAYER_CREATED, hookInterstitial);
    }
    state.hls.on(Hls.Events.MANIFEST_PARSED, () => {
      syncQualityMenu(state.hls);
      video.play().catch(() => {});
      hidePoster();
      setStatus("Playing", "ok");
    });
    if (Hls.Events.LEVEL_SWITCHED) {
      state.hls.on(Hls.Events.LEVEL_SWITCHED, () => refreshQualityHint(state.hls));
    }
    if (Hls.Events.LEVELS_UPDATED) {
      state.hls.on(Hls.Events.LEVELS_UPDATED, () => {
        const sel = $("qualitySelect");
        const keep = sel ? sel.value : "-1";
        syncQualityMenu(state.hls);
        if (sel && keep !== "-1" && sel.querySelector('option[value="' + keep + '"]')) {
          sel.value = keep;
          state.hls.currentLevel = Number(keep);
        }
        refreshQualityHint(state.hls);
      });
    }
    state.hls.on(Hls.Events.LEVEL_LOADED, (_, data) => {
      showSessionWarnings(state.lastSession && state.lastSession.id, setStatus);
      if (data && data.details && data.details.totalduration) {
        const fromLevel = data.details.totalduration;
        const fromVideo = video.duration;
        const next = (isFinite(fromVideo) && fromVideo > fromLevel) ? fromVideo : fromLevel;
        if (next > state.mediaDuration) state.mediaDuration = next;
        if (state.liveMode && state.forceVod) state.windowDuration = state.mediaDuration;
        $("tlHint").textContent = "Duration " + Math.round(state.mediaDuration) + "s — click to add a break.";
        renderTimeline();
      }
      if (!enableInterstitials && data && data.details) {
        rememberPlaylist(data.details.m3u8 || "");
        const levelUrl = data.details.url;
        if (levelUrl && state.useProxy) {
          fetch(levelUrl, { cache: "no-store" })
            .then((r) => r.text())
            .then(rememberPlaylist)
            .catch(() => {});
        }
      }
      refreshAdLabel();
    });
    state.hls.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) setStatus("Player error: " + data.type + " / " + data.details, "err");
    });
    return;
  }
  startAdWatch();
  startSeekThumbs(manifestUrl);
  video.src = manifestUrl;
  video.play().catch(() => {});
  hidePoster();
  setQualityNative();
  setStatus("Playing", "ok");
}

export async function apply(andPlay) {
  try {
    if (andPlay) destroyPlayer();
    $("btnApply").disabled = true;
    const item = findCatalogItem(($("contentUrl") && $("contentUrl").value) || "");
    if (protocolForItem(item) === "dash") {
      const dashUrl = item.dashUrl;
      if (!dashUrl) throw new Error("DASH is not packed for this title");
      state.lastSession = null;
      showSessionId(null);
      $("manifestUrl").value = dashUrl;
      setManifestButtons(true);
      $("sessionMeta").innerHTML = "<strong>DASH</strong> · static MPD · no ads";
      try {
        const res = await fetch(dashUrl, { cache: "no-store" });
        const text = await res.text();
        $("preview").innerHTML = highlight(text);
      } catch (_) {
        $("preview").textContent = dashUrl;
      }
      state.adWindows = [];
      setAdLabel(false);
      setStatus("Ready.", "ok");
      if (andPlay) playUrl(dashUrl, false);
      return;
    }
    if (state.strategy === "sgai" && $("playPath").value === "direct") {
      const url = $("contentUrl").value.trim();
      if (!url) throw new Error("Content URL is required");
      $("manifestUrl").value = url;
      setManifestButtons(true);
      $("sessionMeta").textContent = "Direct CDN — tags are not on the Network response.";
      showSessionId(null);
      $("preview").textContent = "// direct mode: original CDN playlist (no server rewrite)";
      if (andPlay) playUrl(url, adsAllowed());
      setStatus("Direct mode — hls.js may inject client-side. Use Local rewrite to see tags in Network.", "ok");
      return;
    }
    setStatus("Starting…");
    const session = await createSession();
    state.lastSession = session;
    showSessionId(session && session.id);
    if (session.publicManifestUrl) {
      state.lastPublicBase = session.publicManifestUrl.replace(/\/s\/.*$/, "");
    }
    if (!state.useProxy) {
      if (session.strategy === "ssai" && window.HlsDemoRewrite) {
        await window.HlsDemoRewrite.loadAdTracks(session);
      }
      const preview = window.HlsDemoRewrite
        ? await window.HlsDemoRewrite.previewMedia(session, session.contentUrl)
        : "// static rewrite unavailable";
      $("preview").innerHTML = highlight(preview);
      state.adWindows = parseCueWindows(preview);
      $("manifestUrl").value = session.contentUrl;
      setManifestButtons(true);
      $("sessionMeta").innerHTML =
        "<strong>GitHub Pages (static)</strong> · " + state.strategy.toUpperCase() +
        " · ads applied in the browser · CORS required on content/ad hosts";
      setStatus("Ready.", "ok");
      if (andPlay) playUrl(session.contentUrl, adsAllowed() && state.strategy === "sgai", session);
      return;
    }
    $("manifestUrl").value = session.manifestUrl;
    setManifestButtons(true);
    $("sessionMeta").innerHTML =
      "<strong>Session</strong> " + session.id +
      " · <strong>" + (session.strategy || state.strategy).toUpperCase() + "</strong>" +
      " · splices [" + (session.splices || []).join(", ") + "]s";
    const res = await fetch(session.manifestUrl, { cache: "no-store" });
    const text = await res.text();
    if (!res.ok) throw new Error(text);
    $("preview").innerHTML = highlight(text);
    state.adWindows = parseCueWindows(text);
    setStatus("Ready.", "ok");
    if (andPlay) playUrl(session.manifestUrl, adsAllowed() && state.strategy === "sgai");
  } catch (e) {
    const msg = String(e.message || e);
    showPoster();
    setPosterHint(msg);
    setStatus(msg, "err");
  } finally {
    $("btnApply").disabled = false;
  }
}

export function bindPlayer() {
  bindQuality();
  syncProtocolSelect();
  const proto = $("protocol");
  if (proto) {
    proto.value = protocolForItem(findCatalogItem(($("contentUrl") && $("contentUrl").value) || ""));
    proto.addEventListener("change", () => {
      const item = findCatalogItem(($("contentUrl") && $("contentUrl").value) || "");
      if (proto.value === "dash" && !(item && item.dashUrl)) {
        proto.value = "hls";
        setStatus("No DASH pack for this title.", "err");
        return;
      }
      setProtocolPreference(proto.value);
      syncProtocolSelect();
      apply(true);
    });
  }
  const engine = $("playerEngine");
  if (engine) {
    engine.value = state.playerEngine === "shaka" ? "shaka" : "hls";
    engine.addEventListener("change", () => {
      const next = engine.value === "shaka" ? "shaka" : "hls";
      if (next === "shaka" && !shakaAvailable()) {
        engine.value = "hls";
        setStatus("Shaka UI script did not load.", "err");
        return;
      }
      setPlayerEngine(next);
      document.body.classList.toggle("shaka-mode", next === "shaka");
      if (($("manifestUrl") && $("manifestUrl").value) || ($("contentUrl") && $("contentUrl").value)) {
        apply(true);
      } else {
        setStatus(next === "shaka" ? "Shaka." : "hls.js.");
      }
    });
    if (state.playerEngine === "shaka") document.body.classList.add("shaka-mode");
  }
  bind("btnApply", "click", () => apply(true));
  bind("btnGenerate", "click", () => apply(false));
  bind("btnStop", "click", () => { destroyPlayer(); setAdLabel(false); setStatus("Player stopped."); });
  bind("btnLogs", "click", () => {
    const id = state.lastSession && state.lastSession.id;
    window.open(id ? ("/api/logs?session=" + encodeURIComponent(id)) : "/api/logs", "_blank");
  });
}
