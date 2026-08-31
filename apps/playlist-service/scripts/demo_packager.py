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
DEFAULT_AD = "/media/titles/giff-day-1/master.m3u8"


def media_root() -> Path:
    for arg in sys.argv[1:]:
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
        "-i", str(src),
        "-map", "0:v:0",
    ]
    if audio:
        cmd += ["-map", "0:a:0"]
    cmd += [
        "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-profile:v", "high", "-level", "4.1",
        "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0",
    ]
    if audio:
        cmd += ["-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "128k"]
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
    total = sum(p.stat().st_size for p in dest.iterdir() if p.suffix in {".m4s", ".mp4"})
    bw = max(200_000, int(total * 8 / dur)) if dur > 0.1 else 2_000_000
    (dest / "master.m3u8").write_text(
        "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n"
        f'#EXT-X-STREAM-INF:BANDWIDTH={bw},AVERAGE-BANDWIDTH={int(bw * 0.9)},'
        f'RESOLUTION=1280x720,FRAME-RATE={fps},CODECS="avc1.640029,mp4a.40.2"\n'
        "v720.m3u8\n",
        encoding="utf-8",
    )
    return {"durationSec": dur, "fps": fps}


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
            "adUrl": DEFAULT_AD,
            "adOffset": 10,
            "adDuration": 12,
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
                "sub": f"Local · {round(info['durationSec'])}s · 720p",
                "error": None,
                "contentHash": digest,
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
