package com.example.clockapp;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WeatherServiceTest {

    @Test
    public void parseWeatherJson_parsesLocationTemperatureAndCondition() throws Exception {
        String json = "{" +
                "\"name\":\"New York\"," +
                "\"main\":{\"temp\":21.6}," +
                "\"weather\":[{\"main\":\"Clouds\"}]" +
                "}";

        WeatherService.WeatherData data = WeatherService.parseWeatherJson(json);

        assertEquals("New York", data.location);
        assertEquals("22°C", data.temperature);
        assertEquals("Clouds", data.condition);
    }

    @Test
    public void parseWeatherJson_missingWeatherArrayThrows() {
        String json = "{" +
                "\"name\":\"Testville\"," +
                "\"main\":{\"temp\":10}" +
                "}";

        assertThrows(JSONException.class, () -> WeatherService.parseWeatherJson(json));
    }
}
