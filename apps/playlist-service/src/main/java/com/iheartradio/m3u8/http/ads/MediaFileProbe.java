package com.iheartradio.m3u8.http.ads;

import com.iheartradio.m3u8.http.ads.AdDecision.Probe;
import com.iheartradio.m3u8.http.ads.AdDecision.Unavailable;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;

/**
 * Measures a creative that lives under the demo media root.
 * A URL outside that root keeps the duration declared on the slot.
 */
public final class MediaFileProbe implements Probe {

    private final File mediaRoot;

    public MediaFileProbe(File mediaRoot) {
        this.mediaRoot = mediaRoot;
    }

    @Override
    public float mediaSeconds(String url) throws Unavailable {
        File file = resolve(url);
        if (file == null) {
            return Float.NaN;
        }
        if (!file.isFile()) {
            throw new Unavailable(url);
        }
        float dur = AdInventory.readDuration(file);
        return dur > 0f ? dur : Float.NaN;
    }

    File resolve(String url) throws Unavailable {
        if (url == null || mediaRoot == null) {
            return null;
        }
        String path = pathOf(url);
        if (path == null) {
            return null;
        }
        String rel;
        if (path.startsWith("/media/")) {
            rel = path.substring("/media/".length());
        } else if (path.startsWith("media/")) {
            rel = path.substring("media/".length());
        } else {
            return null;
        }
        Path root = mediaRoot.getAbsoluteFile().toPath().normalize();
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root)) {
            throw new Unavailable(url);
        }
        return file.toFile();
    }

    private static String pathOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getPath() != null && uri.getPath().length() > 0) {
                return uri.getPath();
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        int q = url.indexOf('?');
        String bare = q >= 0 ? url.substring(0, q) : url;
        return bare.startsWith("/") || bare.startsWith("media/") ? bare : null;
    }
}
