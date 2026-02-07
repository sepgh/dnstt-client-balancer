package com.github.sepgh.health;

import com.github.sepgh.config.ConfigurationManager;
import com.github.sepgh.config.ProxyConfig;
import com.github.sepgh.network.NetworkInterfaceMonitor;
import com.github.sepgh.proxy.ProxyClient;
import com.github.sepgh.proxy.ProxyClientFactory;
import com.github.sepgh.proxy.impl.DnsTestedSlipStreamProxyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class HealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(HealthChecker.class);
    
    private final ConfigurationManager configManager;
    private final ProxyTester proxyTester;
    private final NetworkInterfaceMonitor networkMonitor;
    private final Map<String, ProxyClient> activeClients = new ConcurrentHashMap<>();
    private final AtomicReference<ProxyClient> selectedProxy = new AtomicReference<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService testExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    private volatile boolean running = false;
    private final int healthCheckIntervalSeconds;
    private final int currentProxyCheckIntervalSeconds;

    public HealthChecker(ConfigurationManager configManager, ProxyTester proxyTester) {
        this.configManager = configManager;
        this.proxyTester = proxyTester;
        this.healthCheckIntervalSeconds = configManager.getConfig().getHealthCheckIntervalSeconds();
        this.currentProxyCheckIntervalSeconds = configManager.getConfig().getCurrentProxyCheckIntervalSeconds();
        
        String networkInterface = configManager.getConfig().getNetworkInterface();
        this.networkMonitor = new NetworkInterfaceMonitor(networkInterface);
    }

    public void start() {
        if (running) {
            logger.warn("HealthChecker is already running");
            return;
        }
        
        running = true;
        logger.info("Starting HealthChecker");
        
        selectInitialProxy();
        
        scheduler.scheduleWithFixedDelay(
            this::checkAllProxies,
            healthCheckIntervalSeconds,
            healthCheckIntervalSeconds,
            TimeUnit.SECONDS
        );
        
        scheduler.scheduleWithFixedDelay(
            this::checkCurrentProxy,
            currentProxyCheckIntervalSeconds,
            currentProxyCheckIntervalSeconds,
            TimeUnit.SECONDS
        );
    }

    public void stop() {
        if (!running) {
            return;
        }
        
        logger.info("Stopping HealthChecker");
        running = false;
        
        scheduler.shutdown();
        testExecutor.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                testExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            testExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        stopAllClients();
    }

    private void selectInitialProxy() {
        logger.info("Selecting initial proxy");
        List<ProxyConfig> proxies = configManager.getProxies();
        
        if (proxies.isEmpty()) {
            logger.error("No proxies configured");
            return;
        }
        
        Map<ProxyClient, ProxyTestResult> results = testProxies(proxies);
        ProxyClient bestProxy = selectBestProxy(results);
        
        if (bestProxy != null) {
            switchToProxy(bestProxy);
        } else {
            logger.error("No working proxy found during initialization");
        }
    }

    private void checkAllProxies() {
        if (!running) return;
        
        // Check network availability first - don't switch proxies if network is down
        if (!networkMonitor.isNetworkAvailable()) {
            logger.warn("Network interface is down, skipping periodic health check");
            return;
        }
        
        logger.debug("Running health check on all proxies");
        List<ProxyConfig> proxies = configManager.getProxies();
        Map<ProxyClient, ProxyTestResult> results = testProxies(proxies);
        
        ProxyClient current = selectedProxy.get();
        ProxyClient bestProxy = selectBestProxy(results);
        
        if (bestProxy == null) {
            logger.warn("No working proxy found during health check");
            return;
        }
        
        if (bestProxy != current) {
            logger.info("Switching to better proxy: {}", bestProxy.getName());
            switchToProxy(bestProxy);
        } else {
            logger.debug("Current proxy {} is still the best option", current.getName());
        }
    }

    private void checkCurrentProxy() {
        if (!running) return;
        
        // Check network availability first - don't switch proxies if network is down
        if (!networkMonitor.isNetworkAvailable()) {
            logger.warn("Network interface is down, skipping current proxy health check");
            return;
        }
        
        ProxyClient current = selectedProxy.get();
        if (current == null) {
            logger.warn("No current proxy selected, attempting to select one");
            selectInitialProxy();
            
            // If still no proxy after selection attempt, check if any are running
            if (selectedProxy.get() == null) {
                logger.info("Still no proxy selected, checking for running proxies");
                for (ProxyClient client : activeClients.values()) {
                    if (client != null && client.isRunning() && client.isHealthy()) {
                        logger.info("Found running and healthy proxy {}, testing it", client.getName());
                        ProxyTestResult result = proxyTester.test(client.getEndpoint());
                        if (result.isSuccess()) {
                            switchToProxy(client);
                            logger.info("Successfully selected running proxy {}", client.getName());
                            return;
                        }
                    }
                }
            }
            return;
        }
        
        logger.debug("Checking current proxy: {}", current.getName());
        
        // First check the proxy's own health status (important for SlipStream)
        if (!current.isHealthy()) {
            logger.warn("Current proxy {} reports unhealthy status", current.getName());
            
            // If it's a DNS-tested SlipStream client, try rotating to next DNS endpoint
            if (current instanceof DnsTestedSlipStreamProxyClient) {
                DnsTestedSlipStreamProxyClient dnsClient = (DnsTestedSlipStreamProxyClient) current;
                logger.info("Attempting to rotate DNS endpoint for {}", current.getName());
                
                if (dnsClient.rotateToNextDnsEndpoint()) {
                    logger.info("Successfully rotated to next DNS endpoint for {}", current.getName());
                    return;
                } else {
                    logger.error("Failed to rotate to any DNS endpoint for {}, selecting new proxy", current.getName());
                }
            }
            
            selectInitialProxy();
            return;
        }
        
        // Then perform SOCKS connectivity test
        ProxyTestResult result = proxyTester.test(current.getEndpoint());
        
        if (!result.isSuccess()) {
            logger.warn("Current proxy {} failed SOCKS connectivity test: {}", current.getName(), result.getErrorMessage());
            
            // If it's a DNS-tested SlipStream client, try rotating to next DNS endpoint
            if (current instanceof DnsTestedSlipStreamProxyClient) {
                DnsTestedSlipStreamProxyClient dnsClient = (DnsTestedSlipStreamProxyClient) current;
                logger.info("Attempting to rotate DNS endpoint for {}", current.getName());
                
                if (dnsClient.rotateToNextDnsEndpoint()) {
                    logger.info("Successfully rotated to next DNS endpoint for {}", current.getName());
                    return;
                } else {
                    logger.error("Failed to rotate to any DNS endpoint for {}, selecting new proxy", current.getName());
                }
            }
            
            selectInitialProxy();
        } else {
            logger.debug("Current proxy {} is healthy (latency: {}ms)", current.getName(), result.getLatencyMs());
        }
    }

    private Map<ProxyClient, ProxyTestResult> testProxies(List<ProxyConfig> proxies) {
        Map<ProxyClient, ProxyTestResult> results = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        
        for (ProxyConfig config : proxies) {
            if (!config.isEnabled()) {
                continue;
            }
            
            futures.add(testExecutor.submit(() -> {
                try {
                    ProxyClient client = activeClients.computeIfAbsent(config.getName(), name -> {
                        ProxyClient newClient = ProxyClientFactory.createClient(config);
                        int retries = 3;
                        Exception lastException = null;
                        
                        for (int attempt = 1; attempt <= retries; attempt++) {
                            try {
                                logger.debug("Starting proxy client {} (attempt {}/{})", name, attempt, retries);
                                newClient.start();
                                logger.info("Successfully started proxy client {} on attempt {}", name, attempt);
                                return newClient;
                            } catch (Exception e) {
                                lastException = e;
                                logger.warn("Failed to start proxy client {} on attempt {}/{}: {}", name, attempt, retries, e.getMessage());
                                
                                if (attempt < retries) {
                                    try {
                                        Thread.sleep(2000); // Wait 2 seconds before retry
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }
                        }
                        
                        logger.error("Failed to start proxy client {} after {} attempts", name, retries, lastException);
                        return null;
                    });
                    
                    // Note: Don't test here, test after all futures complete
                    // This ensures we wait for slow-starting proxies
                    if (client != null && !client.isRunning()) {
                        logger.warn("Proxy client {} exists but is not running", client.getName());
                    }
                } catch (Exception e) {
                    logger.error("Error testing proxy {}", config.getName(), e);
                }
            }));
        }
        
        for (Future<?> future : futures) {
            try {
                future.get(300, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Timeout waiting for proxy test (exceeded 300 seconds), will check if any proxies are running", e);
            } catch (Exception e) {
                logger.error("Error waiting for proxy test", e);
            }
        }
        
        // After all futures complete, test all running proxies
        logger.info("Testing all running proxies after startup phase");
        for (ProxyConfig config : proxies) {
            if (!config.isEnabled()) {
                continue;
            }
            
            ProxyClient client = activeClients.get(config.getName());
            if (client != null && client.isRunning()) {
                if (!results.containsKey(client)) {
                    logger.info("Testing proxy {} that finished starting", client.getName());
                    try {
                        ProxyTestResult result = proxyTester.test(client.getEndpoint());
                        results.put(client, result);
                        logger.info("Test result for {}: success={}, latency={}ms", 
                                  client.getName(), result.isSuccess(), result.getLatencyMs());
                        
                        // If SOCKS test fails and this is a DNS-tested SlipStream client, try rotating immediately
                        if (!result.isSuccess() && client instanceof DnsTestedSlipStreamProxyClient) {
                            DnsTestedSlipStreamProxyClient dnsClient = (DnsTestedSlipStreamProxyClient) client;
                            logger.warn("SOCKS test failed for {}, attempting DNS rotation", client.getName());
                            
                            if (dnsClient.rotateToNextDnsEndpoint()) {
                                logger.info("Successfully rotated to next DNS endpoint for {}, retesting", client.getName());
                                // Retest after rotation
                                ProxyTestResult retestResult = proxyTester.test(client.getEndpoint());
                                results.put(client, retestResult);
                                logger.info("Retest result for {}: success={}, latency={}ms", 
                                          client.getName(), retestResult.isSuccess(), retestResult.getLatencyMs());
                            } else {
                                logger.error("Failed to rotate to any working DNS endpoint for {}", client.getName());
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error testing running proxy {}", client.getName(), e);
                    }
                } else {
                    logger.debug("Proxy {} already tested", client.getName());
                }
            } else if (client != null) {
                logger.warn("Proxy {} exists but is not running", client.getName());
            } else {
                logger.debug("Proxy {} not started yet", config.getName());
            }
        }
        
        logger.info("Proxy testing complete, {} results collected", results.size());
        
        return results;
    }

    private ProxyClient selectBestProxy(Map<ProxyClient, ProxyTestResult> results) {
        return results.entrySet().stream()
            .filter(entry -> entry.getValue().isSuccess())
            .min(Comparator.comparingLong(entry -> entry.getValue().getLatencyMs()))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private void switchToProxy(ProxyClient newProxy) {
        ProxyClient oldProxy = selectedProxy.getAndSet(newProxy);
        if (oldProxy != null && oldProxy != newProxy) {
            logger.info("Switched from proxy {} to {}", oldProxy.getName(), newProxy.getName());
        } else {
            logger.info("Selected proxy: {}", newProxy.getName());
        }
    }

    private void stopAllClients() {
        for (ProxyClient client : activeClients.values()) {
            try {
                client.stop();
            } catch (Exception e) {
                logger.error("Error stopping proxy client {}", client.getName(), e);
            }
        }
        activeClients.clear();
    }

    public ProxyClient getSelectedProxy() {
        return selectedProxy.get();
    }
}
