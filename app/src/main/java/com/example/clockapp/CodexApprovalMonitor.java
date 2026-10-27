package com.example.clockapp;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDialog;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Only polls while the clock is visible. Every choice addresses one live request ID. */
final class CodexApprovalMonitor {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CodexUsageSettings settings;
    private final String endpoint;
    private AppCompatDialog dialog;
    private TextView title;
    private MaterialButton approveButton;
    private MaterialButton denyButton;
    private LinearLayout details;
    private String currentId;
    private boolean active = true;
    private boolean deciding;

    CodexApprovalMonitor(Context context) {
        this.context = context;
        settings = CodexUsageSettings.load(context);
        String address = "";
        try {
            URI uri = new URI(settings.address);
            if (uri.getHost() == null) throw new IllegalArgumentException("Missing host");
            String path = uri.getPath();
            String base = path == null ? "" : path.substring(0, path.lastIndexOf('/') + 1);
            address = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    base + "approvals", null, null).toString();
        } catch (Exception ignored) { }
        endpoint = address;
    }

    void start() {
        if (!endpoint.isEmpty() && !settings.pairingKey.isEmpty()) poll();
    }

    private void poll() {
        if (!active) return;
        executor.execute(() -> {
            try {
                JSONObject result = JsonHttpClient.request(endpoint, null, settings.pairingKey, false);
                JSONArray requests = result.getJSONArray("requests");
                JSONObject request = requests.length() == 0 ? null : requests.getJSONObject(0);
                handler.post(() -> {
                    if (!active) return;
                    if (!deciding) show(request);
                    handler.postDelayed(this::poll, 3000);
                });
            } catch (Exception ignored) {
                handler.post(() -> {
                    if (!active) return;
                    if (dialog != null) {
                        setEnabled(false);
                        title.setText(R.string.codex_approval_offline);
                    }
                    handler.postDelayed(this::poll, 3000);
                });
            }
        });
    }

    private void show(JSONObject request) {
        if (request == null) {
            dismiss();
            return;
        }
        String id = request.optString("id");
        if (id.isEmpty()) return;
        if (!id.equals(currentId)) {
            dismiss();
            currentId = id;
            dialog = new AppCompatDialog(context, R.style.AppTheme);
            dialog.setContentView(R.layout.codex_approval_screen);
            dialog.setCancelable(false);
            title = dialog.findViewById(R.id.codex_approval_title);
            details = dialog.findViewById(R.id.codex_approval_details);
            approveButton = dialog.findViewById(R.id.codex_approval_approve);
            denyButton = dialog.findViewById(R.id.codex_approval_deny);
            approveButton.setOnClickListener(v -> decide("approve"));
            denyButton.setOnClickListener(v -> decide("deny"));
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            for (CodexApprovalDetails.Field field : CodexApprovalDetails.parse(request)) {
                View row = LayoutInflater.from(context).inflate(R.layout.codex_approval_field, details, false);
                ((TextView) row.findViewById(R.id.approval_field_label)).setText(field.label);
                TextView value = row.findViewById(R.id.approval_field_value);
                value.setText(field.value);
                if (field.code) value.setTypeface(Typeface.MONOSPACE);
                details.addView(row);
            }
            dialog.show();
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        if (dialog != null) {
            title.setText(R.string.codex_approval_title);
            setEnabled(true);
        }
    }

    private void decide(String decision) {
        if (deciding || currentId == null) return;
        deciding = true;
        setEnabled(false);
        title.setText(R.string.codex_approval_sending);
        final String id = currentId;
        executor.execute(() -> {
            boolean resolved = false;
            boolean expired = false;
            try {
                JSONObject body = new JSONObject().put("decision", decision);
                resolved = JsonHttpClient.request(endpoint + "/" + id, body,
                        settings.pairingKey, false).getBoolean("resolved");
            } catch (JsonHttpClient.HttpStatusException e) {
                expired = e.status == 409;
            } catch (Exception ignored) { }
            final boolean finished = resolved || expired;
            handler.post(() -> {
                if (!active) return;
                deciding = false;
                if (finished) dismiss();
                else if (dialog != null) {
                    title.setText(R.string.codex_approval_failed);
                    // Retry only this ID. The bridge rejects replays and expired requests.
                    setEnabled(true);
                }
            });
        });
    }

    private void setEnabled(boolean enabled) {
        if (dialog == null) return;
        approveButton.setEnabled(enabled);
        denyButton.setEnabled(enabled);
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
        currentId = null;
    }

    void stop() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        dismiss();
    }
}
