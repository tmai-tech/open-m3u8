package com.iheartradio.m3u8.http;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void errorTextNeverBlank() {
        assertEquals("unknown error", DemoHttp.errorText(null));
        assertEquals("IOException", DemoHttp.errorText(new IOException()));
        assertEquals("IOException", DemoHttp.errorText(new IOException("  ")));
        assertEquals("origin down", DemoHttp.errorText(new IOException("origin down")));
        assertEquals("{\"error\":\"unknown error\"}", DemoHttp.errorJson(null));
        assertEquals("{\"error\":\"origin down\"}", DemoHttp.errorJson("origin down"));
    }

    @Test
    public void nullInputsDoNotThrow() throws Exception {
        assertEquals(0, DemoHttp.readAll(null).length);
        assertEquals("application/octet-stream", DemoHttp.contentTypeFor(null));
        assertNull(DemoHttp.queryParam("a=1", null));
        assertNull(DemoHttp.firstHeader(null, null));
        try {
            DemoHttp.requireHttpUrl(null);
            fail("null url");
        } catch (IllegalArgumentException e) {
            assertEquals("url is required", e.getMessage());
        }
        try {
            DemoHttp.requireHttpUrl("   ");
            fail("blank url");
        } catch (IllegalArgumentException e) {
            assertEquals("url is required", e.getMessage());
        }
        try {
            DemoHttp.fetchRemote(null, null);
            fail("null fetch");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("url is required"));
        }
    }
}
