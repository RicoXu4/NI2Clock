package com.example.clockapp;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST = 1;
    private static final long CLOCK_MOVE_INTERVAL_MS = 30_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View clockArea;
    private View clockLayout;
    private DashboardPanelDialog weatherDialog;
    private DashboardPanelDialog zabbixDialog;
    private TextView clockText;
    private TextView dateText;
    private TextView weatherLocation;
    private TextView weatherTemperature;
    private TextView weatherCondition;
    private TextView lastUpdated;
    private TextView cpuText;
    private TextView ramText;
    private TextView networkText;
    private TextView zabbixStatus;
    private GestureDetector gestureDetector;
    private CodexUsageDialog codexUsageDialog;
    private CodexApprovalMonitor approvalMonitor;
    private boolean longPressHandled;
    private WeatherService weatherService;
    private ZabbixService zabbixService;
    private boolean weatherBinding;
    private boolean zabbixBinding;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clockText.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now));
            dateText.setText(new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now));
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable clockMovement = new Runnable() {
        @Override
        public void run() {
            moveClock();
            handler.postDelayed(this, CLOCK_MOVE_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        clockArea = findViewById(R.id.clock_area);
        clockLayout = findViewById(R.id.clock_layout);
        weatherDialog = new DashboardPanelDialog(this, true, this::refreshWeather, this::openSettings);
        zabbixDialog = new DashboardPanelDialog(this, false, () -> {
            if (zabbixService != null) zabbixService.requestUpdate();
        }, this::openSettings);
        clockText = findViewById(R.id.clock_text);
        dateText = findViewById(R.id.date_text);
        weatherLocation = weatherDialog.findViewById(R.id.weather_location);
        weatherTemperature = weatherDialog.findViewById(R.id.weather_temp);
        weatherCondition = weatherDialog.findViewById(R.id.weather_condition);
        lastUpdated = weatherDialog.findViewById(R.id.last_updated);
        cpuText = zabbixDialog.findViewById(R.id.cpu_text);
        ramText = zabbixDialog.findViewById(R.id.ram_text);
        networkText = zabbixDialog.findViewById(R.id.network_text);
        zabbixStatus = zabbixDialog.findViewById(R.id.zabbix_status);
        clockArea.setContentDescription(getString(R.string.clock_gesture_hint));
        clockArea.setOnLongClickListener(view -> {
            openSettings();
            return true;
        });
        clockText.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/digital-7.ttf"));
        clockArea.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) {
                return;
            }
            clockLayout.animate().cancel();
            clockLayout.setAlpha(1f);
            // Keep the single-line clock readable when the available screen area changes.
            if (right > left) {
                float naturalSize = 96 * getResources().getDisplayMetrics().scaledDensity;
                Paint paint = new Paint(clockText.getPaint());
                paint.setTextSize(naturalSize);
                float naturalWidth = paint.measureText("88:88:88");
                float scale = naturalWidth > 0 ? Math.min(1f, (right - left) / naturalWidth) : 1f;
                clockText.setTextSize(TypedValue.COMPLEX_UNIT_PX, naturalSize * scale);
            }
            clampClockPosition();
        });
        clockLayout.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> clampClockPosition());
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent event) {
                cancelChildTouch(event);
                openSettings();
            }

            @Override
            public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (start == null || end == null) return false;
                float deltaX = end.getX() - start.getX();
                float deltaY = end.getY() - start.getY();
                float threshold = 100 * getResources().getDisplayMetrics().density;
                if (Math.abs(deltaY) > Math.abs(deltaX)) {
                    if (deltaY < -threshold && Math.abs(velocityY) > threshold) {
                        showCodexUsage();
                        return true;
                    }
                    return false;
                }
                if (Math.abs(deltaX) < threshold || Math.abs(velocityX) < threshold) {
                    return false;
                }
                handleSwipe(deltaX < 0);
                return true;
            }
        });
        requestLocationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        longPressHandled = false;
        approvalMonitor = new CodexApprovalMonitor(this);
        approvalMonitor.start();
        handler.post(clockTick);
        handler.postDelayed(clockMovement, 3000);
        weatherBinding = bindService(new Intent(this, WeatherService.class),
                weatherConnection, Context.BIND_AUTO_CREATE);
        zabbixBinding = bindService(new Intent(this, ZabbixService.class),
                zabbixConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) longPressHandled = false;
        boolean gestureHandled = gestureDetector.onTouchEvent(event);
        if (gestureHandled || longPressHandled) {
            cancelChildTouch(event);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void cancelChildTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private void openSettings() {
        if (longPressHandled) return;
        longPressHandled = true;
        clockArea.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void showCodexUsage() {
        if (codexUsageDialog != null && codexUsageDialog.isShowing()) return;
        codexUsageDialog = new CodexUsageDialog(this, this::openSettings);
        codexUsageDialog.show();
    }

    private void handleSwipe(boolean swipeLeft) {
        if (swipeLeft) weatherDialog.show();
        else zabbixDialog.show();
    }

    private void moveClock() {
        clockLayout.animate().alpha(0f).setDuration(1500).withEndAction(() -> {
            clockLayout.setX(ClockPosition.randomOffset(clockArea.getWidth(), clockLayout.getWidth(), random));
            clockLayout.setY(ClockPosition.randomOffset(clockArea.getHeight(), clockLayout.getHeight(), random));
            clockLayout.animate().alpha(1f).setDuration(1500).start();
        }).start();
    }

    private void clampClockPosition() {
        clockLayout.setX(ClockPosition.clamp(clockLayout.getX(), clockArea.getWidth(), clockLayout.getWidth()));
        clockLayout.setY(ClockPosition.clamp(clockLayout.getY(), clockArea.getHeight(), clockLayout.getHeight()));
    }

    private void requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        }
    }

    private void refreshWeather() {
        requestLocationPermission();
        if (weatherService != null) weatherService.requestWeatherUpdate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST && weatherService != null) {
            weatherService.requestWeatherUpdate();
        }
    }

    private final ServiceConnection weatherConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            weatherService = ((WeatherService.WeatherBinder) binder).getService();
            weatherService.setWeatherListener(weatherListener);
            weatherService.requestWeatherUpdate();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            weatherService = null;
        }
    };

    private final ServiceConnection zabbixConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            zabbixService = ((ZabbixService.ZabbixBinder) binder).getService();
            zabbixService.setListener(zabbixListener);
            zabbixService.requestUpdate();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            zabbixService = null;
        }
    };

    private final WeatherService.WeatherListener weatherListener = new WeatherService.WeatherListener() {
        @Override
        public void onWeatherDataReceived(String location, String temperature, String condition) {
            weatherLocation.setText(location);
            weatherTemperature.setText(temperature);
            weatherCondition.setText(condition);
            lastUpdated.setText(getString(R.string.last_updated,
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())));
        }

        @Override
        public void onWeatherError(String error) {
            weatherLocation.setText(R.string.weather_unavailable);
            weatherTemperature.setText(R.string.temperature_placeholder);
            weatherCondition.setText(error);
        }
    };

    private final ZabbixService.ZabbixListener zabbixListener = new ZabbixService.ZabbixListener() {
        @Override
        public void onMetricsReceived(Double cpu, Double ram, Double network) {
            cpuText.setText(getString(R.string.cpu_value, formatMetric(cpu)));
            ramText.setText(getString(R.string.ram_value, formatMetric(ram)));
            networkText.setText(getString(R.string.network_value, formatMetric(network)));
            zabbixStatus.setText(cpu == null && ram == null && network == null
                    ? R.string.zabbix_no_metrics : R.string.zabbix_updated);
        }

        @Override
        public void onZabbixError(String error) {
            cpuText.setText(R.string.cpu_placeholder);
            ramText.setText(R.string.ram_placeholder);
            networkText.setText(R.string.network_placeholder);
            zabbixStatus.setText(error);
        }
    };

    private String formatMetric(Double value) {
        return value == null ? "—" : String.format(Locale.getDefault(), "%.1f", value);
    }

    @Override
    protected void onStop() {
        if (approvalMonitor != null) approvalMonitor.stop();
        if (codexUsageDialog != null) codexUsageDialog.dismiss();
        weatherDialog.dismiss();
        zabbixDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        clockLayout.animate().cancel();
        clockLayout.setAlpha(1f);
        if (weatherService != null) weatherService.setWeatherListener(null);
        if (zabbixService != null) zabbixService.setListener(null);
        if (weatherBinding) unbindService(weatherConnection);
        if (zabbixBinding) unbindService(zabbixConnection);
        weatherBinding = false;
        zabbixBinding = false;
        weatherService = null;
        zabbixService = null;
        super.onStop();
    }
}
