package com.example.clockapp;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;
import android.widget.EditText;
import android.widget.RadioGroup;

import java.net.URISyntaxException;

public class SettingsActivity extends AppCompatActivity {
    private EditText address;
    private EditText codexAddress;
    private EditText codexPairingKey;
    private EditText token;
    private EditText username;
    private EditText password;
    private RadioGroup authentication;
    private CompoundButton ignoreCertificateErrors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep credentials out of screenshots and the recent-app preview.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);
        ((MaterialToolbar) findViewById(R.id.settings_toolbar)).setNavigationOnClickListener(view -> finish());
        codexAddress = findViewById(R.id.codex_bridge_address);
        codexPairingKey = findViewById(R.id.codex_pairing_key);
        address = findViewById(R.id.zabbix_address);
        token = findViewById(R.id.zabbix_token);
        username = findViewById(R.id.zabbix_username);
        password = findViewById(R.id.zabbix_password);
        authentication = findViewById(R.id.zabbix_authentication);
        ignoreCertificateErrors = findViewById(R.id.ignore_certificate_errors);
        ZabbixSettings settings = ZabbixSettings.load(this);
        if (savedInstanceState == null) {
            CodexUsageSettings codex = CodexUsageSettings.load(this);
            codexAddress.setText(codex.address);
            codexPairingKey.setText(codex.pairingKey);
            address.setText(settings.address);
            token.setText(settings.token);
            username.setText(settings.username);
            password.setText(settings.password);
            authentication.check(settings.passwordLogin ? R.id.auth_password : R.id.auth_token);
            ignoreCertificateErrors.setChecked(settings.ignoreCertificateErrors);
        }
        authentication.setOnCheckedChangeListener((group, checkedId) -> updateAuthenticationFields());
        updateAuthenticationFields();
        findViewById(R.id.save_settings).setOnClickListener(view -> saveSettings());
        findViewById(R.id.cancel_settings).setOnClickListener(view -> finish());
    }

    private void updateAuthenticationFields() {
        boolean passwordLogin = authentication.getCheckedRadioButtonId() == R.id.auth_password;
        findViewById(R.id.token_fields).setVisibility(passwordLogin ? View.GONE : View.VISIBLE);
        findViewById(R.id.password_fields).setVisibility(passwordLogin ? View.VISIBLE : View.GONE);
    }

    private void saveSettings() {
        for (int id : new int[]{R.id.zabbix_address_input, R.id.zabbix_token_input,
                R.id.zabbix_username_input, R.id.zabbix_password_input,
                R.id.codex_bridge_address_input, R.id.codex_pairing_key_input}) {
            ((TextInputLayout) findViewById(id)).setError(null);
        }
        String endpoint;
        try {
            endpoint = address.getText().toString().trim().isEmpty()
                    ? "" : ZabbixEndpoint.normalize(address.getText().toString());
        } catch (URISyntaxException e) {
            ((TextInputLayout) findViewById(R.id.zabbix_address_input))
                    .setError(getString(R.string.invalid_zabbix_address));
            address.requestFocus();
            return;
        }
        boolean passwordLogin = authentication.getCheckedRadioButtonId() == R.id.auth_password;
        EditText requiredField = passwordLogin ? username : token;
        if (!endpoint.isEmpty() && requiredField.getText().toString().trim().isEmpty()) {
            ((TextInputLayout) findViewById(passwordLogin ? R.id.zabbix_username_input : R.id.zabbix_token_input))
                    .setError(getString(R.string.required_field));
            requiredField.requestFocus();
            return;
        }
        if (!endpoint.isEmpty() && passwordLogin && password.getText().toString().isEmpty()) {
            ((TextInputLayout) findViewById(R.id.zabbix_password_input))
                    .setError(getString(R.string.required_field));
            password.requestFocus();
            return;
        }
        String bridgeAddress;
        try {
            bridgeAddress = CodexUsageSettings.normalizeAddress(codexAddress.getText().toString());
        } catch (URISyntaxException e) {
            ((TextInputLayout) findViewById(R.id.codex_bridge_address_input))
                    .setError(getString(R.string.invalid_zabbix_address));
            codexAddress.requestFocus();
            return;
        }
        String pairingKey = codexPairingKey.getText().toString().trim();
        if (!bridgeAddress.isEmpty() && pairingKey.isEmpty()) {
            ((TextInputLayout) findViewById(R.id.codex_pairing_key_input)).setError(getString(R.string.required_field));
            codexPairingKey.requestFocus();
            return;
        }
        CodexUsageSettings codex = new CodexUsageSettings(bridgeAddress, pairingKey);
        CodexUsageSettings previous = CodexUsageSettings.load(this);
        if (!previous.address.equals(bridgeAddress) || !previous.pairingKey.equals(pairingKey)) {
            getSharedPreferences(CodexUsageSettings.PREFERENCES, MODE_PRIVATE).edit().remove("snapshot").apply();
        }
        codex.save(this);
        new ZabbixSettings(endpoint, token.getText().toString().trim(),
                username.getText().toString().trim(), password.getText().toString(),
                passwordLogin, ignoreCertificateErrors.isChecked()).save(this);
        setResult(RESULT_OK);
        finish();
    }
}
