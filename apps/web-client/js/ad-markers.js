import { $ } from "./dom.js";
import { state } from "./state.js";

export function adMarkerItems() {
  if (state.strategy === "ssai" && state.adWindows.length) {
    return state.adWindows.map((w) => ({ start: w.start, end: w.end, range: true }));
  }
  const items = [];
  document.querySelectorAll("#breakList .brk-off").forEach((input) => {
    const sec = Number(input.value);
    if (Number.isNaN(sec) || sec < 0) return;
    items.push({ start: sec, end: sec, range: false });
  });
  return items;
}

export function renderSeekMarks() {
  const host = $("seekMarks");
  if (!host) return;
  host.innerHTML = "";
  if (!state.mediaDuration) return;
  adMarkerItems().forEach((item) => {
    const el = document.createElement("div");
    const left = Math.max(0, Math.min(100, (item.start / state.mediaDuration) * 100));
    if (item.range && item.end > item.start) {
      el.className = "seek-ad-range";
      el.style.left = left + "%";
      el.style.width = Math.max(0.35, Math.min(100 - left, ((item.end - item.start) / state.mediaDuration) * 100)) + "%";
      host.appendChild(el);
      const tick = document.createElement("div");
      tick.className = "seek-ad-mark";
      tick.style.left = left + "%";
      host.appendChild(tick);
    } else {
      el.className = "seek-ad-mark";
      el.style.left = left + "%";
      host.appendChild(el);
    }
  });
}

export function renderTimeline() {
  const tl = $("timeline");
  tl.querySelectorAll(".tl-mark").forEach((n) => n.remove());
  if (state.mediaDuration) {
    adMarkerItems().forEach((item) => {
      const mark = document.createElement("div");
      mark.className = "tl-mark";
      mark.style.left = Math.max(0, Math.min(100, (item.start / state.mediaDuration) * 100)) + "%";
      tl.appendChild(mark);
    });
  }
  renderSeekMarks();
}

export function bindSeekMarks() {
  const host = $("seekMarks");
  if (!host) return;
  host.addEventListener("click", (ev) => {
    if (!state.mediaDuration) return;
    const rect = ev.currentTarget.getBoundingClientRect();
    const sec = Math.max(0, ((ev.clientX - rect.left) / rect.width) * state.mediaDuration);
    const v = $("video");
    if (v && isFinite(v.duration)) v.currentTime = sec;
  });
}
