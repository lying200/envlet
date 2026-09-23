# ENV-32: separate Python index roots from runtime paths

The Python adapter introduced in `7cb79dd` registered all probed `sys.path`
entries as SDK user-added paths. The Python plugin appends these paths to Run's
PYTHONPATH even when a child `.envrc` or explicit Run setting replaces that
variable. ENV-31 corrected their WSL spelling but not their runtime meaning.

## Implementation boundary

Probed paths now populate the SOURCES of an `Envlet Python search paths` module
library, in the existing guarded SDK publication write. Only modules using that
SDK are changed. Their content roots already belong to the project and are
omitted from the library. CLASSES stays empty: Python import resolution visits
library sources, while Run preparation appends library classes only.

Python remains responsible for normal SDK paths, mappings, skeletons and
background updates. Envlet no longer seeds SDK mappings with its probe output:
the updater could turn those mapped paths into module source folders and thus
make them runtime additions again. Normal synchronization replaces old Envlet
SDK additional data. No launch-path subtraction, extra cache, listener or retry
is added, and the Run environment merge remains unchanged.

The module libraries persist only necessary index paths. Automatic setup does
not create or import modules; newly imported modules receive the library on the
next environment synchronization. Disabling automatic management retains the
last SDK/library configuration, as other toolchain adapters do. To roll back,
restore the previous SDK and remove Envlet's module libraries if no longer wanted.

## Regression

The full [IDE script](../scripts/verify-python-support.groovy) now requires that
root-only imports fail after env-file, direct, empty and independent-child
PYTHONPATH replacements. Source-root additions explicitly enabled by the user
must still work, including after background refresh. It also verifies that SDK
user-added paths are empty, index library CLASSES are empty, source roots include
the probe's project/external paths, and Python PSI resolves the root-only import
through the source library.

The [fixture](../scripts/python-smoke-fixture.py) includes an approved independent
child environment, a user source-root dependency and a module persisted on disk.
The previous script tried to create a module during asynchronous project load;
JPS could overwrite it. The harness now waits for the disk module, reads it under
a read action using `isDisposed()` explicitly, and saves the project before the
reopen check. These are harness changes, not new production lifecycle handling.

Old 0.2.3-dev fails the actual WSL env-file assertion: the new dependency imports,
but the old root-only dependency incorrectly remains importable. This is the
red test, not just a model of the merge function. See [ENV-24](validation-env24.md)
for setup/controller commands. The new result markers are
`pythonpath-replacement=true` and `index-import-resolution=true`.

## Actual validation

On 2026-09-23, 0.2.4-dev passed the complete Windows IDEA IU-262.10968.63 + full
Python + NixOS WSL suite. Direct/env-file/empty/independent-child replacements
remove the root-only import; explicit source roots remain usable before and after
refresh. PSI resolves the root-only import via the source library after indexing.
Repeated Runs, meaningful PYTHONPATH empty entries, venv switching, deny/re-allow,
unset rejection/replacement and Java SDK preservation also pass.
After saving the project, reopening the same profile with its SDK and module
library metadata intact passes the entire suite again, including the final
source-root-enabled Run after background refresh.

A separate Linux IDEA + PythonCore profile on WSLg passed native SDK selection,
enabled/disabled Run, independent-child replacement and PSI import resolution.
It used its own disposable fixture. The native generic injector's existing
precedence remains unchanged; WSL explicit-override checks do not establish a
new native precedence policy.

All 263 automated tests, local build/buildPlugin and Plugin Verifier pass. The
Internal API baseline is unchanged. This regression is tested through actual Python
Run and PSI/library integration because a pure merge test cannot observe how the
platform turns SDK user additions into runtime paths. Debug/pytest/Console and
PyCharm remain unverified. Daily IDEA was not modified.
