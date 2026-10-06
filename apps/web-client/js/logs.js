const params = new URLSearchParams(location.search);
const session = params.get("session") || params.get("id") || "";
const body = document.getElementById("logsBody");
const empty = document.getElementById("logsEmpty");
const meta = document.getElementById("logsMeta");
const olderBtn = document.getElementById("logsOlder");
const eventSelect = document.getElementById("logsEvent");
let olderBefore = null;
let shown = 0;
let loadSeq = 0;

const wanted = params.get("ev") || params.get("event") || "";
if (eventSelect && [...eventSelect.options].some((o) => o.value === wanted)) {
  eventSelect.value = wanted;
}

function eventFilter() {
  return eventSelect ? eventSelect.value : "";
}

function playlistUrl(ev) {
  return String(ev.contentUrl || ev.url || "");
}

function formatOf(ev) {
  if (ev.format) return String(ev.format);
  const path = playlistUrl(ev).split("?")[0].split("#")[0].toLowerCase();
  if (path.endsWith(".mpd")) return "dash";
  if (path.endsWith(".m3u8")) return "hls";
  return "";
}

function modeOf(ev) {
  if (ev.mode) return String(ev.mode);
  if (ev.forceVod) return "snapshot";
  if (Number(ev.liveCadenceSec) > 0) return "live";
  if (ev.ev === "session") return "vod";
  return "";
}

function discardedOf(ev) {
  const list = Array.isArray(ev.discarded) ? ev.discarded : [];
  return list.map((ad) => {
    const url = ad && ad.url ? String(ad.url) : "";
    const reason = ad && ad.reason ? String(ad.reason) : "";
    if (url && reason) return url + "\nreason: " + reason;
    return url || reason;
  }).filter(Boolean).join("\n\n");
}

function messageOf(ev) {
  if (ev.msg) {
    const text = String(ev.msg);
    if (Array.isArray(ev.discarded) && ev.discarded.length) return text.split("\n")[0];
    return text;
  }
  const bits = [];
  if (ev.strategy) bits.push(ev.strategy);
  if (ev.status != null && ev.url) bits.push("HTTP " + ev.status);
  if (ev.url) bits.push(String(ev.url));
  if (ev.contentUrl) bits.push(String(ev.contentUrl));
  if (ev.adUrl) bits.push("ad " + ev.adUrl);
  if (ev.breakCount != null) bits.push(ev.breakCount + " breaks");
  if (ev.id && !ev.sid) bits.push(String(ev.id));
  if (ev.bytes != null) bits.push(ev.bytes + " bytes");
  if (ev.cues) bits.push(ev.cues + " cues");
  if (ev.dateranges) bits.push(ev.dateranges + " date ranges");
  if (ev.discontinuity) bits.push(ev.discontinuity + " discontinuities");
  if (ev.endlist) bits.push("end");
  if (ev.pdt) bits.push(String(ev.pdt));
  if (ev.ms != null) bits.push(ev.ms + " ms");
  return bits.join("\n");
}

function when(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  if (isNaN(d.getTime())) return String(ts);
  return d.toLocaleString();
}

function asEvent(raw) {
  if (raw && typeof raw === "object") return raw;
  if (typeof raw === "string") {
    try {
      return JSON.parse(raw);
    } catch (e) {
      return { msg: raw };
    }
  }
  return { msg: String(raw) };
}

function sessionHref(sid) {
  const q = new URLSearchParams();
  q.set("session", sid);
  if (eventFilter()) q.set("ev", eventFilter());
  return "/logs.html?" + q.toString();
}

function addCell(tr, className, label, text) {
  const td = document.createElement("td");
  td.className = className;
  td.dataset.label = label;
  td.textContent = text || "";
  tr.appendChild(td);
  return td;
}

function addRow(raw) {
  const ev = asEvent(raw);
  const tr = document.createElement("tr");
  const kind = String(ev.ev || "event");
  const sid = ev.sid ? String(ev.sid) : "";
  addCell(tr, "logs-time", "Time", when(ev.ts));
  const evCell = addCell(tr, "logs-ev", "Event", kind);
  evCell.classList.add(kind);
  const sidCell = addCell(tr, "logs-sid", "Session", "");
  if (sid) {
    const a = document.createElement("a");
    a.href = sessionHref(sid);
    a.textContent = sid;
    sidCell.textContent = "";
    sidCell.appendChild(a);
  }
  addCell(tr, "logs-mode", "Mode", modeOf(ev));
  addCell(tr, "logs-format", "Format", formatOf(ev));
  addCell(tr, "logs-discarded", "Discarded ad", discardedOf(ev));
  addCell(tr, "logs-msg", "Message", messageOf(ev));
  body.appendChild(tr);
  shown++;
}

function rememberFilter() {
  const url = new URL(location.href);
  if (eventFilter()) url.searchParams.set("ev", eventFilter());
  else url.searchParams.delete("ev");
  url.searchParams.delete("event");
  history.replaceState(null, "", url);
}

async function load(before) {
  const seq = ++loadSeq;
  olderBtn.disabled = true;
  const q = new URLSearchParams();
  q.set("limit", "100");
  if (session) q.set("session", session);
  if (eventFilter()) q.set("ev", eventFilter());
  if (before != null && before >= 0) q.set("before", String(before));
  try {
    const res = await fetch("/api/logs?" + q.toString());
    const data = await res.json();
    if (seq !== loadSeq) return;
    const events = data.events || [];
    events.forEach(addRow);
    empty.hidden = shown > 0;
    empty.textContent = shown > 0 ? "" : "No matching log lines.";
    olderBefore = data.hasOlder ? data.olderBefore : null;
    olderBtn.hidden = !data.hasOlder;
    const bits = [shown + " lines"];
    if (session) bits.push("session " + session);
    if (eventFilter()) bits.push(eventFilter());
    meta.textContent = bits.join(" · ");
  } catch (e) {
    if (seq !== loadSeq) return;
    meta.textContent = "Could not load logs";
  } finally {
    if (seq === loadSeq) olderBtn.disabled = false;
  }
}

function resetAndLoad() {
  body.replaceChildren();
  shown = 0;
  olderBefore = null;
  olderBtn.hidden = true;
  empty.hidden = true;
  rememberFilter();
  load(null);
}

olderBtn.addEventListener("click", () => {
  if (olderBefore == null) return;
  load(olderBefore);
});

if (eventSelect) eventSelect.addEventListener("change", resetAndLoad);

load(null);
