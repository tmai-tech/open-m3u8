#!/usr/bin/env python3
"""Grab one JPEG per featured remote title into media/posters/{id}.jpg (same idea as titles/{id}/poster.jpg).

The static FFmpeg here segfaults on remote HLS input. We fetch a playlist, download
one variant segment (plus EXT-X-MAP if present), then decode that local file.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

# Same ids / URLs as apps/web-client/js/state.js + ott.js CATALOG.
FEATURED = [
    ("mux", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    ("apple", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"),
    ("tos", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
    ("elephants", "https://playertest.longtailvideo.com/adaptive/elephants_dream_v4/index.m3u8"),
    ("angel", "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8"),
    ("skate", "https://sample.vodobox.net/skate_phantom_flex_4k/skate_phantom_flex_4k.m3u8"),
    ("vinn", "https://maitv-vod.lab.eyevinn.technology/VINN.mp4/master.m3u8"),
    ("arte", "https://test-streams.mux.dev/test_001/stream.m3u8"),
    ("atmos", "https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8"),
    ("av1", "https://devstreaming-cdn.apple.com/videos/streaming/examples/av1-sample/av1-sample.m3u8"),
    ("fdr", "https://cdn.jwplayer.com/manifests/pZxWPRg4.m3u8"),
    ("blender", "https://ireplay.tv/test/blender.m3u8"),
    ("unifiedLive", "https://demo.unified-streaming.com/k8s/low-latency/stable/channel1/channel1.isml/.m3u8"),
    ("harborLive", "https://hls-harbor-livepush.akamaized.net/live_cdn/nsqIStpj8PaG-Ev/emcQJ0pGpremocy/index.m3u8"),
]

# DASH live cards. Lowest video Representation, one segment still inside the live window.
DASH_LIVE = [
    ("dashTimeline", "https://livesim2.dashif.org/livesim2/segtimeline_1/patch_60/testpic_2s/Manifest.mpd"),
    ("dashPeriods", "https://livesim2.dashif.org/livesim2/periods_60/continuous_1/testpic_2s/Manifest.mpd"),
]


def ffmpeg_bin() -> str:
    env = os.environ.get("FFMPEG")
    if env and Path(env).is_file():
        return env
    home = Path.home() / ".local" / "bin" / "ffmpeg"
    if home.is_file():
        return str(home)
    found = shutil.which("ffmpeg")
    if found:
        return found
    raise SystemExit("ffmpeg not found (set FFMPEG)")


def media_root() -> Path:
    if len(sys.argv) > 1:
        return Path(sys.argv[1]).resolve()
    here = Path(__file__).resolve()
    for p in [here.parents[3] / "media", Path.cwd() / "media"]:
        if p.is_dir():
            return p
    return Path("media").resolve()


def fetch_bytes(url: str, timeout: int = 20) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "open-m3u8-demo/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def fetch_text(url: str) -> str:
    return fetch_bytes(url).decode("utf-8", "replace")


def abs_url(base: str, ref: str) -> str:
    return urllib.request.urljoin(base, ref.strip())


def parse_attr(line: str, key: str) -> str | None:
    m = re.search(r'(?i)\b' + re.escape(key) + r'=("([^"]*)"|[^,]*)', line)
    if not m:
        return None
    return m.group(2) if m.group(2) is not None else m.group(1)


def pick_media_playlist(master: str, master_url: str) -> str:
    lines = master.replace("\r", "").split("\n")
    best_iframe = None
    best_var = None
    pending = None
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line.upper().startswith("#EXT-X-I-FRAME-STREAM-INF"):
            uri = parse_attr(line, "URI")
            bw = int(parse_attr(line, "BANDWIDTH") or "0")
            if uri and (best_iframe is None or bw < best_iframe[0]):
                best_iframe = (bw, abs_url(master_url, uri))
            continue
        if line.upper().startswith("#EXT-X-STREAM-INF"):
            pending = line
            continue
        if pending and not line.startswith("#"):
            codecs = (parse_attr(pending, "CODECS") or "").lower()
            res = parse_attr(pending, "RESOLUTION") or ""
            video = bool(re.search(r"avc|hvc|hev|dvh|vp9|av01", codecs) or re.search(r"\d+x\d+", res))
            bw = int(parse_attr(pending, "BANDWIDTH") or "0")
            if video and (best_var is None or bw < best_var[0]):
                best_var = (bw, abs_url(master_url, line))
            pending = None
    # Prefer a regular variant: I-frame playlists start at t=0 (often black).
    if best_var:
        return best_var[1]
    if best_iframe:
        return best_iframe[1]
    raise RuntimeError("no video variant")


def pick_segment(media: str, media_url: str, at: float = 0.2) -> tuple[str | None, str]:
    """Pick a media URI around `at` of the playlist duration (GIFF/Mars use ~20%)."""
    init = None
    segs = []
    pending_dur = 4.0
    lines = media.replace("\r", "").split("\n")
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line.upper().startswith("#EXT-X-MAP"):
            uri = parse_attr(line, "URI")
            if uri:
                init = abs_url(media_url, uri)
            continue
        m = re.match(r"(?i)#EXTINF:(-?\d+(?:\.\d+)?)", line)
        if m:
            pending_dur = float(m.group(1))
            continue
        if line.startswith("#"):
            continue
        segs.append((pending_dur, abs_url(media_url, line)))
    if not segs:
        raise RuntimeError("no media segment")
    total = sum(d for d, _ in segs)
    target = total * at if total > 0 else 0
    acc = 0.0
    chosen = segs[0][1]
    for dur, uri in segs:
        if acc + dur >= target:
            chosen = uri
            break
        acc += dur
    return init, chosen


def decode_local(ffmpeg: str, src: Path, dest_jpg: Path) -> bool:
    tmp = dest_jpg.with_suffix(".tmp.jpg")
    cmd = [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(src),
        "-map", "0:v:0", "-frames:v", "1", "-update", "1", "-q:v", "3",
        str(tmp),
    ]
    try:
        subprocess.run(cmd, check=True, timeout=30)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        if tmp.is_file():
            tmp.unlink()
        return False
    if not tmp.is_file() or tmp.stat().st_size < 200:
        if tmp.is_file():
            tmp.unlink()
        return False
    tmp.replace(dest_jpg)
    return True


# MPEG-TS decode segfaults on the static FFmpeg we have; use a still instead.
STILLS = {
    "mux": "https://peach.blender.org/wp-content/uploads/poster_bunny_small.jpg",
}


def grab_still(url: str, dest: Path) -> bool:
    try:
        dest.write_bytes(fetch_bytes(url))
    except Exception as e:
        print(f"  fail {dest.stem}: still {e}", flush=True)
        return False
    if dest.stat().st_size < 200:
        dest.unlink()
        print(f"  fail {dest.stem}: empty still", flush=True)
        return False
    print(f"  ok   {dest.stem} (still, {dest.stat().st_size} bytes)", flush=True)
    return True


def grab(ffmpeg: str, url: str, dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    work = dest.parent / (dest.stem + ".work")
    if work.exists():
        shutil.rmtree(work, ignore_errors=True)
    work.mkdir()
    try:
        still = STILLS.get(dest.stem)
        if still:
            return grab_still(still, dest)
        first = fetch_text(url)
        media_url = url
        if "#EXT-X-STREAM-INF" in first.upper() or "#EXT-X-I-FRAME-STREAM-INF" in first.upper():
            media_url = pick_media_playlist(first, url)
            first = fetch_text(media_url)
        # First segment is often a fade/title; try ~20% then later if the jpg is tiny/black.
        for at in (0.2, 0.35, 0.5):
            init_url, seg_url = pick_segment(first, media_url, at)
            clip = work / "clip.bin"
            with clip.open("wb") as out:
                if init_url:
                    out.write(fetch_bytes(init_url))
                out.write(fetch_bytes(seg_url))
            if not decode_local(ffmpeg, clip, dest):
                continue
            if dest.stat().st_size < 2500:
                dest.unlink()
                continue
            print(f"  ok   {dest.stem} ({dest.stat().st_size} bytes @ {int(at * 100)}%)", flush=True)
            return True
        still = STILLS.get(dest.stem)
        if still:
            return grab_still(still, dest)
        print(f"  fail {dest.stem}: decode", flush=True)
        return False
    except Exception as e:
        still = STILLS.get(dest.stem)
        if still:
            return grab_still(still, dest)
        print(f"  fail {dest.stem}: {e}", flush=True)
        return False
    finally:
        shutil.rmtree(work, ignore_errors=True)


def xml_attr(tag: str, key: str) -> str | None:
    m = re.search(r'\b' + re.escape(key) + r'="([^"]*)"', tag)
    return m.group(1) if m else None


def lowest_video(mpd: str) -> str:
    """Inner XML of the lowest-bandwidth video AdaptationSet."""
    best = None
    for m in re.finditer(r"<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet>", mpd, re.S):
        head, body = m.group(1), m.group(2)
        ctype = (xml_attr(head, "contentType") or "").lower()
        mime = (xml_attr(head, "mimeType") or "").lower()
        if ctype != "video" and not mime.startswith("video/"):
            continue
        bw = 10**12
        for rep in re.finditer(r"<Representation\b([^>]*)/?>", body):
            raw = xml_attr(rep.group(1), "bandwidth")
            if raw and raw.isdigit():
                bw = min(bw, int(raw))
        # <= keeps the newest Period when every video rendition shares a bandwidth.
        if best is None or bw <= best[0]:
            best = (bw, body)
    if not best:
        raise RuntimeError("no video adaptation set")
    return best[1]


def dash_segment(mpd: str, mpd_url: str) -> tuple[str, str]:
    """Init URL plus one media segment a few steps behind the live edge."""
    body = lowest_video(mpd)
    st = re.search(r"<SegmentTemplate\b([^>]*)(?:/>|>(.*?)</SegmentTemplate>)", body, re.S)
    if not st:
        raise RuntimeError("no SegmentTemplate")
    attrs, inner = st.group(1), st.group(2) or ""
    rep = re.search(r'<Representation\b[^>]*\bid="([^"]+)"', body)
    rid = rep.group(1) if rep else "video"
    media = xml_attr(attrs, "media") or "$RepresentationID$/$Number$.m4s"
    init = xml_attr(attrs, "initialization") or "$RepresentationID$/init.mp4"

    def fill(tmpl: str, number: int | None, t: int | None) -> str:
        out = tmpl.replace("$RepresentationID$", rid)
        if number is not None:
            out = out.replace("$Number$", str(number))
        if t is not None:
            out = out.replace("$Time$", str(t))
        return abs_url(mpd_url, out)

    timeline = re.search(r'<S\b[^>]*\bt="(\d+)"[^>]*\bd="(\d+)"(?:[^>]*\br="(\d+)")?', inner)
    if timeline:
        t0 = int(timeline.group(1))
        d = int(timeline.group(2))
        r = int(timeline.group(3) or "0")
        # Two segments back from the end so the newest, still-open one is skipped.
        idx = max(0, r - 2)
        return fill(init, None, None), fill(media, None, t0 + d * idx)

    duration = float(xml_attr(attrs, "duration") or "2")
    start_number = int(xml_attr(attrs, "startNumber") or "1")
    pto = float(xml_attr(attrs, "presentationTimeOffset") or "0")
    timescale = float(xml_attr(attrs, "timescale") or "1")
    now = time.time()
    elapsed = max(0.0, now - (pto / timescale))
    # A few segments behind the edge; the DVR window is about a minute.
    index = max(0, int(elapsed / (duration / timescale)) - 3)
    return fill(init, None, None), fill(media, start_number + index, None)


def grab_dash(ffmpeg: str, url: str, dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    work = dest.parent / (dest.stem + ".work")
    if work.exists():
        shutil.rmtree(work, ignore_errors=True)
    work.mkdir()
    try:
        mpd = fetch_text(url)
        init_url, seg_url = dash_segment(mpd, url)
        clip = work / "clip.bin"
        with clip.open("wb") as out:
            out.write(fetch_bytes(init_url))
            out.write(fetch_bytes(seg_url))
        if not decode_local(ffmpeg, clip, dest):
            print(f"  fail {dest.stem}: decode {seg_url}", flush=True)
            return False
        print(f"  ok   {dest.stem} ({dest.stat().st_size} bytes)", flush=True)
        return True
    except Exception as e:
        print(f"  fail {dest.stem}: {e}", flush=True)
        return False
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main() -> None:
    root = media_root()
    out = root / "posters"
    ffmpeg = ffmpeg_bin()
    print(f"featured posters → {out}", flush=True)
    print(f"  FFmpeg: {ffmpeg}", flush=True)
    ok = 0
    for tid, url in FEATURED:
        if grab(ffmpeg, url, out / f"{tid}.jpg"):
            ok += 1
    mux = out / "mux.jpg"
    demo = out / "demo.jpg"
    if mux.is_file() and not demo.is_file():
        shutil.copy2(mux, demo)
        print("  ok   demo (copy of mux)", flush=True)
        ok += 1
    print(f"done {ok}/{len(FEATURED) + 1}", flush=True)
    dash_ok = 0
    for tid, url in DASH_LIVE:
        if grab_dash(ffmpeg, url, out / f"{tid}.jpg"):
            dash_ok += 1
    print(f"dash live {dash_ok}/{len(DASH_LIVE)}", flush=True)


if __name__ == "__main__":
    main()
