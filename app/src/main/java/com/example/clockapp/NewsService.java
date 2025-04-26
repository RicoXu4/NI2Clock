package com.example.clockapp;

import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class NewsService extends Service {

    private final IBinder binder = new NewsBinder();
    private NewsListener listener;

    public class NewsBinder extends Binder {
        NewsService getService() {
            return NewsService.this;
        }
    }

    public interface NewsListener {
        void onZabbixDataReceived(float cpuUsage, float ramUsage, float netUsage);
        void onNewsError(String error);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setNewsListener(NewsListener listener) {
        this.listener = listener;
    }

    public void requestNewsUpdate() {
        new FetchNewsTask().execute();
    }

    private class FetchNewsTask extends AsyncTask<Void, Void, List<NewsItem>> {
        @Override
        protected List<NewsItem> doInBackground(Void... params) {
            String apiUrl = "http://10.77.2.21:20800/api_jsonrpc.php";
            String token = "c7c86e06ce3738e5db616589b77d39f5c1c286e8c9b2917af3fc8afa62f2934d";

            try {
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                JSONObject requestJson = new JSONObject();
                requestJson.put("jsonrpc", "2.0");
                requestJson.put("method", "event.get");

                JSONObject paramsJson = new JSONObject();
                paramsJson.put("output", "extend");
                paramsJson.put("sortfield", "clock");
                paramsJson.put("sortorder", "DESC");
                paramsJson.put("limit", 10);

                requestJson.put("params", paramsJson);
                requestJson.put("auth", token);
                requestJson.put("id", 1);

                connection.getOutputStream().write(requestJson.toString().getBytes());

                InputStream inputStream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }

                JSONObject responseJson = new JSONObject(result.toString());
                JSONArray events = responseJson.getJSONArray("result");

                List<NewsItem> newsList = new ArrayList<>();

                for (int i = 0; i < events.length(); i++) {
                    JSONObject item = events.getJSONObject(i);

                    NewsItem news = new NewsItem();
                    news.title = item.getString("name");
                    news.value = item.getString("lastvalue");
                    newsList.add(news);
                }

                return newsList;

            } catch (IOException | JSONException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<NewsItem> result) {
            if (listener != null) {
                if (result != null && !result.isEmpty()) {
                    float cpu = 0, ram = 0, net = 0;
                    for (NewsItem item : result) {
                        if (item.title.contains("CPU")) cpu = Float.parseFloat(item.value);
                        else if (item.title.contains("RAM")) ram = Float.parseFloat(item.value);
                        else if (item.title.contains("Network")) net = Float.parseFloat(item.value);
                    }
                    listener.onZabbixDataReceived(cpu, ram, net);
                } else {
                    listener.onNewsError("Failed to fetch Zabbix data");
                }
            }
        }
    }

    public static class NewsItem {
        String title;
        String value;
    }
}