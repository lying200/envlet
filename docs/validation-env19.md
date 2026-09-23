# ENV-19: refresh scope and working directory

Modified for Envlet. This fixes a regression introduced in **ed5c7bf / ENV-18**,
not a documented upstream restriction.

## Failure and reproduction

When IDEA opens `/repo/app` and direnv resolves `/repo/.envrc`, startup establishes
`app -> repo`. ENV-18 made watch targets canonical scopes to prevent stale shared
child records from leaving a revoked root environment cached. But it also ran
the next export from `repo`, so successful refresh rebuilt only `repo -> repo`.
The project cache stayed empty and Go/Rust synchronization did not restart.

A platform-backed regression uses the real service, watch debounce and toolchain
subscriber, with a fake CLI. On ed5c7bf it failed twice after the parent environment
was replaced but the project alias was lost. A one-directory diagnostic control
changing only the reload working directory passed. That temporary control was
removed; it was not a general fix.

## Contract and bounded implementation

- Watch targets remain **invalidation scopes**. Both VFS and polling call
  `DirenvService.refreshScope`, rather than treating the scope as the CLI cwd.
- `DirenvCache.beginRefresh` selects a consumer and invalidates the scope in one
  short metadata operation. The project directory is preferred when it belongs
  to that scope. If its previous alias/watch was removed, an ancestor scope may
  trigger a fresh project-directory export; it never supplies a guessed cache hit.
  A known independent project scope is preserved.
- Otherwise the shallowest recorded consumer directory is chosen, with a stable
  path tie-break. Stale queued scopes with no remaining watch records are skipped.
- Exactly one directory is re-exported per affected scope. Other shared process
  directories remain uncached until used. No old alias is restored without a
  successful export, and multiple force-loads cannot erase each other's aliases.
- Export, guarded commit and cancellation handling are shared with normal loads.
  Existing generation rejection, approval checks and threading limits remain.

This adds no persistent state table, SDK retry mechanism or parent-cache fallback.
Go/Rust adapters and their synchronization logic are unchanged. The intentional
cache-only restriction on EDT/locked process launches is unchanged as well.

## Automated validation

`test buildPlugin verifyPlugin` with the installed Go/Rust plugin paths:

- **235 tests pass**, zero failures, errors or skips.
- The parent `.envrc` regression covers both VFS and polling: a new project cache
  entry is published, the toolchain subscriber runs again, and exports use the
  actual project directory.
- Cache tests cover shared alias removal, loss of the project's old watch record,
  independent nested scopes, an independently configured root, invalidation during
  refresh, and an obsolete queued refresh after global invalidation.
- Existing ENV-18 root/child approval revocation tests continue to pass, as do
  the service's concurrent invalidation and toolchain publication tests.

## Isolated IDEA / WSL acceptance

The packaged **0.1.9-dev** was installed only into the disposable IDEA profile,
using Windows IDEA IU-262.10968.63 and NixOS WSL distribution `legion-wsl`.
`scripts/verify-shared-watch-revocation.groovy` ran with
`envlet.validation.ancestorEnvrc=true` and IDEA opened the fixture's `app/` directory.

- Confirmed that the project's loaded `.envrc` is in its parent.
- Loaded a shared child, manually refreshed the root, and confirmed the old child
  alias was removed while the root retained its environment.
- An external controller then ran `direnv deny`: without manual refresh or
  synthetic VFS events, the watcher cleared the project cache. Both the cache-only
  process customizer and an actual WSL process stopped receiving the fixture key.
- External `direnv allow` automatically restored the project alias to the parent
  `.envrc`, and both injection checks regained the key. The process used
  `ParentEnvironmentType.NONE`, so parent shell inheritance could not hide a miss.
- The script reported `ACCEPTANCE=true` and `FINISHED`. Fixture approval was
  restored, the sandbox exited, and its original launch settings were restored.

This runtime check establishes parent-scope refresh and process injection. The
Go/Rust notification restart is covered by platform-backed tests; full SDK UI,
indexing, native Cargo builds, debugging and terminal integration were not rerun
for this change. Previous version-specific evidence remains in earlier records.

Artifact: `build/distributions/envlet-0.1.9-dev.zip`, also copied to
`C:\Users\echoyn\Downloads\Envlet\envlet-0.1.9-dev.zip`.
SHA-256: `063504f4f974d4586f3b966b61eec0bf6d1c9661215f711bcf599ac10e621b60`.

Plugin Verifier: **Compatible** with IU-262.10968.63; 5 deprecated and 140
experimental API usages, no Internal API report. The complete build finished
successfully. Runtime acceptance report: `/home/echoyn/temp/env19-watch-validation.txt`.

## Local evidence

Diagnosis: `/home/echoyn/temp/env19-parent-refresh-{red,control,red-repeat}.log`.
Focused tests: `/home/echoyn/temp/env19-focused.log`.
Full build: `/home/echoyn/temp/env19-build.log`.
Disposable runtime fixture: `/home/echoyn/code/envlet-env19-smoke`, with IDEA
opening `app/` and `.envrc` in its parent. Only this authored fixture is approved
or denied; the user's ordinary projects are not changed.
