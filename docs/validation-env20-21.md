# ENV-20/21: approval refusal and project probe context

Modified for Envlet. Base under review: **a1e8c92**. Packaged fix: **0.1.10-dev**.

## Provenance and reproduction

ENV-20: an unknown child's export could first discover that the root `.envrc` was
Blocked/Denied. Load preparation had invalidated only the unknown child, and
completion recorded its failure without clearing the resolved root scope. The
new watch baseline could then hide the already-observed approval change from
polling. Root processes and SDK publication guards still accepted the old cache.

Upstream 5365283 also failed to clear existing cache on refusal; its comments
expressly required avoiding stale approval, rather than documenting this as an
intentional limitation. Its parent-cache fallback usually skipped the exact
first-child export. Envlet's per-directory preparation and watch grouping exposed
and retained the gap. This is not solely an ENV-19 regression.

ENV-21: the Go/Rust integration added in 814f462 used the latest environment's
export directory for project-level probes. A shared child export replaced the
root cache object, so its Go module could influence project SDK selection. The
ENV-17 extraction into ToolchainProbe preserved that mistake. Go's documented
module/workspace toolchain selection is expected; the wrong probe cwd was ours.

Before the fix, three platform-backed diagnostic tests failed at the intended
assertions: Blocked and Denied retained root cache and allowed stale SDK writes;
shared-child loading redirected both process cwd and the `direnv exec` directory.
See `/home/echoyn/temp/envlet-review-a1e8c92-red-repeat.log` and its saved regression
patch. The first diagnostic attempt also exposed a fixture cleanup issue; the
final diagnostic run contains assertion failures, not setup timeouts.

## Implementation constraints

- After generation validation, an explicit approval refusal with a resolved
  scope takes a distinct completion branch. That branch clears the scope's
  environment and aliases, records the queried-directory failure, replaces
  obsolete scope watches with recovery watches, and publishes environment
  invalidation plus the UI refusal state under the existing metadata lock.
- Generic failures do not imply parent-scope refusal. Independent child scopes
  remain isolated. Results invalidated during export cannot publish refusal,
  watches or retry state afterward.
- Project toolchain probes explicitly obtain the IDEA project directory. Both
  the process runner cwd and `direnv exec` use it. ToolchainProbe accepts a Path
  instead of an environment object, so export provenance cannot silently select
  probe context. The environment's original workingDir is preserved.
- The existing process adapter is injectable through an internal probe overload,
  letting tests exercise the actual project-directory choice, mapping and stale
  checks. Go/Rust adapter behavior and normal child-process cwd are unchanged.

No new retry scheduler or state table is introduced. Approval refusal becomes an
explicit state transition; probe context becomes an explicit input.

## Automated validation

**241 tests pass**, zero failures, errors or skips. Added cross-component coverage:

- Root cached, then first child reports root Blocked/Denied: root is unavailable,
  old synchronization is cancelled, stale SDK publication is refused, the updated
  watch baseline is quiet, and a later approval event restores root synchronization.
- Scope refusal removes shared aliases and obsolete watches while preserving an
  independent scope; the invalidation event contains no environment values.
- Refusal invalidated while in flight cannot restore watches, failures or events.
- Shared children with separate go.mod requirements cannot move project probes
  from the project directory. Both process cwd and direnv exec are asserted.
- An `.envrc` above the project does not redirect the probe to that ancestor;
  stale environments skip the process entirely.

Existing independent blocked/failed-child, scope-refresh, cancellation, mapping,
privacy and SDK publication tests continue to pass.

Build log: `/home/echoyn/temp/env20-21-build.log`.
Focused tests: `/home/echoyn/temp/env20-21-focused.log`.
Plugin Verifier: **Compatible** with IU-262.10968.63; 5 deprecated and 140
experimental API usages, no Internal API report. `test buildPlugin verifyPlugin`
completed successfully.

## Native Go and isolated IDEA / WSL acceptance

Authored fixture: `/home/echoyn/code/envlet-env20-21-smoke`.
One approved `.envrc` supplies PATH/GOTOOLCHAIN; root and shared child have separate
go.mod files. Using already-installed Nix Go binaries, real `direnv exec ... go env`
selects **Go 1.26.0** at root and **Go 1.26.7** in the child, with different GOROOTs.
No toolchain download or ordinary project changes were needed.

The packaged plugin then ran in the isolated Windows IDEA IU-262.10968.63 profile,
WSL distribution `legion-wsl`, through
`scripts/verify-first-child-and-project-probe.groovy`:

1. The Go adapter published a valid project SDK from the root's Go 1.26.0 GOROOT.
2. Loading the shared child replaced the root cache with an environment whose
   export provenance still named that child. The next actual Go probe ran in
   root (recorded by the fixture wrapper); the observed project SDK stayed at root.
3. File watching was disabled. The external controller revoked this fixture's
   approval, and the script verified that root was still cached before asking
   Envlet to load a previously unseen child.
4. That child load removed root cache and invalidated the old SDK synchronization
   context. Cache-only injection and an actual WSL process with parent environment
   disabled both lost the fixture key.
5. Watching was re-enabled; external approval automatically restored root cache
   and actual WSL process injection without manual reload or synthetic VFS events.

The report ended with `ACCEPTANCE=true` and `FINISHED`. Approval was restored,
the sandbox exited, and original launch settings were restored. The main IDEA
installation was not replaced. Report: `/home/echoyn/temp/env20-21-validation.txt`.

This validates actual Go selection, first-child revocation and WSL process
injection. Rust SDK UI, indexing, Cargo native builds, debugger and terminal
behavior were not rerun; previous version-specific evidence remains separate.

Artifact: `build/distributions/envlet-0.1.10-dev.zip`, copied to
`C:\Users\echoyn\Downloads\Envlet\envlet-0.1.10-dev.zip`.
SHA-256: `dc4d1bac5f6e811173e4af722a071f5b3ff00102207466716d4579387bc86efd`.
