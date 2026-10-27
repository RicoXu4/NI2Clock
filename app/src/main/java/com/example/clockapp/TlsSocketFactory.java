package com.example.clockapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Enables supported modern TLS protocols on older Android releases. */
final class TlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    TlsSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    private Socket configure(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) socket;
            List<String> protocols = new ArrayList<>();
            for (String protocol : ssl.getSupportedProtocols()) {
                if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
            }
            if (!protocols.isEmpty()) ssl.setEnabledProtocols(protocols.toArray(new String[0]));
        }
        return socket;
    }

    @Override
    public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }

    @Override
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket() throws IOException { return configure(delegate.createSocket()); }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return configure(delegate.createSocket(socket, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, local, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, local, localPort));
    }
}
