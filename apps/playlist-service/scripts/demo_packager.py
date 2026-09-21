#!/usr/bin/env python3
"""Watch media/inbox/*.mp4 and package fMP4 HLS. Separate process from DemoPlayerServer."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from ollama_describe import describe_poster, ollama_host, ollama_model

POLL_SEC = 2


def argv_value(flag: str) -> str | None:
    prefix = flag + "="
    args = sys.argv[1:]
    for i, arg in enumerate(args):
        if arg == flag and i + 1 < len(args):
            return args[i + 1]
        if arg.startswith(prefix):
            return arg.split("=", 1)[1]
    return None


def media_root() -> Path:
    skip_next = False
    for arg in sys.argv[1:]:
        if skip_next:
            skip_next = False
            continue
        if arg in ("--ladder", "--dash"):
            skip_next = True
            continue
        if arg.startswith("--ladder=") or arg.startswith("--dash="):
            continue
        if not arg.startswith("-"):
            return Path(arg).resolve()
    here = Path(__file__).resolve()
    for p in [here.parents[3] / "media", Path.cwd() / "media"]:
        if p.is_dir() or p.parent.is_dir():
            return p
    return Path("media").resolve()


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
    raise SystemExit("demo-packager: ffmpeg not found on PATH (set FFMPEG)")


def ffprobe_bin(ffmpeg: str) -> str:
    p = Path(ffmpeg)
    sib = p.with_name("ffprobe" + (".exe" if p.suffix.lower() == ".exe" else ""))
    if sib.is_file():
        return str(sib)
    return shutil.which("ffprobe") or "ffprobe"


def load_catalog(root: Path) -> list:
    f = root / "catalog.json"
    if not f.is_file():
        return []
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
        return list(data.get("titles") or [])
    except Exception:
        return []


def save_catalog(root: Path, titles: list) -> None:
    root.mkdir(parents=True, exist_ok=True)
    tmp = root / "catalog.json.tmp"
    tmp.write_text(json.dumps({"titles": titles}, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(root / "catalog.json")


def upsert(titles: list, item: dict) -> list:
    out = []
    found = False
    for t in titles:
        if t.get("id") == item["id"]:
            t = dict(t)
            t.update(item)
            found = True
        out.append(t)
    if not found:
        return titles
    return out


def probe(ffprobe: str, src: Path) -> tuple[float, int, bool]:
    def run(*args) -> str:
        return subprocess.check_output([ffprobe, *args, str(src)], text=True).strip()

    try:
        dur = float(run("-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1"))
    except Exception:
        dur = 0.0
    fps = 24
    try:
        raw = run("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate",
                  "-of", "default=nw=1:nk=1")
        if "/" in raw:
            n, d = raw.split("/", 1)
            fps = max(1, round(float(n) / float(d)))
        else:
            fps = max(1, round(float(raw)))
    except Exception:
        pass
    audio = False
    try:
        kind = run("-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_type",
                   "-of", "default=nw=1:nk=1")
        audio = "audio" in kind.lower()
    except Exception:
        pass
    return dur, fps, audio


def job_log(root: Path, tid: str, msg: str) -> None:
    log_dir = root / "inbox" / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%H:%M:%S")
    line = f"{ts} {msg.rstrip()}\n"
    with (log_dir / f"{tid}.log").open("a", encoding="utf-8") as fh:
        fh.write(line)
    print(f"demo-packager[{tid}]: {msg}", flush=True)


class Cancelled(Exception):
    pass


def cancel_path(root: Path, tid: str) -> Path:
    return root / "inbox" / f"{tid}.cancel"


def is_cancelled(root: Path, tid: str) -> bool:
    return cancel_path(root, tid).is_file()


def catalog_has(root: Path, tid: str) -> bool:
    return any(t.get("id") == tid for t in load_catalog(root))


def cleanup_job(root: Path, tid: str) -> None:
    inbox = root / "inbox"
    dest = root / "titles" / tid
    if dest.is_dir():
        shutil.rmtree(dest, ignore_errors=True)
    for p in [
        inbox / f"{tid}.mp4",
        inbox / f"{tid}.work.mp4",
        inbox / f"{tid}.failed.mp4",
        inbox / "done" / f"{tid}.mp4",
        inbox / "logs" / f"{tid}.log",
        cancel_path(root, tid),
    ]:
        if p.is_file():
            p.unlink()


def run_logged(root: Path, tid: str, cmd: list[str]) -> None:
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    assert proc.stdout is not None
    for line in proc.stdout:
        if is_cancelled(root, tid) or not catalog_has(root, tid):
            proc.kill()
            proc.wait()
            raise Cancelled(tid)
        job_log(root, tid, line.rstrip())
    code = proc.wait()
    if is_cancelled(root, tid) or not catalog_has(root, tid):
        raise Cancelled(tid)
    if code != 0:
        raise subprocess.CalledProcessError(code, cmd)


def package(ffmpeg: str, ffprobe: str, src: Path, dest: Path, root: Path, tid: str) -> dict:
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    dur, fps, audio = probe(ffprobe, src)
    job_log(root, tid, f"probe duration={dur:.3f}s fps={fps} audio={audio}")
    gop = fps * 4
    cmd = [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "info",
    ] + av_input_args(src, audio) + [
        "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-profile:v", "high", "-level", "4.1",
        "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0",
        "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "128k",
    ]
    cmd += [
        "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
        "-hls_flags", "independent_segments", "-hls_segment_type", "fmp4",
        "-hls_fmp4_init_filename", "init.m4s",
        "-hls_segment_filename", str(dest / "v720_%d.m4s"),
        str(dest / "v720.m3u8"),
    ]
    job_log(root, tid, "ffmpeg hls start")
    run_logged(root, tid, cmd)
    job_log(root, tid, "ffmpeg hls done")
    poster_at = dur * 0.2 if dur > 0 else 1
    job_log(root, tid, "poster start")
    run_logged(root, tid, [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-ss", str(poster_at), "-i", str(src),
        "-map", "0:v:0", "-frames:v", "1", "-update", "1", "-q:v", "3",
        str(dest / "poster.jpg"),
    ])
    job_log(root, tid, "poster done")
    write_dummy_subs(dest)
    job_log(root, tid, "dummy subs written")
    width, height = probe_video_size(ffprobe, dest / "init.m4s")
    encode_missing_ladder_rungs(ffmpeg, src, dest, height, fps, audio, root, tid)
    variants = collect_variants(dest, ffprobe, fps, dur)
    if variants:
        write_master_variants(dest, variants, has_subs=(dest / "subs.m3u8").is_file())
    else:
        total = sum(p.stat().st_size for p in dest.iterdir() if p.suffix in {".m4s", ".mp4"})
        bw = max(200_000, int(total * 8 / dur)) if dur > 0.1 else 2_000_000
        write_master(dest, bw, fps, has_subs=(dest / "subs.m3u8").is_file(),
                     width=width, height=height)
        variants = [{
            "uri": "v720.m3u8", "bandwidth": bw,
            "width": width, "height": height, "fps": fps,
        }]
    info = {
        "durationSec": dur, "fps": fps, "width": width, "height": height,
        "variants": variants,
        "hasAudio": bool(audio),
    }
    try:
        write_dash_mpd(dest, info)
        job_log(root, tid, "dash mpd written")
    except Cancelled:
        raise
    except Exception as e:
        job_log(root, tid, f"dash mpd failed {e}")
    return info


def dash_manifest_path(dest: Path) -> Path:
    return dest / "manifest.mpd"


def dash_url_for(tid: str, dest: Path) -> str | None:
    if dash_manifest_path(dest).is_file():
        return f"/media/titles/{tid}/manifest.mpd"
    return None


def iso_duration(seconds: float) -> str:
    ms = max(0, int(round(float(seconds) * 1000)))
    whole, frac = divmod(ms, 1000)
    if frac == 0:
        return f"PT{whole}S"
    return f"PT{whole}.{frac:03d}S".rstrip("0")


def xml_esc(s: str) -> str:
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def parse_map_uri(playlist: Path) -> str | None:
    if not playlist.is_file():
        return None
    for line in playlist.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.startswith("#EXT-X-MAP:"):
            continue
        rest = line.split(":", 1)[1]
        for part in rest.split(","):
            part = part.strip()
            if part.startswith("URI="):
                return part.split("=", 1)[1].strip().strip('"')
    return None


def segment_timeline_xml(durs: list[float]) -> str:
    ms = [max(1, int(round(d * 1000))) for d in durs]
    if not ms:
        return ""
    lines = []
    i = 0
    first = True
    while i < len(ms):
        d = ms[i]
        r = 0
        j = i + 1
        while j < len(ms) and ms[j] == d:
            r += 1
            j += 1
        attr = f't="0" d="{d}"' if first else f'd="{d}"'
        if r:
            attr += f' r="{r}"'
        lines.append(f"            <S {attr}/>")
        first = False
        i = j
    return "\n".join(lines)


def write_dash_mpd(dest: Path, info: dict | None = None) -> Path:
    """Write manifest.mpd pointing at existing HLS CMAF (no second encode)."""
    info = info or {}
    variants = list(info.get("variants") or [])
    if not variants:
        raise RuntimeError("no HLS variants for MPD")
    dur = float(info.get("durationSec") or 0)
    if dur <= 0:
        for v in variants:
            uri = dest / str(v.get("uri") or "")
            durs = parse_extinf(uri)
            if durs:
                dur = sum(durs)
                break
    if dur <= 0:
        raise RuntimeError("unknown duration for MPD")
    fps = info.get("fps") or 24
    try:
        fps_i = int(round(float(fps)))
    except (TypeError, ValueError):
        fps_i = 24
    max_w = max((int(v.get("width") or 0) for v in variants), default=0)
    max_h = max((int(v.get("height") or 0) for v in variants), default=0)
    iso = iso_duration(dur)
    reps = []
    for v in variants:
        playlist = dest / str(v.get("uri") or "v720.m3u8")
        stem = playlist.stem
        durs = parse_extinf(playlist)
        if not durs:
            raise RuntimeError(f"no EXTINF in {playlist.name}")
        init = parse_map_uri(playlist) or rung_init_name(int(stem[1:]) if stem[1:].isdigit() else 720)
        timeline = segment_timeline_xml(durs)
        bw = int(v.get("bandwidth") or 0) or 500_000
        width = int(v.get("width") or 0)
        height = int(v.get("height") or 0)
        rid = xml_esc(stem)
        size = ""
        if width > 0 and height > 0:
            size = f' width="{width}" height="{height}"'
        reps.append(
            f'      <Representation id="{rid}" bandwidth="{bw}"{size} frameRate="{fps_i}">\n'
            f'        <SegmentTemplate timescale="1000" initialization="{xml_esc(init)}"\n'
            f'                         media="{xml_esc(stem)}_$Number$.m4s" startNumber="0">\n'
            f"          <SegmentTimeline>\n{timeline}\n"
            f"          </SegmentTimeline>\n"
            f"        </SegmentTemplate>\n"
            f"      </Representation>"
        )
    as_attrs = [
        'id="0"', 'contentType="video"', 'mimeType="video/mp4"',
        'codecs="avc1.640029,mp4a.40.2"',
        'segmentAlignment="true"', 'startWithSAP="1"',
    ]
    if max_w > 0:
        as_attrs.append(f'maxWidth="{max_w}"')
    if max_h > 0:
        as_attrs.append(f'maxHeight="{max_h}"')
    as_attrs.append(f'maxFrameRate="{fps_i}"')
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"\n'
        '     profiles="urn:mpeg:dash:profile:isoff-live:2011"\n'
        '     type="static"\n'
        f'     mediaPresentationDuration="{iso}"\n'
        '     minBufferTime="PT2S">\n'
        f'  <Period id="p0" duration="{iso}">\n'
        f'    <AdaptationSet {" ".join(as_attrs)}>\n'
        + "\n".join(reps) + "\n"
        "    </AdaptationSet>\n"
        "  </Period>\n"
        "</MPD>\n"
    )
    out = dash_manifest_path(dest)
    out.write_text(xml, encoding="utf-8")
    return out


def backfill_dash(root: Path, ffmpeg: str, ffprobe: str, tid: str) -> None:
    dest = root / "titles" / tid
    if not dest.is_dir() or not (dest / "master.m3u8").is_file():
        raise SystemExit(f"demo-packager --dash: no HLS pack at {dest}")
    dur = 0.0
    for p in video_playlists(dest):
        durs = parse_extinf(p)
        if durs:
            dur = sum(durs)
            break
    fps = 24
    variants = collect_variants(dest, ffprobe, fps, dur or 1.0)
    width = max((int(v.get("width") or 0) for v in variants), default=0)
    height = max((int(v.get("height") or 0) for v in variants), default=0)
    write_dash_mpd(dest, {
        "durationSec": dur, "fps": fps, "width": width, "height": height,
        "variants": variants,
    })
    titles = upsert(load_catalog(root), {"id": tid, "dashUrl": dash_url_for(tid, dest)})
    save_catalog(root, titles)
    job_log(root, tid, "dashUrl set")


# Extra rungs below the source-size encode. Never encode a rung >= source height.
LADDER_HEIGHTS = (360, 480)


def encode_missing_ladder_rungs(
        ffmpeg: str, src: Path, dest: Path, source_height: int, fps: int,
        audio: bool, root: Path, tid: str) -> None:
    for rung in LADDER_HEIGHTS:
        if source_height <= rung:
            continue
        if (dest / f"v{rung}.m3u8").is_file():
            job_log(root, tid, f"v{rung}.m3u8 already present")
            continue
        encode_rung(ffmpeg, src, dest, rung, fps, audio, root, tid)


def av_input_args(src: Path, audio: bool) -> list[str]:
    """Always emit muxed H.264+AAC so SSAI can append into a 2-track buffer."""
    if audio:
        return ["-i", str(src), "-map", "0:v:0", "-map", "0:a:0"]
    return [
        "-i", str(src),
        "-f", "lavfi", "-t", "36000",
        "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
        "-map", "0:v:0", "-map", "1:a:0", "-shortest",
    ]


def rung_init_name(height: int) -> str:
    return "init.m4s" if height == 720 else f"init{height}.m4s"


def encode_rung(ffmpeg: str, src: Path, dest: Path, height: int, fps: int,
                audio: bool, root: Path, tid: str) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    gop = max(1, fps * 4)
    stem = f"v{height}"
    init_name = rung_init_name(height)
    cmd = [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "info",
    ] + av_input_args(src, audio) + [
        "-vf", f"scale=-2:{height}",
        "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-profile:v", "high", "-level", "4.1",
        "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0",
        "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "128k",
    ]
    cmd += [
        "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
        "-hls_flags", "independent_segments", "-hls_segment_type", "fmp4",
        "-hls_fmp4_init_filename", init_name,
        "-hls_segment_filename", str(dest / f"{stem}_%d.m4s"),
        str(dest / f"{stem}.m3u8"),
    ]
    job_log(root, tid, f"ffmpeg hls {height}p start")
    run_logged(root, tid, cmd)
    job_log(root, tid, f"ffmpeg hls {height}p done")


def video_playlists(dest: Path) -> list[Path]:
    out = []
    for p in dest.glob("v*.m3u8"):
        if p.stem[1:].isdigit():
            out.append(p)
    return sorted(out, key=lambda p: int(p.stem[1:]))


def collect_variants(dest: Path, ffprobe: str, fps: int | float, dur: float) -> list[dict]:
    variants = []
    for playlist in video_playlists(dest):
        height = int(playlist.stem[1:])
        init = dest / rung_init_name(height)
        probe_src = init if init.is_file() else playlist
        width, probed_h = probe_video_size(ffprobe, probe_src)
        if probed_h > 0:
            height = probed_h
        total = sum(p.stat().st_size for p in dest.glob(playlist.stem + "_*.m4s"))
        if init.is_file():
            total += init.stat().st_size
        bw = max(80_000, int(total * 8 / dur)) if dur > 0.1 else 500_000
        variants.append({
            "uri": playlist.name,
            "bandwidth": bw,
            "width": width,
            "height": height,
            "fps": fps,
        })
    return variants


def ladder_label(variants: list[dict]) -> str:
    heights = sorted({int(v["height"]) for v in variants if v.get("height")})
    if not heights:
        return "video"
    if len(heights) == 1:
        return height_label(heights[0])
    return f"{heights[0]}p–{heights[-1]}p"


def parse_extinf(playlist: Path) -> list[float]:
    durs: list[float] = []
    for line in playlist.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.startswith("#EXTINF:"):
            continue
        raw = line.split(":", 1)[1].split(",", 1)[0].strip()
        try:
            durs.append(float(raw))
        except ValueError:
            continue
    return durs


def _fmt_vtt_ts(sec: float) -> str:
    if sec < 0:
        sec = 0.0
    h = int(sec // 3600)
    m = int((sec % 3600) // 60)
    s = sec - h * 3600 - m * 60
    return f"{h:02d}:{m:02d}:{s:06.3f}"


def write_dummy_subs(dest: Path) -> bool:
    """WebVTT media playlist aligned to v720 EXTINF. Dummy cues only."""
    v720 = dest / "v720.m3u8"
    if not v720.is_file():
        return False
    durs = parse_extinf(v720)
    if not durs:
        return False
    for old in dest.glob("subs_*.vtt"):
        old.unlink()
    target = max(1, int(max(durs) + 0.999))
    lines = [
        "#EXTM3U",
        "#EXT-X-VERSION:6",
        f"#EXT-X-TARGETDURATION:{target}",
        "#EXT-X-MEDIA-SEQUENCE:0",
        "#EXT-X-PLAYLIST-TYPE:VOD",
    ]
    t = 0.0
    n = len(durs)
    for i, dur in enumerate(durs):
        cue_end = max(0.05, dur - 0.05)
        # Library fMP4 starts near 0s. The MPEG-TS +10s epoch put cues ~10s
        # late (and inside a mid-roll that begins before 10s).
        mpegts = int(round(t * 90000))
        (dest / f"subs_{i}.vtt").write_text(
            "WEBVTT\n"
            f"X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:{mpegts}\n"
            "\n"
            f"00:00:00.000 --> {_fmt_vtt_ts(cue_end)}\n"
            f"Dummy subtitle {i + 1}/{n}\n",
            encoding="utf-8",
        )
        lines.append(f"#EXTINF:{dur:.6f},")
        lines.append(f"subs_{i}.vtt")
        t += dur
    lines.append("#EXT-X-ENDLIST")
    lines.append("")
    (dest / "subs.m3u8").write_text("\n".join(lines), encoding="utf-8")
    return True


def probe_video_size(ffprobe: str, src: Path) -> tuple[int, int]:
    if not src.is_file():
        return 0, 0
    try:
        raw = subprocess.check_output([
            ffprobe, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-of", "csv=p=0:s=x", str(src),
        ], text=True).strip()
        if "x" not in raw:
            return 0, 0
        w_s, h_s = raw.split("x", 1)
        w, h = int(w_s), int(h_s)
        return (w, h) if w > 0 and h > 0 else (0, 0)
    except Exception:
        return 0, 0


def height_label(height: int) -> str:
    return f"{height}p" if height > 0 else "video"


def parse_resolution_attr(stream: str) -> tuple[int, int]:
    if not stream or "RESOLUTION=" not in stream:
        return 0, 0
    raw = stream.split("RESOLUTION=", 1)[1].split(",", 1)[0].strip()
    if "x" not in raw:
        return 0, 0
    try:
        w, h = raw.split("x", 1)
        wi, hi = int(w), int(h)
        return (wi, hi) if wi > 0 and hi > 0 else (0, 0)
    except ValueError:
        return 0, 0


def write_master_variants(dest: Path, variants: list[dict], has_subs: bool) -> None:
    parts = [
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        "#EXT-X-INDEPENDENT-SEGMENTS",
    ]
    if has_subs:
        parts.append(
            '#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",'
            'DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,LANGUAGE="en",URI="subs.m3u8"'
        )
    for v in variants:
        bw = int(v.get("bandwidth") or 0) or 500_000
        attrs = [
            f"BANDWIDTH={bw}",
            f"AVERAGE-BANDWIDTH={max(1, int(bw * 0.9))}",
        ]
        width = int(v.get("width") or 0)
        height = int(v.get("height") or 0)
        if width > 0 and height > 0:
            attrs.append(f"RESOLUTION={width}x{height}")
        fps = v.get("fps")
        if fps:
            attrs.append(f"FRAME-RATE={fps}")
        attrs.append('CODECS="avc1.640029,mp4a.40.2"')
        if height > 0:
            attrs.append(f'NAME="{height}"')
        stream = "#EXT-X-STREAM-INF:" + ",".join(attrs)
        if has_subs:
            stream += ',SUBTITLES="subs"'
        parts.append(stream)
        parts.append(str(v.get("uri") or "v720.m3u8"))
    parts.append("")
    (dest / "master.m3u8").write_text("\n".join(parts), encoding="utf-8")


def write_master(dest: Path, bw: int, fps: int | float, has_subs: bool,
                 width: int = 0, height: int = 0) -> None:
    write_master_variants(dest, [{
        "uri": "v720.m3u8",
        "bandwidth": bw,
        "width": width,
        "height": height,
        "fps": fps,
    }], has_subs)


def patch_master_subs(dest: Path) -> None:
    master = dest / "master.m3u8"
    if not master.is_file() or not (dest / "subs.m3u8").is_file():
        return
    text = master.read_text(encoding="utf-8", errors="replace")
    stream = ""
    fps: int | float = 24
    bw = 2_000_000
    for line in text.splitlines():
        if line.startswith("#EXT-X-STREAM-INF:"):
            stream = line
            break
    if "BANDWIDTH=" in stream:
        try:
            bw = int(stream.split("BANDWIDTH=")[1].split(",")[0])
        except ValueError:
            pass
    if "FRAME-RATE=" in stream:
        try:
            fps = float(stream.split("FRAME-RATE=")[1].split(",")[0])
        except ValueError:
            pass
    width, height = parse_resolution_attr(stream)
    pairs = parse_stream_pairs(text)
    if len(pairs) > 1:
        variants = []
        for line, uri in pairs:
            w, h = parse_resolution_attr(line)
            b = bw
            if "BANDWIDTH=" in line:
                try:
                    b = int(line.split("BANDWIDTH=")[1].split(",")[0])
                except ValueError:
                    pass
            f = fps
            if "FRAME-RATE=" in line:
                try:
                    f = float(line.split("FRAME-RATE=")[1].split(",")[0])
                except ValueError:
                    pass
            variants.append({
                "uri": uri, "bandwidth": b, "width": w, "height": h, "fps": f,
            })
        write_master_variants(dest, variants, has_subs=True)
        return
    write_master(dest, bw, fps, has_subs=True, width=width, height=height)


def parse_stream_pairs(text: str) -> list[tuple[str, str]]:
    pairs = []
    pending = None
    for line in text.splitlines():
        if line.startswith("#EXT-X-STREAM-INF:"):
            pending = line
            continue
        if pending and line and not line.startswith("#"):
            pairs.append((pending, line.strip()))
            pending = None
    return pairs


def backfill_resolution(root: Path, ffprobe: str) -> None:
    titles_dir = root / "titles"
    if not titles_dir.is_dir():
        return
    sizes: dict[str, tuple[int, int]] = {}
    for dest in sorted(titles_dir.iterdir()):
        master = dest / "master.m3u8"
        init = dest / "init.m4s"
        if not dest.is_dir() or not master.is_file() or not init.is_file():
            continue
        width, height = probe_video_size(ffprobe, init)
        if width <= 0 or height <= 0:
            print(f"demo-packager: skip {dest.name} (no video size)", flush=True)
            continue
        text = master.read_text(encoding="utf-8", errors="replace")
        stream = ""
        bw = 2_000_000
        fps: int | float = 24
        for line in text.splitlines():
            if line.startswith("#EXT-X-STREAM-INF:"):
                stream = line
                break
        if "BANDWIDTH=" in stream:
            try:
                bw = int(stream.split("BANDWIDTH=")[1].split(",")[0])
            except ValueError:
                pass
        if "FRAME-RATE=" in stream:
            try:
                fps = float(stream.split("FRAME-RATE=")[1].split(",")[0])
            except ValueError:
                pass
        playlists = video_playlists(dest)
        if len(playlists) > 1:
            variants = collect_variants(dest, ffprobe, fps, 1.0)
            # keep existing BANDWIDTH from master when collect has no duration
            existing = {uri: line for line, uri in parse_stream_pairs(text)}
            for v in variants:
                line = existing.get(v["uri"], "")
                if "BANDWIDTH=" in line:
                    try:
                        v["bandwidth"] = int(line.split("BANDWIDTH=")[1].split(",")[0])
                    except ValueError:
                        pass
            write_master_variants(dest, variants, has_subs=(dest / "subs.m3u8").is_file())
            heights = [int(v["height"]) for v in variants if v.get("height")]
            sizes[dest.name] = (0, max(heights) if heights else height)
            print(f"demo-packager: {dest.name} ladder "
                  + ",".join(f'{v["width"]}x{v["height"]}' for v in variants), flush=True)
            continue
        write_master(dest, bw, fps, has_subs=(dest / "subs.m3u8").is_file(),
                     width=width, height=height)
        sizes[dest.name] = (width, height)
        print(f"demo-packager: {dest.name} {width}x{height}", flush=True)
    rows = load_catalog(root)
    changed = False
    for row in rows:
        tid = row.get("id")
        if tid not in sizes:
            continue
        _w, height = sizes[tid]
        dur = row.get("durationSec")
        try:
            dur_s = round(float(dur)) if dur is not None else None
        except (TypeError, ValueError):
            dur_s = None
        label = height_label(height)
        sub = f"Local · {dur_s}s · {label}" if dur_s is not None else f"Local · {label}"
        if row.get("sub") != sub:
            row["sub"] = sub
            changed = True
    if changed:
        save_catalog(root, rows)


def add_ladder(root: Path, ffmpeg: str, ffprobe: str, tid: str) -> None:
    dest = root / "titles" / tid
    src = root / "inbox" / "done" / f"{tid}.mp4"
    if not dest.is_dir() or not (dest / "v720.m3u8").is_file():
        raise SystemExit(f"demo-packager: no packaged title {tid}")
    if not src.is_file():
        raise SystemExit(f"demo-packager: missing source {src}")
    dur, fps, audio = probe(ffprobe, src)
    src_w, src_h = probe_video_size(ffprobe, dest / "init.m4s")
    if src_h <= 0:
        src_w, src_h = probe_video_size(ffprobe, src)
    encode_missing_ladder_rungs(ffmpeg, src, dest, src_h, fps, audio, root, tid)
    variants = collect_variants(dest, ffprobe, fps, dur)
    if not variants:
        raise SystemExit(f"demo-packager: no video playlists in {dest}")
    write_master_variants(dest, variants, has_subs=(dest / "subs.m3u8").is_file())
    label = ladder_label(variants)
    row = next((t for t in load_catalog(root) if t.get("id") == tid), None)
    if row is not None:
        dur_s = row.get("durationSec")
        try:
            shown = round(float(dur_s if dur_s is not None else dur))
        except (TypeError, ValueError):
            shown = round(dur)
        save_catalog(root, upsert(load_catalog(root), {
            "id": tid,
            "sub": f"Local · {shown}s · {label}",
        }))
    print(f"demo-packager: ladder {tid} " + ", ".join(
        f'{v["width"]}x{v["height"]} ({v["uri"]})' for v in variants), flush=True)


def backfill_dummy_subs(root: Path) -> None:
    titles = root / "titles"
    if not titles.is_dir():
        return
    for dest in sorted(titles.iterdir()):
        if not dest.is_dir() or not (dest / "v720.m3u8").is_file():
            continue
        if write_dummy_subs(dest):
            patch_master_subs(dest)
            print(f"demo-packager: dummy subs {dest.name}", flush=True)


def sweep_orphan_cancels(root: Path) -> None:
    inbox = root / "inbox"
    if not inbox.is_dir():
        return
    for flag in inbox.glob("*.cancel"):
        tid = flag.name[: -len(".cancel")]
        if (inbox / f"{tid}.mp4").is_file() or (inbox / f"{tid}.work.mp4").is_file():
            continue
        if catalog_has(root, tid):
            continue
        flag.unlink()


def tick(root: Path, ffmpeg: str, ffprobe: str) -> None:
    inbox = root / "inbox"
    if not inbox.is_dir():
        return
    sweep_orphan_cancels(root)
    waiting = []
    for src in inbox.glob("*.mp4"):
        name = src.name
        if name.endswith(".work.mp4") or name.endswith(".failed.mp4"):
            continue
        waiting.append(src)
    waiting.sort(key=lambda p: p.stat().st_mtime)
    for src in waiting:
        name = src.name
        tid = name[:-4]
        if is_cancelled(root, tid) or not catalog_has(root, tid):
            job_log(root, tid, "skip cancelled")
            cleanup_job(root, tid)
            continue
        work = inbox / f"{tid}.work.mp4"
        if work.exists():
            work.unlink()
        src.rename(work)
        job_log(root, tid, f"packaging claimed {work.name}")
        if is_cancelled(root, tid) or not catalog_has(root, tid):
            job_log(root, tid, "cancelled")
            cleanup_job(root, tid)
            continue
        patch = {
            "id": tid,
            "url": f"/media/titles/{tid}/master.m3u8",
            "poster": f"/media/titles/{tid}/poster.jpg",
            "adUrl": None,
            "adOffset": 0,
            "adDuration": 0,
            "status": "packaging",
            "sub": "Packaging…",
        }
        existing = next((t for t in load_catalog(root) if t.get("id") == tid), None)
        if existing is None:
            job_log(root, tid, "cancelled")
            cleanup_job(root, tid)
            continue
        titles = upsert(load_catalog(root), patch)
        save_catalog(root, titles)
        if is_cancelled(root, tid) or not catalog_has(root, tid):
            job_log(root, tid, "cancelled")
            cleanup_job(root, tid)
            continue
        dest = root / "titles" / tid
        try:
            info = package(ffmpeg, ffprobe, work, dest, root, tid)
            if is_cancelled(root, tid) or not catalog_has(root, tid):
                raise Cancelled(tid)
            done = inbox / "done"
            done.mkdir(exist_ok=True)
            final = done / f"{tid}.mp4"
            if final.exists():
                final.unlink()
            work.rename(final)
            digest = hashlib.sha256(final.read_bytes()).hexdigest()
            titles = upsert(load_catalog(root), {
                "id": tid,
                "status": "ready",
                "durationSec": info["durationSec"],
                "sub": f"Local · {round(info['durationSec'])}s · {ladder_label(info.get('variants') or [])}",
                "error": None,
                "contentHash": digest,
                "hasAudio": bool(info.get("hasAudio")),
                "dashUrl": dash_url_for(tid, dest),
            })
            save_catalog(root, titles)
            job_log(root, tid, f"ready {info['durationSec']:.1f}s")
            enrich_title(root, tid)
        except Cancelled:
            job_log(root, tid, "cancelled")
            cleanup_job(root, tid)
        except Exception as e:
            if is_cancelled(root, tid) or not catalog_has(root, tid):
                job_log(root, tid, "cancelled")
                cleanup_job(root, tid)
                continue
            job_log(root, tid, f"failed {e}")
            failed = inbox / f"{tid}.failed.mp4"
            if failed.exists():
                failed.unlink()
            if work.exists():
                work.rename(failed)
            titles = upsert(load_catalog(root), {
                "id": tid,
                "status": "failed",
                "sub": "Failed",
                "error": str(e),
            })
            save_catalog(root, titles)
            print(f"demo-packager: failed {tid}: {e}", flush=True)


def enrich_title(root: Path, tid: str) -> None:
    poster = root / "titles" / tid / "poster.jpg"
    row = next((t for t in load_catalog(root) if t.get("id") == tid), None)
    current = (row or {}).get("title")
    job_log(root, tid, f"ollama describe {ollama_model()} @ {ollama_host()}")
    desc = describe_poster(poster, current)
    if not desc:
        job_log(root, tid, "ollama skipped (not running, no model, or empty reply)")
        return
    patch = {"id": tid}
    if desc.get("title"):
        patch["title"] = desc["title"]
    if desc.get("summary"):
        patch["summary"] = desc["summary"]
    if len(patch) == 1:
        job_log(root, tid, "ollama returned nothing usable")
        return
    save_catalog(root, upsert(load_catalog(root), patch))
    job_log(root, tid, "ollama "
            + ("title=" + desc["title"] + " " if desc.get("title") else "")
            + ("summary set" if desc.get("summary") else ""))


def enrich_ready(root: Path) -> None:
    for t in load_catalog(root):
        if t.get("status") not in (None, "ready"):
            continue
        if t.get("summary"):
            continue
        tid = t.get("id")
        if not tid:
            continue
        enrich_title(root, tid)


def main() -> None:
    root = media_root()
    root.mkdir(parents=True, exist_ok=True)
    if "--enrich" in sys.argv:
        print("demo-packager --enrich", flush=True)
        print(f"  Media:   {root}", flush=True)
        print(f"  Ollama:  {ollama_model()} @ {ollama_host()}", flush=True)
        enrich_ready(root)
        return
    if "--subs" in sys.argv:
        print("demo-packager --subs", flush=True)
        print(f"  Media:   {root}", flush=True)
        backfill_dummy_subs(root)
        return
    if "--fix-resolution" in sys.argv:
        ffmpeg = ffmpeg_bin()
        probe = ffprobe_bin(ffmpeg)
        print("demo-packager --fix-resolution", flush=True)
        print(f"  Media:   {root}", flush=True)
        backfill_resolution(root, probe)
        return
    dash_id = argv_value("--dash")
    if dash_id:
        ffmpeg = ffmpeg_bin()
        probe = ffprobe_bin(ffmpeg)
        print(f"demo-packager --dash {dash_id}", flush=True)
        print(f"  Media:   {root}", flush=True)
        backfill_dash(root, ffmpeg, probe, dash_id)
        return
    ladder_id = argv_value("--ladder")
    if ladder_id:
        ffmpeg = ffmpeg_bin()
        probe = ffprobe_bin(ffmpeg)
        print(f"demo-packager --ladder {ladder_id}", flush=True)
        print(f"  Media:   {root}", flush=True)
        add_ladder(root, ffmpeg, probe, ladder_id)
        return
    ffmpeg = ffmpeg_bin()
    probe = ffprobe_bin(ffmpeg)
    print("demo-packager", flush=True)
    print(f"  Media:  {root}", flush=True)
    print(f"  FFmpeg: {ffmpeg}", flush=True)
    print(f"  Ollama: {ollama_model()} @ {ollama_host()}", flush=True)
    print(f"  Inbox:  {root / 'inbox'}", flush=True)
    while True:
        try:
            tick(root, ffmpeg, probe)
        except Exception as e:
            print(f"demo-packager: {e}", file=sys.stderr, flush=True)
        time.sleep(POLL_SEC)


if __name__ == "__main__":
    main()
