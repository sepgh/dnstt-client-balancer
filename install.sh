#!/bin/bash
#
# SOCKS Proxy Load Balancer - Installation Script
# Supports: Debian/Ubuntu, Fedora, CentOS/RHEL, Arch Linux, openSUSE
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/sepgh/dnstt-client-balancer/main/install.sh | sudo bash
#   wget -qO- https://raw.githubusercontent.com/sepgh/dnstt-client-balancer/main/install.sh | sudo bash
#
set -e

# Configuration
INSTALL_DIR="/opt/proxy-balancer"
CONFIG_DIR="/etc/proxy-balancer"
LOG_DIR="/var/log/proxy-balancer"
SERVICE_USER="proxy-balancer"
SERVICE_GROUP="proxy-balancer"
REQUIRED_JAVA_VERSION="21"
REPO_URL="https://github.com/sepgh/dnstt-client-balancer"
LISTEN_PORT="${LISTEN_PORT:-1080}"
UPSTREAM_PORT="${UPSTREAM_PORT:-9080}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check if running as root
check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "This script must be run as root (use sudo)"
        exit 1
    fi
}

# Detect Linux distribution
detect_distro() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        DISTRO=$ID
        DISTRO_FAMILY=$ID_LIKE
    elif [ -f /etc/redhat-release ]; then
        DISTRO="rhel"
    elif [ -f /etc/debian_version ]; then
        DISTRO="debian"
    else
        DISTRO="unknown"
    fi
    log_info "Detected distribution: $DISTRO"
}

# Get package manager
get_package_manager() {
    if command -v apt-get &> /dev/null; then
        PKG_MANAGER="apt"
    elif command -v dnf &> /dev/null; then
        PKG_MANAGER="dnf"
    elif command -v yum &> /dev/null; then
        PKG_MANAGER="yum"
    elif command -v pacman &> /dev/null; then
        PKG_MANAGER="pacman"
    elif command -v zypper &> /dev/null; then
        PKG_MANAGER="zypper"
    else
        log_error "No supported package manager found"
        exit 1
    fi
    log_info "Using package manager: $PKG_MANAGER"
}

# Check Java version
check_java_version() {
    if command -v java &> /dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
        if [[ "$JAVA_VER" -ge "$REQUIRED_JAVA_VERSION" ]]; then
            log_success "Java $JAVA_VER is installed (required: $REQUIRED_JAVA_VERSION+)"
            return 0
        else
            log_warn "Java $JAVA_VER found, but Java $REQUIRED_JAVA_VERSION+ is required"
            return 1
        fi
    else
        log_warn "Java is not installed"
        return 1
    fi
}

# Install Java based on distribution
install_java() {
    log_info "Installing Java $REQUIRED_JAVA_VERSION..."
    
    case $PKG_MANAGER in
        apt)
            apt-get update -qq
            apt-get install -y openjdk-21-jre-headless || {
                # Fallback: Add Adoptium repository for older Ubuntu/Debian
                log_info "Adding Adoptium repository..."
                apt-get install -y wget apt-transport-https gnupg
                wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
                echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/adoptium.list
                apt-get update -qq
                apt-get install -y temurin-21-jre
            }
            ;;
        dnf)
            dnf install -y java-21-openjdk-headless || {
                log_info "Adding Adoptium repository..."
                cat > /etc/yum.repos.d/adoptium.repo << 'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF
                dnf install -y temurin-21-jre
            }
            ;;
        yum)
            yum install -y java-21-openjdk-headless || {
                log_info "Adding Adoptium repository..."
                cat > /etc/yum.repos.d/adoptium.repo << 'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF
                yum install -y temurin-21-jre
            }
            ;;
        pacman)
            pacman -Sy --noconfirm jre21-openjdk-headless || pacman -Sy --noconfirm jdk21-openjdk
            ;;
        zypper)
            zypper --non-interactive install java-21-openjdk-headless || {
                log_info "Adding Adoptium repository..."
                zypper addrepo --gpgcheck-strict -f https://packages.adoptium.net/artifactory/rpm/opensuse/$(. /etc/os-release && echo $VERSION_ID)/$(uname -m) adoptium
                zypper --non-interactive install temurin-21-jre
            }
            ;;
    esac
    
    # Verify installation
    if check_java_version; then
        log_success "Java installed successfully"
    else
        log_error "Failed to install Java $REQUIRED_JAVA_VERSION"
        exit 1
    fi
}

