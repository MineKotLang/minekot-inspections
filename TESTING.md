# Dynamic inspections test matrix

This matrix is release traceability for `minekot-inspections`, `minekot-rules`, `minekot-toolchain`, and `minekot-toolkit`. Local gates must pass before review. Remote-only gates require published artifacts or GitHub Actions and cannot be replaced by local success.

## Local contracts

| Requirement                                                                           | Owning tests                                                                                                      |
|:--------------------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------|
| Frozen core and loader ABI                                                            | Binary compatibility validator `apiCheck` tasks                                                                   |
| Pure findings, options, suppressions, and guarded edits                               | `InspectionValidationTest`, `InspectionPolicyTest`                                                                |
| Catalog activation, lifecycle, replacement, severity defaults, and legacy fallback   | `InspectionPolicyTest`, `MigratedRulesTest.catalog defaults activate only safe rules`                             |
| Every migrated rule flags and stays quiet on explicit fixtures                        | `MigratedRulesTest.every migrated rule has should flag and should not flag coverage`                              |
| Shared whitespace and Gradle performance contracts                                    | `MigratedRulesTest.whitespace formatting complete matrix`, `MigratedRulesTest.gradle performance complete matrix` |
| Focused repository, dependency, task, and test Gradle contracts                       | `MigratedRulesTest.focused gradle rules cover deterministic contracts`                                            |
| Main-thread semantic proof, MineKot helper availability, and safe correction          | `MigratedRulesTest.main thread blocking is semantic and correction reaches fixed point`, resolved API tests       |
| Deterministic corrections and second-run idempotence                                  | `MigratedRulesTest`, `StringTemplateBracesInspectionTest`                                                         |
| Detekt discovery, diagnostic identity, message, and offsets                           | `MineKotDetektRuleSetProviderTest`, `DetektAdapterIntegrationTest`                                                |
| IDEA diagnostic, packaged description, suppression, severity, preview, guarded fix   | `MineKotDynamicInspectionTest`                                                                                    |
| Generation draining, close, and rule quarantine                                       | `MineKotIdeaInspectionGenerationTest`                                                                             |
| Strict manifest and channel schemas                                                   | `RulesManifestCodecTest`, `RulesChannelIndexCodecTest`                                                            |
| Forbidden packages, malformed ZIPs, and catalog substitution                          | `RulesArtifactValidatorTest`                                                                                      |
| Exact online/offline resolution and corrupt-cache recovery                            | `DefaultRulesResolverTest`                                                                                        |
| Conditional channel refresh, chain continuity, and same-tag rejection                 | `DefaultRulesChannelResolverTest`                                                                                 |
| Cross-instance locking, leases, activation records, cleanup, and quarantine           | `ContentAddressedRulesCacheTest`, `RulesChannelIndexCodecTest`                                                    |
| Sigstore digest and official identity policy boundary                                 | `SigstoreRulesSignatureVerifierTest`                                                                              |
| Per-project updater activation, stale refresh, LKG restoration, and disposal          | `MineKotRulesProjectServiceTest`                                                                                  |
| Bounded authenticated conditional IDEA transport and rate backoff                     | `IdeaRulesHttpTransportTest`                                                                                      |
| Direct Gradle DSL editing, complex declarations, and indirect-config refusal          | `MineKotGradleDslEditorTest`                                                                                      |
| Exact Gradle lock, verified offline output, cache miss, and configuration-cache reuse | `MineKotToolchainPluginTest`                                                                                      |
| Reproducible release evidence and thin JAR                                            | `RulesReleaseTasksTest`, `validateThinRulesJar`                                                                   |
| Workflow trigger, permissions, immutable actions, identity, and idempotence clauses   | `ReleaseWorkflowContractTest`                                                                                     |
| IntelliJ compatibility and dynamic unload eligibility                                 | `verifyPlugin`                                                                                                    |
| Complete canonical Maven artifacts, IDEA main JAR, and relocated loader runtime       | `verifyInspectionPublications`                                                                                    |
| Immutable, SHA-pinned build and release workflow contract                             | `verifyReleaseWorkflow`                                                                                           |

## Remote-only contracts

| Requirement                                                  | Required evidence                                              |
|:-------------------------------------------------------------|:---------------------------------------------------------------|
| Real keyless Fulcio certificate and Rekor transparency proof | First GitHub Actions release verifies uploaded Sigstore bundle |
| Minimum and current published core linkage                   | Rules CI matrix after first core v1 publication                |
| Superseded-run cancellation and remote-head race             | Two controlled pushes to canonical `master`                    |
| Idempotent workflow rerun against existing GitHub assets     | Rerun completed release workflow and compare terminal result   |
| Stable-index withdrawal and security revocation              | Signed channel update exercised against hosted assets          |
| Maven publication scopes and consumer resolution             | Clean external consumer after inspections publication          |
| Hosted rules parity through released toolchain and toolkit   | Published tag and manifest digest exercised by both hosts      |

## Release rule

Local green status does not mark rollout ready. Remote-only evidence must reach terminal success before bundled toolchain rules are removed or `Latest` activation is enabled.
