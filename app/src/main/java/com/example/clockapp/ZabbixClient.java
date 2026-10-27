package com.example.clockapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

final class ZabbixClient {
    private final ZabbixSettings settings;
    private boolean bearerAuthentication;

    ZabbixClient(ZabbixSettings settings) {
        this.settings = settings;
    }

    ZabbixMetrics fetchMetrics() throws IOException, JSONException {
        String version = call("apiinfo.version", new JSONObject(), null).getString("result");
        bearerAuthentication = usesBearerAuthentication(version);
        String token = settings.token;
        if (settings.passwordLogin) {
            JSONObject credentials = new JSONObject();
            credentials.put("username", settings.username);
            credentials.put("password", settings.password);
            token = call("user.login", credentials, null).getString("result");
        }
        try {
            JSONObject params = new JSONObject();
            params.put("output", new JSONArray().put("name").put("lastvalue").put("units"));
            params.put("monitored", true);
            JSONObject response = call("item.get", params, token);
            return ZabbixMetrics.parse(response.getJSONArray("result"));
        } finally {
            if (settings.passwordLogin) {
                try {
                    call("user.logout", new JSONArray(), token);
                } catch (IOException | JSONException ignored) {
                    // A logout failure must not discard successfully fetched metrics.
                }
            }
        }
    }

    static boolean usesBearerAuthentication(String version) throws IOException {
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 6 || major == 6 && minor >= 4;
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IOException("Unsupported Zabbix version", e);
        }
    }

    private JSONObject call(String method, Object params, String token) throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", 1);
        if (token != null && !bearerAuthentication) request.put("auth", token);
        JSONObject response = JsonHttpClient.request(settings.address, request,
                bearerAuthentication ? token : null, settings.ignoreCertificateErrors);
        if (response.has("error")) throw new IOException("Zabbix API rejected the request");
        return response;
    }
}
