# ENV-17: toolchain diagnostics and configuration decisions

This implements the maintenance recommendations from the `f48c92b` review. It is
not a claim that every suggested weakness was a reproduced user-visible defect.
The independently reproduced stale-cache defect is covered by
[ENV-16](validation-env16.md).

## Changes

- Probes return success, stale or a categorized failure. Success output is hidden
  from `toString`; failure records do not retain stdout, stderr or exceptions.
  Diagnostics contain only language, stage, reason and optional exit code. Stale
  work is a normal skip, absent tools use debug logging, and cancellation propagates.
- Go output is converted to a configuration plan before SDK construction. Invalid
  JSON, missing/non-string fields and unmappable paths reject the plan. Empty
  GOPATH remains valid. An unmappable GOPATH entry now rejects the entire plan
  instead of silently discarding that entry.
- Rust source precedence and optional-tool selection are in `RustToolchainPlan`.
  A valid configured source wins over the sysroot fallback. Missing sources remain
  nonfatal, with a safe diagnostic; rustc/cargo remain usable without formatters or
  debugger helpers. Existing executable resolution and managed SDK directory
  implementations/tests remain in place.
- Both adapters publish through `applyIfCurrent` on EDT after discovery. This checks
  management enablement, project trust and current root environment identity at
  the actual SDK mutation point. It is a guard before publication, not a transaction
  locking the IDE SDK setters together with all future cache invalidations.

The optional language-module separation, WSL execution adapter and SDK directory
ownership model are preserved. No environment values are persisted.

## Automated checks

`test buildPlugin` passes **219 tests**, with zero failures, errors or skips.
New tests cover malformed/missing Go values, complete path mapping, empty GOPATH,
Rust source precedence/fallback/absence, missing optional tools, safe probe result
rendering, nonzero exits, missing executables, launch failures, cancellation and
in-flight invalidation. A platform-backed publication-guard test verifies that
an old environment cannot invoke the SDK writer after invalidation/replacement,
and disabled management cannot invoke it even with a current environment.

Plugin Verifier for the final archive against IU-262.10968.63 reports
**Compatible**: 5 deprecated API usages, 140 experimental API usages, no Internal
API report. These counts match 0.1.5-dev. `test buildPlugin verifyPlugin` completes
successfully.

## Actual IDEA validation

Isolated Windows IDEA IU-262.10968.63 with Go/Rust plugins, NixOS WSL, and a newly
authored plain-direnv project `~/code/envlet-env17-smoke`. Root `.envrc` is approved;
the nested `blocked/.envrc` remains unapproved. The main IDEA installation is not
modified. `scripts/verify-toolchain-sync.groovy` runs actual compiler probes and
observes real SDK services; it does not write SDK settings itself.

With `envlet-0.1.7-dev.zip`:

1. Go and Rust probes wait at controlled gates. An independent child becomes
   Blocked. Releasing the gates publishes Go SDK/GOPATH and Rust SDK/sources, while
   the root environment is unchanged and the global display state remains Blocked.
2. Root invalidation immediately removes Rust provider ownership; explicit reload
   restores it with the new environment.
3. A fixture marker makes Go return malformed JSON and makes Rust report a sysroot
   with no sources, while removing `RUST_SRC_PATH`. Reload retains the prior Go
   SDK/GOPATH and publishes a valid Rust compiler with no explicit source path.
4. Removing the markers and reloading restores the Rust source setting.
5. IDEA logs contain `GO / CONFIGURATION / INVALID_OUTPUT` and
   `RUST / CONFIGURATION / MISSING_SOURCES`. The private output canary is absent
   from the log; no raw log was copied into the validation report.

Final report: `ACCEPTANCE=true`, `FINISHED`.
Local evidence: `/home/echoyn/temp/env17-toolchain-validation.txt`,
`env17-diagnostic-results.txt`, `env17-build.log`.
Archive SHA-256: `4cbc9a710dcf89e6163942218356b3684f884563dd508ca4b32964bd5d131129`.

Native Linux desktop IDEA, terminal UI, debugging and native Cargo builds were not
rerun for this refactor. Earlier version-specific acceptance remains documented
separately; these checks establish discovery/publication behavior, not all IDE
features. This remains a personal development build, not a Marketplace release.
