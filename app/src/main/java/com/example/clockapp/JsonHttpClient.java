package com.example.clockapp;

import android.annotation.SuppressLint;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class JsonHttpClient {
    private static final int TIMEOUT_MS = 15_000;

    private JsonHttpClient() {}

    static JSONObject request(String address, JSONObject body) throws IOException, JSONException {
        return request(address, body, null, false);
    }

    static JSONObject request(String address, JSONObject body, String bearerToken,
            boolean ignoreCertificateErrors) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            // Never follow redirects with credentials or a certificate bypass.
            connection.setInstanceFollowRedirects(false);
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                if (ignoreCertificateErrors) configureCertificateBypass(https);
                https.setSSLSocketFactory(new TlsSocketFactory(https.getSSLSocketFactory()));
            }
            if (bearerToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(Charset.forName("UTF-8")));
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new HttpStatusException(status);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), Charset.forName("UTF-8")))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return new JSONObject(response.toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    // Explicitly requested by the user for their Zabbix server. This socket factory
    // and hostname verifier belong only to this connection, never global defaults.
    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static void configureCertificateBypass(HttpsURLConnection connection) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            connection.setSSLSocketFactory(context.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> true);
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to configure TLS", e);
        }
    }
}
