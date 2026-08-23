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
};

export const HINTS = {
  ssai: "Stitches the ad into the media playlist (CUE-OUT / CUE-IN + DISCONTINUITY). Any HLS player can play the result.",
  sgai: "Leaves content as content and injects EXT-X-DATERANGE CLASS=com.apple.hls.interstitial. hls.js 1.6+ loads the ad separately.",
};

export const LENGTH_HINTS = {
  ssai: "Each row is offset + ad URL + max duration (whole segments). Tears of Steel ≈ 4s/seg.",
  sgai: "Each row’s duration is DURATION + X-PLAYOUT-LIMIT for that interstitial.",
};

export const DEFAULT_AD = SAMPLES.ad;

export const state = {
  strategy: localStorage.getItem("hls-demo-strategy") || "ssai",
  hls: null,
  lastSession: null,
  mediaDuration: 0,
  useProxy: false,
  proxyKnown: false,
  adWindows: [],
  adPollTimer: null,
  watchingAds: false,
  lastPublicBase: null,
  liveMode: false,
  seekGuard: false,
  ssaiSeekQueue: [],
  ssaiFinalTarget: null,
  ssaiWasInAd: false,
  ssaiIgnoreSnapUntil: 0,
  guardedMedia: [],
  lastGoodTime: new WeakMap(),
  mediaCurrentTime: Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "currentTime"),
};