# Install build dependencies
install_build_deps() {
    log_info "Installing build dependencies..."
    
    case $PKG_MANAGER in
        apt)
            apt-get update -qq
            apt-get install -y git maven curl
            ;;
        dnf)
            dnf install -y git maven curl
            ;;
        yum)
            yum install -y git curl
            # Maven might not be in default repos, install manually
            if ! command -v mvn &> /dev/null; then
                log_info "Installing Maven manually..."
                curl -fsSL https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz | tar xz -C /opt
                ln -sf /opt/apache-maven-3.9.6/bin/mvn /usr/local/bin/mvn
            fi
            ;;
        pacman)
            pacman -Sy --noconfirm git maven curl
            ;;
        zypper)
            zypper --non-interactive install git maven curl
            ;;
    esac
}

# Clone and build project
build_project() {
    log_info "Building project..."
    
    TEMP_DIR=$(mktemp -d)
    cd "$TEMP_DIR"
    
    log_info "Cloning repository..."
    git clone --depth 1 "$REPO_URL" repo
    cd repo
    
    log_info "Building with Maven (this may take a few minutes)..."
    mvn clean package -DskipTests -q
    
    if [[ ! -f "target/proxy-balancer.jar" ]]; then
        log_error "Build failed: proxy-balancer.jar not found"
        exit 1
    fi
    
    log_success "Build completed successfully"
    
    # Store path for later
    BUILD_DIR="$TEMP_DIR/repo"
}

# Create system user
create_user() {
    if id "$SERVICE_USER" &>/dev/null; then
        log_info "User $SERVICE_USER already exists"
    else
        log_info "Creating system user: $SERVICE_USER"
        useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
    fi
}

# Install files
install_files() {
    log_info "Installing files..."
    
    # Create directories
    mkdir -p "$INSTALL_DIR"
    mkdir -p "$CONFIG_DIR"
    mkdir -p "$LOG_DIR"
    
    # Copy JAR file
    cp "$BUILD_DIR/target/proxy-balancer.jar" "$INSTALL_DIR/"
    
    # Create default config (forward to SOCKS on port 9080)
    cat > "$CONFIG_DIR/config.yaml" << EOF
# SOCKS Proxy Load Balancer Configuration
# Generated by install.sh

# SOCKS server binding configuration
listen_host: "127.0.0.1"
listen_port: $LISTEN_PORT

# Health check intervals (in seconds)
health_check_interval_seconds: 30
current_proxy_check_interval_seconds: 10

# Connection settings
connection_timeout_ms: 5000
test_url: "http://www.google.com"
test_rounds: 3

# Logging
log_subprocess_output: false

# Proxy configurations
proxies:
  # Default: Forward to local SOCKS proxy on port $UPSTREAM_PORT
  - type: "direct"
    name: "local-socks"
    enabled: true
    config:
      host: "127.0.0.1"
      port: $UPSTREAM_PORT
EOF
    
    # Copy systemd service file
    cp "$BUILD_DIR/systemd/proxy-balancer.service" /etc/systemd/system/
    
    # Set permissions
    chown -R "$SERVICE_USER:$SERVICE_GROUP" "$INSTALL_DIR"
    chown -R "$SERVICE_USER:$SERVICE_GROUP" "$CONFIG_DIR"
    chown -R "$SERVICE_USER:$SERVICE_GROUP" "$LOG_DIR"
    chmod 750 "$INSTALL_DIR"
    chmod 750 "$CONFIG_DIR"
    chmod 640 "$CONFIG_DIR/config.yaml"
    chmod 750 "$LOG_DIR"
    
    log_success "Files installed successfully"
}

