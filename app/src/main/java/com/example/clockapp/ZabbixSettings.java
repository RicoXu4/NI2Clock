package com.example.clockapp;

import android.content.Context;
import android.content.SharedPreferences;

final class ZabbixSettings {
    private static final String PREFERENCES = "zabbix_settings";
    final String address;
    final String token;
    final String username;
    final String password;
    final boolean passwordLogin;
    final boolean ignoreCertificateErrors;

    ZabbixSettings(String address, String token, String username, String password,
            boolean passwordLogin, boolean ignoreCertificateErrors) {
        this.address = address;
        this.token = token;
        this.username = username;
        this.password = password;
        this.passwordLogin = passwordLogin;
        this.ignoreCertificateErrors = ignoreCertificateErrors;
    }

    static ZabbixSettings load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return new ZabbixSettings(
                preferences.getString("address", BuildConfig.ZABBIX_URL),
                preferences.getString("token", BuildConfig.ZABBIX_TOKEN),
                preferences.getString("username", ""), preferences.getString("password", ""),
                preferences.getBoolean("password_login", false),
                preferences.getBoolean("ignore_certificate_errors", false));
    }

    boolean isConfigured() {
        return !address.isEmpty() && (passwordLogin
                ? !username.isEmpty() && !password.isEmpty() : !token.isEmpty());
    }

    void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("address", address).putString("token", token)
                .putString("username", username).putString("password", password)
                .putBoolean("password_login", passwordLogin)
                .putBoolean("ignore_certificate_errors", ignoreCertificateErrors).apply();
    }
}
