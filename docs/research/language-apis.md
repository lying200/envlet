# Go / Rust API validation for Windows IDEA + WSL

Investigated 2026-09-22. Scope: IntelliJ IDEA 2026.2.3 (`262.10968.63`) running on Windows, project and devenv toolchains in WSL distribution `legion-wsl`.

## Runtime follow-up

The packaged Envlet build was loaded into the installed Windows IDEA using an isolated
configuration. Opening a trusted WSL fixture automatically selected Go 1.26.7, its devenv
GOPATH, the Nix Rust toolchain and standard library source path without manual SDK edits.
This is additional runtime evidence; importing/indexing/building are tracked separately.
Rust 262 also requires the matching **Native Debugging Support** plugin, as declared by
its distribution. Both were installed only in the validation profile.
Automatic management currently leaves the last selected SDK paths when disabled; it does
not restore previous manual settings. This differs from the restoration recommendation below.

## Result

**Both languages have callable APIs that can bind a WSL toolchain.** A minimal Java call sketch below compiled successfully against the user's actual IDEA and Go binaries plus the matching official Rust plugin. This establishes API availability and type compatibility, not runtime correctness: no project configuration was modified, Rust was not installed into the user's IDE, and indexing/run/debug remain integration-test work.

An earlier proposal overstated `com.goide.sdkProvider`: it contributes SDK creation **UI actions** (`getCreateSdkActions`), not automatic environment-based SDK resolution. Envlet should prepare a SDK and use `GoSdkService` for automatic project binding. The official [Go extension-point list](https://plugins.jetbrains.com/docs/intellij/goland-extension-point-list.html) lists that EP; its actual interface was checked in the installed binary [G].

## Evidence and versions

- **[I]** Installed IDEA at `C:/Users/echoyn/AppData/Local/Programs/IntelliJ IDEA`; WSL classes in `lib/intellij.platform.ide.impl.jar`. `javap -v -p` inspected signatures, bytecode and annotations.
- **[G]** Installed Go plugin `org.jetbrains.plugins.go`, version `262.10968.63`, compatibility `262.10968`–`262.*`; API classes in `lib/intellij.go.impl.jar`. SHA-256: `90c751bf9266ee6762ca75672113fecef7c291135fc6faae9b3cd2b26847287f`. Official [Go plugin](https://plugins.jetbrains.com/plugin/9568-go).
- **[R]** Official Rust plugin `com.jetbrains.rust`, version `262.10968.75`, compatibility `262.10968`–`262.*`. Downloaded using [Marketplace update metadata](https://plugins.jetbrains.com/api/plugins/22407/updates?size=10) and the [exact release archive](https://plugins.jetbrains.com/files/22407/1173114/intellij-rust-262.10968.75.zip). Archive SHA-256: `fb92e1e718f602c236c6047d8e26ead4d8591d9727d684a71439922ff0086b61`. API classes in `intellij-rust/lib/modules/intellij.rustrover.core.jar`; service registration verified in `META-INF/rust-core.xml`.
- Scratch artifacts, `javap` dumps, downloaded Rust archive, sketch and compile result are in `/home/echoyn/temp/envlet-api`; they are not shipped with Envlet.

**Build requirement discovered:** these exact Go/Rust/WSL classes have class-file major version **69 (Java 25)**. Linux JDK 21 failed with `class file has wrong version 69.0, should be 65.0`. Repeating with the installed Windows IDEA's `jbr/bin/javac.exe` (`javac 25.0.4`) succeeded. Do not assume Java 21 can compile the adapter against build 262 just because the fork previously targeted an earlier platform. [I, G, R]

## Go binding

| Operation | Confirmed entry point | Behavior / constraints |
| --- | --- | --- |
| Map Linux GOROOT | `WSLDistribution.getWindowsPath(String)` | Use the project's distribution; produces a Windows-accessible path. A `/nix/store/...` string alone is not a Windows SDK. [I] |
| Construct SDK | `GoSdk.fromHomePath(String)` | Pass mapped GOROOT, then check `sdk.isValid()`. Go's reported GOROOT is authoritative; do not assume it is the parent of the resolved `go` executable. [G] |
| Confirm WSL | `GoWslUtil.getWsl(GoSdk)` / `isInWsl(GoSdk)` | WSL association derives from SDK location. Compare distribution with the project before applying. [G] |
| Bind project | `GoSdkService.getInstance(project).setSdk(sdk)` | Normal notifying path asserts EDT, updates SDK state, clears Go resolve caches, invokes `GoLibrariesUtil.updateLibraries(...RESCAN_DEPENDENCIES_IF_NEEDED...)`, and publishes SDK-related changes. Do not suppress notifications with `setSdk(sdk, false)`. [G] |
| Project GOPATH | `GoProjectLibrariesService.setLibraryRootUrls(Collection<String>)` | Split Linux `GOPATH` on `:`, map each root to Windows, convert to VFS URL. Library setters notify roots changes. [G] |
| Disable unrelated host GOPATH | `setUseGoPathFromSystemEnvironment(false)` | Apply only when Envlet owns project toolchain management. Preserve/restore the user's prior setting. [G] |
| Go modules environment | `VgoProjectSettings.setEnvironment(Map<String,String>)` | Exists but is persistent project configuration. It is not a replacement for per-process environment injection. [G] |

Probe `go env -json GOROOT GOPATH GOMODCACHE GOVERSION GOOS GOARCH` **inside the loaded direnv environment in that WSL distribution**. Use `go env` output rather than reading environment variables alone: default GOROOT/GOPATH can be absent from the environment. Do not enable whole-GOPATH indexing merely to refresh roots; leave `isIndexEntireGopath` policy untouched. [Implementation recommendation based on G]

`GoWslProjectConfigurator.configureProjectInWsl` exists, but performs first-time project configuration including defaults discovered outside Envlet's precise environment. Prefer explicit known settings to rerunning the configurator. [G]

## Rust binding

The current official documentation supports WSL toolchains selected through a UNC path and separately configurable standard library sources: [Rust toolchain](https://www.jetbrains.com/help/rust/rust-toolchain.html). These are current binary APIs, not assumptions based on the archived open-source intellij-rust project. [R]

| Operation | Confirmed entry point | Behavior / constraints |
| --- | --- | --- |
| Explicit WSL toolchain | `new RsWslToolchain(new WslPath(distroId, linuxBin))` | Stores a WSL distribution and Linux directory, creates a mapped local location, and patches tool execution for WSL. Directory must expose `rustc` and `cargo`. [I, R] |
| Settings service | `project.getService(RustProjectSettingsService.class)` | Project service, also exposes currently selected toolchain, stdlib and environment getters. [R] |
| Update settings | `service.modify(state -> ...)` | Copies previous state, performs mutation, then emits settings change notifications. Use this instead of assigning raw state or `loadState`. [R] |
| Toolchain state | `state.setToolchain(RsToolchainBase)` | Persists the toolchain's system-independent mapped location; getter reconstructs via provider. Current plugin registers `RsWslToolchainProvider`. [R] |
| Standard library | `state.setExplicitPathToStdlib(String)` | Supply Windows-accessible source location; validate existence/layout with the real Rust plugin. [R] |
| Project env | `state.setEnvs(Map<String,String>)` | Public setter exists; project-level variables are also documented in [Rust environment settings](https://www.jetbrains.com/help/rust/rust-environment-variables.html). This state persists: do not dump arbitrary direnv values into it. [R] |
| Cargo refresh | `project.getService(CargoProjectsService.class).refreshAllProjects(false)` | Returns `kotlinx.coroutines.Deferred<List<CargoProject>>`; observe completion in real implementation. Settings changes may already trigger refresh, so avoid duplicate refreshes. [R] |
| Initial discovery | `CargoProjectsService.discoverAndRefresh()` or `attachCargoProject(Path)` | Discovery returns `CompletableFuture`; attach returns `Deferred`. Use only when initial Cargo project import is needed, with Windows-visible manifest paths. [R] |

For Nix/devenv, `rustc` and `cargo` can come from separate store packages. Do not blindly select the parent directory of `readlink -f $(command -v rustc)` as the entire toolchain. Prefer an existing dev profile `bin` directory that exposes both and verify with the selected environment. Probe `rustc --print sysroot`, check `RUST_SRC_PATH` and source candidates, and report missing sources rather than automatically invoking rustup. These are implementation recommendations; no Rust toolchain was installed or changed in this investigation.

## API status and maintenance

`javap -v` found **no `ApiStatus.Internal`, `Experimental`, `Obsolete`, or removal annotations on the inspected Go binding classes or Rust binding/settings/Cargo service classes**, and no relevant package-info annotations. That means callable, unmarked APIs in these versions; it does **not** establish a documented long-term binary compatibility contract. Keep the adapters optional and verify each supported build. [G, R]

`WslPath` and `WSLDistribution.getWindowsPath` are unmarked in the installed platform. Some nearby WSL methods are explicitly restricted: `WSLDistribution.doPatchCommandLine` and the old `(commandLine, project, shell, boolean)` overload are **Internal**; `getEnvironmentVariable` is **Internal**; `getUNCRootPath` is **Experimental**. Avoid these when implementing the adapter. The public `patchCommandLine(commandLine, project, WSLCommandLineOptions)` overload is the relevant execution entry point. [I]

Core process injection remains a separate compatibility concern; proving language setters exist does not prove environment forwarding through every WSL launch path. The runtime acceptance tests must inspect the actual version/path seen by Go builds, Cargo metadata, rustc, and native build scripts.

## Compiled minimal call sketch

This exact Java source compiled with exit code 0 and no diagnostics using the Windows IDEA JBR 25 compiler against all installed IDEA/Go jars and the downloaded official Rust jars. It is a type-checking fixture, not the production adapter: ownership, cancellation, validation, threading around probes, rollback and completion reporting must be added before automatic use.

```java
import com.goide.sdk.GoSdk;
import com.goide.sdk.GoSdkService;
import com.goide.execution.GoWslUtil;
import com.goide.project.GoProjectLibrariesService;
import com.intellij.execution.wsl.WslPath;
import com.intellij.execution.wsl.WSLDistribution;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import org.rust.cargo.toolchain.wsl.RsWslToolchain;
import org.rust.cargo.project.settings.RustProjectSettingsService;
import org.rust.cargo.project.model.CargoProjectsService;
import java.util.List;
import java.util.Map;
import kotlin.Unit;

public final class EnvletApiSmoke {
  // Inputs are already probed in the project's selected WSL distribution.
  public static GoSdk prepareGo(WSLDistribution distro, String linuxGoroot) {
    GoSdk sdk = GoSdk.fromHomePath(distro.getWindowsPath(linuxGoroot));
    if (!sdk.isValid() || !GoWslUtil.isInWsl(sdk))
      throw new IllegalArgumentException("Invalid WSL Go SDK");
    return sdk;
  }
  // Invoke on EDT, after background probing and ownership checks.
  public static void applyGo(Project project, GoSdk sdk,
      WSLDistribution distro, List<String> linuxGopaths) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    GoSdkService.getInstance(project).setSdk(sdk);
    GoProjectLibrariesService libraries = GoProjectLibrariesService.getInstance(project);
    libraries.setUseGoPathFromSystemEnvironment(false);
    libraries.setLibraryRootUrls(linuxGopaths.stream()
        .map(distro::getWindowsPath).map(VfsUtilCore::pathToUrl).toList());
  }
  // linuxBin must expose BOTH rustc and cargo. Stdlib path is already mapped to UNC.
  // environmentOverrides is deliberately chosen project configuration, not an env dump.
  public static void applyRust(Project project, String distroId, String linuxBin,
      String stdlibUnc, Map<String, String> environmentOverrides) {
    RsWslToolchain toolchain = new RsWslToolchain(new WslPath(distroId, linuxBin));
    project.getService(RustProjectSettingsService.class).modify(state -> {
      state.setToolchain(toolchain);
      state.setExplicitPathToStdlib(stdlibUnc);
      state.setEnvs(environmentOverrides);
      return Unit.INSTANCE;
    });
  }
  // Explicit refresh action; settings changes may already schedule one.
  public static void refreshRust(Project project) {
    project.getService(CargoProjectsService.class).refreshAllProjects(false);
  }
}
```

Optional plugin descriptors should depend on `org.jetbrains.plugins.go` and `com.jetbrains.rust` respectively so missing language plugins do not prevent Envlet loading. The Rust classes live in a current content module; run Plugin Verifier/module dependency checks as part of packaging, beyond this direct classpath compilation. [G, R]

## Required runtime checks

1. In an isolated WSL Go project, apply detected SDK/GOPATH; confirm settings show the right distribution, standard library resolves, and Run uses the expected Go version.
2. In an isolated WSL Cargo project after installing the matching Rust plugin, apply the profile bin/source paths; confirm Cargo metadata succeeds, standard library navigation works, and native build scripts receive the devenv compiler/linker environment.
3. Change devenv inputs, reload, and confirm only Envlet-owned settings update. Reject invalid probe results and preserve a useful diagnostic instead of installing a guessed SDK.
4. Keep two projects using different toolchains open and confirm project/distribution isolation, including the Go modules and Rust Cargo background processes.
5. Repeat with Shell integration enabled after the fish compatibility fix; terminal success alone does not establish language-model correctness.
