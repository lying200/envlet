# Python support investigation

Investigated 2026-09-23 against Envlet `5cbe208` / `0.1.11-dev`, Windows IDEA
`IU-262.10968.63`, and the installed **Python Community Edition** plugin
(`PythonCore`, `262.10968.63`). This note records documentation, source and
binary inspection only; it does not establish runtime success.

## Documented IDEA behavior

IDEA requires a Python SDK and supports interpreter selection at project and
module level. Existing virtual environments can be selected by their Python
executable. Its WSL configuration asks for the distribution and Linux interpreter
path. A selected interpreter and its configured paths supply code indexing;
having `python` on a terminal's `PATH` is not equivalent to configuring that SDK.
[IDEA Python SDK documentation](https://www.jetbrains.com/help/idea/configuring-python-sdk.html)

PyCharm documents discovering interpreters for projects opened in `\\wsl$` and
supports existing WSL virtualenv, conda, pipenv, poetry, uv and hatch environments.
That documentation does **not** promise to evaluate an arbitrary project's
`.envrc` or track subsequent direnv environment changes. WSL interpreter discovery
therefore should not be described as direnv integration.
[WSL interpreter documentation](https://www.jetbrains.com/help/pycharm/using-wsl-as-a-remote-interpreter.html)

## Baseline before ENV-24

Envlet has no Python adapter, Python dependency, SDK synchronizer or interpreter
selection setting. The descriptor registers general process injection and optional
terminal, Gradle, Java, JavaScript, Go and Rust integrations. Consequently Envlet
does not intentionally bind a Python SDK from `VIRTUAL_ENV` or the direnv `PATH`.
This is a repository inference, not a claim that IDEA itself can never discover
such an environment. [Plugin descriptor](../../src/main/resources/META-INF/plugin.xml),
[process customizer](../../core/src/main/kotlin/io/github/salatmaster/direnv/inject/DirenvCommandLineEnvCustomizer.kt)

Environment delivery must be tested separately from SDK selection. The existing
customizer changes `GeneralCommandLine` environments when it can resolve a trusted,
enabled project and the actual working directory. It cannot itself update Python
SDK indexing roots or intercept every alternative process API.
[Process customizer](../../core/src/main/kotlin/io/github/salatmaster/direnv/inject/DirenvCommandLineEnvCustomizer.kt)

## Process routes in the inspected build

| Route | Inspected behavior | Consequence for current Envlet |
| --- | --- | --- |
| Python normal Run | `PythonCommandLineState.execute(Executor)` uses the Targets route: `PythonExecution` → `TargetedCommandLine` → `TargetEnvironment.createProcess`. | The concrete target implementation matters; using Targets alone does not prove a bypass. |
| Local target | `LocalTargetEnvironment.createProcess` creates a `GeneralCommandLine` and calls its `createProcess()`. | The generic Envlet hook remains reachable; actual run/import behavior still needs validation. |
| WSL target | `WslTargetEnvironment.createProcess` delegates to `EelTargetEnvironment`, which calls `EelExecApi.spawnProcess` directly. | This route bypasses `GeneralCommandLineEnvCustomizer`; core injection alone cannot provide direnv values to it. |

There is an important fixture distinction: the installed
`PythonInterpreterTargetEnvironmentFactory.findPythonTargetInterpreter(sdk, project)`
uses target factories only when SDK additional data implements
`TargetBasedSdkAdditionalData`. Otherwise it returns a
`HelpersAwareLocalTargetEnvironmentRequest`. Thus a bare SDK with only a UNC
`homePath` tests the **local target** route (whose eventual general command line
may still launch through EEL), not the WSL target route. Inspect the selected
request class during runtime validation rather than inferring it from the path.
In the installed `python-ce` jars, the target factory EP is declared but no WSL
factory implementation or registration was found. The full Python plugin and its
enabled target factories must be checked before claiming target-aware WSL support
for the user's installation. These are local binary/descriptor observations;
the generic product documentation alone does not identify the installed plugin's
available factories.

This does **not** mean PythonCore necessarily requires the full Python plugin to
execute every WSL interpreter. Its installed `CreateSdkKt` exposes the suspend
`createLocalSdkGuessingTypeByPath(Path, ModuleOrProject, String?, Continuation)`
helper. Despite its name, it resolves the path's EEL descriptor, constructs an
`EelFileSystem`, detects the Python flavor and sets up an EEL-backed ordinary SDK.
`new ModuleOrProject.ProjectOnly(project)` supplies the working directory. The
returned `com.jetbrains.python.Result` offers `getSuccessOrNull()` and
`getErrorOrNull()`. This is a better fixture creation route than manually guessing
additional data, but the helper is `ApiStatus.Internal` and persists the SDK;
use only the isolated validation profile for this experiment.
[JetBrains SDK creation source](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/sdk/createSdk.kt)

These paths were checked in the installed build's bytecode, not inferred only
from current `master`. The corresponding public JetBrains source shows the same
Python, local and WSL route structure:
[PythonCommandLineState](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/run/PythonCommandLineState.java),
[LocalTargetEnvironment](https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/target/local/LocalTargetEnvironment.java),
[WslTargetEnvironment](https://github.com/JetBrains/intellij-community/blob/master/platform/execution-impl/src/com/intellij/execution/wsl/target/WslTargetEnvironment.kt).
WSL's login environment may provide unrelated variables, so use a fixture-only
canary and a direct `GeneralCommandLine` control to distinguish Envlet injection.
This last sentence is a validation recommendation, not a runtime result.

## Potential adapter seams and limits

`Pythonid.runConfigurationExtension` is a documented extension for Python run
configurations. However, in this installed build, its legacy `patchCommandLine`
call appears in the legacy process branch, not the normal Targets branch. Merely
implementing that method would not cover the WSL route above.
[Run configuration extension documentation](https://plugins.jetbrains.com/docs/intellij/run-configurations.html#modifying-existing-run-configurations),
[PythonCommandLineState source](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/run/PythonCommandLineState.java)

The Targets branch invokes `PythonCommandLineTargetEnvironmentProvider` before
launch. Its `extendTargetEnvironment(project, request, pythonExecution, runParams)`
can access the transient Python execution, but **both `Internal` and `Experimental`
annotations are present** in the installed binary and official source. It is a
plausible technical integration point, not an established stable API. A production
choice requires an explicit compatibility decision and tests; do not silently
persist direnv values into the user's run configuration as a workaround.
[Provider source](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/run/target/PythonCommandLineTargetEnvironmentProvider.kt),
[official extension-point list](https://plugins.jetbrains.com/docs/intellij/intellij-community-plugins-extension-point-list.html#python-plugin)

The installed plugin also exposes `PythonSdkType`,
`PySdkExtKt.configurePythonSdk(project, module, sdk)` and
`PyTargetAwareAdditionalData` with interpreter and target configuration fields.
This establishes candidate SDK integration surfaces only: no compile check,
threading audit, compatibility promise, or SDK setup/indexing success is claimed.
Do not assume an ordinary SDK with a UNC executable is interchangeable with a
target-aware WSL SDK: the factory distinction above changes its process route.
[IDEA's explicit WSL SDK setup](https://www.jetbrains.com/help/idea/configuring-python-sdk.html#configure-a-wsl-interpreter)

Local binary evidence is retained outside the repository in
`/home/echoyn/temp/envlet-python-research/`: `python-command-line.txt`,
`target-environments.txt`, `target-provider.txt`, `python-sdk-api.txt` and virtualenv
flavor dumps. Classes came from the installed `python-ce/lib/modules/` jars and
IDEA's `lib/intellij.platform.execution{,.impl}.jar`; only read-only `javap` inspection
was used. Runtime acceptance should cover fresh SDK discovery, an explicitly
selected WSL SDK, actual Run/test/debug processes, environment changes and
revocation separately. Package management, console and debugger helpers must not
be assumed covered by a successful plain Run test.

### Virtualenv-specific diagnostic boundary

The installed `PythonCommandLineState.setupVirtualEnvVariables` checks the
`python.activate.virtualenv.on.run` registry setting and the detected environment's
`isActivatable` property. `PySdkUtil.activateVirtualEnv` redetects the environment
from SDK home, so simply marking a venv executable as a Unix/system flavor does
not suppress activation. In `ActivatableScriptExtKt.readPythonEnvironment`, a
POSIX script path leads to the system-default shell and
`ShellEnvironmentReader.shellCommand`; this happens before the method's
`IOException` handler. A failure there is a separate prerequisite to reaching
Envlet's process hook. Inspecting the exception with Envlet disabled and creating
the SDK through the helper above distinguishes a plugin issue from an invalid
manual fixture. A Nix system interpreter with a fixture-only `PYTHONPATH` is also
a useful independent process-injection test because it does not require virtualenv
activation. These are bytecode-based test recommendations, not observed outcomes.

## Implementation API decision for build 262

Follow-up inspection used the matching full Python plugin retained at
`EnvletValidation/env23-disabled-python` and PythonCore at
`EnvletValidation/env23-disabled-python-ce`. The following is an implementation
recommendation based on those binaries; runtime evidence belongs in the separate
validation record.

- **Native Linux:** use an ordinary Python SDK with a real Linux executable and
  `PythonSdkAdditionalData(flavorAndData, projectWorkingDirectory)`. Discover and
  validate the interpreter in the current direnv environment; setting SDK home
  does not itself establish standard-library/package roots.
- **Windows + WSL:** use `WslTargetEnvironmentConfiguration(distribution)` and
  `PyTargetAwareAdditionalData`; set its Linux `interpreterPath`, then use its
  generated `sdkId` as SDK home. The full plugin's
  `com.intellij.python.wsl.PythonWslInterpreterTargetEnvironmentFactory` accepts
  that target data and returns a helpers-aware WSL request. Check an enabled
  factory accepts the configuration before publication; do not silently fall
  back to a bare UNC/local SDK when support is missing.
- **Missing factory:** leave SDK configuration unchanged and show a specific
  “WSL Python interpreter support unavailable” diagnostic. Offer the manual
  interpreter route or explain that this integration requires the full Python
  plugin's WSL support. This requirement applies to the selected target route,
  not to every conceivable PythonCore/EEL execution.

**API status is not the same as JVM visibility.** `PythonSdkType.setupSdkPaths(Sdk)`
is an unmarked public `SdkType` override. In contrast,
`PySdkExtKt.configurePythonSdk`, `ModuleExKt.setPythonSdk`,
`PythonSdkUpdater.updateVersionAndPathsSynchronouslyAndScheduleRemaining`, the
inspected `PyTargetAwareAdditionalData` constructors, and `UnixPythonSdkFlavor`
are Internal. Manually constructing a target SDK therefore reduces helper
dependencies but does **not** remove Internal API usage. Keep the necessary
surface in a small optional Python adapter, document it and verify the exact
supported build. Avoid reflection merely to hide such dependencies from verification.
[SDK creation source and status](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/sdk/createSdk.kt)

Recommended lifecycle:

1. Probe interpreter and paths on a background thread without IDE locks. Check
   project trust, approval and the current environment generation.
2. Create/update the SDK's home and additional data and register it in
   `ProjectJdkTable` in a short EDT write action. Persist only necessary SDK
   identity, interpreter/distribution and source paths, never the direnv map.
3. Refresh SDK roots outside EDT/read/write locks. The explicit-project updater
   is preferable for autonomous synchronization if Internal APIs are accepted:
   `updateVersionAndPathsSynchronouslyAndScheduleRemaining(sdk, project)` evaluates
   version/local paths and schedules remaining work. A `true` result does not mean
   remote skeleton generation or indexing completed. `setupSdkPaths(Sdk)` instead
   obtains its project from UI context using `invokeAndWait`, then calls the same
   updater; its public status alone does not make it the better background API.
4. Recheck current environment before project/module binding. For pure Python
   projects, platform SDK setters can bind an owned SDK. Mixed IDEA modules need
   the Python plugin's facet-aware binding: `ModuleExKt.setPythonSdk` routes through
   `PyModuleService`, which uses Python facets for non-Python modules. Directly
   replacing such a module's SDK can overwrite its Java SDK. The native setter
   expects a background caller, performs its short UI write internally and emits
   Python SDK notifications. Do not put it inside a caller-held read/write action.

A minimal first implementation can explicitly restrict automatic binding to
eligible Python modules and report other module layouts. If mixed modules are
included, use the native facet-aware lifecycle rather than duplicating its rules.
Separate environment delivery remains necessary for actual Run/test processes;
correct SDK roots alone cannot repair the Targets injection gap. Acceptance must
observe actual imports and roots after asynchronous refresh, not just SDK names.

For the initial implementation, restrict automatic project publication to a
project SDK that is absent or Python; preserve any non-Python project SDK and
report that mixed-language SDK binding is not managed. Also preserve explicit
non-Python module SDKs. This intentionally avoids `configurePythonSdk`: its code
sets the project SDK when the module matches the project base directory, so it
does not independently guarantee preservation of a Java project SDK.

Minimal build-262 construction sketch (not a complete lifecycle implementation):

```kotlin
import com.intellij.execution.wsl.target.WslTargetEnvironmentConfiguration
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.flavors.PyFlavorAndData
import com.jetbrains.python.sdk.flavors.PyFlavorData
import com.jetbrains.python.sdk.flavors.UnixPythonSdkFlavor
import com.jetbrains.python.target.PyTargetAwareAdditionalData

val flavor = PyFlavorAndData(PyFlavorData.Empty, UnixPythonSdkFlavor.getInstance())
val localData = PythonSdkAdditionalData(flavor, projectDirectory)
val wslData = PyTargetAwareAdditionalData(
    flavor, projectDirectory, WslTargetEnvironmentConfiguration(distribution), null
).apply { interpreterPath = linuxExecutable }
// In a short SDK-modificator write: homePath = wslData.sdkId,
// sdkAdditionalData = wslData. For native Linux use the executable as homePath.
```

Use the direct four-argument constructor for WSL to avoid constructing unused
base additional data; it has the same Internal status as the two-argument overload.
Read `sdkId` only after assigning the interpreter path. Do not call `setSdkId`:
its implementation always throws because this identifier is derived from fields.
Do not force `valid` or helper-upload flags to simulate successful SDK setup.
Internal API usage in this adapter is an explicit build-262 compatibility choice;
keep verifier diagnostics visible and do not suppress the verifier or hide calls
behind reflection.

Inspection artifacts: `configure-sdk-v.txt`, `module-ex-v.txt`,
`sdk-type-updater-v.txt`, `sdk-setup-c.txt`, `additional-api-v.txt`,
`module-service.txt` and `wsl-factory.txt` under the same external research directory.


## Final implementation choices (ENV-24)

- Probe a candidate inside `direnv exec` and retain the PATH-selected launcher:
  Nix wrappers and venv symlinks must not be replaced by `sys.executable`.
- Publish SDK metadata only after the shared root-environment guard succeeds.
  Python's public `PythonSdkUpdater.scheduleUpdate` then owns background setup on
  the published SDK. Do not run the synchronous-and-schedule updater on a temporary
  candidate: a second request on the published SDK can race skeleton generation.
- `ProjectRootManager` owns the Python project SDK. A non-Python project SDK is
  left in place, with a safe `PYTHON/CONFIGURATION/SDK_CONFLICT` diagnostic. Module
  facets and mixed-language ownership are deliberately not inferred.
- The Python Target provider changes only the in-memory `PythonExecution` after
  target cwd mapping, and checks the WSL distribution and project containment.
  The same cache-only rule applies on EDT/under IDE locks as in the core injector.
- WSL/EEL Targets combine inherited environment with string overrides; this
  extension has no removal operation. A requested unset is rejected explicitly
  unless an explicit Run setting replaces it. Empty strings are not removals.
- Native local runs continue through the core injector. In particular its existing
  direnv-over-command-line precedence differs from the WSL adapter's explicit Run
  override precedence; no global precedence changes were made for Python.
- `verifyPlugin` checks binary compatibility and an exact Internal API baseline.
  The reviewed API/caller references are in `config/python-262-internal-api.txt`.
  They remain visible in verifier reports; new references fail the Gradle task.
  This is a compatibility commitment for 262, not a claim of public API stability.


### Startup project-model boundary

Actual reopen tests reproduced early SDK selection being overwritten by the
platform's later disk-to-workspace synchronization. The Python startup activity
now suspends on `WorkspaceModelInternal.awaitSynchronizationWithJpsModel` before
registering its environment listener. The listener then reconciles the current
root snapshot, including a load that completed during that wait. This is one
platform lifecycle boundary, not polling, retries or another cache. It also makes
the existing Java SDK check meaningful after disk configuration has loaded.
The platform deprecates JpsProjectLoadingManager in favor of this method; this
additional Internal dependency is explicitly recorded in the verifier baseline.
[Platform lifecycle API guidance](https://github.com/JetBrains/intellij-community/blob/master/platform/projectModel-api/src/com/intellij/workspaceModel/ide/JpsProjectLoadingManager.kt)
