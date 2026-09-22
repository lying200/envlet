# ENV-11: native Cargo environment in Windows IDEA / WSL

Investigated 2026-09-23 against Windows IDEA IU-262.10968.63 and Rust plugin
262.10968.75, with NixOS WSL `legion-wsl`. Development artifact: 0.1.1-dev.

## Failure and cause

The owner's `ef-memory` Cargo sync failed in `libsqlite3-sys` 0.35.0 (bundled SQLite):
`CC=None`, followed by `failed to find tool "cc"`. Its approved devenv environment
already contained `CC=gcc` and an executable Nix GCC wrapper. The same dependency
compiled successfully through `direnv exec` in 12.71 seconds using an independent
target directory. No project source or Nix configuration changes were required.

A disposable Cargo package with the same bundled SQLite dependency reproduced the
failure in the installed IDEA, using separate config/system/plugins/log directories.
With 0.1.0-dev, Cargo workspace metadata was `UpToDate` but build-script evaluation
was `UpdateFailed`. The original MVP's ordinary WSL process probe did not exercise
Rust's actual Cargo launch path and therefore missed this problem.

Bytecode inspection and a runtime differential identified the boundary:

1. Rust's `RsWslToolchain.patchCommandLine` forces `launchWithWslExe=true`.
2. That legacy launcher serializes explicit environment into its shell command
   before `GeneralCommandLine.setupEnvironment` calls Envlet's customizer.
3. Envlet reports injection, but the Linux Cargo process still receives the earlier
   environment. A real `CC`/`cc` probe passes with the default EEL launcher and fails
   when forced through legacy `wsl.exe`.

## Fix and boundaries

The optional Rust module registers an `RsToolchainProvider`. It recognizes only an
open, trusted, enabled project's `.devenv/profile/bin` with automatic Rust management
enabled. Discovery prefers that profile only when its rustc and cargo are the same
files selected by the loaded environment's PATH. A shared Nix store toolchain path
does not identify a project and is not claimed by this provider.

`EnvletWslRustToolchain` retains WSL path mapping and tool discovery, while its UNC
executables let the platform start processes through EEL. Remote compiler paths
remain POSIX paths. Rust reconstructs toolchains from the saved home, so a provider
is necessary in addition to setting the home once.

Rust also chooses its build-script helper by concrete toolchain type. The adapter
corrects `RUSTC_WRAPPER` only when it equals the exact bundled host helper selected
by Rust, replacing it with the mapped Linux helper selected through `RsPathManager`.
An arbitrary user wrapper is preserved. No environment values are copied into IDE
settings, shell command strings or Envlet logs. No global Rust registry is changed,
and the implementation does not depend on the Internal `RsEelToolchain` class.

The scope is this managed devenv profile on build 262. Other toolchain directories
retain the previous launch behavior. This does not promise generic WSL toolchain
support, sudo execution or debugger compatibility. Cold environment loading and
reworked terminal/debugger validation remain separate tasks (ENV-9 and ENV-10).
Cargo manifest attachment remains the user's normal import action.

## Repeatable real-IDE regression

Use [scripts/verify-rust-wsl.groovy](../scripts/verify-rust-wsl.groovy) with an isolated
IDEA profile, matching Rust/Native Debugging Support plugins and Envlet installed.
The fixture must be a trusted WSL project with an approved `.envrc`, a devenv profile
containing Rust and a C compiler, and this Cargo dependency:

```toml
[dependencies]
libsqlite3-sys = { version = "=0.35.0", features = ["bundled"] }
```

Its `src/main.rs` must reference `std::env::consts::OS`. Prepare the Cargo lockfile
and dependency cache so offline builds can run. Set these properties in the isolated
profile's `idea.properties`:

```properties
envlet.validation.project=//wsl.localhost/DISTRO/path/to/disposable-project
envlet.validation.result=C:/path/to/isolated-profile/rust-validation.txt
```

With `IDEA_PROPERTIES` pointing to that file, run:

```text
idea64.exe ideScript C:\path\to\verify-rust-wsl.groovy
```

The script explicitly attaches the fixture's Cargo manifest as test setup. It waits
for actual import completion, reads the latest Cargo project snapshot, asserts
workspace and native build-script status, and launches Cargo's own generated command
lines with both terminal modes. It also checks provider scope, disabling management,
custom wrapper preservation and standard library reference resolution. The report
records statuses rather than arbitrary build output or environment contents.

## Observed acceptance

`test buildPlugin verifyPlugin` succeeded: 164 tests, zero failures/errors/skips.
Plugin Verifier reports **Compatible** with IU-262.10968.63, with 5 deprecated and
140 experimental API usages (the same counts as the MVP) and no Internal API usages.
Final packaging also incorporates the changelog, XML comment and formatting changes;
runtime behavior is the same as the tested build.

The installed Windows IDEA executed the regression using the production adapter:

```text
direnv=Loaded
toolchain=EnvletWslRustToolchain
provider.scope-and-disable=passed
custom-rustc-wrapper=preserved
cargo.workspace=UpToDate
cargo.buildScripts=UpToDate
cargo.build.terminal=false, exit=0
cargo.build.terminal=true, exit=0
rust.stdlib-reference=resolved
FINISHED
```

The fixture used rustc 1.98.1 and cargo 1.98.0. This is a real Rust plugin import
and native dependency build, not a simulated environment map. It does not establish
that every crate in the owner's full application builds, or that debugger sessions
work. The ordinary IDEA instance was left running with its existing plugin; the
new ZIP must be installed through **Install Plugin from Disk**, followed by restart
and Cargo reload. The previous 0.1.0-dev ZIP is retained for rollback.

Artifact: `build/distributions/envlet-0.1.1-dev.zip`.
SHA-256: `e301ef80adf66830876383158aa0347304cea72094bb1c9c20e5442065aa5e33`.
A matching copy is staged in the owner's Windows `Downloads/Envlet` directory.
