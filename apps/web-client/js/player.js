import { $, bind } from "./dom.js";
import { state } from "./state.js";
import { highlight, setStatus } from "./status.js";
import { createSession } from "./api.js";
import { setManifestButtons } from "./form.js";
import { parseCueWindows, rememberPlaylist } from "./cues.js";
import { renderTimeline } from "./ad-markers.js";
import { refreshAdLabel, setAdLabel } from "./ad-overlay.js";
import { resetAdClock } from "./seek-sgai.js";
import { startAdWatch, stopAdWatch, guardInterstitialMedia } from "./seek-guard.js";
import { hidePoster, setPosterHint, showPoster } from "./ott.js";

let onDestroyed = () => {};
export function setOnDestroyed(fn) { onDestroyed = fn || (() => {}); }

export function destroyPlayer() {
  stopAdWatch();
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  const v = $("video");
  v.removeAttribute("src");
  v.load();
  state.mediaDuration = 0;
  setAdLabel(false);
  onDestroyed();
}

export function playUrl(manifestUrl, enableInterstitials, staticSession) {
  destroyPlayer();
  const video = $("video");
  if (window.Hls && Hls.isSupported()) {
    const cfg = { enableWorker: true, lowLatencyMode: false };
    if ("enableInterstitialPlayback" in Hls.DefaultConfig) {
      cfg.enableInterstitialPlayback = !!enableInterstitials;
    }
    if ("interstitialAppendInPlace" in Hls.DefaultConfig) {
      cfg.interstitialAppendInPlace = false;
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
      state.hls.on(Hls.Events.INTERSTITIAL_STARTED, hookInterstitial);
    }
    if (Hls.Events.INTERSTITIAL_ASSET_STARTED) {
      state.hls.on(Hls.Events.INTERSTITIAL_ASSET_STARTED, hookInterstitial);
    }
    if (Hls.Events.INTERSTITIAL_ASSET_PLAYER_CREATED) {
      state.hls.on(Hls.Events.INTERSTITIAL_ASSET_PLAYER_CREATED, hookInterstitial);
    }
    state.hls.on(Hls.Events.MANIFEST_PARSED, () => {
      video.play().catch(() => {});
      hidePoster();
    setStatus("Playing " + state.strategy.toUpperCase() + " — " + manifestUrl, "ok");
    });
    state.hls.on(Hls.Events.LEVEL_LOADED, (_, data) => {
      if (data && data.details && data.details.totalduration) {
        const fromLevel = data.details.totalduration;
        const fromVideo = video.duration;
        const next = (isFinite(fromVideo) && fromVideo > fromLevel) ? fromVideo : fromLevel;
        if (next > state.mediaDuration) state.mediaDuration = next;
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
  video.src = manifestUrl;
  video.play().catch(() => {});
  hidePoster();
  setStatus("Playing via native HLS — " + manifestUrl, "ok");
}

export async function apply(andPlay) {
  try {
    $("btnApply").disabled = true;
    if (state.strategy === "sgai" && $("playPath").value === "direct") {
      const url = $("contentUrl").value.trim();
      if (!url) throw new Error("Content URL is required");
      $("manifestUrl").value = url;
      setManifestButtons(true);
      $("sessionMeta").textContent = "Direct CDN — tags are not on the Network response.";
      $("preview").textContent = "// direct mode: original CDN playlist (no server rewrite)";
      if (andPlay) playUrl(url, !state.liveMode);
      setStatus("Direct mode — hls.js may inject client-side. Use Local rewrite to see tags in Network.", "ok");
      return;
    }
    setStatus("Creating " + state.strategy.toUpperCase() + " session…");
    const session = await createSession();
    state.lastSession = session;
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
      setStatus(state.strategy.toUpperCase() + " ready (in-browser rewrite).", "ok");
      if (andPlay) playUrl(session.contentUrl, !state.liveMode && state.strategy === "sgai", session);
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
    setStatus(state.strategy.toUpperCase() + " manifest ready.", "ok");
    if (andPlay) playUrl(session.manifestUrl, !state.liveMode && state.strategy === "sgai");
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
  bind("btnApply", "click", () => apply(true));
  bind("btnGenerate", "click", () => apply(false));
  bind("btnStop", "click", () => { destroyPlayer(); setAdLabel(false); setStatus("Player stopped."); });
}
