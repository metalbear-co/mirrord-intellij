# CLAUDE.md

Context for Claude Code when working with the mirrord IntelliJ plugin.

## Quick Reference

```bash
# Build plugin
./gradlew buildPlugin

# Run IDE with plugin loaded (for debugging)
./gradlew runIde

# Lint Kotlin code
./gradlew ktlintCheck

# Run E2E tests (downloads PyCharm, runs headless)
./gradlew test

# Run IDE in test mode (interactive, localhost:8082)
./gradlew runIdeForUiTests
```

## Overview

mirrord-intellij is a Kotlin-based IntelliJ plugin that integrates mirrord with JetBrains IDEs. It intercepts run/debug configurations to inject mirrord environment variables, letting developers run local processes in the context of their Kubernetes cluster.

- **Plugin ID:** com.metalbear.mirrord
- **Language:** Kotlin (1.8.22, JVM target 17)
- **Build system:** Gradle (Kotlin DSL)
- **Platform:** IntelliJ 2024.1+ (also supports PyCharm, GoLand, RubyMine, Rider, WebStorm)
- **Compiler flag:** `allWarningsAsErrors = true`

## Architecture

**Multi-module Gradle build:**

```
modules/
├── core/                    # Shared plugin logic (~26 Kotlin classes)
└── products/                # IDE-specific implementations
    ├── idea/                # IntelliJ IDEA (Java debugging)
    ├── pycharm/             # PyCharm (Python)
    ├── goland/              # GoLand (Go)
    ├── rubymine/            # RubyMine (Ruby)
    ├── nodejs/              # WebStorm/Node.js
    ├── rider/               # Rider (.NET)
    ├── tomcat/              # Tomcat integration
    └── bazel/               # Bazel build support
```

Each product module has minimal IDE-specific glue code and depends on `:mirrord-core`.

**Key core classes** (`modules/core/src/main/kotlin/com/metalbear/mirrord/`):

| Class | Purpose |
|-------|---------|
| `MirrordProjectService` | Project-scoped service managing plugin state and lifecycle |
| `MirrordExecManager` | Coordinates mirrord execution, target selection dialog |
| `MirrordApi` | Wraps mirrord CLI (list targets, verify config, container run) |
| `MirrordConfigAPI` | Config file parsing/validation, JSON schema support, file watcher |
| `MirrordBinaryManager` | Binary discovery and download from GitHub releases |
| `MirrordSettingsState` | Application-level persisted settings (XML serialization) |
| `MirrordExecDialog` | Target selection UI (pods/deployments with filtering) |
| `MirrordDropDown` | Toolbar action menu (enable/disable, settings, docs) |

**IDE-specific integration:** Each product module provides a `RunConfigurationExtension` that intercepts run configs for its IDE type (e.g., `IdeaRunConfigurationExtension` for Java).

## Code Style

- **Kotlin** with strict compilation (`allWarningsAsErrors = true`)
- **ktlint** for linting (check: `./gradlew ktlintCheck`)
- 4-space indentation (ktlint default)
- No wildcard imports (disabled in `.editorconfig`)
- Suppress annotations for IntelliJ experimental APIs: `@Suppress("UnstableApiUsage")`
- Data classes with Gson `@SerializedName` for JSON config parsing
- Extension functions for fluent APIs

## Dependencies

**Core:**
- `com.google.code.gson:gson` - JSON parsing
- `com.github.zafarkhaja:java-semver` - Semantic versioning
- `kotlinx-collections-immutable` - Immutable collections

**Testing:**
- `com.intellij.remoterobot:remote-robot` - UI test framework
- JUnit 5
- Video recording on test failure

## Testing

- **E2E UI tests** using IntelliJ Remote Robot framework
- Tests download PyCharm Community 2024.1, install plugin, run headless via Xvfb
- Test flow: open project, enable mirrord, start debugging, select pod, verify breakpoint hit
- Test workspace: `test-workspace/` (Python app)
- Sample K8s manifests: `sample/kubernetes/app.yaml`
- Video recording on failure (ffmpeg)
- Key env vars: `POD_TO_SELECT`, `KUBE_SERVICE`, `MIRRORD_TELEMETRY=false`

## Plugin Configuration

Main descriptor: `src/main/resources/META-INF/plugin.xml`

**Optional dependencies** (each has its own extension XML):
- `com.intellij.modules.java` (mirrord-idea.xml)
- `com.intellij.modules.python` (mirrord-pycharm.xml)
- `org.jetbrains.plugins.go` (mirrord-goland.xml)
- `com.intellij.modules.ruby` (mirrord-rubymine.xml)
- NodeJS (mirrord-js.xml)
- `com.intellij.modules.rider` (mirrord-rider.xml)

## Changelog

Uses **towncrier** for changelog management. Fragments in `changelog.d/`. CI checks for fragment on every PR.

## CI/CD

- **ci.yaml:** towncrier check, markdown lint, ktlint, E2E tests
- **release.yaml:** On git tag, builds plugin, signs, publishes to JetBrains Marketplace
- **plugin_verifier.yaml:** Checks compatibility with IDE versions
- **reusable_e2e.yaml:** Shared E2E workflow (used by both this repo and main mirrord repo)

## Key Patterns

- **Service layer:** `MirrordProjectService` (project-scoped) and `MirrordSettingsState` (application-scoped)
- **Extension points:** RunConfigurationExtension for intercepting run configs per IDE
- **Async work:** ProgressManager for long-running operations (target listing, binary download)
- **Error handling:** MirrordError class with rich messages and help text
- **Custom Delve binaries:** `bin/macos/` contains custom-built Go debugger binaries for macOS
