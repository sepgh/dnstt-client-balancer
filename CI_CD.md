# CI/CD Documentation

This document describes the Continuous Integration and Continuous Deployment setup for the Proxy Load Balancer project.

## Overview

The project uses GitHub Actions for:
1. **Continuous Integration**: Running tests on every push/PR
2. **Continuous Deployment**: Building and releasing multi-architecture binaries

## Workflows

### Test Workflow

**File**: `.github/workflows/test.yml`

**Purpose**: Ensures code quality by running integration tests

**Triggers**:
- Push to `main` or `develop` branches
- Pull requests targeting `main`

**Steps**:
1. Checkout code
2. Set up Java 21 (Temurin distribution)
3. Build project with Maven
4. Run integration test suite
5. Upload test results as artifacts

**Duration**: ~3-5 minutes

### Release Workflow

**File**: `.github/workflows/release.yml`

**Purpose**: Build native binaries for multiple architectures and create releases

**Triggers**:
- Push to `main` branch
- Push of version tags (e.g., `v1.0.0`)

**Build Matrix**:
| Platform | Architecture | Binary Name |
|----------|-------------|-------------|
| Linux | AMD64 (x86_64) | `proxy-balancer-linux-amd64` |
| Linux | ARM64 (aarch64) | `proxy-balancer-linux-arm64` |
| Linux | x86 (i686) | `proxy-balancer-linux-x86` |

**Steps**:
1. Checkout code
2. Set up GraalVM 21 with native-image
3. Build project with Maven (tests skipped)
4. Compile native image for target architecture
5. Prepare and verify binary exists
6. Upload as artifact
7. Create SHA256 checksums
8. Generate release notes
9. Create draft release with all binaries

**Duration**: ~10-15 minutes per architecture (parallel execution)

**Note**: Tests are intentionally skipped in the release workflow because:
- Tests run separately in the dedicated test workflow
- Prevents duplicate test execution
- Speeds up release builds
- The test workflow must pass before merging to `main`

## Architecture Details

### AMD64 (x86_64)
- **Runner**: `ubuntu-latest`
- **Target**: Standard 64-bit Intel/AMD processors
- **Use Cases**: Most Linux servers, desktops, cloud VMs

### ARM64 (aarch64)
- **Runner**: `ubuntu-latest-arm64` (requires ARM64 runner)
- **Target**: 64-bit ARM processors
- **Use Cases**: 
  - Raspberry Pi 4/5
  - AWS Graviton instances
  - Oracle Cloud ARM instances
  - Other ARM-based servers

### x86 (i686)
- **Runner**: `ubuntu-latest` (cross-compilation)
- **Target**: 32-bit x86 processors
- **Use Cases**: Legacy systems, embedded devices
- **Note**: Uses static linking with musl libc

## Release Process

### Automatic (Recommended)

1. Merge changes to `main` branch
2. GitHub Actions automatically:
   - Runs tests
   - Builds all architectures
   - Creates draft release
3. Review the draft release
4. Edit release notes if needed
5. Publish the release

### Manual (Using Tags)

1. Create and push a version tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
2. GitHub Actions triggers release workflow
3. Review and publish draft release

## Binary Verification

Each release includes SHA256 checksums for verification:

```bash
# Download binary and checksum
wget https://github.com/USER/REPO/releases/download/TAG/proxy-balancer-linux-amd64
wget https://github.com/USER/REPO/releases/download/TAG/proxy-balancer-linux-amd64.sha256

# Verify
sha256sum -c proxy-balancer-linux-amd64.sha256
```

## Local Testing

Before pushing, test the build locally:

```bash
# Install GraalVM
sdk install java 21-graalvm

# Build native image
mvn clean package -Pnative -DskipTests

# Test
./target/proxy-balancer --version
```

## Troubleshooting

### Tests Fail in CI

1. Check test logs in GitHub Actions
2. Run tests locally:
   ```bash
   ./run-integration-test.sh
   ```
3. Fix issues and push again

### ARM64 Build Fails

**Issue**: No ARM64 runner available

**Solutions**:
1. Use GitHub's ARM64 runners (if available for your plan)
2. Set up self-hosted ARM64 runner
3. Remove ARM64 from build matrix temporarily

### x86 Build Fails

**Issue**: Cross-compilation issues

**Solutions**:
1. Remove x86 from matrix if not needed
2. Use Docker-based cross-compilation
3. Set up dedicated x86 runner

### Native Image Out of Memory

**Issue**: Build fails with OOM error

**Solution**: Increase heap size in workflow:
```yaml
-Dgraalvm.native-image.buildArgs="-J-Xmx4g"
```

## Monitoring

### View Workflow Runs
```
https://github.com/YOUR_USERNAME/dnstt-client-balancer/actions
```

### Add Status Badges

Add to `README.md`:

```markdown
![Tests](https://github.com/YOUR_USERNAME/dnstt-client-balancer/workflows/Run%20Tests/badge.svg)
![Release](https://github.com/YOUR_USERNAME/dnstt-client-balancer/workflows/Build%20and%20Release/badge.svg)
```

## Customization

### Add More Architectures

Edit `.github/workflows/release.yml` matrix:

```yaml
# macOS ARM64
- os: macos
  arch: arm64
  runner: macos-14
  artifact_name: proxy-balancer-macos-arm64

# Windows AMD64
- os: windows
  arch: amd64
  runner: windows-latest
  artifact_name: proxy-balancer-windows-amd64.exe
```

### Change Test Frequency

Modify triggers in `test.yml`:

```yaml
on:
  push:
    branches: [ main, develop, feature/* ]
  pull_request:
    branches: [ main, develop ]
  schedule:
    - cron: '0 0 * * *'  # Daily at midnight
```

### Auto-Publish Releases

Change in `release.yml`:

```yaml
with:
  draft: false  # Publish immediately
  prerelease: false
```

## Best Practices

1. **Always run tests locally** before pushing
2. **Review draft releases** before publishing
3. **Test binaries** from artifacts before release
4. **Use semantic versioning** for tags (v1.0.0, v1.1.0, etc.)
5. **Keep release notes updated** with changes
6. **Monitor workflow runs** for failures
7. **Update dependencies** regularly

## Security

- Workflows use `GITHUB_TOKEN` (automatically provided)
- No additional secrets required
- Binaries are built in isolated runners
- SHA256 checksums provided for verification
- All builds are reproducible

## Performance

- **Test workflow**: ~3-5 minutes
- **Release workflow**: ~15-25 minutes per architecture
- **Parallel builds**: All architectures build simultaneously
- **Caching**: Maven dependencies cached between runs

## Support

For issues with workflows:
1. Check workflow logs in GitHub Actions
2. Review `.github/workflows/README.md`
3. Test locally with same Java/GraalVM version
4. Open an issue with workflow logs
