# ENV-14: directory preparation before process launch

## Provenance

Both reviewed regressions were introduced in Envlet commit `814f462`, not inherited
unchanged from upstream. Baseline `5365283` walked parent cache entries without
checking for an intervening `.envrc`. Envlet removed that fallback to avoid injecting
the wrong scope, but initially replaced it with fire-and-forget loading, gated on
the last global `Loaded` status. This left the first process without its environment
and let a failed directory prevent loading other, healthy directories.

The first-subdirectory limitation in `docs/validation.md` was also written by Envlet
in `814f462`. It is not evidence that the upstream author chose that behavior.

Upstream does explicitly require the synchronous command-line customizer to remain
cache-only because callers can be on EDT or hold a read lock (`CONTRIBUTING.md`,
`887bce3`). Its Gradle hook waits cancellably on a known background execution path
(`f068daf`). Those code comments establish the threading rationale; they do not
establish any further intention about nested `.envrc` support.

## Implementation boundary

- Use the directory actually requested by the process. Do not restore parent guessing.
- A cache miss on a background thread without IDE read/write access waits for loading
  before injecting the environment into that same process.
- EDT/read/write-locked callers remain cache-only and schedule background warming.
  Their first uncached invocation can still miss the environment. This preserves
  the upstream threading constraint, not a universal first-launch guarantee.
- Automatic failures are retained per normalized queried directory for 60 seconds;
  a later attempt can retry. Explicit reload and watched changes bypass the delay.
  The status bar's last global result is no longer used as a loading gate.
- The existing load mutex coalesces successful concurrent requests; the same-directory
  failure policy also avoids repeating a failed load for every queued request.
- Cancellation escapes the customizer and clears an incomplete load. No approval is
  granted automatically; project trust and the enabled setting still gate execution.

## API evidence

Inspected the exact IDEA `262.10968.63` binaries. `CommandLineEnvCustomizer` has only
synchronous `customizeEnv(GeneralCommandLine, Map)`, with no suspending counterpart.
`GeneralCommandLineEnvCustomizerService` invokes it inline from environment setup.

The public one-argument `runBlockingMaybeCancellable` in
`com.intellij.openapi.progress` preserves an existing cancellation context and also
accepts ordinary background process threads without a Job/ProgressIndicator. It
has background-thread/blocking-context annotations and no Internal annotation in
this build. The boolean overload is Internal and is not used. The CLI's configured
timeout still applies when the caller offers no cancellation context. Direct
`runBlockingCancellable` on a plain pooled thread produced the platform diagnostic
"There is no ProgressIndicator or Job in this thread" during the regression test.

See JetBrains' [execution contexts](https://plugins.jetbrains.com/docs/intellij/execution-contexts.html)
and [threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html).
The exact overload choice was checked against the installed binary, not inferred
from a generic coroutine example.

## Validation

Before the fix, the isolated Windows IDEA + NixOS WSL probe on `0.1.3-dev` showed:
first new-subdirectory process missing the fixture variable; second process passing;
a root reload making the next child process miss again; blocked A leaving healthy B
uncached across repeated launches. Explicitly loading B restored it.

Three new tests exercised the real customizer entry point on a background thread,
without first awaiting `service.load(child)`. All three failed on `21c82ee`.
Additional regressions cover bounded failed retries, concurrent requests, explicit
reload recovery, directory-specific load results, read/EDT boundaries and cancellation.

On 2026-09-23, `test buildPlugin` passed **180 tests** (0 failures/errors/skips).
The packaged `0.1.4-dev` passed isolated Windows IDEA + NixOS WSL acceptance:

| Scenario | Result |
| --- | --- |
| First process in a new ordinary subdirectory | Environment present |
| First child process after root reload removed its alias | Environment present |
| Blocked A, then first and second process in healthy B | Both have environment; B cached |
| Already-cached root after A failed | Environment present |
| Unapproved nested `.envrc` | No parent-environment injection |

Re-ran the existing plain-direnv Rust acceptance with the same ZIP: both separate
rustc/cargo store bins and common-bin layouts selected `EnvletWslRustToolchain`,
Cargo workspace/build-script status was `UpToDate`, bundled SQLite builds passed
in ordinary and terminal modes, and project isolation, disable and approval
revoke/restore checks passed. These results do not establish debugger, reworked
terminal UI or native NixOS desktop IDE support.

Artifact SHA-256: `4d85368e94f8113ef885857a330119d05883f3b7c5c08a45f5fb7a8d5bf98467`.
Plugin Verifier reports **Compatible** with `IU-262.10968.63`; no Internal API usage report. Existing deprecated/experimental platform APIs remain a compatibility limit.

The real-process acceptance script is `scripts/verify-subdirectory-environments.groovy`;
it uses the disposable fixture from the plain-direnv Rust validation. No user project
approval or main IDEA installation is changed.
