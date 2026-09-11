# Release guide

`minekot-inspections` must be released before `minekot-rules`, `minekot-toolchain`, or `minekot-toolkit` can consume a stable dynamic inspection generation.

## One-time repository setup

1. Create public repository `MineKotLang/minekot-inspections` with default branch `master`.
2. Add repository remote as `origin`.
3. Create GitHub environment `production`.
4. Add `MINEKOT_MAVEN_USERNAME` and `MINEKOT_MAVEN_PASSWORD` as environment secrets.
5. Protect `master`; require `Build and check` before merge.
6. Protect `v1.*` tags so only maintainers can create release tags.

Credentials are read only by release workflow and Gradle publishing tasks. Never store them in repository files, Gradle properties, logs, or build artifacts.

## Local release gate

Run:

```bash
./gradlew clean check apiCheck --warning-mode=fail --no-scan --no-daemon --no-configuration-cache
```

`check` stages all five Maven publications and verifies their main, source, and Javadoc JARs. It also verifies that IntelliJ adapter is published as canonical JAR and loader runtime private classifier contains relocated Sigstore code without bundled Kotlin classes.

## Release procedure

1. Merge release commit into `master` after required CI succeeds.
2. Create annotated tag such as `v1.0.0` at exact `master` head.
3. Push tag.
4. Wait for `Release inspections` workflow to finish.
5. Resolve every module from a clean external Gradle consumer.
6. Pin released version in `minekot-rules`; run its minimum/current core linkage matrix.
7. Release signed rules generation.
8. Pin exact rules tag and manifest digest in toolchain and toolkit.

Release workflow rejects non-v1 semantic tags, tags away from current `master`, partial Maven releases, and remote bytes differing from locally verified staged publications. Reruns accept only byte-identical existing Maven artifacts.

## Published modules

- `org.minekot.inspections:minekot-inspections-core`
- `org.minekot.inspections:minekot-inspections-detekt`
- `org.minekot.inspections:minekot-inspections-idea`
- `org.minekot.inspections:minekot-inspections-loader`
- `org.minekot.inspections:minekot-inspections-loader-runtime`
