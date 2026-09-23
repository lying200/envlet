# ENV-24–28: Python SDK and execution environment

Validated on 2026-09-23 with Envlet 0.2.0-dev, IDEA IU-262.10968.63 and
Python 262.10968.63. ENV-23's [preimplementation checks](validation-python.md)
established the missing SDK adapter and missing WSL Target injection.

## Implementation boundary

`products/python` is optional on PythonCore. It probes the PATH-selected
interpreter in the **project root** using the existing direnv toolchain probe,
keeps virtualenv symlinks/Nix launchers, and builds SDK metadata from the reported
version and search paths. It neither creates venvs nor installs packages.

The startup activity waits for the platform's disk/workspace synchronization
before selecting an SDK. Reopen tests reproduced JPS clearing an earlier SDK
write; waiting at this lifecycle boundary fixes that race without retries. SDK
publication uses the shared current-root guard, preserves a non-Python project
SDK selection even when the SDK cannot currently resolve, and schedules standard Python background setup on the published SDK only.

The WSL Python Target provider resolves each execution's actual cwd and verifies
its distribution and project boundary before preparing the environment. It
reuses the existing cache/service and respects project trust, approval and
disabled settings. Explicit Run variables win on this WSL path, and PYTHONPATH
merging retains Python's helper/content paths. Saved Run configuration maps are
not modified. Native local runs keep using the existing generic injector.

WSL Target overlays cannot express deletion of a target-inherited variable.
Envlet rejects such a launch with a specific message unless an explicit Run value
replaces it; removing the override or sending an empty string would be incorrect.
Use a direnv-loaded terminal for this case. No wrapper or environment file is
generated to emulate unsupported Target semantics.

## Actual IDE checks

All projects/profiles were disposable, separate from the user's daily IDEA.
Fixtures use plain direnv, a Python 3.14.7 venv and a module in an exported
PYTHONPATH; no devenv dependency is involved. Only authored fixtures were approved.

| Check | Observation |
| --- | --- |
| Windows IDEA + full Python + NixOS WSL | Automatically selected `.venv/bin/python` using WSL Target SDK data |
| Python Run, Envlet disabled/enabled | Variable absent/present; WSL fixture dependency unavailable/available; actual process exit 0 |
| Explicit WSL Run variable | Overrides the direnv value |
| First run in shared child | Receives the environment without an earlier child load |
| Unapproved child `.envrc` | Does not receive the root environment |
| `.venv` → `.venv-next` | Watch reload selects new interpreter; actual Python Run reports new executable |
| External deny → allow | Root injection stops; first shared-child run after recovery immediately receives environment |
| Export with `unset HOME` | Specific unsupported-unset error; no Python application process output |
| Native Linux IDEA + PythonCore | Local Python SDK auto-selected; actual Python Run receives variable and imports fixture dependency |
| Reopen with cached project model | SDK selection survives the disk/model synchronization boundary |
| Non-Python SDK selection | Reload preserves the configured Java SDK name/type, including an unresolved SDK |
| Python plugin absent | Fresh Linux IDEA profile loads Envlet; generic process injection still works |

Native Linux was the Linux IDEA binary running on NixOS WSL through WSLg. This
exercises the native Linux code path, not a separate physical desktop installation.
Disabling Envlet leaves the last SDK and its import paths configured, as with the
other language adapters; it does not undo SDK metadata. Therefore a native Run
can still import a path retained in that SDK while environment injection is off.

## Automated checks and compatibility

The build suite has 252 passing tests, including ten Python tests covering PATH
order, machine mapping, malformed probes, venv/wrapper preservation, run overrides,
PYTHONPATH helper retention, unset rejection and execution-map isolation.
Existing shared-service tests cover invalidation and stale SDK publication guards.

The platform marks the Python WSL seams and workspace synchronization API as
Internal. `verifyPlugin` retains binary/OverrideOnly failures and checks Internal
reports against an exact [reviewed baseline](../config/python-262-internal-api.txt).
An initial run failed for the two newly introduced workspace references, verifying
that the gate rejects unreviewed uses. Those calls were then inspected and added
explicitly. They remain visible in the reports; there is no blanket package ignore.

## Reproduction

Prepare a new disposable directory on the direnv machine:

```sh
python3 scripts/python-smoke-fixture.py prepare ~/code/envlet-python-support-smoke
```

Install the built ZIP and matching full Python plugin into a separate Windows
IDEA profile. Run [verify-python-support.groovy](../scripts/verify-python-support.groovy)
using `idea64.exe ideScript`, with these properties set in that profile's
`idea.properties` (substitute distribution/user/profile paths):

```properties
envlet.validation.project=//wsl.localhost/DISTRIBUTION/home/USER/code/envlet-python-support-smoke
envlet.validation.targetProject=/home/USER/code/envlet-python-support-smoke
envlet.validation.result=C:/path/to/isolated-profile/python-validation.txt
```

Start the controller before launching the IDE script:

```sh
python3 scripts/python-smoke-fixture.py control ~/code/envlet-python-support-smoke \
  --report /mnt/c/path/to/isolated-profile/python-validation.txt
```

The controller switches fixture venvs, denies/allows the fixture, exercises an
unset, then restores its original `.envrc`. The report must end with
`ACCEPTANCE=true` and `FINISHED`. Reopen the same profile/project to cover the
cached-model startup case. The earlier `verify-python-wsl.groovy` is the ENV-23
baseline investigation, not the new acceptance test.

## Not established by these checks

- No PyCharm runtime test; shared Python APIs suggest applicability but do not
  establish product compatibility. The verifier target remains IDEA 262.
- Debug, pytest, Python console, package management and completed indexing have
  not been validated. The actual process checks use Python Run.
- WSL needs the full Python plugin's WSL factory. PythonCore alone supports the
  native Linux path tested here.
- Native local process precedence remains direnv-over-command-line; the WSL
  adapter's explicit Run override policy is not a global precedence change.
- No automatic SDK setup for native Windows, SSH/Docker targets or mixed Java/Python
  modules. A non-Python project SDK is preserved; select module SDKs manually.
- Nix-wrapper preservation is covered by the pure plan test. The actual venv
  process checks do not establish all devenv/Poetry/uv/Conda layouts.
