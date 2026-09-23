# ENV-23 Python support investigation

Date: 2026-09-23. Envlet 0.1.11-dev, reviewed source f058a26 (merged in
5cbe208), Windows IDEA IU-262.10968.63, NixOS WSL `legion-wsl`, Python 3.14.7.
This is an investigation, not a Python adapter implementation or release claim.

## Result

Envlet supplies the environment to Python processes launched through its generic
GeneralCommandLine hook. It does not currently configure a Python SDK, and the
Python plugin's WSL Target Run bypasses that hook. Consequently Python support is
partial for this Windows IDEA + WSL workflow.

The source/API investigation is in [python-support.md](research/python-support.md).
There is no Python product module or SDK synchronization setting in Envlet.
Opening this fixture and waiting for direnv loading did not assign a project SDK.
This scripted project opening is not an acceptance test of IDEA's interactive
import wizard or all of its automatic virtualenv discovery behavior.

## Actual process comparisons

Disposable fixture: `/home/echoyn/code/envlet-python-smoke`. It contains a Python
venv created with `python3 -m venv --without-pip .venv`, a module in `extras/`, and
an authored, explicitly approved `.envrc`:

```bash
export VIRTUAL_ENV="$PWD/.venv"
PATH_add "$VIRTUAL_ENV/bin"
export PYTHONPATH="$PWD/extras"
export ENVLET_PYTHON_TEST=fixture-only
```

`probe.py` records whether `sys.prefix != sys.base_prefix`, the fixed fixture
marker is present, and the module from `extras/` can be imported. Only fixture
values and the selected executable are recorded; ordinary project environments
were not dumped. No packages were downloaded into this virtualenv.

| Launch | Envlet | Venv used | Fixture variable | Import from PYTHONPATH |
| --- | --- | --- | --- | --- |
| Terminal `direnv exec ... python probe.py` | n/a | yes | yes | yes |
| IDEA GeneralCommandLine, UNC executable + Linux script arguments | disabled | yes | no | no |
| Same GeneralCommandLine | enabled | yes | yes | yes |
| Python Run, configured WSL Target SDK | disabled | yes | no | no |
| Same Python Run | enabled | yes | no | no |
| Same Python Run, explicit fixture variables as a positive control | enabled | yes | yes | yes |

All five IDE comparison processes exited 0. Missing imports are caught and reported
as booleans by the probe; exit 0 alone therefore does not mean environment support.
The positive control establishes that the WSL interpreter and dependency path work.
It is not a proposal to copy direnv values into saved run configurations.

The successful Python Run experiments used the actual
`PythonScriptCommandLineState.execute(DefaultRunExecutor)` process path with
`PyTargetAwareAdditionalData` and `HelpersAwareWslTargetEnvironmentRequest`.
Envlet's customizer was not called manually to simulate these Python Run results.

## Plugin variants and unsuccessful setup experiments

The user's installed plugin is Python Community Edition (`PythonCore`)
262.10968.63. In the isolated profile it reported no Python interpreter Target
factories. Generic process comparisons succeeded with this plugin installed.

For the WSL Target comparison, the matching full Python plugin (`Pythonid`)
262.10968.63 was downloaded from the official JetBrains Marketplace and installed
**only in the isolated profile**, alongside PythonCore. It registered the real
`PythonWslInterpreterTargetEnvironmentFactory`, and the resulting SDK and Run
processes used that provider. The user's regular IDEA plugins were not changed.

PythonCore also contains an EEL-aware SDK creation helper. It is therefore not
correct to conclude that every WSL Python route requires Pythonid merely because
its target factories are absent. The additional experiments did not validate that
alternative route:

- A manually constructed UNC virtualenv SDK failed in virtualenv activation with
  Envlet both disabled and enabled. This construction alone is not proof of a
  product bug.
- The plugin's own `createLocalSdkGuessingTypeByPath` helper also failed for the
  virtualenv, in `ShellEnvironmentReader.shellCommand` through
  `readPythonEnvironment` / `activateVirtualEnv`.
- Trying the Nix system interpreter through that helper returned a `PyInvalidSdk`;
  the experiment was stopped without a successful Python Run result.

These are setup observations, not a supported configuration recommendation. The
successful WSL Target comparison above is the basis for the environment-delivery
conclusion, independent of those failed setup experiments.

## Reproduction and remaining boundaries

[verify-python-wsl.groovy](../scripts/verify-python-wsl.groovy) records the successful
WSL Target comparison. It assumes the authored fixture, both matching Python
plugins, and an isolated `idea64.exe ideScript` profile; its SDK API calls are test
scaffolding, not APIs adopted by Envlet production code.

Local reports:

- `/home/echoyn/temp/env23-python-target-validation.txt`: complete successful
  off/on/explicit-control comparison, ending in `FINISHED`.
- `/home/echoyn/temp/env23-python-ce-venv.txt`: preliminary Community setup.
- `/home/echoyn/temp/env23-python-eel-venv.txt`: plugin helper activation failure.
- `/home/echoyn/temp/env23-python-eel-system.txt`: incomplete system-SDK experiment.

No new production code was added and no Gradle tests were needed for these
runtime/documentation observations. SDK indexing/completion, debugger, test runner,
Python console, package management, native Linux IDEA, and a devenv-specific
Python environment were not validated. Plain direnv is sufficient to demonstrate
the WSL Run integration gap, but does not establish those other capabilities.

The isolated IDE was stopped, original launcher properties/script restored, and
both test-only Python plugins moved outside its active plugins directory. Fixtures,
reports and sandbox-only SDK metadata remain available for follow-up. The main
IDE and ordinary projects were not changed.
