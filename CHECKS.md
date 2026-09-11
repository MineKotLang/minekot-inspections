# Inspection infrastructure roadmap

This roadmap owns shared contracts and host adapters. Concrete policy checks and correction behavior belong to [`minekot-rules`](https://github.com/MineKotLang/minekot-rules/blob/master/CHECKS.md). Gradle execution belongs to `minekot-toolchain`; IntelliJ activation and user experience belong to `minekot-toolkit`.

## Core SPI

- [x] Freeze SPI-major-1 descriptors, findings, guarded text edits, sessions, catalogs, and policy types.
- [x] Add catalog-owned safe activation, lifecycle, replacement, and recommended-severity defaults without changing v1 constructors.
- [x] Expose resolved call identity, richer immutable call details, expression facts, outer-receiver classification, and symbol availability as optional capabilities.
- [x] Preserve old catalog behavior when additive capabilities are absent.
- [x] Validate identities, options, correction guards, catalog defaults, and ABI declarations.

## Detekt adapter

- [x] Discover exactly one catalog from the selected rules generation.
- [x] Apply shared policy and catalog activation defaults before constructing a file-scoped session.
- [x] Supply host-owned call, receiver, expression-context, and symbol-availability facts.
- [x] Merge non-conflicting guarded corrections deterministically and reject stale or overlapping edits.

## IntelliJ adapter

- [x] Register one static dynamic inspection instead of one class per downloaded rule.
- [x] Supply host-owned Analysis API facts without returning analysis objects.
- [x] Convert pure findings into native diagnostics, suppressions, severities, and preview-safe quick fixes.
- [x] Drain active sessions, quarantine crashing rules, close retired generations, and retain no downloaded object in diagnostics.

## Loader

- [x] Keep loader API PSI-free.
- [x] Verify release identity, manifest signature, size, digest, compatibility, and forbidden JAR namespaces before class definition.
- [x] Use content-addressed storage, atomic promotion, process locks, leases, quarantine, activation records, and last-known-good state.
- [x] Preserve verified artifacts offline while requiring online-first verification for new artifacts.

## Remaining release evidence

- [ ] Verify first real keyless release against Fulcio and Rekor.
- [ ] Run minimum/current published-core linkage in rules CI.
- [ ] Exercise same signed generation through released toolchain and toolkit hosts.
