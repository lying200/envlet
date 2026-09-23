# ENV-16: cache generations and environment notifications

**Follow-up:** ENV-16 introduced a shared-watch ownership regression, fixed in
0.1.8-dev. The original watch validation did not cover root reload followed by
shared-child approval revocation. See [ENV-18](validation-env18.md).

The stale-export race is inherited from upstream `5365283`: `invalidate()` could
clear the cache while an export held the coroutine load mutex, and that older
export could then repopulate it. A gated real-service regression fails on
`f48c92b` with “An export started before invalidate(null) restored the invalidated
cache”. This is distinct from the Envlet-introduced ENV-14/15 regressions.

## Implementation

`DirenvCache` owns environments, verified aliases, retry records, logical watch
sets, the current display state and pending notifications under one short metadata
lock. A unique load ticket identifies the active export generation. Explicit
invalidation rejects that ticket for the affected scope; completion checks the
resolved scope before writing any result. Known alias mappings stay on the ticket
while a shared reload is pending. A global invalidation rejects every old result;
an independent nested `.envrc` may still commit after its parent's invalidation.

Queries are read-only. CLI export, filesystem inspection, VFS registration and
message-bus callbacks never run under the metadata lock. The existing coroutine
mutex still serializes exports. Scheduled-load deduplication stays in the service:
it tracks outstanding coroutine lifetimes, not environment availability.

`DirenvEnvironmentListener` carries only scope and revision. All language listeners
use it; UI listeners retain `DirenvStateListener`. Subscribers read the latest
cache, since another commit can happen before notification delivery. The service
drains notifications outside the lock, in commit order, and suppresses superseded
UI results. Go/Rust also reconcile immediately on subscription and continue to
check root environment identity before applying SDK settings.

Watch metadata commits with the environment. Physical VFS registration reconciles
current snapshots in the background; it can lag invalidation but cannot install
an obsolete export's logical watch set. VFS events match authoritative metadata.
Previously committed watches are retained during ordinary reload/failure so
external file changes can still recover a failed environment; explicit
invalidation clears the affected watches.

## Validation

- Original gated regression: fails before the fix, passes after it.
- `test buildPlugin`: 203 tests, no failures/errors/skips.
- New tests cover global/directory invalidation during export, obsolete failures
  not throttling retries, no obsolete watches/success notifications, shared aliases,
  independent scopes, read-only queries, safe event rendering, cancellation and
  reentrant invalidation during notification delivery.
- Existing first-process, UI/locked cache-only, PATH, Rust SDK directory and
  ENV-15 synchronization regressions remain in the full suite.
- Isolated Windows IDEA IU-262.10968.63, NixOS WSL, plain-direnv fixture
  `~/code/envlet-env16-smoke`: actual gated Go/Rust probes finish after an unrelated
  child becomes Blocked, publish SDK/GOPATH/sources without root reload, then root
  invalidation drops Rust binding ownership and an explicit reload restores it.
- The final combined ENV-16/17 artifact's compatibility verdict is recorded in
  [ENV-17 validation](validation-env17.md).

The test fixture is authored and disposable; only its root `.envrc` is approved.
No main IDEA installation was replaced. Native Linux desktop IDEA, terminal UI,
Cargo native builds and debugging were not rerun for this refactor; previous
version-specific observations remain in their respective validation records.
