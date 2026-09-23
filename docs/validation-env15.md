# ENV-15: root toolchain synchronization survives independent directory loads

## Cause and ownership

The Go/Rust synchronization listener was added by Envlet in `814f462`. It cancelled
its current probe on every project-wide status event, then started a replacement
only for `Loaded`. An independent child publishing `Loading` followed by
`Blocked`/`Failed` therefore cancelled a still-valid root probe without replacing it.
Loading the root again from cache produced no notification. This is an Envlet bug,
not an upstream threading constraint, and predates the ENV-14 process-loading fix.

## Change

Each language subscription remembers the root environment identity for its current
or completed attempt. On a notification it reads the current eligible root cache:

- Same root environment: leave the existing/completed attempt alone, irrespective
  of the child-directory status displayed in the project UI.
- Missing/replaced root environment: cancel stale work; start for a new valid root.
- Disabled language management/plugin or untrusted project: no eligible root, so
  cancel and forget the attempt. SDK settings retain their existing policy.

A shared child scope can invalidate the root cache, so events are not simply
filtered by directory name. Existing publication checks still reject stale results.
An unsuccessful probe for an otherwise unchanged root is retried through a root
reload, not through unrelated child notifications. Listeners use the public
`MessageBus.connect(CoroutineScope)` overload, tying subscriptions to the same
lifetime as probe jobs (checked against the installed IDEA 262 binary).

## Regression tests

`EnvletToolchainSyncTest` uses the actual project message bus and `DirenvService`,
with a gated suspend callback occupying the Go/Rust probe window. Tests cover:

- Blocked and failed independent children during an active root probe, followed
  by a root cache hit; the original synchronization must finish.
- Independent successful child loads during/after root synchronization, without
  cancelling or repeating SDK updates.
- Failed root reload cancellation and successful reload recovery.
- Reloading a child that shares and replaces the root environment.
- Language/plugin disable and re-enabling management with the same cached root.
- A late language subscriber after an independent child was blocked.

Before the fix, 4 of the initial 7 tests failed. With the fix, all 8 pass. The
controlled callback checks scheduling; it does not itself invoke the Go/Rust SDKs.

## Packaged IDEA validation

`scripts/verify-toolchain-sync.groovy` drives a disposable plain-direnv project.
Its Go `env` and Rust `--print sysroot` wrapper commands mark that each real probe
has started, then wait on a release file. The script loads an unapproved independent
child while both probes are in flight, verifies the root cache remains identical,
then releases them. It requires real Go SDK/GOPATH and Rust SDK/source publication
while the global status is still `Blocked`, excluding a later successful load as
the reason synchronization recovered. The script never assigns SDK settings or
approves `.envrc`; only the authored root fixture is approved during setup.

Validated 2026-09-23 with packaged `0.1.5-dev`:

- `test buildPlugin`: **188 tests**, zero failures/errors/skips.
- Isolated Windows IDEA `262.10968.63` + NixOS WSL: **passed** with real Go/Rust
  gated executables. Both probes had started before the child was blocked. After
  release, Go SDK/GOPATH and Rust SDK/source configuration completed while the root
  environment identity was unchanged and project status remained `Blocked`.
- No main IDEA plugin installation or user project was modified. The authored
  fixture is `~/code/envlet-toolchain-sync-smoke`; its child remains unapproved.
- This test verifies SDK publication during the reported race. Existing ENV-13/14
  records cover Cargo builds; this run does not revalidate debugger or terminal UI.

Artifact SHA-256: `bac37b1cf5a964f1bf4e74f2637bb72eabc0aca86a48b419ccafb6f24e904345`.
Plugin Verifier: **Compatible** with `IU-262.10968.63` (5 deprecated and 140 experimental API usages; no Internal API usage report).
