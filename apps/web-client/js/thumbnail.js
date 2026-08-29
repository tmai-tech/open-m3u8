function resolveUrl(base, ref) {
  if (ref && (ref.indexOf("/api/origin?") === 0 || ref.indexOf("/s/") === 0)) return ref;
  let b = base || "";
  if (b && b.charAt(0) === "/" && typeof location !== "undefined") {
    b = location.origin + b;
  }
  try { return new URL(ref, b).href; } catch (_) { return ref; }
}

function parseAttrMap(line) {
  const map = {};
  const re = /([A-Z0-9-]+)=("([^"]*)"|[^,]*)/gi;
  let m;
  while ((m = re.exec(line))) {
    map[m[1].toUpperCase()] = m[3] != null ? m[3] : m[2];
  }
  return map;
}

/** Prefer image playlist, then I-frame variant. Regular variants are fallback only. */
export function pickPosterPlaylist(masterText, masterUrl) {
  const lines = String(masterText || "").replace(/\r/g, "").split("\n");
  let image = null;
  let iframe = null;
  let lowest = null;
  let pending = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    if (/^#EXT-X-IMAGE-STREAM-INF/i.test(line)) {
      const a = parseAttrMap(line);
      if (a.URI) image = resolveUrl(masterUrl, a.URI);
      continue;
    }
    if (/^#EXT-X-I-FRAME-STREAM-INF/i.test(line)) {
      const a = parseAttrMap(line);
      const bw = parseInt(a.BANDWIDTH, 10) || 0;
      if (a.URI && (!iframe || bw < iframe.bw)) iframe = { url: resolveUrl(masterUrl, a.URI), bw };
      continue;
    }
    if (/^#EXT-X-STREAM-INF/i.test(line)) {
      pending = parseAttrMap(line);
      continue;
    }
    if (pending && !line.startsWith("#")) {
      const codecs = (pending.CODECS || "").toLowerCase();
      const res = pending.RESOLUTION || "";
      const video = /avc|hvc|hev|dvh|vp9|av01/.test(codecs) || /\d+x\d+/.test(res);
      const bw = parseInt(pending.BANDWIDTH, 10) || 0;
      if (video && (!lowest || bw < lowest.bw)) lowest = { url: resolveUrl(masterUrl, line), bw };
      pending = null;
    }
  }
  if (image) return { url: image, kind: "image", variant: lowest && lowest.url };
  if (iframe) return { url: iframe.url, kind: "iframe", variant: lowest && lowest.url };
  return { url: null, kind: "dummy", variant: lowest && lowest.url };
}

export function viaOrigin(url) {
  if (!url) return url;
  if (url.indexOf("/api/origin?") >= 0) return url;
  if (url.charAt(0) === "/") return url;
  try {
    if (typeof location !== "undefined" && new URL(url).origin === location.origin) return url;
  } catch (_) { /* wrap remote */ }
  return "/api/origin?url=" + encodeURIComponent(url);
}

export async function fetchText(url) {
  try {
    const res = await fetch(url, { mode: "cors", cache: "no-store" });
    if (res.ok) return res.text();
  } catch (_) { /* try same-origin proxy */ }
  const res = await fetch(viaOrigin(url), { cache: "no-store" });
  if (!res.ok) throw new Error("HTTP " + res.status);
  return res.text();
}

/** Sum EXTINF durations. Used as the Snapshot ad timeline before play. */
export function playlistDurationSec(text) {
  let total = 0;
  const lines = String(text || "").replace(/\r/g, "").split("\n");
  for (let i = 0; i < lines.length; i++) {
    const m = /^#EXTINF:(-?\d+(?:\.\d+)?)/i.exec(lines[i].trim());
    if (m) total += Number(m[1]);
  }
  return total;
}

/**
 * Duration of the current live window (or a VOD media playlist).
 * Follows the lowest video variant when {@code url} is a master.
 */
export async function probeMediaDuration(url) {
  if (!url) return 0;
  const first = await fetchText(url);
  if (!/#EXT-X-STREAM-INF/i.test(first)) {
    return playlistDurationSec(first);
  }
  const pick = pickPosterPlaylist(first, url);
  if (!pick.variant) return 0;
  const media = await fetchText(pick.variant);
  return playlistDurationSec(media);
}

function captureFrame(video) {
  const w = video.videoWidth || 640;
  const h = video.videoHeight || 360;
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(video, 0, 0, w, h);
  return canvas.toDataURL("image/jpeg", 0.82);
}

const MAX_CAPTURES = 2;
let capturesActive = 0;
const captureWaiters = [];

function acquireCapture() {
  return new Promise((resolve) => {
    if (capturesActive < MAX_CAPTURES) {
      capturesActive++;
      resolve();
      return;
    }
    captureWaiters.push(resolve);
  });
}

function releaseCapture() {
  capturesActive--;
  if (captureWaiters.length && capturesActive < MAX_CAPTURES) {
    capturesActive++;
    captureWaiters.shift()();
  }
}

