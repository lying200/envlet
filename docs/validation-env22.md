# ENV-22: immediate child recovery after approval

Modified for Envlet. Base: **f61ec83**. Packaged fix: **0.1.11-dev**.

## Failure and fix

After a previously unknown child discovers root approval refusal, its alias is
absent and its failure is throttled for 60 seconds. The recovery watch still
records the child's root scope. Re-approval refreshes root, but scope cleanup used
only live aliases and an active load snapshot; the new active load was not yet
installed. The child's cooldown survived, and the successful root commit removed
the old watch record that could associate it with the scope.

The production change makes `removeScope` enumerate the existing `knownScopes()`
map (live aliases plus retained watch ownership), together with the active load
snapshot. It clears related cooldowns before those watches are replaced. It does
not restore aliases, shorten the retry interval, change assignment ordering, or
clear independent scopes' failures. No new state or retry mechanism is added.

This is an Envlet metadata-cleanup gap in the per-directory retry feature. The
ENV-20 approval tests previously stopped at root recovery, so they missed whether
the original failed child could immediately prepare a new process.

## Automated evidence

On f61ec83, extending both Blocked and Denied recovery tests to immediately call
`environmentForProcess(child)` fails: root is restored but the child returns null.
A cache test independently confirms the stale shared cooldown while checking
that an independent scope retains its failure. All three pass with the fix.

These assertions are now permanent. The service tests verify exactly one fresh
export after root recovery, a resulting child environment, and the replacement
toolchain synchronization callback. They first assert that the child alias was
not restored automatically. This tests real re-resolution, not parent guessing.

**242 tests pass**, with zero failures, errors or skips. Full command:
`test buildPlugin verifyPlugin` with the locally installed Go/Rust plugin paths.
Plugin Verifier: **Compatible** with IU-262.10968.63; 5 deprecated and 140
experimental API usages, no Internal API report. The full build completed
successfully.

Earlier red/control logs: `/home/echoyn/temp/env22-recovery-{red,control}.log`.
Full build log: `/home/echoyn/temp/env22-build.log`.

## Isolated runtime acceptance

Fixture: `/home/echoyn/code/envlet-env22-smoke`, authored for this validation.
The existing `scripts/verify-first-child-and-project-probe.groovy` now extends the
approval-recovery scenario by starting an actual WSL process from the previously
denied `unseen/` directory as soon as root recovers. It checks that the directory
was uncached before launch, becomes cached after launch, and the process receives
the fixture key with parent environment inheritance disabled. The check must
complete before the original 60-second refusal window expires.

This script also retains the prior first-child revocation and project Go probe
directory checks. Approval changes are restricted to the authored fixture.

The packaged plugin passed in Windows IDEA IU-262.10968.63 with NixOS WSL
`legion-wsl`. The script reported
`automatic-approval.child-process-without-cooldown=true`, `ACCEPTANCE=true` and
`FINISHED`. Existing first-child revocation, root Go SDK selection and shared
module probe-directory assertions also passed. Independent failure preservation
is covered by the automated cache test.

Only the authored fixture was approved/denied. Approval was restored, the isolated
IDE exited, and the original validation launch settings were restored. The main
IDE installation was not replaced. Report: `/home/echoyn/temp/env22-validation.txt`.
This does not revalidate Rust SDK UI, indexing, native Cargo builds, debugging or
terminal integration; earlier version-specific records remain separate.

Artifact: `build/distributions/envlet-0.1.11-dev.zip`, also copied to
`C:\Users\echoyn\Downloads\Envlet\envlet-0.1.11-dev.zip`.
SHA-256: `1f5cc4418ddce86ea330eb49f8c128979c25d6da1699e2d990c0f1631917c8a8`.
