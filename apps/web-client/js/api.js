import { state } from "./state.js";
import { buildLocalSession } from "./form.js";

export async function fetchSession(id) {
  if (!id || id === "static") return null;
  const res = await fetch("/api/session?id=" + encodeURIComponent(id), { cache: "no-store" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) return null;
  return data.session || null;
}

export function showSessionWarnings(id, setStatus) {
  if (!id || id === "static" || !setStatus) return;
  fetchSession(id).then((s) => {
    const w = (s && s.warnings) || [];
    if (w.length) setStatus(w.join("\n\n"), "warn");
  }).catch(() => {});
}

export async function createSession() {
  const body = buildLocalSession();
  if (state.proxyKnown && !state.useProxy) {
    return Object.assign({ id: "static", manifestUrl: body.contentUrl }, body);
  }
  try {
    const res = await fetch("/api/session", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.session) {
      state.proxyKnown = true;
      state.useProxy = true;
      return data.session;
    }
    if (!state.proxyKnown && (res.status === 404 || res.status === 405)) {
      state.proxyKnown = true;
      state.useProxy = false;
      return Object.assign({ id: "static", manifestUrl: body.contentUrl }, body);
    }
    throw new Error(data.error || ("HTTP " + res.status));
  } catch (e) {
    if (!state.proxyKnown && e && e.name === "TypeError") {
      state.proxyKnown = true;
      state.useProxy = false;
      return Object.assign({ id: "static", manifestUrl: body.contentUrl }, body);
    }
    throw e;
  }
}
