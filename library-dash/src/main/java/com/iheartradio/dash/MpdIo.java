package com.iheartradio.dash;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lindstrom.mpd.MPDParser;
import io.lindstrom.mpd.data.MPD;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Parse and write DASH MPDs. Thin façade over {@code io.lindstrom:mpd-parser}.
 * Unknown or future DASH attributes should be added here (or in a fork), not in
 * the HLS {@code library} module.
 */
public final class MpdIo {

    private final MPDParser parser;

    public MpdIo() {
        this(new MPDParser());
    }

    public MpdIo(MPDParser parser) {
        this.parser = parser == null ? new MPDParser() : parser;
        ignoreUnknownProperties(this.parser);
    }

    /**
     * ffmpeg and other packagers emit elements 0.14 does not model
     * ({@code ServiceDescription}, {@code par}, …). Drop them on parse.
     */
    static void ignoreUnknownProperties(MPDParser parser) {
        if (parser == null) {
            return;
        }
        Class<?> c = parser.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!ObjectMapper.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    ObjectMapper mapper = (ObjectMapper) f.get(parser);
                    if (mapper != null) {
                        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    }
                    return;
                } catch (Exception ignored) {
                    return;
                }
            }
            c = c.getSuperclass();
        }
    }

    public MPD parse(InputStream in) throws IOException {
        return parser.parse(in);
    }

    public MPD parse(String xml) throws IOException {
        if (xml == null) {
            throw new IOException("MPD xml is null");
        }
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    public String write(MPD mpd) throws IOException {
        return parser.writeAsString(mpd);
    }

    public byte[] writeBytes(MPD mpd) throws IOException {
        return parser.writeAsBytes(mpd);
    }
}
