# SOCKS Proxy Load Balancer

A high-performance SOCKS proxy load balancer written in Java that automatically selects and forwards traffic to the best available proxy backend. Supports multiple proxy types including direct SOCKS proxies, process-based proxies, SlipStream with DNS testing, and custom implementations.

**Important Note**: This project is not a full SOCKS implementation that negotiates/chooses between SOCKS versions. It mostly acts as a TCP forwarder / balancer in front of one or more upstream proxy endpoints.
The goal of the project however is to host both socks and http proxy as listener. As a result, for now, despite the word "SOCKS" being used everywhere in the documentation, the project actually forwards TCP traffic to any upstream proxy server. But the health check and tests only support "socks" protocol.

## Features

- **Automatic Proxy Selection**: Continuously monitors proxy health and automatically switches to the best performing proxy
- **Multiple Proxy Types**: Support for:
  - Direct SOCKS proxies
  - Process-based proxies (any command-line proxy tool)
  - SlipStream with certificate validation
  - DNS-tested SlipStream with automatic DNS resolver selection
- **Health Checking**: Periodic health checks with configurable intervals and automatic failover
- **Multi-Round Testing**: Configurable test rounds for accurate average latency measurements
- **DNS Resolver Testing**: Automatic DNS endpoint testing and selection for SlipStream
- **Port Placeholder**: Dynamic port injection into subprocess commands
- **Thread-Safe Configuration**: Dynamic configuration management with thread-safe access
- **Cross-Platform**: Works on Linux, Windows, and macOS
- **GraalVM Native Image**: Build native executables for fast startup and low memory footprint
- **Extensible Architecture**: Clean interfaces for adding custom proxy client implementations

## Architecture

The application consists of several key components:

1. **Configuration Manager**: Thread-safe configuration management with support for dynamic updates
2. **Proxy Clients**: Abstract interface for different proxy types (direct, process-based)
3. **Health Checker**: Monitors proxy health and selects the best available proxy
4. **Proxy Tester**: Tests SOCKS proxies using actual connections
5. **SOCKS Proxy Server**: Forwards incoming SOCKS connections to the selected backend proxy



## Installation (Linux)

### Quick Install (One Command)

Install on any Linux server with a single command:

```bash
# Using curl
curl -fsSL https://raw.githubusercontent.com/sepgh/dnstt-client-balancer/main/install.sh | sudo bash

# Using wget
wget -qO- https://raw.githubusercontent.com/sepgh/dnstt-client-balancer/main/install.sh | sudo bash
```

The installer automatically:
- Detects your Linux distribution (Debian/Ubuntu, Fedora, CentOS/RHEL, Arch, openSUSE)
- If your CPU arch is `amd` based, it will use built binary from latest release.
- If your CPU arch is `arm` based, it will check for java 21+ installation and installs it if missing
- Sets `amd` systems to use binary app and `arm` systems to jus `java -jar` format.
- Creates a dedicated system user (`proxy-balancer`)
- Installs files to standard locations
- Sets up a production-ready systemd service

### Installation Paths

| Component | Path |
|-----------|------|
| JAR file | `/opt/proxy-balancer/proxy-balancer.jar` |
| Config file | `/etc/proxy-balancer/config.yaml` |
| Log directory | `/var/log/proxy-balancer` |
| Systemd service | `/etc/systemd/system/proxy-balancer.service` |

### Default Configuration

The default configuration:
- Listens on `127.0.0.1:1080`
- Forwards to a SOCKS proxy on `127.0.0.1:9080`

Customize ports during installation:

```bash
LISTEN_PORT=8080 UPSTREAM_PORT=1080 curl -fsSL .../install.sh | sudo bash
```

### Service Management

```bash
# Start the service
sudo systemctl start proxy-balancer

# Stop the service
sudo systemctl stop proxy-balancer

# Restart after config changes
sudo systemctl restart proxy-balancer

# Check status
sudo systemctl status proxy-balancer

# View logs
sudo journalctl -u proxy-balancer -f

# Enable/disable auto-start on boot
sudo systemctl enable proxy-balancer
sudo systemctl disable proxy-balancer
```

### Edit Configuration

```bash
sudo nano /etc/proxy-balancer/config.yaml
sudo systemctl restart proxy-balancer
```

### Uninstall

