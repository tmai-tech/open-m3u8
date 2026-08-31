#!/usr/bin/env python3
"""Ask a local Ollama vision model for a display title + summary from poster.jpg."""
from __future__ import annotations

import base64
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path

PROMPT = (
    "Look at this video poster frame. Reply with JSON only, no markdown, "
    'exactly: {"title":"...","summary":"..."}. '
    "title: 2 to 6 words, concrete, no UUID, no filename. "
    "summary: one or two short sentences about what is on screen. "
    "Do not invent brand names or people you cannot see."
)


def ollama_host() -> str:
    return (os.environ.get("OLLAMA_HOST") or "http://127.0.0.1:11434").rstrip("/")


def ollama_model() -> str:
    return os.environ.get("OLLAMA_MODEL") or "llava"


def looks_generated(title: str | None) -> bool:
    if not title:
        return True
    n = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    if n.startswith("grok-video") or n == "grok-clip" or n == "untitled":
        return True
    return bool(re.search(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{8,}", n
    ))


def _parse_json_object(text: str) -> dict:
    if not text:
        return {}
    raw = text.strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```(?:json)?\s*", "", raw)
        raw = re.sub(r"\s*```$", "", raw)
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{.*\}", raw, re.S)
    if not m:
        return {}
    try:
        data = json.loads(m.group(0))
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        return {}


def describe_poster(poster: Path, current_title: str | None = None) -> dict | None:
    """Return {title?, summary?} or None if Ollama is down / fails."""
    if poster is None or not poster.is_file() or poster.stat().st_size < 200:
        return None
    b64 = base64.b64encode(poster.read_bytes()).decode("ascii")
    body = {
        "model": ollama_model(),
        "stream": False,
        "format": "json",
        "messages": [
            {
                "role": "user",
                "content": PROMPT,
                "images": [b64],
            }
        ],
    }
    req = urllib.request.Request(
        ollama_host() + "/api/chat",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
        return None
    msg = (payload.get("message") or {}).get("content") or payload.get("response") or ""
    data = _parse_json_object(msg)
    out: dict = {}
    title = (data.get("title") or "").strip()
    summary = (data.get("summary") or "").strip()
    if title and looks_generated(current_title) and not looks_generated(title):
        if len(title) > 80:
            title = title[:80].rstrip()
        out["title"] = title
    if summary:
        if len(summary) > 280:
            summary = summary[:277].rstrip() + "…"
        out["summary"] = summary
    return out or None
