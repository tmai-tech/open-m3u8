export const SAMPLES = {
  mux: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
  apple: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
  tos: "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
  ad: "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
  elephants: "https://playertest.longtailvideo.com/adaptive/elephants_dream_v4/index.m3u8",
  angel: "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
  skate: "https://sample.vodobox.net/skate_phantom_flex_4k/skate_phantom_flex_4k.m3u8",
  vinn: "https://maitv-vod.lab.eyevinn.technology/VINN.mp4/master.m3u8",
  arte: "https://test-streams.mux.dev/test_001/stream.m3u8",
  atmos: "https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8",
  av1: "https://devstreaming-cdn.apple.com/videos/streaming/examples/av1-sample/av1-sample.m3u8",
  fdr: "https://cdn.jwplayer.com/manifests/pZxWPRg4.m3u8",
  blender: "https://ireplay.tv/test/blender.m3u8",
  unifiedLive: "https://demo.unified-streaming.com/k8s/low-latency/stable/channel1/channel1.isml/.m3u8",
  harborLive: "https://hls-harbor-livepush.akamaized.net/live_cdn/nsqIStpj8PaG-Ev/emcQJ0pGpremocy/index.m3u8",
  dashTimeline: "https://livesim2.dashif.org/livesim2/segtimeline_1/patch_60/testpic_2s/Manifest.mpd",
  dashPeriods: "https://livesim2.dashif.org/livesim2/periods_60/continuous_1/testpic_2s/Manifest.mpd",
  mars: "/media/titles/summer-on-mars/master.m3u8",
  cave: "/media/titles/the-cave-charles-ancelle/master.m3u8",
  giff: "/media/titles/giff-day-1/master.m3u8",
  grok: "/media/titles/grok-clip/master.m3u8",
  grok8e30: "/media/titles/grok-video-8e30b4bc-5d97-4e1e-ab64-129b5/master.m3u8",
  grok27bf: "/media/titles/grok-video-27bf43d8-2faf-4e7e-b7a0-aaab9/master.m3u8",
  adMars: "/media/ads/mars-clip/master.m3u8",
  adGiff: "/media/ads/giff-day-1/master.m3u8",
  adGrok: "/media/ads/grok-clip/master.m3u8",
};

export const HINTS = {
  ssai: "Stitches the ad into the media playlist (CUE-OUT / CUE-IN + DISCONTINUITY). Any HLS player can play the result.",
  sgai: "Leaves content as content and injects EXT-X-DATERANGE CLASS=com.apple.hls.interstitial. hls.js 1.6+ loads the ad separately.",
};

export const LENGTH_HINTS = {
  ssai: "Add break places a cue. Add ad on that card stitches another creative in the same pod.",
  sgai: "Add break places one interstitial. Add ad appends another asset on that break’s list.",
};

export const DEFAULT_AD = SAMPLES.ad;

export const state = {
  strategy: localStorage.getItem("hls-demo-strategy") || "ssai",
  playerEngine: readPlayerEngine(),
  protocolPreference: readProtocolPreference(),
  hls: null,
  shaka: null,
  shakaUi: null,
  shakaAd: null,
  shakaAdPlaying: false,
  lastSession: null,
  mediaDuration: 0,
  useProxy: false,
  proxyKnown: false,
  adWindows: [],
  adPollTimer: null,
  watchingAds: false,
  lastPublicBase: null,
  liveMode: false,
  hlsLiveAds: readHlsLiveAds(),
  forceVod: false,
  windowDuration: 0,
  seekGuard: false,
  ssaiSeekQueue: [],
  ssaiFinalTarget: null,
  ssaiWasInAd: false,
  ssaiIgnoreSnapUntil: 0,
  guardedMedia: [],
  lastGoodTime: new WeakMap(),
  sgaiPlayedOnce: new Set(),
  mediaCurrentTime: Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "currentTime"),
};

function readPlayerEngine() {
  try {
    const q = new URLSearchParams(location.search).get("player");
    if (q === "shaka" || q === "hls") return q;
  } catch (e) { /* ignore */ }
  const stored = localStorage.getItem("hls-demo-player");
  return stored === "shaka" ? "shaka" : "hls";
}

export function setPlayerEngine(name) {
  state.playerEngine = name === "shaka" ? "shaka" : "hls";
  try { localStorage.setItem("hls-demo-player", state.playerEngine); } catch (e) { /* ignore */ }
}

function readProtocolPreference() {
  try {
    const q = new URLSearchParams(location.search).get("protocol");
    if (q === "dash" || q === "hls") return q;
  } catch (e) { /* ignore */ }
  try {
    const stored = localStorage.getItem("hls-demo-protocol");
    if (stored === "dash" || stored === "hls") return stored;
  } catch (e) { /* ignore */ }
  return "hls";
}

export function setProtocolPreference(name) {
  state.protocolPreference = name === "dash" ? "dash" : "hls";
  try { localStorage.setItem("hls-demo-protocol", state.protocolPreference); } catch (e) { /* ignore */ }
}

export function itemHasDash(item) {
  return !!(item && item.dashUrl);
}

/** Effective protocol for this title: DASH only if the pack exists. */
export function protocolForItem(item) {
  if (state.protocolPreference === "dash" && itemHasDash(item)) return "dash";
  return "hls";
}

export function isMpdUrl(url) {
  return /\.mpd(\?|#|$)/i.test(url || "");
}

/** Rolling-live SGAI: one ad under 15s, one frozen START-DATE per interval. */
export const LIVE_CADENCE = { intervalSec: 60, playoutLimit: 15 };

function readHlsLiveAds() {
  try {
    return localStorage.getItem("hls-demo-live-ads") !== "0";
  } catch (e) {
    return true;
  }
}

export function setHlsLiveAds(on) {
  state.hlsLiveAds = !!on;
  try { localStorage.setItem("hls-demo-live-ads", state.hlsLiveAds ? "1" : "0"); } catch (e) { /* ignore */ }
}

export function isLiveCadenceSession() {
  if (!(state.liveMode && !state.forceVod && state.strategy === "sgai")) return false;
  const url = (document.getElementById("contentUrl") || {}).value || "";
  if (/\.mpd(\?|#|$)/i.test(url)) return true;
  return state.hlsLiveAds !== false;
}

/** Ads on VOD, live Snapshot, and rolling live SGAI. */
export function adsAllowed() {
  if (!state.liveMode) return true;
  if (state.forceVod) return true;
  return state.strategy === "sgai";
}
