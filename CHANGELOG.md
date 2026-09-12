# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

### Changed

- Update the verified build baseline to Gradle 9.7.1, Kotlin 2.4.20, IntelliJ IDEA 2026.1.5, Detekt 2.0.0-alpha.6, Sigstore Java 2.3.0, kotlinx.serialization 1.11.0, JUnit Platform 6.1.3, and current build plugins. IDEA 2026.2 remains excluded until JetBrains resolves plugin-test startup blocker IJPL-248701.

### Fixed

- Pass SHA-256 artifact digest bytes to `sigstore-java` when verifying standard manifest and stable-index bundles.

### Features

- Add optional catalog activation/lifecycle defaults plus immutable call, expression, and symbol-availability capabilities without changing frozen v1 constructors.
- Supply new semantic capabilities from both Detekt and IntelliJ adapters.

### Tests and documentation

- Cover default-policy fallback and invalid catalog metadata; document current modules, host boundaries, API declarations, and infrastructure roadmap ownership.
- Package and verify the static MineKot inspection description required by IntelliJ Inspection Settings and batch reports.
