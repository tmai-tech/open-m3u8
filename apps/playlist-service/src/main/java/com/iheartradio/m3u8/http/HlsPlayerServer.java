package com.iheartradio.m3u8.http;

/**
 * Compatibility entry point. Use {@link DemoPlayerServer} ({@code ./gradlew runDemo}).
 */
public final class HlsPlayerServer {

    public static void main(String[] args) throws Exception {
        DemoPlayerServer.main(args);
    }

    private HlsPlayerServer() {
    }
}
