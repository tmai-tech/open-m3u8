#!/usr/bin/env python3
"""
Local HLS rewrite proxy + static file server for the HLS player.

- Serves the player UI (index.html, etc.)
- POST /api/rewrite-config  → store start + ad interstitial settings
- GET  /api/rewrite-config  → read current settings
- GET  /proxy?url=<remote>  → fetch remote resource; if it is an M3U8,
  inject EXT-X-START / interstitial EXT-X-DATERANGE and rewrite all
  playlist/segment URIs to come back through this proxy.

Network tab will show the *rewritten* playlist body on localhost responses.
"""

from __future__ import annotations

import json
import re
import threading
import traceback
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
HOST = "127.0.0.1"
PORT = 8765

# Synthetic PDT when content has no PROGRAM-DATE-TIME
SYNTH_PDT_BASE = "2020-01-01T00:00:00.000Z"
SYNTH_PDT_MS = 1577836800000  # 2020-01-01T00:00:00.000Z
USER_AD_ID_PREFIX = "user-ad-"

_config_lock = threading.Lock()
_REWRITE_CONFIG: dict[str, Any] = {
    "start": {"override": False, "timeOffset": 0.0, "precise": False},
    "ads": [],
    "snapSegment": True,
    "resumeOffset": 0.0,
    "restrictSkip": False,
}


def get_config() -> dict[str, Any]:
    with _config_lock:
        return json.loads(json.dumps(_REWRITE_CONFIG))


def set_config(data: dict[str, Any]) -> dict[str, Any]:
    with _config_lock:
        if "start" in data and isinstance(data["start"], dict):
            _REWRITE_CONFIG["start"] = {
                "override": bool(data["start"].get("override", False)),
                "timeOffset": float(data["start"].get("timeOffset", 0) or 0),
                "precise": bool(data["start"].get("precise", False)),
            }
        if "ads" in data and isinstance(data["ads"], list):
            ads = []
            for i, a in enumerate(data["ads"]):
                if not isinstance(a, dict):
                    continue
                uri = (a.get("assetUri") or a.get("url") or "").strip()
                if not uri:
                    continue
                ads.append(
                    {
                        "id": str(a.get("id") or f"{USER_AD_ID_PREFIX}{i+1}"),
                        "offsetSec": float(a.get("offsetSec", 0) or 0),
                        "durationSec": float(a.get("durationSec", 15) or 15),
                        "assetUri": uri,
                    }
                )
            _REWRITE_CONFIG["ads"] = ads
        if "snapSegment" in data:
            _REWRITE_CONFIG["snapSegment"] = bool(data["snapSegment"])
        if "resumeOffset" in data:
            _REWRITE_CONFIG["resumeOffset"] = float(data["resumeOffset"] or 0)
        if "restrictSkip" in data:
            _REWRITE_CONFIG["restrictSkip"] = bool(data["restrictSkip"])
        return json.loads(json.dumps(_REWRITE_CONFIG))


def format_offset(n: float) -> str:
    if float(n).is_integer():
        return str(int(n))
    return str(round(float(n) * 1000) / 1000)


