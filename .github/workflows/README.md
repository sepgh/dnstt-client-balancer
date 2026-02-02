# GitHub Actions Workflows

This directory contains CI/CD workflows for the Proxy Load Balancer project.

## Workflows

### 1. Test Workflow (`test.yml`)

**Triggers:**
- Push to `main` or `develop` branches
- Pull requests to `main` branch

**What it does:**
- Sets up Java 21 with Maven
- Compiles the project
- Runs the integration test suite
- Uploads test results as artifacts

**Status Badge:**
```markdown
![Tests](https://github.com/sepgh/dnstt-client-balancer/workflows/Run%20Tests/badge.svg)
```

### 2. Release Workflow (`release.yml`)

**Triggers:**
- Push to `main` branch
- Push of version tags (e.g., `v1.0.0`)

**What it does:**
- Builds native binaries for multiple architectures:
  - **Linux AMD64** (x86_64) - Most common 64-bit systems
  - **Linux ARM64** (aarch64) - Raspberry Pi 4/5, ARM servers, Apple Silicon via Rosetta
  - **Linux x86** (i686) - 32-bit x86 systems
- Creates SHA256 checksums for each binary
- Generates release notes
- Creates a **draft release** on GitHub with all binaries

**Steps:**
1. Checkout code
2. Set up GraalVM 21 with native-image
3. Build project with Maven (tests skipped - they run in test workflow)
4. Compile native image for target architecture
5. Prepare and verify binary exists
6. Upload as artifact
7. Create SHA256 checksums
8. Generate release notes
9. Create draft release with all binaries

**Note**: Tests are skipped in the release workflow since they run separately in the test workflow. This prevents duplicate test runs and speeds up the release process.

**Build Matrix**:
| Platform | Architecture | Binary Name | Status |
|----------|-------------|-------------|--------|
| Linux | AMD64 (x86_64) | `proxy-balancer-linux-amd64` | ✅ Enabled |
| Linux | ARM64 (aarch64) | `proxy-balancer-linux-arm64` | ⚠️ Disabled* |
| Linux | x86 (i686) | `proxy-balancer-linux-x86` | ✅ Enabled |

*ARM64 requires GitHub Team/Enterprise plan or self-hosted runner. See `ARM64_BUILD.md` for setup instructions.

**Note:** The release is created as a **draft**, allowing you to:
1. Review the binaries
2. Edit release notes
3. Test the binaries
4. Publish when ready

## Setup Requirements

### For ARM64 Builds

GitHub Actions requires ARM64 runners. You have two options:

1. **GitHub-hosted ARM64 runners** (if available for your plan)
2. **Self-hosted ARM64 runner**

To use self-hosted runners, modify the `release.yml`:

```yaml
- os: linux
  arch: arm64
  runner: [self-hosted, linux, arm64]  # Use your self-hosted runner
  artifact_name: proxy-balancer-linux-arm64
```

### For x86 (32-bit) Builds

The workflow uses cross-compilation with musl libc for x86 builds. This requires:
- GraalVM with native-image
- musl-tools for static linking

If x86 builds fail, you can:
1. Remove the x86 entry from the matrix
2. Use a self-hosted x86 runner
3. Use Docker-based cross-compilation

## Customization

### Changing Build Targets

Edit the `matrix` section in `release.yml`:

```yaml
strategy:
  matrix:
    include:
      # Add or remove architectures here
      - os: linux
        arch: amd64
        runner: ubuntu-latest
        artifact_name: proxy-balancer-linux-amd64
```

### Adding macOS/Windows Builds

Add to the matrix:

```yaml
# macOS ARM64 (Apple Silicon)
- os: macos
  arch: arm64
  runner: macos-14
  artifact_name: proxy-balancer-macos-arm64

# macOS AMD64 (Intel)
- os: macos
  arch: amd64
  runner: macos-13
  artifact_name: proxy-balancer-macos-amd64

# Windows AMD64
- os: windows
  arch: amd64
  runner: windows-latest
  artifact_name: proxy-balancer-windows-amd64.exe
```

### Automatic Release Publishing

To automatically publish releases (instead of drafts), change in `release.yml`:

```yaml
- name: Create Draft Release
  uses: softprops/action-gh-release@v1
  with:
    draft: false  # Change from true to false
```

### Version Tagging

The workflow automatically generates version tags based on:
- Maven project version from `pom.xml`
- Timestamp for uniqueness

To use Git tags instead:

```yaml
on:
  push:
    tags:
      - 'v*'  # Trigger on version tags like v1.0.0
```

Then create releases with:
```bash
git tag v1.0.0
git push origin v1.0.0
```

## Secrets Required

The workflows use `GITHUB_TOKEN` which is automatically provided by GitHub Actions. No additional secrets are needed.

## Troubleshooting

### ARM64 Build Waiting Forever

**Error:** `Waiting for a runner to pick up this job...`

**Cause:** ARM64 runners are only available on GitHub Team/Enterprise plans, not the free tier.

**Solution:** 
1. **Recommended**: Set up a self-hosted ARM64 runner (see `ARM64_BUILD.md`)
2. Use Oracle Cloud free tier ARM instance as runner (free forever)
3. Upgrade to GitHub Team plan ($4/user/month)
4. Keep ARM64 disabled (current default)

### x86 Build Fails

**Error:** `native-image build failed`

**Solution:** 
1. The x86 build uses cross-compilation which may not work in all cases
2. Consider removing x86 from the matrix if not needed
3. Or use a dedicated x86 runner

### Native Image Build Fails

**Error:** `Out of memory` or `Build timeout`

**Solution:** Add to the build step:
```yaml
- name: Build native image
  run: |
    mvn -Pnative native:compile -DskipTests \
      -Dgraalvm.native-image.buildArgs="-J-Xmx4g"
```

## Testing Locally

Test the native build locally before pushing:

```bash
# Install GraalVM 21
sdk install java 21-graalvm

# Build native image
mvn clean package -Pnative -DskipTests

# Test the binary
./target/proxy-balancer --version
```

## Monitoring

View workflow runs at:
```
https://github.com/sepgh/dnstt-client-balancer/actions
```

Download artifacts from completed runs to test before release.
