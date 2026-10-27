package com.example.clockapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CodexUsageDialog extends BottomSheetDialog {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LinearLayout rows;
    private final TextView status;
    private final View loading;
    private final GestureDetector gestures;
    private boolean requesting;
    private boolean closed;
    private boolean longPressHandled;
    private CodexUsageSnapshot snapshot;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            requestUsage();
            handler.postDelayed(this, 60_000L);
        }
    };

    CodexUsageDialog(Context context, Runnable openSettings) {
        super(context);
        setContentView(R.layout.codex_usage_sheet);
        rows = findViewById(R.id.codex_usage_rows);
        status = findViewById(R.id.codex_usage_status);
        loading = findViewById(R.id.codex_usage_loading);
        findViewById(R.id.codex_usage_refresh).setOnClickListener(view -> requestUsage());
        findViewById(R.id.codex_usage_close).setOnClickListener(view -> dismiss());
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent event) {
                longPressHandled = true;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                CodexUsageDialog.super.dispatchTouchEvent(cancel);
                cancel.recycle();
                dismiss();
                openSettings.run();
            }
        });
        setOnShowListener(dialog -> {
            getBehavior().setSkipCollapsed(true);
            getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            readCachedSnapshot();
            handler.post(refresh);
        });
        setOnDismissListener(dialog -> {
            closed = true;
            handler.removeCallbacksAndMessages(null);
            executor.shutdownNow();
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return longPressHandled || super.dispatchTouchEvent(event);
    }

    private void readCachedSnapshot() {
        String cached = getContext().getSharedPreferences(CodexUsageSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getString("snapshot", null);
        if (cached == null) return;
        try {
            snapshot = CodexUsageSnapshot.parse(new JSONObject(cached));
            render(true);
        } catch (Exception ignored) {
            // Invalid cache entries are replaced by the next successful read.
        }
    }

    private void requestUsage() {
        if (closed || requesting) return;
        CodexUsageSettings settings = CodexUsageSettings.load(getContext());
        if (settings.address.isEmpty() || settings.pairingKey.isEmpty()) {
            loading.setVisibility(View.GONE);
            status.setText(R.string.codex_usage_not_configured);
            return;
        }
        requesting = true;
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                // The Zabbix certificate bypass never applies to Codex usage.
                JSONObject json = JsonHttpClient.request(settings.address, null, settings.pairingKey, false);
                CodexUsageSnapshot result = CodexUsageSnapshot.parse(json);
                handler.post(() -> {
                    if (closed) return;
                    requesting = false;
                    loading.setVisibility(View.GONE);
                    snapshot = result;
                    getContext().getSharedPreferences(CodexUsageSettings.PREFERENCES, Context.MODE_PRIVATE)
                            .edit().putString("snapshot", json.toString()).apply();
                    render(result.refreshFailed);
                });
            } catch (Exception e) {
                Log.w("CodexUsage", "Usage bridge read failed: " + e.getClass().getSimpleName());
                handler.post(() -> {
                    if (closed) return;
                    requesting = false;
                    loading.setVisibility(View.GONE);
                    if (snapshot != null) render(true);
                    else status.setText(e instanceof JsonHttpClient.HttpStatusException
                            && ((JsonHttpClient.HttpStatusException) e).status == 401
                            ? R.string.codex_usage_pairing_failed : R.string.codex_usage_unreachable);
                });
            }
        });
    }

    private void render(boolean failed) {
        rows.removeAllViews();
        NumberFormat number = NumberFormat.getNumberInstance();
        number.setMaximumFractionDigits(1);
        for (CodexUsageSnapshot.Window window : snapshot.windows) {
            View row = getLayoutInflater().inflate(R.layout.codex_usage_window, rows, false);
            ((TextView) row.findViewById(R.id.usage_window_title)).setText(
                    getContext().getString(R.string.codex_window_title, window.name, windowLabel(window.durationMinutes)));
            TextView percentage = row.findViewById(R.id.usage_window_percentage);
            LinearProgressIndicator progress = row.findViewById(R.id.usage_window_progress);
            if (window.usedPercent == null) {
                percentage.setText(R.string.codex_usage_unknown);
                progress.setVisibility(View.GONE);
            } else {
                percentage.setText(getContext().getString(R.string.codex_usage_percentage,
                        number.format(window.usedPercent), number.format(100 - window.usedPercent)));
                progress.setProgress((int) Math.round(window.usedPercent * 10));
            }
            ((TextView) row.findViewById(R.id.usage_window_reset)).setText(window.resetsAt > 0
                    ? getContext().getString(R.string.codex_usage_reset, date(window.resetsAt))
                    : getContext().getString(R.string.codex_usage_reset_unknown));
            rows.addView(row);
        }
        long age = System.currentTimeMillis() / 1000 - snapshot.observedAt;
        boolean stale = failed || age > 300;
        status.setText(getContext().getString(stale ? R.string.codex_usage_stale : R.string.codex_usage_updated,
                date(snapshot.observedAt)));
        if (snapshot.windows.isEmpty()) status.setText(R.string.codex_usage_no_windows);
    }

    private String windowLabel(int minutes) {
        if (minutes == 10080) return getContext().getString(R.string.codex_window_weekly);
        if (minutes > 0 && minutes % 1440 == 0) return getContext().getString(R.string.codex_window_days, minutes / 1440);
        if (minutes > 0 && minutes % 60 == 0) return getContext().getString(R.string.codex_window_hours, minutes / 60);
        if (minutes > 0) return getContext().getString(R.string.codex_window_minutes, minutes);
        return getContext().getString(R.string.codex_window_unknown);
    }

    private String date(long seconds) {
        return new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date(seconds * 1000));
    }
}
