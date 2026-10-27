package com.example.clockapp;

import org.junit.Test;
import java.net.URISyntaxException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ZabbixEndpointTest {
    @Test
    public void acceptsBaseAddressesAndFullApiUrls() throws Exception {
        assertEquals("https://example.com/api_jsonrpc.php", ZabbixEndpoint.normalize(" https://example.com/ "));
        assertEquals("http://10.0.0.1:8080/zabbix/api_jsonrpc.php",
                ZabbixEndpoint.normalize("http://10.0.0.1:8080/zabbix/"));
        assertEquals("https://example.com/zabbix/api_jsonrpc.php",
                ZabbixEndpoint.normalize("https://example.com/zabbix/api_jsonrpc.php"));
    }

    @Test
    public void rejectsInvalidAddressesAndEmbeddedCredentials() throws Exception {
        for (String address : new String[]{"", "example.com", "file:///etc/passwd", "https://",
                "https://user:pass@example.com", "https://example.com?token=test",
                "https://example.com/#fragment", "http://example.com:99999"}) {
            try {
                ZabbixEndpoint.normalize(address);
                fail("Accepted an invalid address");
            } catch (URISyntaxException expected) { }
        }
    }
}
