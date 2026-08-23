package com.iheartradio.m3u8.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DemoHttpTest {

    @Test
    public void parsesCloudflareQuickTunnelHostname() {
        assertEquals(
                "https://hartford-constitution-paintings-prohibited.trycloudflare.com",
                DemoHttp.httpsOriginFromQuickTunnelJson(
                        "{\"hostname\":\"hartford-constitution-paintings-prohibited.trycloudflare.com\"}"));
        assertEquals(
                "https://example.trycloudflare.com",
                DemoHttp.httpsOriginFromQuickTunnelJson(
                        "{\"hostname\":\"https://example.trycloudflare.com\"}"));
        assertNull(DemoHttp.httpsOriginFromQuickTunnelJson("{\"hostname\":\"127.0.0.1\"}"));
        assertNull(DemoHttp.httpsOriginFromQuickTunnelJson("{\"ok\":true}"));
        assertNull(DemoHttp.httpsOriginFromQuickTunnelJson(null));
    }
}
