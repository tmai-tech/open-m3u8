import { $ } from "./dom.js";
import { state } from "./state.js";
import { fetchText, pickPosterPlaylist, viaOrigin } from "./thumbnail.js";

const THUMB_W = 160;
const THUMB_H = 90;
let token = 0;
let cues = [];
let kind = null;
let thumbHls = null;
let thumbVideo = null;
let seekBusy = false;
let pendingSec = null;
let hideTimer = null;
let boundVideo = null;

function resolveUrl(base, ref) {
  if (!ref) return ref;
  if (ref.indexOf("/api/origin?") === 0 || ref.indexOf("/s/") === 0) return ref;
  try { return new URL(ref, base).href; } catch (_) { return ref; }
}

function parseCues(text, baseUrl) {
  const lines = String(text || "").replace(/\r/g, "").split("\n");
  const out = [];
  let t = 0;
  let dur = 0;
  let range = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    const inf = /^#EXTINF:(-?\d+(?:\.\d+)?)/i.exec(line);
    if (inf) {
      dur = Number(inf[1]) || 0;
      continue;
    }
    const br = /^#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?/i.exec(line);
    if (br) {
      range = { len: Number(br[1]), off: br[2] != null ? Number(br[2]) : null };
      continue;
    }
    if (line.charAt(0) === "#") continue;
    out.push({ t: t, dur: dur, url: resolveUrl(baseUrl, line), range: range });
    t += dur;
    dur = 0;
    range = null;
  }
  return out;
}

function cueAt(sec) {
  if (!cues.length) return null;
  let hit = cues[0];
  for (let i = 0; i < cues.length; i++) {
    if (cues[i].t <= sec) hit = cues[i];
    else break;
  }
  return hit;
}

function thumbEls() {
  return { box: $("seekThumb"), img: $("seekThumbImg"), time: $("seekThumbTime") };
}

function formatTime(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return m + ":" + (r < 10 ? "0" : "") + r;
}

function placeThumb(sec) {
  const { box } = thumbEls();
  const wrap = $("heroStage") || $("video") && $("video").parentElement;
  if (!box || !wrap) return;
  const dur = state.mediaDuration || ($("video") && $("video").duration) || 0;
  const w = wrap.getBoundingClientRect().width;
  const x = dur > 0 ? (Math.max(0, Math.min(sec, dur)) / dur) * w : w / 2;
  const half = THUMB_W / 2;
  box.style.left = Math.max(half + 8, Math.min(w - half - 8, x)) + "px";
  box.hidden = false;
}

function showTime(sec) {
  const { time } = thumbEls();
  if (time) time.textContent = formatTime(sec);
}

function capture(video) {
  if (!video || video.videoWidth < 2 || video.readyState < 2) return null;
  const canvas = document.createElement("canvas");
  canvas.width = THUMB_W;
  canvas.height = THUMB_H;
  const ctx = canvas.getContext("2d");
  try { ctx.drawImage(video, 0, 0, THUMB_W, THUMB_H); } catch (_) { return null; }
  return canvas.toDataURL("image/jpeg", 0.72);
}

function waitForFrame(video) {
  return new Promise((resolve) => {
    let settled = false;
    const done = () => {
      if (settled) return;
      settled = true;
      resolve();
    };
    const timer = setTimeout(done, 500);
    const finish = () => {
      clearTimeout(timer);
      try { video.pause(); } catch (_) { /* ignore */ }
      done();
    };
    const grab = () => {
      if (video.requestVideoFrameCallback) {
        video.requestVideoFrameCallback(() => finish());
        return;
      }
      finish();
    };
    const p = video.play();
    if (p && p.then) p.then(grab).catch(done);
    else grab();
  });
}

async function showImageCue(cue) {
  const { img } = thumbEls();
  if (!img || !cue || !cue.url) return;
  let url = cue.url;
  if (url.indexOf("http") === 0 && url.indexOf(location.origin) !== 0) url = viaOrigin(url);
  if (cue.range && cue.range.len) {
    const headers = {};
    const start = cue.range.off != null ? cue.range.off : 0;
    headers.Range = "bytes=" + start + "-" + (start + cue.range.len - 1);
    const res = await fetch(url, { headers: headers, cache: "force-cache" });
    if (!res.ok) return;
    const blob = await res.blob();
    img.src = URL.createObjectURL(blob);
    return;
  }
  img.src = url;
}

function seekHidden(sec) {
  if (!thumbVideo) return;
  if (seekBusy) {
    pendingSec = sec;
    return;
  }
  const snap = cueAt(sec);
  const target = snap ? snap.t : sec;
  if (!isFinite(target)) return;
  seekBusy = true;
  const finish = () => {
    seekBusy = false;
    if (pendingSec != null) {
      const next = pendingSec;
      pendingSec = null;
      seekHidden(next);
    }
  };
  const onSeeked = () => {
    thumbVideo.removeEventListener("seeked", onSeeked);
    waitForFrame(thumbVideo).then(() => {
      const { img } = thumbEls();
      const data = capture(thumbVideo);
      if (img && data) img.src = data;
      finish();
    });
  };
  thumbVideo.addEventListener("seeked", onSeeked);
  try {
    if (Math.abs((thumbVideo.currentTime || 0) - target) < 0.04 && thumbVideo.videoWidth >= 2) {
      thumbVideo.removeEventListener("seeked", onSeeked);
      waitForFrame(thumbVideo).then(() => {
        const { img } = thumbEls();
        const data = capture(thumbVideo);
        if (img && data) img.src = data;
        finish();
      });
      return;
    }
    thumbVideo.currentTime = target;
  } catch (_) {
    thumbVideo.removeEventListener("seeked", onSeeked);
    finish();
  }
}

