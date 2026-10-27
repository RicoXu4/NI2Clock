package com.example.clockapp;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class JsonHttpClientTest {
    @Test
    public void selfSignedBypassIsOptInAndDoesNotChangeGlobalTrust() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/self-signed-test-server.p12")) {
            keyStore.load(input, "test-only".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "test-only".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        MockWebServer server = new MockWebServer();
        server.useHttps(context.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("{\"result\":\"ok\"}"));
        server.start();
        String address = server.url("/").toString();
        try {
            assertUntrusted(address);
            assertEquals("ok", JsonHttpClient.request(address, null, null, true).getString("result"));
            assertUntrusted(address);
        } finally {
            server.shutdown();
        }
    }

    private void assertUntrusted(String address) throws Exception {
        try {
            JsonHttpClient.request(address, null);
            fail("A self-signed server was accepted without opting in");
        } catch (IOException expected) { }
    }
}
