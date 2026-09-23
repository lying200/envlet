# ENV-30: preserve Python search-path semantics

The Python adapter introduced in `7cb79dd` filtered empty entries after splitting
PYTHONPATH. For a script outside its working directory, this could remove cwd
from the import search path and cause ModuleNotFoundError. This predates the
ENV-29 env-file fix and is not an upstream direnv limitation.

The merge now ignores whole empty input values before splitting. It retains
every empty entry and its position, while nonempty paths still keep their first
occurrence. Repeated empty entries matter: deduplicating `:` to an empty string
would remove cwd when no helper paths are present. Explicit Run overrides and
the native process injector are unchanged; no new cache or lifecycle is added.

## Validation

On 2026-09-23, three new regression tests failed against the old implementation.
After the fix, all 263 tests passed. Five added tests cover leading/trailing and
consecutive separators, separator-only values without helpers, whole empty
values, empty entries from the IDE, nonempty-path deduplication, input/run
isolation, and the unchanged explicit-override branch.

A separate harness called the compiled production merge and launched Python
3.14.7 with a script outside cwd and a module available only in cwd. Leading,
trailing and consecutive separators gave exit 0 before merging and exit 1
(ModuleNotFoundError) after the old merge. All three give exit 0 with the fix.
A whole empty PYTHONPATH still does not make the cwd-only module importable.

The existing [fixture](../scripts/python-smoke-fixture.py) now exports a trailing
separator and places `envlet_cwd_dependency` only in `shared/`. The
[IDE acceptance script](../scripts/verify-python-support.groovy) runs the root
script from `shared/`, with content/source-root additions disabled, and requires
`pythonpath-cwd-import=true`. Follow [ENV-24](validation-env24.md) to reproduce.

The actual Windows IDEA 262 + full Python + WSL run passed this cwd-only import
check twice with 0.2.2-dev. Local build/buildPlugin/Plugin Verifier also passed;
the Internal API baseline is unchanged.

## Separate IDE acceptance failure

Follow-up: ENV-31 identified the cause as missing SDK path mappings during
Envlet SDK construction. WSL Target normally delegates to EEL in this build;
there was no SDK type switch. See [diagnosis and repair](validation-env31.md).

The full IDE suite did **not** pass: after SDK switching and approval denial,
execution used EelTargetEnvironment and sent a UNC cwd as `/wsl.localhost/...`,
then failed with ENOENT. Both attempts reached and passed the new import check.
A control run using the old 0.2.1-dev ZIP in the same fixture/profile also hit
this mapping error, during ordinary Run before reaching the denial step.
That confirms the symptom occurs with the old build as well; it does not yet
isolate the cause or rule out persisted SDK state. This issue is tracked
separately rather than adding an unverified SDK workaround to this change.
