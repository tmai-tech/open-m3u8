const $ = (id) => document.getElementById(id);

let jobs = [];
let selectedId = null;
let logOpen = false;

function setStatus(msg, kind) {
  const el = $("status");
  if (!el) return;
  el.textContent = msg;
  el.className = "status" + (kind ? " " + kind : "");
}

function jobById(id) {
  return jobs.find((j) => j.id === id) || null;
}

async function loadJobs() {
  const res = await fetch("/api/catalog", { cache: "no-store" });
  if (!res.ok) throw new Error("catalog HTTP " + res.status);
  const data = await res.json();
  jobs = data.titles || [];
}

async function loadLog(id) {
  const res = await fetch("/api/ingest?id=" + encodeURIComponent(id), { cache: "no-store" });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || ("log HTTP " + res.status));
  }
  return res.json();
}

function setLogOpen(open) {
  logOpen = !!open;
  const pane = $("logPane");
  const btn = $("btnToggleLog");
  if (pane) pane.classList.toggle("is-min", !logOpen);
  if (btn) {
    btn.textContent = logOpen ? "Hide" : "Log";
    btn.title = logOpen ? "Hide log" : "Show log";
  }
}

function paintJobs() {
  const host = $("jobList");
  if (!host) return;
  host.innerHTML = "";
  if (!jobs.length) {
    host.innerHTML = '<p class="hint">No jobs yet.</p>';
    return;
  }
  jobs.forEach((j) => {
    const row = document.createElement("div");
    const st = j.status || "ready";
    row.className = "job-row" + (j.id === selectedId ? " is-on" : "");
    const action = (st === "queued" || st === "packaging") ? "Cancel" : "Delete";
    row.innerHTML =
      '<button type="button" class="job-main"><strong></strong><span></span></button>' +
      '<span class="job-chip"></span>' +
      '<button type="button" class="btn btn-ghost btn-sm job-refresh">Refresh</button>' +
      '<a class="btn btn-success btn-sm job-play">Play</a>' +
      '<button type="button" class="btn btn-ghost btn-sm job-delete"></button>';
    row.querySelector("strong").textContent = j.title || j.id;
    row.querySelector(".job-main span").textContent = j.sub || "";
    const chip = row.querySelector(".job-chip");
    chip.textContent = st;
    chip.className = "job-chip job-chip-" + st;
    const play = row.querySelector(".job-play");
    if (st === "ready") {
      play.href = "./?play=" + encodeURIComponent(j.id);
    } else {
      play.hidden = true;
    }
    row.querySelector(".job-delete").textContent = action;
    row.querySelector(".job-main").addEventListener("click", () => selectJob(j.id, false));
    row.querySelector(".job-refresh").addEventListener("click", (ev) => {
      ev.stopPropagation();
      refreshJob(j.id);
    });
    row.querySelector(".job-delete").addEventListener("click", (ev) => {
      ev.stopPropagation();
      deleteJob(j);
    });
    host.appendChild(row);
  });
}

function paintDetail(detail) {
  const title = $("logTitle");
  const status = $("jobStatus");
  const log = $("log");
  const job = jobById(selectedId);
  const name = (detail && detail.title) || (job && job.title) || selectedId || "Log";
  if (title) title.textContent = name;
  const st = (detail && detail.status) || (job && job.status) || "";
  if (status) {
    status.textContent = st
      ? (st + (detail && detail.error ? " — " + detail.error : ""))
      : "Select a job, then Refresh.";
  }
  if (log) {
    log.textContent = detail && detail.log
      ? detail.log
      : (st ? "(no log yet — is the packager running?)" : "No log loaded.");
    log.scrollTop = log.scrollHeight;
  }
}

function selectJob(id, fetchLog) {
  selectedId = id;
  if (id) history.replaceState(null, "", "#" + encodeURIComponent(id));
  paintJobs();
  const job = jobById(id);
  paintDetail(job ? { title: job.title, status: job.status, error: job.error, log: "" } : null);
  if (fetchLog) refreshJob(id);
}

async function deleteJob(job) {
  if (!job || !job.id) return;
  const st = job.status || "ready";
  const action = (st === "queued" || st === "packaging") ? "Cancel" : "Delete";
  const ok = window.confirm(action + " \"" + (job.title || job.id)
    + "\" and remove all packaged files?");
  if (!ok) return;
  setStatus(action + " " + job.id + "…");
  try {
    const res = await fetch("/api/ingest?id=" + encodeURIComponent(job.id), { method: "DELETE" });
    const data = await res.json().catch(() => ({}));
    if (res.status === 409) {
      setStatus(data.error || "In use as a house ad — not deleted.", "err");
      return;
    }
    if (!res.ok) {
      setStatus(data.error || (action + " failed (" + res.status + ")"), "err");
      return;
    }
    if (selectedId === job.id) selectedId = null;
    await loadJobs();
    paintJobs();
    paintDetail(null);
    setStatus((job.title || job.id) + " removed.", "ok");
  } catch (e) {
    setStatus(action + " failed: " + e.message, "err");
  }
}