```bash
curl -fsSL https://raw.githubusercontent.com/sepgh/dnstt-client-balancer/main/install.sh | sudo bash -s -- --uninstall
```

### Manual Installation

If you prefer manual installation:

1. **Install Java 21+**
2. **Build the project**: _read below_
3. **Copy files**:
   ```bash
   sudo mkdir -p /opt/proxy-balancer /etc/proxy-balancer
   sudo cp target/proxy-balancer.jar /opt/proxy-balancer/
   sudo cp config.example.yaml /etc/proxy-balancer/config.yaml
   sudo cp systemd/proxy-balancer.service /etc/systemd/system/
   ```
4. **Create user**: `sudo useradd --system --no-create-home proxy-balancer`
5. **Set permissions** and **enable service**:
   ```bash
   sudo chown -R proxy-balancer:proxy-balancer /opt/proxy-balancer /etc/proxy-balancer
   sudo systemctl daemon-reload
   sudo systemctl enable --now proxy-balancer
   ```


## Building

### Standard JAR (All Platforms)

```bash
mvn clean package
```

This creates `target/proxy-balancer.jar` - a fat JAR with all dependencies included.

### GraalVM Native Image

Requires GraalVM with native-image installed:

```bash
mvn clean package -Pnative
```

This creates a platform-specific native binary in `target/proxy-balancer` (or `proxy-balancer.exe` on Windows).

## Configuration

Create a `config.yaml` file with your proxy settings:

```yaml
listen_host: "127.0.0.1"
listen_port: 1080

health_check_interval_seconds: 30
current_proxy_check_interval_seconds: 10
connection_timeout_ms: 5000
test_url: "http://www.google.com"

proxies:
  - type: "direct"
    name: "public-proxy-1"
    enabled: true
    config:
      host: "proxy.example.com"
      port: 1080

  - type: "xray"
    name: "xray-vless-1"
    enabled: true
    config:
      command: "/usr/local/bin/xray"
      args:
        - "run"
        - "-c"
        - "/etc/xray/config.json"
      host: "127.0.0.1"
      port: 10808
      startup_delay_ms: 3000
```

### Configuration Options

#### Main Configuration

