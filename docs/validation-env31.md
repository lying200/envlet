# ENV-31: initialize WSL Python SDK root mappings

Validated on 2026-09-23 with Envlet 0.2.3-dev, Windows IDEA IU-262.10968.63,
full Python 262.10968.63 and the disposable NixOS WSL Python fixture.

Follow-up: [ENV-32](validation-env32.md) removes probed roots from SDK user-added
paths entirely. It uses source-only index libraries and leaves normal interpreter
mappings to Python's updater. This record describes the earlier mapping repair;
the current regression still checks mapping correctness and repeated real Runs.

## Cause

This was an Envlet SDK-construction omission, not a direnv limitation. Added SDK
VirtualFiles use host UNC paths. Without corresponding target mappings,
`PyTargetAwareAdditionalData.getPathsAddedByUser` returned those paths unchanged.
Python's background source refresher preserved them as user-provided mappings
before its discovered mappings. When a trailing PYTHONPATH separator brought cwd
into the probed search roots, a bad project-root mapping also redirected script
and working-directory resolution to a UNC-shaped Linux path, causing ENOENT.

The reduced fixture failed on its second ordinary Run, without venv switching
or approval changes. SDK identity, additional-data type and WSL request type did
not change. WSL Target delegates to EEL in this platform build; EEL in the stack
trace was not evidence of an SDK type switch. Replacing the bad mapping with an
explicit local-root to Linux-root pair made the same Run succeed even with
Envlet disabled. Appending a lower-priority duplicate did not fix it.

## Repair boundary

While preparing the unpublished SDK, the existing Envlet path mapper converts
each probed SDK search directory into an explicit local/remote mapping. These
mappings are installed before registering added VirtualFiles. The normal SDK
synchronization replaces the complete additional data, including previously
malformed mappings. Native SDK construction is unchanged.

This adds no cache, retry, listener or SDK lifecycle state. It covers roots
outside the project as well as cwd. The existing mapper preserves venv symlinks
and Nix wrapper paths. A trial using the platform's broad WSL root mapping was
rejected by the same runtime assertion: joining remote `/` with the suffix
yielded `//home/...`. Explicit pairs avoid this ambiguous prefix concatenation.

## Regression and actual IDE checks

The [acceptance script](../scripts/verify-python-support.groovy) now asserts that
every user-added SDK root maps exactly to its WSL path, includes project cwd and
includes an external SDK root. It repeats actual Python launches and checks the
mappings before/after venv switching and approval revocation. The fixture retains
ENV-30's trailing PYTHONPATH separator and cwd-only dependency.

- Old 0.2.2-dev fails the new initial SDK-mapping assertion.
- Fixed 0.2.3-dev passes the complete WSL suite with `ACCEPTANCE=true` and
  `FINISHED`. Three repeated background-refresh Runs exit 0; mapping assertions
  pass initially, after each repeat, after venv switching, after revocation and
  at the end.
- Root and first shared-child Run, blocked child, explicit/env-file overrides,
  cwd-only import, venv switch, deny/re-allow, inherited-unset rejection and
  env-file replacement, and Java SDK preservation all pass.
- Reopening the same profile/project without deleting SDK metadata passes the
  entire suite again, including all seven SDK-mapping checks and repeated Runs.
- All 263 automated tests pass; local build, plugin ZIP and Plugin Verifier pass.
  The exact Internal API baseline is unchanged.

The regression lives at the real platform SDK/Run boundary because the defect
requires Python's added-path mapping and background updater; a pure mapping
helper test would not reproduce that interaction. Reproduce using the
[ENV-24 setup](validation-env24.md), then reopen the same profile/project without
deleting SDK metadata and rerun the controller and acceptance script.

These checks establish Windows IDEA WSL **Run**, not Debug, pytest, Console,
PyCharm or completed indexing. Native Linux results in ENV-24 remain historical;
this follow-up does not rerun that unchanged branch. Daily IDEA is not modified.