# Setup systemd service
setup_service() {
    log_info "Setting up systemd service..."
    
    systemctl daemon-reload
    systemctl enable proxy-balancer.service
    
    log_success "Service enabled (will start on boot)"
}

# Cleanup
cleanup() {
    if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
        log_info "Cleaning up temporary files..."
        rm -rf "$TEMP_DIR"
    fi
}

# Print summary
print_summary() {
    echo ""
    echo "=============================================="
    log_success "Installation completed successfully!"
    echo "=============================================="
    echo ""
    echo "Installation paths:"
    echo "  - JAR file:    $INSTALL_DIR/proxy-balancer.jar"
    echo "  - Config file: $CONFIG_DIR/config.yaml"
    echo "  - Log dir:     $LOG_DIR"
    echo "  - Service:     /etc/systemd/system/proxy-balancer.service"
    echo ""
    echo "Default configuration:"
    echo "  - Listens on:  127.0.0.1:$LISTEN_PORT"
    echo "  - Forwards to: 127.0.0.1:$UPSTREAM_PORT (SOCKS proxy)"
    echo ""
    echo "Service commands:"
    echo "  sudo systemctl start proxy-balancer    # Start the service"
    echo "  sudo systemctl stop proxy-balancer     # Stop the service"
    echo "  sudo systemctl restart proxy-balancer  # Restart the service"
    echo "  sudo systemctl status proxy-balancer   # Check status"
    echo "  sudo journalctl -u proxy-balancer -f   # View logs"
    echo ""
    echo "Edit configuration:"
    echo "  sudo nano $CONFIG_DIR/config.yaml"
    echo "  sudo systemctl restart proxy-balancer"
    echo ""
    log_info "Start the service with: sudo systemctl start proxy-balancer"
    echo ""
}

# Uninstall function
uninstall() {
    log_info "Uninstalling proxy-balancer..."
    
    # Stop and disable service
    systemctl stop proxy-balancer.service 2>/dev/null || true
    systemctl disable proxy-balancer.service 2>/dev/null || true
    
    # Remove files
    rm -f /etc/systemd/system/proxy-balancer.service
    rm -rf "$INSTALL_DIR"
    rm -rf "$CONFIG_DIR"
    rm -rf "$LOG_DIR"
    
    # Remove user
    userdel "$SERVICE_USER" 2>/dev/null || true
    
    systemctl daemon-reload
    
    log_success "Uninstallation completed"
}

# Main installation flow
main() {
    echo ""
    echo "=============================================="
    echo "  SOCKS Proxy Load Balancer Installer"
    echo "=============================================="
    echo ""
    
    # Handle uninstall flag
    if [[ "$1" == "--uninstall" || "$1" == "-u" ]]; then
        check_root
        uninstall
        exit 0
    fi
    
    # Handle help flag
    if [[ "$1" == "--help" || "$1" == "-h" ]]; then
        echo "Usage: $0 [OPTIONS]"
        echo ""
        echo "Options:"
        echo "  --uninstall, -u    Uninstall proxy-balancer"
        echo "  --help, -h         Show this help message"
        echo ""
        echo "Environment variables:"
        echo "  LISTEN_PORT        Port to listen on (default: 1080)"
        echo "  UPSTREAM_PORT      Upstream SOCKS port (default: 9080)"
        echo ""
        echo "Examples:"
        echo "  curl -fsSL <url>/install.sh | sudo bash"
        echo "  LISTEN_PORT=8080 UPSTREAM_PORT=1080 sudo bash install.sh"
        echo ""
        exit 0
    fi
    
    check_root
    detect_distro
    get_package_manager
    
    # Check and install Java if needed
    if ! check_java_version; then
        install_java
    fi
    
    install_build_deps
    build_project
    create_user
    install_files
    setup_service
    cleanup
    print_summary
}

# Trap for cleanup on error
trap cleanup EXIT

# Run main
main "$@"
