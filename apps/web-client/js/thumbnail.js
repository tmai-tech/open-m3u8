function resolveUrl(base, ref) {
  if (ref && ref.indexOf("/api/origin?") === 0) return ref;
  try { return new URL(ref, base).href; } catch (_) { return ref; }
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

export function capturePosterFromUrl(srcUrl, timeoutMs) {
  timeoutMs = timeoutMs || 18000;
  return new Promise((resolve, reject) => {
    if (!srcUrl || !window.Hls || !Hls.isSupported()) {
      reject(new Error("no player"));
      return;
    }
    const video = document.createElement("video");
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
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
      const seekTo = 3;
      const grab = () => {
        try {
          if (video.videoWidth < 2) {
            video.play().then(() => {
              video.pause();
              finish(null, captureFrame(video));
            }).catch(() => finish(new Error("play failed")));
            return;
          }
          finish(null, captureFrame(video));
        } catch (e) {
          finish(e);
        }
      };
      const onSeeked = () => {
        video.removeEventListener("seeked", onSeeked);
        grab();
      };
      video.addEventListener("loadeddata", () => {
        if (video.seekable && video.seekable.length) {
          video.addEventListener("seeked", onSeeked);
          try { video.currentTime = Math.min(seekTo, Math.max(0.1, (video.duration || seekTo) * 0.05)); }
          catch (_) { grab(); }
        } else {
          grab();
        }
      }, { once: true });
    });
    hls.loadSource(srcUrl);
    hls.attachMedia(video);
  });
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

export async function loadPoster(contentUrl) {
  try {
    const text = await fetchText(contentUrl);
    const pick = pickPosterPlaylist(text, contentUrl);
    if (pick.kind === "iframe" || pick.kind === "image") {
      try {
        const dataUrl = await capturePosterFromUrl(viaOrigin(pick.url));
        return { dataUrl, kind: pick.kind, source: pick.url };
      } catch (_) {
        if (pick.variant) {
          const dataUrl = await capturePosterFromUrl(viaOrigin(pick.variant));
          return { dataUrl, kind: pick.kind, source: pick.variant };
        }
        throw _;
      }
    }
    return { kind: "dummy" };
  } catch (_) {
    return { kind: "dummy" };
  }
}
