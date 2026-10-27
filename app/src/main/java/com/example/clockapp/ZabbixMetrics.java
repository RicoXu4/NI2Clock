package com.example.clockapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ZabbixMetrics {
    Double cpu;
    Double ram;
    Double network;

    static ZabbixMetrics parse(JSONArray items) throws JSONException {
        ZabbixMetrics metrics = new ZabbixMetrics();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String name = item.optString("name");
            double value = item.optDouble("lastvalue", Double.NaN);
            if (Double.isNaN(value) || Double.isInfinite(value)) continue;
            String units = item.optString("units");
            if (metrics.cpu == null && name.contains("CPU") && "%".equals(units)) {
                metrics.cpu = value;
            } else if (metrics.ram == null && name.contains("RAM") && "%".equals(units)) {
                metrics.ram = value;
            } else if (metrics.network == null && name.contains("Network")) {
                if ("bps".equals(units)) metrics.network = value / 1_000_000;
                else if ("Bps".equals(units)) metrics.network = value * 8 / 1_000_000;
                else if ("Mbps".equals(units)) metrics.network = value;
            }
        }
        return metrics;
    }
}
