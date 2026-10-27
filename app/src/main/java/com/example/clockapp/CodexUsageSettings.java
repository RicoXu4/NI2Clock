package com.example.clockapp;

import android.content.Context;
import java.net.URI;
import java.net.URISyntaxException;

final class CodexUsageSettings {
    static final String PREFERENCES = "codex_usage_settings";
    final String address;
    final String pairingKey;

    CodexUsageSettings(String address, String pairingKey) {
        this.address = address;
        this.pairingKey = pairingKey;
    }

    static CodexUsageSettings load(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return new CodexUsageSettings(preferences.getString("address", BuildConfig.CODEX_USAGE_URL),
                preferences.getString("pairing_key", BuildConfig.CODEX_USAGE_TOKEN));
    }

    void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("address", address).putString("pairing_key", pairingKey).apply();
    }

    static String normalizeAddress(String address) throws URISyntaxException {
        String value = address.trim();
        if (value.isEmpty()) return "";
        URI uri = new URI(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() > 65535) {
            throw new URISyntaxException("", "Invalid usage bridge address");
        }
        if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) {
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            value += "/usage";
        }
        return value;
    }
}
