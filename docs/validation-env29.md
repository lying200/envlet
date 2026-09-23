# ENV-29: Python WSL Run env-file precedence

Validated on 2026-09-23 with Envlet 0.2.1-dev, Windows IDEA IU-262.10968.63,
full Python 262.10968.63 and the disposable NixOS WSL Python fixture.

## Cause and implementation

This was introduced by Envlet's Python WSL adapter, not an upstream direnv
limitation. Python prepares env-file variables before invoking the Target
extension, but the adapter only treated `runParams.envs` as explicit. Direnv
could overwrite file values or incorrectly reject an unset replacement.

`PythonRunOverrides` reads plain env files with the platform parser and overlays
direct Run settings. The same map feeds both environment merging and inherited
unset checks. Order is direct settings > ordered env files > direnv. IDE-generated
variables are not reclassified as user overrides, and explicit PYTHONPATH keeps
the platform's helper additions. Saved configuration maps are unchanged.

This is a small launch-input correction: no new cache, retry, listener or SDK
lifecycle state. Native process injection is unchanged.

## Platform boundary

The inspected [Python preparation code](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/python/src/com/jetbrains/python/run/PythonCommandLineState.java)
reads files and direct settings separately before adding interpreter/IDE values.
The [platform file reader](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/execution-impl/src/com/intellij/execution/util/EnvFilesUtil.kt)
also executes `.sh`/`.bat` scripts. The extension does not expose the provenance
of the merged execution environment, so replaying scripts would repeat side
effects. The adapter rejects those script configurations with a fixed message
instead of replaying them. The platform may already have executed them once
before the extension is called. Plain files are read again at launch; no script
output or environment map is persisted or logged.

## Regression evidence

- Four initial regression tests failed with the old direct-only override input.
- All 258 tests pass, including six new parser/merge tests: file precedence,
  unset replacement, direct/ordered-file precedence and run isolation, helper
  PYTHONPATH retention, empty explicit values, and no script replay.
- Real old-plugin WSL Run: file dependency import succeeded while
  `envfile_override=false`, proving the file was read but its value overwritten.
- Real fixed-plugin WSL Run: `envfile_override=true`; file PYTHONPATH dependency
  imports successfully. Adding a direct setting gives `direct_override=true`.
- With `.envrc` unsetting HOME, the ordinary run still rejects unsupported
  deletion. A Run env file supplying HOME launches successfully and reports
  `envfile_unset=true` and `envfile_override=true`.

The updated [fixture](../scripts/python-smoke-fixture.py) and
[IDE acceptance script](../scripts/verify-python-support.groovy) reproduce these
checks alongside SDK switching, first child launch, denial/re-approval and
non-Python SDK preservation. Setup is documented in [ENV-24](validation-env24.md).
The new acceptance marker is `env-file-priority-and-unset=true`.

This follow-up tests actual Windows IDEA WSL **Run**, not Debug/pytest/Console
or PyCharm. ENV-24's native Linux results remain historical, not rerun here.