async function refreshJob(id) {
  selectedId = id;
  if (id) history.replaceState(null, "", "#" + encodeURIComponent(id));
  setStatus("Refreshing " + id + "…");
  try {
    const detail = await loadLog(id);
    const job = jobById(id);
    if (job) {
      if (detail.status) job.status = detail.status;
      if (detail.sub) job.sub = detail.sub;
      if (detail.title) job.title = detail.title;
      if (detail.error !== undefined) job.error = detail.error;
    }
    paintJobs();
    paintDetail(detail);
    setLogOpen(true);
    setStatus((detail.title || id) + " — " + (detail.status || "unknown"),
      detail.status === "failed" ? "err" : "ok");
  } catch (e) {
    setStatus("Refresh failed: " + e.message, "err");
  }
}

async function boot() {
  try {
    await loadJobs();
    if (!selectedId && location.hash.length > 1) {
      selectedId = decodeURIComponent(location.hash.slice(1));
    }
    paintJobs();
    if (selectedId && jobById(selectedId)) {
      paintDetail(jobById(selectedId));
    }
    setStatus("Idle — Refresh a job to check status.");
  } catch (e) {
    setStatus("Cannot reach the demo server: " + e.message, "err");
  }
}

const MAX_FILES = 3;

function pickFiles(list) {
  return Array.prototype.slice.call(list || []).slice(0, MAX_FILES);
}

async function ingestFile(file) {
  if (!file) return { ok: false, name: "upload.mp4", msg: "missing file" };
  const name = file.name || "upload.mp4";
  if (!/\.mp4$/i.test(name)) {
    return { ok: false, name, msg: "not an MP4" };
  }
  const body = new FormData();
  body.append("file", file, name);
  body.append("title", name.replace(/\.mp4$/i, ""));
  const res = await fetch("/api/ingest", { method: "POST", body });
  const data = await res.json().catch(() => ({}));
  const queued = (data.titles && data.titles[0]) || data;
  if (res.status === 409 || queued.status === "duplicate") {
    return { ok: true, duplicate: true, name, job: queued };
  }
  if (res.status !== 202) {
    return { ok: false, name, msg: data.error || ("HTTP " + res.status) };
  }
  return { ok: true, duplicate: false, name, job: queued };
}

async function ingestFiles(fileList) {
  const raw = Array.prototype.slice.call(fileList || []);
  const truncated = raw.length > MAX_FILES;
  const files = pickFiles(raw);
  if (!files.length) return;
  const notes = [];
  let lastJob = null;
  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    setStatus("Uploading " + (i + 1) + "/" + files.length + " — " + (file.name || "upload.mp4") + "…");
    try {
      const result = await ingestFile(file);
      lastJob = result.job || lastJob;
      if (result.ok && result.duplicate) {
        notes.push((result.job && result.job.title) || result.name + " (duplicate)");
      } else if (result.ok) {
        notes.push((result.job && result.job.title) || result.name);
      } else {
        notes.push(result.name + " failed: " + result.msg);
      }
    } catch (e) {
      notes.push((file.name || "file") + " failed: " + (e && e.message ? e.message : e));
    }
  }
  if (lastJob && lastJob.id) selectedId = lastJob.id;
  await loadJobs();
  paintJobs();
  if (lastJob) paintDetail(lastJob);
  const failed = notes.some((n) => n.indexOf(" failed:") >= 0);
  setStatus((truncated ? "Only the first " + MAX_FILES + " files were taken. " : "")
    + notes.join(" · ") + ". Refresh a job for status.", failed ? "err" : "ok");
}

function bindDrop() {
  const drop = $("drop");
  const input = $("file");
  if (!drop || !input) return;
  drop.addEventListener("click", () => input.click());
  input.addEventListener("change", () => {
    const files = Array.prototype.slice.call(input.files || []);
    input.value = "";
    if (files.length) ingestFiles(files);
  });
  ["dragenter", "dragover"].forEach((ev) => {
    drop.addEventListener(ev, (e) => {
      e.preventDefault();
      drop.classList.add("is-over");
    });
  });
  ["dragleave", "drop"].forEach((ev) => {
    drop.addEventListener(ev, (e) => {
      e.preventDefault();
      drop.classList.remove("is-over");
    });
  });
  drop.addEventListener("drop", (e) => {
    const files = e.dataTransfer && e.dataTransfer.files;
    if (files && files.length) ingestFiles(files);
  });
}

const toggle = $("btnToggleLog");
if (toggle) toggle.addEventListener("click", () => setLogOpen(!logOpen));
bindDrop();
setLogOpen(false);
boot();
