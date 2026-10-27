package com.example.clockapp;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ZabbixClientTest {
    @Test
    public void apiTokenUsesLegacyBodyAuthenticationOnSixZero() throws Exception {
        verifyAuthentication("6.0.40", false, false);
    }

    @Test
    public void apiTokenUsesBearerHeaderOnCurrentServers() throws Exception {
        verifyAuthentication("7.4.0", false, true);
    }

    @Test
    public void passwordLoginObtainsAndReleasesSession() throws Exception {
        verifyAuthentication("7.4.0", true, true);
    }

    @Test
    public void versionBoundaryMatchesBearerSupport() throws Exception {
        assertFalse(ZabbixClient.usesBearerAuthentication("6.2.9"));
        assertTrue(ZabbixClient.usesBearerAuthentication("6.4.0"));
    }

    private MockResponse result(Object value) throws Exception {
        return new MockResponse().setBody(new JSONObject().put("jsonrpc", "2.0")
                .put("result", value).put("id", 1).toString());
    }

    private void verifyAuthentication(String version, boolean passwordLogin, boolean bearer) throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(result(version));
        if (passwordLogin) server.enqueue(result("test-session"));
        server.enqueue(result(new JSONArray().put(new JSONObject()
                .put("name", "CPU utilization").put("units", "%").put("lastvalue", "12"))));
        if (passwordLogin) server.enqueue(result(true));
        server.start();
        try {
            ZabbixSettings settings = new ZabbixSettings(server.url("/api_jsonrpc.php").toString(),
                    "test-token", "test-user", "test-password", passwordLogin, false);
            assertEquals(12, new ZabbixClient(settings).fetchMetrics().cpu, 0);
            assertEquals(passwordLogin ? 4 : 2, server.getRequestCount());
            RecordedRequest versionRequest = server.takeRequest();
            JSONObject versionBody = new JSONObject(versionRequest.getBody().readUtf8());
            assertEquals("apiinfo.version", versionBody.getString("method"));
            assertFalse(versionBody.has("auth"));
            assertNull(versionRequest.getHeader("Authorization"));
            if (passwordLogin) {
                JSONObject login = new JSONObject(server.takeRequest().getBody().readUtf8());
                assertEquals("user.login", login.getString("method"));
                assertEquals("test-user", login.getJSONObject("params").getString("username"));
                assertEquals("test-password", login.getJSONObject("params").getString("password"));
            }
            RecordedRequest metricsRequest = server.takeRequest();
            JSONObject metrics = new JSONObject(metricsRequest.getBody().readUtf8());
            String expectedToken = passwordLogin ? "test-session" : "test-token";
            assertEquals("item.get", metrics.getString("method"));
            if (bearer) {
                assertFalse(metrics.has("auth"));
                assertEquals("Bearer " + expectedToken, metricsRequest.getHeader("Authorization"));
            } else {
                assertEquals(expectedToken, metrics.getString("auth"));
                assertNull(metricsRequest.getHeader("Authorization"));
            }
            if (passwordLogin) {
                assertEquals("user.logout", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("method"));
            }
        } finally {
            server.shutdown();
        }
    }
}
