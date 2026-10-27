package com.example.clockapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CodexUsageSnapshotTest {
    private JSONObject snapshot(JSONObject window) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("observedAt", 1234)
                .put("buckets", new JSONArray().put(new JSONObject().put("name", "codex")
                        .put("windows", new JSONArray().put(window))));
    }

    @Test
    public void knownUsageAndResetArePreserved() throws Exception {
        CodexUsageSnapshot value = CodexUsageSnapshot.parse(snapshot(new JSONObject()
                .put("usedPercent", 43).put("durationMinutes", 300).put("resetsAt", 2345)));
        assertEquals(43, value.windows.get(0).usedPercent, 0);
        assertEquals(300, value.windows.get(0).durationMinutes);
        assertEquals(2345, value.windows.get(0).resetsAt);
        assertEquals("Codex", value.windows.get(0).name);
    }

    @Test
    public void missingValuesNeverBecomeZeroUsage() throws Exception {
        CodexUsageSnapshot value = CodexUsageSnapshot.parse(snapshot(new JSONObject().put("usedPercent", JSONObject.NULL)));
        assertNull(value.windows.get(0).usedPercent);
        assertEquals(0, value.windows.get(0).resetsAt);
    }

    @Test
    public void outOfRangeValuesAreClamped() throws Exception {
        assertEquals(100, CodexUsageSnapshot.parse(snapshot(new JSONObject().put("usedPercent", 150)))
                .windows.get(0).usedPercent, 0);
        assertEquals(0, CodexUsageSnapshot.parse(snapshot(new JSONObject().put("usedPercent", -20)))
                .windows.get(0).usedPercent, 0);
    }

    @Test(expected = org.json.JSONException.class)
    public void timestampIsRequiredSoUnknownDataCannotLookFresh() throws Exception {
        CodexUsageSnapshot.parse(new JSONObject().put("schemaVersion", 1).put("buckets", new JSONArray()));
    }

    @Test
    public void bridgeAddressesAreValidatedAndCanBeDisabled() throws Exception {
        assertEquals("http://10.0.0.1:18765/usage", CodexUsageSettings.normalizeAddress(" http://10.0.0.1:18765/ "));
        assertEquals("https://example.com/usage", CodexUsageSettings.normalizeAddress("https://example.com/usage"));
        assertEquals("", CodexUsageSettings.normalizeAddress(""));
        for (String invalid : new String[]{"file:///etc/passwd", "http://user:password@example.com", "http://example.com?key=test"}) {
            try {
                CodexUsageSettings.normalizeAddress(invalid);
                fail("Invalid address accepted");
            } catch (java.net.URISyntaxException expected) { }
        }
    }
}