- `listen_host`: IP address to bind the SOCKS server (default: 127.0.0.1)
- `listen_port`: Port to bind the SOCKS server (default: 1080)
- `health_check_interval_seconds`: How often to check all proxies (default: 30)
- `current_proxy_check_interval_seconds`: How often to check the current proxy (default: 10)
- `connection_timeout_ms`: Connection timeout for proxy tests (default: 5000)
- `test_url`: URL to test proxy connectivity (default: http://www.google.com)
- `test_rounds`: Number of test rounds for averaging latency (default: 1)
- `log_subprocess_output`: Enable subprocess output logging (default: false, set true for debugging)
- `network_interface`: Network interface to monitor (optional, examples: "eth0" (Linux), "en0" (macOS), "Ethernet" (Windows))

#### Proxy Types

##### Direct Proxy

For existing SOCKS proxies:

```yaml
- type: "direct"
  name: "my-proxy"
  enabled: true
  config:
    host: "proxy.example.com"
    port: 1080
```

##### Process-Based Proxy

For any proxy tool that needs to be started as a subprocess. Use `{PORT}` placeholder for dynamic port injection:

```yaml
- type: "process"
  name: "my-xray-proxy"
  enabled: true
  config:
    command: "/usr/local/bin/xray"
    args:
      - "run"
      - "-c"
      - "/path/to/config.json"
    host: "127.0.0.1"
    port: 10808
    startup_delay_ms: 3000
    working_dir: "/path/to/working/dir"  # optional
    env:  # optional environment variables
      LOG_LEVEL: "info"
```

**Port Placeholder Example:**

```yaml
- type: "process"
  name: "dnstt-client"
  enabled: true
  config:
    command: "/usr/local/bin/dnstt-client"
    args:
      - "-doh"
      - "https://dns.example.com/dns-query"
      - "-pubkey-file"
      - "/etc/dnstt/pubkey"
      - "example.com"
      - "127.0.0.1:{PORT}"  # {PORT} will be replaced with actual port
    host: "127.0.0.1"
    port: 7000
    startup_delay_ms: 2000
```

##### SlipStream Proxy

For SlipStream with certificate validation:

```yaml
- type: "slipstream"
  name: "slipstream-proxy"
  enabled: true
  config:
    binary_path: "/usr/local/bin/slipstream"
    resolver_ip: "8.8.8.8"  # DNS resolver IP
    resolver_port: 53        # DNS resolver port (default: 53)
    domain: "example.com"    # Target domain
    cert_path: "/etc/slipstream/cert.pem"  # Certificate file (must exist)
    host: "127.0.0.1"
    port: 8000
    startup_delay_ms: 2000
```

**Configuration Requirements:**
- `binary_path`: Must exist and be executable (on Linux/macOS)
- `cert_path`: Must exist and be readable
- `domain`: Required
- `resolver_ip`: Defaults to 127.0.0.1
- `resolver_port`: Defaults to 53

##### DNS-Tested SlipStream Proxy

Automatically tests multiple DNS resolvers and selects the fastest one:

```yaml
- type: "dns-tested-slipstream"
  name: "smart-slipstream"
  enabled: true
  config:
    binary_path: "/usr/local/bin/slipstream"
    domain: "example.com"
    cert_path: "/etc/slipstream/cert.pem"
    host: "127.0.0.1"
    port: 8000
    startup_delay_ms: 2000
    
    # DNS endpoints to test (list format)
    dns_endpoints:
      - "8.8.8.8:53"
      - "1.1.1.1:53"
      - "9.9.9.9:53"
      - "208.67.222.222:53"
    
    # OR load from file (one endpoint per line)
    dns_endpoints_file: "/etc/slipstream/dns-servers.txt"
    
    # DNS testing configuration
    dns_test_timeout_ms: 3000
    dns_test_domain: "www.google.com"  # Domain to query for testing
```

**DNS Endpoints File Format (`dns-servers.txt`):**
```
# Google DNS
8.8.8.8:53
8.8.4.4:53

# Cloudflare DNS
1.1.1.1:53
1.0.0.1:53

# Quad9 DNS
9.9.9.9:53

# OpenDNS
208.67.222.222:53
208.67.220.220:53
```

**How it works:**
1. Tests all DNS endpoints by sending DNS queries
2. Sorts them by latency (fastest first)
3. Uses the fastest DNS resolver with SlipStream
4. Only starts if at least one DNS endpoint works

## Running

### With JAR (Any Platform)

```bash
# Linux/macOS
java -jar target/proxy-balancer.jar [config-path]

# Windows
java -jar target\proxy-balancer.jar [config-path]
```

If no config path is provided, it looks for `config.yaml` in the current directory.

### With Native Image

```bash
# Linux/macOS
./proxy-balancer [config-path]

# Windows
proxy-balancer.exe [config-path]
```

## Usage

Once running, configure your applications to use the SOCKS proxy at the configured `listen_host:listen_port` (default: `127.0.0.1:1080`).

The application will:
1. Start all enabled proxy clients
2. Test each proxy to find the best one
3. Forward all SOCKS traffic to the selected proxy
4. Continuously monitor proxy health
5. Automatically switch to a better proxy if the current one fails

## Extending

### Adding Custom Proxy Types

1. Create a new class extending `AbstractProxyClient`:

```java
public class MyCustomProxyClient extends AbstractProxyClient {
    public MyCustomProxyClient(ProxyConfig config) {
        super(config);
    }

    @Override
    public void start() throws Exception {
        // Initialize your proxy
        // Set this.endpoint to the SOCKS proxy endpoint
        setRunning(true);
    }

    @Override
    public void stop() throws Exception {
        // Clean up resources
        setRunning(false);
    }
}
```

2. Register it in `ProxyClientFactory`:

```java
case "mycustom" -> new MyCustomProxyClient(config);
```

### Dynamic Configuration Updates

The `ConfigurationManager` supports thread-safe dynamic updates:

```java
// Add a new proxy
configManager.addProxy(new ProxyConfig("direct", "new-proxy", config));

// Remove a proxy
configManager.removeProxy("proxy-name");

// Enable/disable a proxy
configManager.updateProxyEnabled("proxy-name", false);
```

## Logging

The application uses SLF4J with a simple logger. Configure logging by setting system properties:

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar target/dnstt-client-balancer-1.0-SNAPSHOT.jar
```

Log levels: `trace`, `debug`, `info`, `warn`, `error`


## License

See LICENSE file for details.

## Contributing

Contributions are welcome! Please ensure your code follows the existing architecture and includes appropriate documentation.