export function previewSeekAt(sec) {
  if (kind == null) return;
  const dur = state.mediaDuration || ($("video") && $("video").duration) || 0;
  if (!isFinite(sec) || sec < 0) return;
  if (dur > 0 && !isFinite(dur)) return;
  showTime(sec);
  placeThumb(sec);
  if (kind === "image") {
    const cue = cueAt(sec);
    if (cue) showImageCue(cue).catch(() => {});
    return;
  }
  seekHidden(sec);
}

export function hideSeekThumb() {
  const { box } = thumbEls();
  if (box) box.hidden = true;
}

function onBarMove(ev) {
  const host = ev.currentTarget;
  const dur = state.mediaDuration || ($("video") && $("video").duration) || 0;
  if (!dur || !isFinite(dur)) return;
  const rect = host.getBoundingClientRect();
  const sec = Math.max(0, ((ev.clientX - rect.left) / rect.width) * dur);
  previewSeekAt(sec);
}

function onBarLeave() {
  hideSeekThumb();
}

function onVideoSeeking() {
  if (hideTimer) {
    clearTimeout(hideTimer);
    hideTimer = null;
  }
  const v = $("video");
  if (!v) return;
  previewSeekAt(v.currentTime || 0);
}

function onVideoSeeked() {
  if (hideTimer) clearTimeout(hideTimer);
  hideTimer = setTimeout(hideSeekThumb, 280);
}

function bindScrub() {
  const marks = $("seekMarks");
  if (marks && !marks._thumbBound) {
    marks._thumbBound = true;
    marks.addEventListener("mousemove", onBarMove);
    marks.addEventListener("mouseleave", onBarLeave);
  }
  const v = $("video");
  if (v && v !== boundVideo) {
    unbindVideo();
    boundVideo = v;
    v.addEventListener("seeking", onVideoSeeking);
    v.addEventListener("seeked", onVideoSeeked);
  }
}

function unbindVideo() {
  if (!boundVideo) return;
  boundVideo.removeEventListener("seeking", onVideoSeeking);
  boundVideo.removeEventListener("seeked", onVideoSeeked);
  boundVideo = null;
}

function stopHidden() {
  if (thumbHls) {
    try { thumbHls.destroy(); } catch (_) { /* ignore */ }
    thumbHls = null;
  }
  if (thumbVideo) {
    try { thumbVideo.remove(); } catch (_) { /* ignore */ }
    thumbVideo = null;
  }
  seekBusy = false;
  pendingSec = null;
}

export function stopSeekThumbs() {
  token++;
  kind = null;
  cues = [];
  hideSeekThumb();
  stopHidden();
  unbindVideo();
}

function startHiddenPlayer(src, myToken) {
  if (!window.Hls || !Hls.isSupported() || !src) return;
  stopHidden();
  const video = document.createElement("video");
  video.muted = true;
  video.playsInline = true;
  video.preload = "auto";
  video.crossOrigin = "anonymous";
  video.setAttribute("playsinline", "");
  video.style.cssText = "position:fixed;left:-4000px;top:0;width:160px;height:90px;opacity:0;pointer-events:none";
  document.body.appendChild(video);
  const hls = new Hls({
    enableWorker: true,
    maxBufferLength: 8,
    maxMaxBufferLength: 12,
    capLevelToPlayerSize: false,
    lowLatencyMode: false,
  });
  hls.on(Hls.Events.ERROR, (_, data) => {
    if (data && data.fatal) stopHidden();
  });
  hls.on(Hls.Events.MANIFEST_PARSED, () => {
    if (myToken !== token) return;
    waitForFrame(video);
  });
  hls.loadSource(src);
  hls.attachMedia(video);
  thumbHls = hls;
  thumbVideo = video;
}

export async function startSeekThumbs(masterUrl) {
  const my = ++token;
  stopHidden();
  cues = [];
  kind = null;
  hideSeekThumb();
  if (!masterUrl) return;
  try {
    const text = await fetchText(masterUrl);
    if (my !== token) return;
    const pick = pickPosterPlaylist(text, masterUrl);
    if (pick.kind !== "iframe" && pick.kind !== "image" && !pick.variant) return;
    if (pick.kind === "image" || pick.kind === "iframe") {
      try {
        const media = await fetchText(pick.url);
        if (my !== token) return;
        cues = parseCues(media, pick.url);
      } catch (_) {
        cues = [];
      }
    }
    bindScrub();
    if (pick.kind === "image" && cues.length && /\.(jpe?g|png|webp)(\?|$)/i.test(cues[0].url || "")) {
      kind = "image";
      return;
    }
    // I-frame-only playlists rarely paint in <video>; decode the lowest regular variant.
    const src = pick.variant || pick.url;
    if (!src) return;
    kind = "video";
    startHiddenPlayer(src, my);
  } catch (_) {
    if (my === token) {
      kind = null;
      cues = [];
    }
  }
}
