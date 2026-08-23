import { $ } from "./dom.js";

export function setStatus(msg, kind) {
  $("status").textContent = msg;
  $("status").className = "status" + (kind ? " " + kind : "");
}

export function highlight(text) {
  return text
    .replace(/&/g, "&amp;").replace(/</g, "&lt;")
    .replace(/#(EXT-X-CUE-OUT(?:-CONT)?|EXT-X-CUE-IN)[^\n]*/g, '<span class="cue">$&</span>')
    .replace(/#EXT-X-DISCONTINUITY/g, '<span class="disc">#EXT-X-DISCONTINUITY</span>')
    .replace(/#(EXTM3U|EXT-X-VERSION|EXT-X-TARGETDURATION|EXT-X-MEDIA-SEQUENCE|EXT-X-PLAYLIST-TYPE|EXT-X-ENDLIST|EXTINF|EXT-X-KEY|EXT-X-MAP|EXT-X-DATERANGE|EXT-X-STREAM-INF|EXT-X-MEDIA)[^\n]*/g,
      '<span class="tag">$&</span>');
}