function posterSeekTime(video) {
  const d = video.duration;
  if (isFinite(d) && d > 0) return d * 0.2;
  const s = video.seekable;
  if (s && s.length) {
    const start = s.start(0);
    const end = s.end(s.length - 1);
    if (end > start) return start + (end - start) * 0.2;
  }
  return 3;
}

function waitPosterFrame(video) {
  return new Promise((resolve) => {
    let settled = false;
    const done = () => {
      if (settled) return;
      settled = true;
      try { video.pause(); } catch (_) { /* ignore */ }
      resolve();
    };
    setTimeout(done, 600);
    const afterPlay = () => {
      if (video.requestVideoFrameCallback) {
        video.requestVideoFrameCallback(() => done());
        return;
      }
      done();
    };
    const p = video.play();
    if (p && p.then) p.then(afterPlay).catch(done);
    else afterPlay();
  });
}

export function capturePosterFromUrl(srcUrl, timeoutMs) {
  timeoutMs = timeoutMs || 18000;
  return acquireCapture().then(() => new Promise((resolve, reject) => {
    if (!srcUrl || !window.Hls || !Hls.isSupported()) {
      reject(new Error("no player"));
      return;
    }
    const video = document.createElement("video");
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
    video.crossOrigin = "anonymous";
    video.setAttribute("playsinline", "");
    video.style.cssText = "position:fixed;left:-4000px;top:0;width:160px;height:90px;opacity:0;pointer-events:none";
    document.body.appendChild(video);
    const hls = new Hls({ enableWorker: true, maxBufferLength: 8, capLevelToPlayerSize: true });
    let done = false;
    const finish = (err, url) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { hls.destroy(); } catch (_) { /* ignore */ }
      try { video.remove(); } catch (_) { /* ignore */ }
      if (err) reject(err);
      else resolve(url);
    };
    const timer = setTimeout(() => finish(new Error("poster timeout")), timeoutMs);
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data && data.fatal) finish(new Error(data.details || "hls error"));
    });
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      const grab = () => {
        waitPosterFrame(video).then(() => {
          try {
            if (video.videoWidth < 2) {
              finish(new Error("no frame"));
              return;
            }
            finish(null, captureFrame(video));
          } catch (e) {
            finish(e);
          }
        });
      };
      const onSeeked = () => {
        video.removeEventListener("seeked", onSeeked);
        grab();
      };
      video.addEventListener("loadeddata", () => {
        const target = posterSeekTime(video);
        if (video.seekable && video.seekable.length && isFinite(target)) {
          video.addEventListener("seeked", onSeeked);
          try { video.currentTime = target; }
          catch (_) { grab(); }
        } else {
          grab();
        }
      }, { once: true });
    });
    hls.loadSource(srcUrl);
    hls.attachMedia(video);
  })).finally(releaseCapture);
}

export function dummyPosterDataUrl(title, line) {
  const canvas = document.createElement("canvas");
  canvas.width = 1280;
  canvas.height = 720;
  const ctx = canvas.getContext("2d");
  const g = ctx.createLinearGradient(0, 0, 1280, 720);
  g.addColorStop(0, "#1a1220");
  g.addColorStop(0.45, "#2a1a28");
  g.addColorStop(1, "#0c1018");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 1280, 720);
  ctx.fillStyle = "rgba(240,160,48,.12)";
  ctx.fillRect(0, 520, 1280, 200);
  ctx.fillStyle = "#eef1f7";
  ctx.font = "700 64px Segoe UI, system-ui, sans-serif";
  ctx.fillText(String(title || "Untitled").slice(0, 42), 72, 400);
  ctx.fillStyle = "#8b95a8";
  ctx.font = "400 28px Segoe UI, system-ui, sans-serif";
  ctx.fillText(line || "No I-frame variant in this master — placeholder art.", 72, 456);
  return canvas.toDataURL("image/jpeg", 0.85);
}

export async function loadPoster(contentUrl, opts) {
  const allowVariant = !opts || opts.allowVariant !== false;
  try {
    const text = await fetchText(contentUrl);
    const pick = pickPosterPlaylist(text, contentUrl);
    if (pick.kind === "iframe" || pick.kind === "image") {
      try {
        const dataUrl = await capturePosterFromUrl(viaOrigin(pick.url));
        return { dataUrl, kind: pick.kind, source: pick.url };
      } catch (_) {
        if (allowVariant && pick.variant) {
          const dataUrl = await capturePosterFromUrl(viaOrigin(pick.variant));
          return { dataUrl, kind: "variant", source: pick.variant };
        }
        throw _;
      }
    }
    if (allowVariant && pick.variant) {
      const dataUrl = await capturePosterFromUrl(viaOrigin(pick.variant));
      return { dataUrl, kind: "variant", source: pick.variant };
    }
    return { kind: "dummy" };
  } catch (_) {
    return { kind: "dummy" };
  }
}
