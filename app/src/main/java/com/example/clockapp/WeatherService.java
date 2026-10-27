package com.example.clockapp;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WeatherService extends Service {
    private static final long UPDATE_INTERVAL_MS = 60 * 60 * 1000L;
    private static final long LOCATION_TIMEOUT_MS = 20_000L;
    private final IBinder binder = new WeatherBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LocationManager locationManager;
    private WeatherListener listener;
    private boolean updating;
    private boolean destroyed;

    public class WeatherBinder extends Binder {
        WeatherService getService() {
            return WeatherService.this;
        }
    }

    public interface WeatherListener {
        void onWeatherDataReceived(String location, String temperature, String condition);
        void onWeatherError(String error);
    }

    private final Runnable periodicUpdate = new Runnable() {
        @Override
        public void run() {
            requestWeatherUpdate();
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    private final Runnable locationTimeout = () -> {
        stopLocationUpdates();
        reportError(getString(R.string.location_timeout));
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        handler.postDelayed(periodicUpdate, UPDATE_INTERVAL_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setWeatherListener(WeatherListener listener) {
        this.listener = listener;
    }

    public void requestWeatherUpdate() {
        if (destroyed || updating) {
            return;
        }
        if (BuildConfig.WEATHER_API_KEY.isEmpty()) {
            reportError(getString(R.string.weather_not_configured));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            reportError(getString(R.string.location_permission_denied));
            return;
        }
        try {
            if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                reportError(getString(R.string.location_disabled));
                return;
            }
            updating = true;
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                    locationListener, Looper.getMainLooper());
            handler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);
        } catch (SecurityException | IllegalArgumentException e) {
            stopLocationUpdates();
            reportError(getString(R.string.location_unavailable));
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            stopLocationUpdates();
            executor.execute(() -> fetchWeather(location));
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {
            stopLocationUpdates();
            reportError(getString(R.string.location_disabled));
        }
    };

    private void fetchWeather(Location location) {
        String address = "https://api.openweathermap.org/data/2.5/weather?lat="
                + location.getLatitude() + "&lon=" + location.getLongitude()
                + "&units=metric&appid=" + android.net.Uri.encode(BuildConfig.WEATHER_API_KEY);
        try {
            JSONObject response = JsonHttpClient.request(address, null);
            String city = response.getString("name");
            String temperature = Math.round(response.getJSONObject("main").getDouble("temp")) + "°C";
            String condition = response.getJSONArray("weather").getJSONObject(0).getString("main");
            handler.post(() -> {
                updating = false;
                if (!destroyed && listener != null) {
                    listener.onWeatherDataReceived(city, temperature, condition);
                }
            });
        } catch (Exception e) {
            Log.w("WeatherService", "Weather request failed: " + e.getClass().getSimpleName());
            handler.post(() -> reportError(getString(R.string.weather_fetch_failed)));
        }
    }

    private void reportError(String message) {
        updating = false;
        if (!destroyed && listener != null) {
            listener.onWeatherError(message);
        }
    }

    private void stopLocationUpdates() {
        handler.removeCallbacks(locationTimeout);
        locationManager.removeUpdates(locationListener);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        listener = null;
        stopLocationUpdates();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
