/** Client-side SSAI / SGAI playlist rewrite for static hosts (GitHub Pages). */
(function (global) {
  var SYNTH_PDT = "2020-01-01T00:00:00.000Z";
  var SYNTH_MS = Date.parse(SYNTH_PDT);

  function resolveUrl(base, ref) {
    try {
      return new URL(ref, base).href;
    } catch (e) {
      return ref;
    }
  }

  function isMaster(text) {
    return /#EXT-X-STREAM-INF/i.test(text) || /#EXT-X-MEDIA:/i.test(text);
  }

  function isMedia(text) {
    return /#EXTINF/i.test(text);
  }

  function parseFloatSafe(s, fallback) {
    var n = parseFloat(s);
    return isFinite(n) ? n : fallback;
  }

  function toIso(ms) {
    return new Date(ms).toISOString().replace(/\.\d{3}Z$/, function (m) {
      return m;
    });
  }

  function snapOffset(offset, starts) {
    if (!starts || !starts.length) return offset;
    var best = starts[0];
    var bestD = Math.abs(offset - best);
    for (var i = 1; i < starts.length; i++) {
      var d = Math.abs(offset - starts[i]);
      if (d < bestD) {
        best = starts[i];
        bestD = d;
      }
    }
    return best;
  }

  function parseTracks(text, playlistUrl) {
    var lines = text.replace(/\r/g, "").split("\n");
    var header = [];
    var tracks = [];
    var pending = [];
    var started = false;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var trim = line.trim();
      if (!trim) continue;
      if (!started) {
        if (/^#EXTINF/i.test(trim)) {
          started = true;
        } else if (/^#EXT-X-ENDLIST/i.test(trim)) {
          header.push(line);
          continue;
        } else {
          header.push(line);
          continue;
        }
      }
      if (/^#EXT-X-ENDLIST/i.test(trim)) continue;
      if (/^#EXTINF/i.test(trim)) {
        pending.push(line);
        var m = trim.match(/#EXTINF\s*:\s*([0-9.]+)/i);
        var dur = m ? parseFloatSafe(m[1], 0) : 0;
        var uri = null;
        var j = i + 1;
        while (j < lines.length) {
          var n = lines[j].trim();
          if (!n) {
            j++;
            continue;
          }
          if (n.charAt(0) === "#") {
            pending.push(lines[j]);
            j++;
            continue;
          }
          uri = resolveUrl(playlistUrl, n);
          i = j;
          break;
        }
        tracks.push({ tags: pending.slice(), uri: uri, duration: dur });
        pending = [];
      } else if (trim.charAt(0) === "#") {
        pending.push(line);
      }
    }
    return { header: header, tracks: tracks };
  }

  function trackStarts(tracks) {
    var starts = [0];
    var t = 0;
    for (var i = 0; i < tracks.length; i++) {
      t += tracks[i].duration || 0;
      starts.push(t);
    }
    return starts;
  }

  function bumpVersion(header, min) {
    var found = false;
    var out = header.map(function (line) {
      var m = line.match(/^#EXT-X-VERSION:(\d+)/i);
      if (m) {
        found = true;
        var v = Math.max(parseInt(m[1], 10) || 0, min);
        return "#EXT-X-VERSION:" + v;
      }
      return line;
    });
    if (!found) {
      if (out.length && /^#EXTM3U/i.test(out[0])) {
        out.splice(1, 0, "#EXT-X-VERSION:" + min);
      } else {
        out.unshift("#EXT-X-VERSION:" + min);
      }
    }
    return out;
  }

  function headerHasPdt(header) {
    return header.some(function (l) {
      return /^#EXT-X-PROGRAM-DATE-TIME/i.test(l);
    });
  }

  function injectSgai(text, playlistUrl, session) {
    var parsed = parseTracks(text, playlistUrl);
    var header = bumpVersion(parsed.header, 7);
    var starts = trackStarts(parsed.tracks);
    var snap = session.snapToSegment !== false;
    var ranges = [];
    var breakList = session.breaks && session.breaks.length
      ? session.breaks
      : (session.offsets || []).map(function (off) {
          return { offsetSec: off, durationSec: session.adLength || 15, assetUri: session.adUrl };
        });
    for (var i = 0; i < breakList.length; i++) {
      var br = breakList[i];
      var off = snap ? snapOffset(br.offsetSec, starts) : br.offsetSec;
      var dur = br.durationSec > 0 ? br.durationSec : 15;
      var asset = br.assetUri || session.adUrl;
      var startDate = toIso(SYNTH_MS + Math.round(off * 1000));
      var id = br.id || ("user-ad-" + (i + 1));
      var parts = [
        "ID=\"" + id + "\"",
        "CLASS=\"com.apple.hls.interstitial\"",
        "START-DATE=" + startDate,
        "DURATION=" + dur.toFixed(1),
        "X-ASSET-URI=\"" + asset + "\"",
        "X-RESUME-OFFSET=0.0",
        "X-PLAYOUT-LIMIT=" + dur.toFixed(1),
      ];
      if (session.restrictSkip) parts.push("X-RESTRICT=\"SKIP\"");
      ranges.push("#EXT-X-DATERANGE:" + parts.join(","));
    }

    var insertAt = header.length;
    for (var h = 0; h < header.length; h++) {
      if (/^#EXTINF/i.test(header[h])) {
        insertAt = h;
        break;
      }
    }
    var before = header.slice(0, insertAt);
    var after = header.slice(insertAt);
    if (!headerHasPdt(header) && parsed.tracks.length) {
      before.push("#EXT-X-PROGRAM-DATE-TIME:" + SYNTH_PDT);
    }
    var out = before.concat(ranges).concat(after);
    parsed.tracks.forEach(function (tr) {
      out = out.concat(tr.tags);
      out.push(tr.uri);
    });
    if (!out.some(function (l) { return /^#EXT-X-ENDLIST/i.test(l); })) {
      out.push("#EXT-X-ENDLIST");
    }
    return out.join("\n") + "\n";
  }

  function trimTracks(tracks, maxSec) {
    if (!(maxSec > 0)) return tracks.slice();
    var out = [];
    var sum = 0;
    for (var i = 0; i < tracks.length; i++) {
      if (sum >= maxSec && out.length) break;
      out.push(tracks[i]);
      sum += tracks[i].duration || 0;
      if (sum >= maxSec) break;
    }
    return out.length ? out : tracks.slice(0, 1);
  }

  function stitchSsai(text, playlistUrl, adTracks, session) {
    var parsed = parseTracks(text, playlistUrl);
    var header = parsed.header.slice();
    var content = parsed.tracks;
    var starts = trackStarts(content);
    var snap = session.snapToSegment !== false;
    var breakList = session.breaks && session.breaks.length
      ? session.breaks.slice()
      : (session.offsets || []).map(function (off) {
          return { offsetSec: off, durationSec: session.adLength || 0, assetUri: session.adUrl };
        });
    breakList.sort(function (a, b) { return a.offsetSec - b.offsetSec; });

    var insertAfter = {};
    for (var i = 0; i < breakList.length; i++) {
      var off = snap ? snapOffset(breakList[i].offsetSec, starts) : breakList[i].offsetSec;
      var idx = -1;
      if (off <= 0) idx = -1;
      else {
        var acc = 0;
        idx = content.length - 1;
        for (var t = 0; t < content.length; t++) {
          acc += content[t].duration || 0;
          if (acc >= off - 1e-3) {
            idx = t;
            break;
          }
        }
      }
      if (!insertAfter[idx]) insertAfter[idx] = [];
      insertAfter[idx].push(i);
    }

    function tracksForBreak(br) {
      var url = (br && br.assetUri) || session.adUrl;
      var raw = (session._adsByUrl && url && session._adsByUrl[url]) || adTracks || [];
      return trimTracks(raw, br && br.durationSec > 0 ? br.durationSec : session.adLength);
    }

    function emitAd(lines, breakIdx) {
      var ads = tracksForBreak(breakList[breakIdx]);
      var adDur = ads.reduce(function (s, t) { return s + (t.duration || 0); }, 0);
      lines.push("#EXT-X-DISCONTINUITY");
      lines.push("#EXT-X-CUE-OUT:" + adDur.toFixed(1));
      var elapsed = 0;
      for (var a = 0; a < ads.length; a++) {
        if (a > 0) {
          lines.push("#EXT-X-CUE-OUT-CONT:" + elapsed.toFixed(1) + "/" + adDur.toFixed(1));
        }
        lines.push("#EXTINF:" + ads[a].duration.toFixed(3) + ",");
        lines.push(ads[a].uri);
        elapsed += ads[a].duration || 0;
      }
      lines.push("#EXT-X-CUE-IN");
    }

    var lines = header.slice();
    if (insertAfter[-1]) {
      insertAfter[-1].forEach(function (_, n) { emitAd(lines, n); });
    }
    for (var c = 0; c < content.length; c++) {
      if (c === 0 || (insertAfter[c - 1] && insertAfter[c - 1].length)) {
        if (c > 0) lines.push("#EXT-X-DISCONTINUITY");
      }
      lines = lines.concat(content[c].tags);
      lines.push(content[c].uri);
      if (insertAfter[c]) {
        insertAfter[c].forEach(function (_, n) { emitAd(lines, n); });
      }
    }
    if (!lines.some(function (l) { return /^#EXT-X-ENDLIST/i.test(l); })) {
      lines.push("#EXT-X-ENDLIST");
    }
    return lines.join("\n") + "\n";
  }

  function firstVariantUrl(text, playlistUrl) {
    var lines = text.replace(/\r/g, "").split("\n");
    var saw = false;
    for (var i = 0; i < lines.length; i++) {
      var trim = lines[i].trim();
      if (/^#EXT-X-STREAM-INF/i.test(trim)) {
        saw = true;
        continue;
      }
      if (saw && trim && trim.charAt(0) !== "#") {
        return resolveUrl(playlistUrl, trim);
      }
    }
    return null;
  }

  function createLoader(Hls, session, hooks) {
    var Base = Hls.DefaultConfig.loader;
    function Loader(config) {
      Base.call(this, config);
    }
    Loader.prototype = Object.create(Base.prototype);
    Loader.prototype.constructor = Loader;
    Loader.prototype.load = function (context, config, callbacks) {
      var onSuccess = callbacks.onSuccess;
      callbacks.onSuccess = function (response, stats, ctx, networkDetails) {
        var done = function (data) {
          response.data = data;
          onSuccess(response, stats, ctx, networkDetails);
        };
        try {
          if (!response || typeof response.data !== "string") {
            onSuccess(response, stats, ctx, networkDetails);
            return;
          }
          var data = response.data;
          if (!/#EXT/i.test(data)) {
            done(data);
            return;
          }
          if (isMaster(data) && !isMedia(data)) {
            done(data);
            return;
          }
          if (!isMedia(data)) {
            done(data);
            return;
          }
          var url = (ctx && ctx.url) || "";
          var out = data;
          if (session.strategy === "sgai") {
            out = injectSgai(data, url, session);
          } else if (session._adsByUrl || session._adTracks) {
            out = stitchSsai(data, url, session._adTracks, session);
          }
          if (hooks && hooks.onRewritten) hooks.onRewritten(out, url);
          done(out);
        } catch (e) {
          console.warn("static rewrite failed", e);
          onSuccess(response, stats, ctx, networkDetails);
        }
      };
      return Base.prototype.load.call(this, context, config, callbacks);
    };
    return Loader;
  }

  async function loadAdTracksForUrl(session, url) {
    if (!url) throw new Error("Ad URL is required");
    session._adsByUrl = session._adsByUrl || {};
    if (session._adsByUrl[url]) return session._adsByUrl[url];
    var res = await fetch(url, { cache: "no-store" });
    if (!res.ok) throw new Error("Ad playlist HTTP " + res.status + " for " + url);
    var text = await res.text();
    var adUrl = url;
    if (isMaster(text) && !isMedia(text)) {
      var child = firstVariantUrl(text, url);
      if (!child) throw new Error("Ad master has no variants");
      res = await fetch(child, { cache: "no-store" });
      if (!res.ok) throw new Error("Ad variant HTTP " + res.status);
      text = await res.text();
      adUrl = child;
    }
    var tracks = parseTracks(text, adUrl).tracks;
    if (!tracks.length) throw new Error("Ad playlist has no segments");
    session._adsByUrl[url] = tracks;
    return tracks;
  }

  async function loadAdTracks(session) {
    var urls = [];
    if (session.breaks) {
      session.breaks.forEach(function (b) {
        var u = b.assetUri || session.adUrl;
        if (u && urls.indexOf(u) < 0) urls.push(u);
      });
    }
    if (!urls.length && session.adUrl) urls.push(session.adUrl);
    var first = null;
    for (var i = 0; i < urls.length; i++) {
      var tracks = await loadAdTracksForUrl(session, urls[i]);
      if (!first) first = tracks;
    }
    session._adTracks = first;
    return first;
  }

  async function previewMedia(session, contentUrl) {
    var res = await fetch(contentUrl, { cache: "no-store" });
    if (!res.ok) throw new Error("Content playlist HTTP " + res.status);
    var text = await res.text();
    var url = contentUrl;
    if (isMaster(text) && !isMedia(text)) {
      var child = firstVariantUrl(text, contentUrl);
      if (!child) return text;
      res = await fetch(child, { cache: "no-store" });
      if (!res.ok) throw new Error("Variant HTTP " + res.status);
      text = await res.text();
      url = child;
    }
    if (session.strategy === "sgai") return injectSgai(text, url, session);
    await loadAdTracks(session);
    return stitchSsai(text, url, session._adTracks, session);
  }

  global.HlsDemoRewrite = {
    createLoader: createLoader,
    loadAdTracks: loadAdTracks,
    previewMedia: previewMedia,
    injectSgai: injectSgai,
    stitchSsai: stitchSsai,
  };
})(window);