def quote_attr(s: str) -> str:
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def to_iso_date(ms: int) -> str:
    # UTC ISO with milliseconds
    from datetime import datetime, timezone

    return (
        datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def is_master_playlist(text: str) -> bool:
    return bool(
        re.search(r"#EXT-X-STREAM-INF\b", text, re.I)
        or re.search(r"#EXT-X-I-FRAME-STREAM-INF\b", text, re.I)
    )


def is_media_playlist(text: str) -> bool:
    return bool(
        re.search(r"#EXTINF\b", text, re.I)
        or re.search(r"#EXT-X-TARGETDURATION\b", text, re.I)
        or re.search(r"#EXT-X-MAP\b", text, re.I)
        or re.search(r"#EXT-X-PART\b", text, re.I)
    )


def looks_like_playlist(url: str, content_type: str, body: bytes) -> bool:
    ct = (content_type or "").lower()
    if "mpegurl" in ct or "m3u" in ct:
        return True
    path = urllib.parse.urlparse(url).path.lower()
    if path.endswith(".m3u8") or path.endswith(".m3u"):
        return True
    # sniff
    head = body[:64].lstrip().lower()
    return head.startswith(b"#extm3u") or head.startswith(b"#ext")


def parse_media_timeline(text: str) -> dict[str, Any]:
    lines = text.lstrip("\ufeff").splitlines()
    segment_starts = [0.0]
    cumulative = 0.0
    first_pdt_ms = None
    for line in lines:
        pdt = re.match(r"#EXT-X-PROGRAM-DATE-TIME:(.+)$", line, re.I)
        if pdt:
            raw = pdt.group(1).strip()
            try:
                from datetime import datetime

                # support Z
                if raw.endswith("Z"):
                    raw_p = raw[:-1] + "+00:00"
                else:
                    raw_p = raw
                ms = int(datetime.fromisoformat(raw_p).timestamp() * 1000)
                if first_pdt_ms is None:
                    first_pdt_ms = ms
            except Exception:
                pass
            continue
        inf = re.match(r"#EXTINF:([0-9.]+)", line, re.I)
        if inf:
            dur = float(inf.group(1) or 0)
            cumulative += dur
            segment_starts.append(cumulative)
    return {
        "duration": cumulative,
        "segmentStarts": segment_starts,
        "firstPdtMs": first_pdt_ms,
        "hasProgramDateTime": first_pdt_ms is not None,
    }


def snap_offset(offset: float, segment_starts: list[float]) -> float:
    if not segment_starts:
        return offset
    best = segment_starts[0]
    best_dist = abs(offset - best)
    for s in segment_starts:
        d = abs(offset - s)
        if d < best_dist:
            best_dist = d
            best = s
    return best


def build_start_tag(time_offset: float, precise: bool) -> str:
    return (
        f"#EXT-X-START:TIME-OFFSET={format_offset(time_offset)},"
        f"PRECISE={'YES' if precise else 'NO'}"
    )


def build_daterange_tag(
    break_item: dict[str, Any],
    start_date_iso: str,
    resume_offset: float,
    restrict_skip: bool,
) -> str:
    parts = [
        f"ID={quote_attr(break_item['id'])}",
        f"CLASS={quote_attr('com.apple.hls.interstitial')}",
        f"START-DATE={start_date_iso}",
    ]
    dur = float(break_item.get("durationSec") or 0)
    if dur > 0:
        parts.append(f"DURATION={format_offset(dur)}")
    parts.append(f"X-ASSET-URI={quote_attr(break_item['assetUri'])}")
    parts.append(f"X-RESUME-OFFSET={format_offset(resume_offset)}")
    if restrict_skip:
        parts.append(f"X-RESTRICT={quote_attr('SKIP')}")
    return "#EXT-X-DATERANGE:" + ",".join(parts)


def inject_media_tags(text: str, cfg: dict[str, Any], proxy_base: str) -> str:
    """Inject EXT-X-START + interstitial DATERANGEs into a media playlist."""
    start = cfg.get("start") or {}
    ads = cfg.get("ads") or []
    snap = bool(cfg.get("snapSegment", True))
    resume = float(cfg.get("resumeOffset") or 0)
    restrict_skip = bool(cfg.get("restrictSkip", False))

    need_start = bool(start.get("override"))
    need_ads = len(ads) > 0
    if not need_start and not need_ads:
        return text

    timeline = parse_media_timeline(text)
    lines = text.lstrip("\ufeff").splitlines()
    out: list[str] = []
    version_index = -1
    extm3u_index = -1
    need_synth_pdt = need_ads and not timeline["hasProgramDateTime"]
    base_pdt_ms = (
        timeline["firstPdtMs"] if timeline["hasProgramDateTime"] else SYNTH_PDT_MS
    )

    for line in lines:
        if re.match(r"#EXT-X-DATERANGE:", line, re.I) and USER_AD_ID_PREFIX in line:
            continue
        if need_start and re.match(r"#EXT-X-START\b", line, re.I):
            continue
        if need_synth_pdt and re.match(r"#EXT-X-PROGRAM-DATE-TIME:", line, re.I):
            if "2020-01-01T00:00:00" in line:
                continue
        if re.match(r"#EXTM3U\b", line, re.I) and extm3u_index < 0:
            extm3u_index = len(out)
        if re.match(r"#EXT-X-VERSION\b", line, re.I):
            version_index = len(out)
        out.append(line)

    header_inserts: list[str] = []
    if need_start:
        header_inserts.append(
            build_start_tag(float(start.get("timeOffset") or 0), bool(start.get("precise")))
        )

    if need_ads:
        if version_index >= 0:
            m = re.match(r"#EXT-X-VERSION:(\d+)", out[version_index], re.I)
            ver = int(m.group(1)) if m else 1
            if ver < 7:
                out[version_index] = "#EXT-X-VERSION:7"
        else:
            header_inserts.insert(0, "#EXT-X-VERSION:7")

        sorted_ads = sorted(ads, key=lambda a: float(a.get("offsetSec") or 0))
        for br in sorted_ads:
            off = float(br.get("offsetSec") or 0)
            if snap:
                off = snap_offset(off, timeline["segmentStarts"])
            # Keep original asset URI here; rewrite_playlist_uris() will
            # route X-ASSET-URI through /proxy so it appears in Network.
            start_ms = int(base_pdt_ms + round(off * 1000))
            header_inserts.append(
                build_daterange_tag(
                    {
                        "id": br["id"],
                        "durationSec": br.get("durationSec"),
                        "assetUri": br["assetUri"],
                    },
                    to_iso_date(start_ms),
                    resume,
                    restrict_skip,
                )
            )

    insert_at = 0
    if version_index >= 0:
        insert_at = version_index + 1
    elif extm3u_index >= 0:
        insert_at = extm3u_index + 1
    for i, h in enumerate(header_inserts):
        out.insert(insert_at + i, h)

    if need_synth_pdt:
        for j, line in enumerate(out):
            if re.match(r"#EXTINF:", line, re.I) or re.match(r"#EXT-X-MAP:", line, re.I):
                out.insert(j, f"#EXT-X-PROGRAM-DATE-TIME:{SYNTH_PDT_BASE}")
                break

    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


_ATTR_URI_RE = re.compile(
    r'(\b(?:URI|X-ASSET-URI|X-ASSET-LIST)=)("?)([^",\s][^,]*?)\2',
    re.I,
)


def proxy_url(proxy_base: str, absolute_target: str) -> str:
    return proxy_base.rstrip("/") + "/proxy?url=" + urllib.parse.quote(absolute_target, safe="")


def resolve_ref(playlist_url: str, ref: str) -> str:
    ref = ref.strip()
    if not ref or ref.startswith("data:"):
        return ref
    return urllib.parse.urljoin(playlist_url, ref)


def rewrite_playlist_uris(text: str, playlist_url: str, proxy_base: str) -> str:
    """Rewrite URI lines and URI= attributes to go through the local proxy."""
    lines = text.splitlines()
    out: list[str] = []

    def rewrite_attr_line(line: str) -> str:
        def repl(m: re.Match[str]) -> str:
            prefix, quote, val = m.group(1), m.group(2), m.group(3)
            # strip surrounding quotes if regex left them weird
            val = val.strip().strip('"')
            abs_url = resolve_ref(playlist_url, val)
            proxied = proxy_url(proxy_base, abs_url)
            q = quote or '"'
            return f"{prefix}{q}{proxied}{q}"

        return _ATTR_URI_RE.sub(repl, line)

    for line in lines:
        if not line or line.startswith("#"):
            # tag lines may contain URI="..."
            if "URI=" in line.upper() or "X-ASSET-URI=" in line.upper() or "X-ASSET-LIST=" in line.upper():
                out.append(rewrite_attr_line(line))
            else:
                out.append(line)
            continue
        # URI line
        abs_url = resolve_ref(playlist_url, line.strip())
        out.append(proxy_url(proxy_base, abs_url))

    ending = "\n" if text.endswith("\n") else ""
    return "\n".join(out) + ending


def fetch_remote(url: str) -> tuple[int, dict[str, str], bytes]:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "open-m3u8-hls-player-proxy/1.0",
            "Accept": "*/*",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = getattr(resp, "status", 200) or 200
            headers = {k.lower(): v for k, v in resp.headers.items()}
            body = resp.read()
            return status, headers, body
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b""
        headers = {k.lower(): v for k, v in (e.headers.items() if e.headers else [])}
        return e.code, headers, body


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, fmt: str, *args: Any) -> None:
        # quieter, still useful
        sys_stderr_write = __import__("sys").stderr.write
        sys_stderr_write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send_json(self, code: int, obj: Any) -> None:
        data = json.dumps(obj, indent=2).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _send_bytes(
        self,
        code: int,
        body: bytes,
        content_type: str,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Expose-Headers", "X-Playlist-Rewritten, X-Proxy-Target")
        self.send_header("Cache-Control", "no-store")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "86400")
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path.rstrip("/") == "/api/rewrite-config":
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            try:
                data = json.loads(raw.decode("utf-8") or "{}")
            except json.JSONDecodeError:
                self._send_json(400, {"error": "invalid JSON"})
                return
            cfg = set_config(data)
            self._send_json(200, {"ok": True, "config": cfg})
            return
        self._send_json(404, {"error": "not found"})

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path.rstrip("/") == "/api/rewrite-config":
            self._send_json(200, get_config())
            return

        if path.rstrip("/") == "/api/health":
            self._send_json(200, {"ok": True, "proxy": True, "port": PORT})
            return

        if path.rstrip("/") == "/proxy":
            self._handle_proxy(parsed)
            return

        # static
        if path == "/":
            self.path = "/index.html"
        return SimpleHTTPRequestHandler.do_GET(self)

    def _proxy_base(self) -> str:
        host = self.headers.get("Host") or f"{HOST}:{PORT}"
        # always http for local tool
        return f"http://{host}"

    def _handle_proxy(self, parsed: urllib.parse.ParseResult) -> None:
        qs = urllib.parse.parse_qs(parsed.query)
        target = (qs.get("url") or [None])[0]
        if not target:
            self._send_json(400, {"error": "missing url query parameter"})
            return

        target = urllib.parse.unquote(target)
        parts = urllib.parse.urlparse(target)
        if parts.scheme not in ("http", "https"):
            self._send_json(400, {"error": "only http/https remote URLs are allowed"})
            return

        try:
            status, headers, body = fetch_remote(target)
        except Exception as e:
            self._send_json(502, {"error": f"fetch failed: {e}", "url": target})
            return

        content_type = headers.get("content-type", "application/octet-stream")
        proxy_base = self._proxy_base()
        rewritten = False
        extra = {"X-Proxy-Target": target}

        if status >= 400:
            self._send_bytes(status, body, content_type, extra)
            return

        if looks_like_playlist(target, content_type, body):
            try:
                text = body.decode("utf-8")
            except UnicodeDecodeError:
                text = body.decode("utf-8", errors="replace")

            cfg = get_config()
            # 1) inject tags on media playlists
            if is_media_playlist(text) and not is_master_playlist(text):
                text = inject_media_tags(text, cfg, proxy_base)
                rewritten = True
            # 2) always rewrite URIs so the full tree stays on the proxy
            #    (also rewrites X-ASSET-URI we just inserted)
            text = rewrite_playlist_uris(text, target, proxy_base)
            rewritten = True

            out = text.encode("utf-8")
            extra["X-Playlist-Rewritten"] = "1"
            # Helpful for DevTools: identify response
            if is_master_playlist(text):
                extra["X-Playlist-Kind"] = "master"
            elif is_media_playlist(text):
                extra["X-Playlist-Kind"] = "media"
            else:
                extra["X-Playlist-Kind"] = "playlist"

            self._send_bytes(
                200,
                out,
                "application/vnd.apple.mpegurl; charset=utf-8",
                extra,
            )
            return

        # binary / segment passthrough
        self._send_bytes(status, body, content_type, extra)


def main() -> None:
    import sys

    port = PORT
    if len(sys.argv) > 1:
        port = int(sys.argv[1])

    server = ThreadingHTTPServer((HOST, port), Handler)
    print(f"HLS rewrite proxy + player")
    print(f"  UI:     http://{HOST}:{port}/")
    print(f"  Proxy:  http://{HOST}:{port}/proxy?url=<encoded-m3u8>")
    print(f"  Config: POST/GET http://{HOST}:{port}/api/rewrite-config")
    print(f"  Root:   {ROOT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
        server.server_close()


if __name__ == "__main__":
    main()
