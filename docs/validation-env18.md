# ENV-18: shared watch ownership and approval revocation

## Confirmed regression and provenance

The review of `d3b0be3` identified a real P1 regression. A platform-backed test
loads root, loads a child sharing root's `.envrc`, force-reloads root, then sends
a watched-file event after configuring the CLI to report approval blocked. It
waits for Blocked and asserts root is no longer cached.

The **same test passes on `f48c92b` and fails on `d3b0be3`**. The passing comparison
was run in a separate checkout, not inferred from code. ENV-16 changed the watch
service from incremental replacement to rebuilding snapshots. Root reload removed
child aliases but left their watch records; rebuilding in root/child order let the
orphan child become the only target for the shared file. Refreshing that child
could no longer invalidate root, so root's old environment remained injectable.
The older registry's single-target-per-file limitation was inherited, but this
specific ordering regression was introduced by ENV-16 (`9ea5f76`).

## Fix

Each committed watch record carries its environment scope and commit revision,
independently of queried-directory aliases. A successful scope reload replaces
old records for that scope; explicit invalidation removes them by scope even if
aliases are already gone. Failed reloads can retain scoped recovery watches so
external approval changes are still noticed. Watch metadata is only an
invalidation hint: cache lookup never uses it to supply an environment.

The watcher merges records by environment scope, retaining intermediate missing
`.envrc` watches while their queried aliases remain valid. It chooses each file's
latest committed baseline explicitly. Reload targets are scopes, and the registry
returns **all** scopes dependent on a file, independent of insertion order.
A newly created nested `.envrc` invalidates existing aliases; the next child
process resolves its own environment rather than inheriting the old parent.

Blocked/Denied outcomes carry the actual reported `.envrc` scope as well. A child
that becomes independent must own its new approval watches rather than retain
its former parent's scope. Long CLI/VFS IO remains outside the metadata lock;
project trust and explicit direnv approval requirements are unchanged.

## Automated validation

- Original regression: red on `d3b0be3`, green on `f48c92b`, green after the fix.
- Full `test buildPlugin`: **227 tests**, no failures, errors or skips.
- Coverage includes VFS and polling paths for root/child refresh followed by
  revocation; scope cleanup and failure recovery; multiple dependent scopes in
  both insertion orders; intermediate nested `.envrc` creation; and a formerly
  shared child receiving its own Blocked/Denied watch scope.
- Existing generation, first-process, language synchronization, SDK planning and
  environment privacy tests remain in the full suite.
- Final `verifyPlugin`: **Compatible** with IU-262.10968.63, 5 deprecated and
  140 experimental API usages, no Internal API report.

## Actual IDEA / WSL validation

An authored, disposable plain-direnv fixture at `~/code/envlet-env18-smoke` was
opened in the isolated Windows IDEA IU-262.10968.63 profile with `0.1.8-dev`.
The script [verify-shared-watch-revocation.groovy](../scripts/verify-shared-watch-revocation.groovy)
loads root and a shared child, then force-reloads root and confirms the child alias
is gone. An external controller runs `direnv deny`, waits for the script, then
runs `direnv allow`. There is **no manual plugin refresh or synthetic VFS event**
after these approval changes.

Observed results:

- Automatic revocation leaves root uncached and Blocked/Denied.
- The actual process-environment customizer no longer injects the fixture variable.
- Real WSL shell processes launched through `GeneralCommandLine` with parent
  environment disabled see the variable before revocation, do not see it after
  revocation, and see it again after automatic approval recovery.
- Final report: `real-wsl-processes.verified=true`, `ACCEPTANCE=true`, `FINISHED`.

Evidence in `/home/echoyn/temp`: `env18-red.log`, `env18-before.log`,
`env18-final-build.log`, `env18-final-verifier.log`, `env18-watch-validation.txt`,
`env18-final-controller.log`. The controller always restores approval for this
explicitly authored fixture; no ordinary user project approval was changed.

Archive SHA-256:
`f1be0163f73b251339faa1886381ccd8131c6a62ed3ee32e93dbdc2a640282f9`.

The main IDEA installation was not replaced. Go/Rust SDK, Cargo native build,
terminal UI, debugger and native Linux desktop acceptance were not rerun for this
watch-only change; their earlier version-specific results remain separate.
