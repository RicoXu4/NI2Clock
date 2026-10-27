package com.example.clockapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ZabbixMetricsTest {
    private JSONObject item(String name, String value, String units) throws Exception {
        return new JSONObject().put("name", name).put("lastvalue", value).put("units", units);
    }

    @Test
    public void missingOrInvalidValuesRemainUnavailable() throws Exception {
        JSONArray items = new JSONArray()
                .put(item("CPU utilization", "invalid", "%"))
                .put(item("RAM usage", "NaN", "%"))
                .put(item("Network usage", "Infinity", "bps"));
        ZabbixMetrics metrics = ZabbixMetrics.parse(items);
        assertNull(metrics.cpu);
        assertNull(metrics.ram);
        assertNull(metrics.network);
    }

    @Test
    public void countersAreNotDisplayedAsPercentages() throws Exception {
        JSONArray items = new JSONArray()
                .put(item("CPU count", "8", ""))
                .put(item("RAM total", "16384", "B"))
                .put(item("CPU utilization", "0", "%"))
                .put(item("RAM usage", "42.5", "%"));
        ZabbixMetrics metrics = ZabbixMetrics.parse(items);
        assertEquals(0, metrics.cpu, 0);
        assertEquals(42.5, metrics.ram, 0);
    }

    @Test
    public void networkRatesAreConvertedToMegabits() throws Exception {
        String[] units = {"bps", "Bps", "Mbps"};
        String[] values = {"8000000", "1000000", "8"};
        for (int i = 0; i < units.length; i++) {
            ZabbixMetrics metrics = ZabbixMetrics.parse(new JSONArray()
                    .put(item("Network incoming", values[i], units[i])));
            assertEquals(8, metrics.network, 0);
        }
    }

    @Test
    public void unknownNetworkUnitsRemainUnavailable() throws Exception {
        ZabbixMetrics metrics = ZabbixMetrics.parse(new JSONArray()
                .put(item("Network packets", "200", "pps")));
        assertNull(metrics.network);
    }
}
