// WeatherService.java
package com.example.clockapp;

import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class WeatherService extends Service {

    private final IBinder binder = new WeatherBinder();
    private LocationManager locationManager;
    private WeatherListener listener;
    private final long UPDATE_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private Handler updateHandler;
    private Runnable updateRunnable;
    private long lastUpdated = 0;

    public class WeatherBinder extends Binder {
        WeatherService getService() {
            return WeatherService.this;
        }
    }

    public interface WeatherListener {
        void onWeatherDataReceived(String location, String temperature, String condition);
        void onWeatherError(String error);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        updateHandler = new Handler();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                requestWeatherUpdate();
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };
        updateHandler.post(updateRunnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }

    public void setWeatherListener(WeatherListener listener) {
        this.listener = listener;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void forceRefresh() {
        requestWeatherUpdate();
    }

    public void requestWeatherUpdate() {
        try {
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                    locationListener, null);
        } catch (SecurityException e) {
            if (listener != null) {
                listener.onWeatherError("Location permission denied");
            }
        }
    }

    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            new FetchWeatherTask().execute(location.getLatitude(), location.getLongitude());
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {
            if (listener != null) {
                listener.onWeatherError("Location provider disabled");
            }
        }
    };

    private class FetchWeatherTask extends AsyncTask<Double, Void, WeatherData> {
        @Override
        protected WeatherData doInBackground(Double... params) {
            double latitude = params[0];  // New York
            double longitude = params[1];

            String apiUrl = "http://api.openweathermap.org/data/2.5/weather?" +
                    "lat=" + latitude + "&lon=" + longitude + "&units=metric" +
                    "&appid=82b12e5fa111e46061396a9e98aee61d";

            try {
                Log.d("WeatherService", "Lat: " + latitude + ", Lon: " + longitude);
                Log.d("WeatherService", "Requesting weather from: " + apiUrl);
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                int responseCode = connection.getResponseCode();
                Log.d("WeatherService", "Response Code: " + responseCode);

                InputStream inputStream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }

                Log.d("WeatherService", "Raw Response: " + result.toString());

                JSONObject jsonObject = new JSONObject(result.toString());

                WeatherData weather = new WeatherData();
                weather.location = jsonObject.getString("name");

                JSONObject main = jsonObject.getJSONObject("main");
                weather.temperature = Math.round(main.getDouble("temp")) + "°C";

                JSONObject weatherDetails = jsonObject.getJSONArray("weather").getJSONObject(0);
                weather.condition = weatherDetails.getString("main");

                return weather;

            } catch (IOException | JSONException e) {
                Log.e("WeatherService", "Exception: " + e.getMessage(), e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(WeatherData result) {
            if (listener != null) {
                if (result != null) {
                    listener.onWeatherDataReceived(result.location,
                            result.temperature,
                            result.condition);
                    lastUpdated = System.currentTimeMillis();
                } else {
                    listener.onWeatherError("Failed to fetch weather data");
                }
            }
        }
    }

    private static class WeatherData {
        String location;
        String temperature;
        String condition;
    }
}
