package com.example.clockapp;

import java.net.URI;
import java.net.URISyntaxException;

final class ZabbixEndpoint {
    private ZabbixEndpoint() {}

    static String normalize(String address) throws URISyntaxException {
        URI uri = new URI(address.trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535) {
            throw new URISyntaxException("", "Enter an HTTP or HTTPS Zabbix address");
        }
        String result = address.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (!result.endsWith("/api_jsonrpc.php")) result += "/api_jsonrpc.php";
        return result;
    }
}
