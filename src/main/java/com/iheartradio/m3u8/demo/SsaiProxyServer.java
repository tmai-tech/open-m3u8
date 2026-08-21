package com.iheartradio.m3u8.demo;

import java.io.File;
import java.io.IOException;

/**
 * Compatibility entry point and test facade. Use {@link DemoPlayerServer}.
 */
public final class SsaiProxyServer {

    public static final float DEFAULT_MAX_AD_DURATION_SEC =
            DemoPlayerServer.DEFAULT_MAX_AD_DURATION_SEC;

    private final DemoPlayerServer delegate;

    public SsaiProxyServer(int port, File staticRoot) {
        this.delegate = new DemoPlayerServer(port, staticRoot);
    }

    public static void main(String[] args) throws Exception {
        DemoPlayerServer.main(args);
    }

    public void start() throws IOException {
        delegate.start();
    }
}
