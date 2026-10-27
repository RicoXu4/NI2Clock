package com.example.clockapp;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ZabbixService extends Service {
    private final IBinder binder = new ZabbixBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ZabbixListener listener;
    private boolean updating;
    private boolean destroyed;

    public class ZabbixBinder extends Binder {
        ZabbixService getService() {
            return ZabbixService.this;
        }
    }

    public interface ZabbixListener {
        void onMetricsReceived(Double cpu, Double ram, Double network);
        void onZabbixError(String error);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setListener(ZabbixListener listener) {
        this.listener = listener;
    }

    public void requestUpdate() {
        if (destroyed || updating) {
            return;
        }
        ZabbixSettings settings = ZabbixSettings.load(this);
        if (!settings.isConfigured()) {
            if (listener != null) {
                listener.onZabbixError(getString(R.string.zabbix_not_configured));
            }
            return;
        }
        updating = true;
        executor.execute(() -> {
            try {
                ZabbixMetrics metrics = new ZabbixClient(settings).fetchMetrics();
                handler.post(() -> {
                    updating = false;
                    if (!destroyed && listener != null) {
                        listener.onMetricsReceived(metrics.cpu, metrics.ram, metrics.network);
                    }
                });
            } catch (Exception e) {
                Log.w("ZabbixService", "Zabbix request failed: " + e.getClass().getSimpleName());
                handler.post(() -> {
                    updating = false;
                    if (!destroyed && listener != null) {
                        listener.onZabbixError(e instanceof JsonHttpClient.HttpStatusException
                                ? getString(R.string.zabbix_http_error, ((JsonHttpClient.HttpStatusException) e).status)
                                : getString(R.string.zabbix_fetch_failed));
                    }
                });
            }
        });
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        listener = null;
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
