# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

### Fixed

- Pass SHA-256 artifact digest bytes to `sigstore-java` when verifying standard manifest and stable-index bundles.

### Features

- Add optional catalog activation/lifecycle defaults plus immutable call, expression, and symbol-availability capabilities without changing frozen v1 constructors.
- Supply new semantic capabilities from both Detekt and IntelliJ adapters.

### Tests and documentation

- Cover default-policy fallback and invalid catalog metadata; document current modules, host boundaries, API declarations, and infrastructure roadmap ownership.
- Package and verify the static MineKot inspection description required by IntelliJ Inspection Settings and batch reports.
