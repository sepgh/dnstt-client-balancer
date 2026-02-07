package com.github.sepgh.server;

import com.github.sepgh.health.HealthChecker;
import com.github.sepgh.proxy.ProxyClient;
import com.github.sepgh.proxy.ProxyEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketOptions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SocksProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(SocksProxyServer.class);
    
    private static final int BUFFER_SIZE = 65536; // 64KB for better throughput
    private static final int SO_RCVBUF = 262144; // 256KB receive buffer
    private static final int SO_SNDBUF = 262144; // 256KB send buffer
    
    private final String host;
    private final int port;
    private final HealthChecker healthChecker;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public SocksProxyServer(String host, int port, HealthChecker healthChecker) {
        this.host = host;
        this.port = port;
        this.healthChecker = healthChecker;
    }

    public void start() throws IOException {
        if (running.get()) {
            logger.warn("SOCKS proxy server is already running");
            return;
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.setReceiveBufferSize(SO_RCVBUF);
        serverSocket.bind(new InetSocketAddress(host, port));
        running.set(true);

        logger.info("SOCKS proxy server started on {}:{}", host, port);

        acceptThread = new Thread(this::acceptConnections, "socks-accept-thread");
        acceptThread.start();
    }

    public void stop() {
        if (!running.get()) {
            return;
        }

        logger.info("Stopping SOCKS proxy server");
        running.set(false);

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing server socket", e);
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("SOCKS proxy server stopped");
    }

    private void acceptConnections() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                
                // Optimize client socket
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setReceiveBufferSize(SO_RCVBUF);
                clientSocket.setSendBufferSize(SO_SNDBUF);
                
                logger.debug("Accepted connection from {}", clientSocket.getRemoteSocketAddress());
                
                executor.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("Error accepting connection", e);
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            ProxyClient selectedProxy = healthChecker.getSelectedProxy();
            if (selectedProxy == null) {
                logger.warn("No proxy selected, closing client connection");
                clientSocket.close();
                return;
            }

            ProxyEndpoint backend = selectedProxy.getEndpoint();
            logger.debug("Forwarding connection to backend proxy: {}", backend);

            Socket backendSocket = new Socket();
            try {
                // Optimize backend socket
                backendSocket.setTcpNoDelay(true);
                backendSocket.setKeepAlive(true);
                backendSocket.setReceiveBufferSize(SO_RCVBUF);
                backendSocket.setSendBufferSize(SO_SNDBUF);
                
                backendSocket.connect(new InetSocketAddress(backend.getHost(), backend.getPort()), 5000);
                
                // Use virtual threads for forwarding (Java 21+)
                Thread clientToBackend = Thread.ofVirtual().name("forward-c2b").start(
                    new SocketForwarder(clientSocket, backendSocket, "client->backend")
                );
                Thread backendToClient = Thread.ofVirtual().name("forward-b2c").start(
                    new SocketForwarder(backendSocket, clientSocket, "backend->client")
                );

                clientToBackend.join();
                backendToClient.join();

            } catch (Exception e) {
                logger.error("Error forwarding connection", e);
                backendSocket.close();
            }
        } catch (Exception e) {
            logger.error("Error handling client connection", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.debug("Error closing client socket", e);
            }
        }
    }

    private static class SocketForwarder implements Runnable {
        private static final Logger logger = LoggerFactory.getLogger(SocketForwarder.class);
        private final Socket source;
        private final Socket destination;
        private final String direction;

        public SocketForwarder(Socket source, Socket destination, String direction) {
            this.source = source;
            this.destination = destination;
            this.direction = direction;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                var in = source.getInputStream();
                var out = destination.getOutputStream();
                
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    // Remove flush() for better throughput - TCP will handle buffering
                }
            } catch (IOException e) {
                logger.debug("Connection closed [{}]: {}", direction, e.getMessage());
            } finally {
                try {
                    destination.shutdownOutput();
                } catch (IOException e) {
                    logger.debug("Error shutting down output [{}]", direction, e);
                }
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
