package com.iheartradio.m3u8.http;

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

    @Test
    public void jsonStringValueDecodesUnicodeEscape() {
        assertEquals("Local \u00b7 12s \u00b7 720p",
                DemoHttp.jsonStringValue(
                        "{\"sub\":\"Local \\u00b7 12s \\u00b7 720p\"}", "sub"));
        assertEquals("ok", DemoHttp.jsonStringValue("{\"a\":\"ok\"}", "a"));
    }
}
