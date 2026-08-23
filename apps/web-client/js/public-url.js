import { $, bind } from "./dom.js";
import { state } from "./state.js";
import { setStatus } from "./status.js";

export function httpsManifestFromLocal(localUrl) {
  if (!localUrl) return "";
  if (localUrl.indexOf("https://") === 0) return localUrl;
  const path = localUrl.replace(/^https?:\/\/[^/]+/, "");
  if (state.lastPublicBase) return state.lastPublicBase.replace(/\/$/, "") + path;
  if (location.protocol === "https:") return location.origin + path;
  return "";
}

export function publicHttpsUrl() {
  return (state.lastSession && state.lastSession.publicManifestUrl)
    || httpsManifestFromLocal($("manifestUrl").value)
    || state.lastPublicBase
    || "";
}

export function fillPublicRow() {
  const input = $("publicManifestUrl");
  const copyBtn = $("btnCopyPublic");
  const watchBtn = $("btnWatch");
  if (!input) return;
  const httpsUrl = publicHttpsUrl();
  if (input.value !== httpsUrl) input.value = httpsUrl;
  if (copyBtn) copyBtn.disabled = false;
  if (watchBtn) watchBtn.disabled = !(httpsUrl && httpsUrl.indexOf("https://") === 0);
}

export function officialHlsJsUrl(src) {
  const demoConfig = btoa(JSON.stringify({
    enableStreaming: true,
    autoRecoverError: true,
    stopOnStall: false,
    dumpfMP4: false,
    levelCapping: -1,
    limitMetrics: -1,
  }));
  const hlsjsConfig = btoa(JSON.stringify({
    debug: true,
    enableWorker: true,
    lowLatencyMode: true,
    backBufferLength: 90,
  }));
  return "https://hlsjs.video-dev.org/demo/?src=" + encodeURIComponent(src)
    + "&demoConfig=" + demoConfig
    + "&hlsjsConfig=" + hlsjsConfig;
}

export function bindPublicUrl() {
  bind("btnWatch", "click", () => {
    const src = publicHttpsUrl();
    if (!src || src.indexOf("https://") !== 0) {
      setStatus("Generate a session with a Cloudflare URL first.", "err");
      return;
    }
    window.open(officialHlsJsUrl(src), "_blank");
  });
  bind("btnCopyPublic", "click", async () => {
    fillPublicRow();
    const url = publicHttpsUrl();
    if (!url) {
      setStatus("No HTTPS URL yet. Generate a session while cloudflared is running, or open this UI via the printed trycloudflare.com link.", "err");
      return;
    }
    try { await navigator.clipboard.writeText(url); setStatus("Copied HTTPS URL for hlsjs.video-dev.org.", "ok"); }
    catch (_) { $("publicManifestUrl").select(); document.execCommand("copy"); }
  });
  bind("btnCopy", "click", async () => {
    const url = $("manifestUrl").value;
    if (!url) return;
    try { await navigator.clipboard.writeText(url); setStatus("Copied manifest URL.", "ok"); }
    catch (_) { $("manifestUrl").select(); document.execCommand("copy"); }
  });
}
