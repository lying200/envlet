# ENV-13: Rust SDKs independent of devenv — 2026-09-23

Artifact: 0.1.3-dev. Verified in Windows IDEA IU-262.10968.63 with Rust plugin
262.10968.75 and NixOS WSL. The user's ordinary IDEA was not restarted or modified.

## Problem and design

The [plain-direnv baseline](validation-plain-direnv.md) demonstrated two failures:
separate Nix bin directories could not be discovered as one Rust SDK, and a common
non-devenv directory used Rust's legacy WSL launcher and lost the compiler environment.
The same projects compiled and ran bundled SQLite from the terminal.

Rust reconstructs SDKs through `RsToolchainProvider.getToolchain(homePath)`, which
has no project argument. Claiming a shared `/nix/store` directory would affect every
project using it. A project-specific, real SDK home solves both problems:

1. Discover rustc and cargo independently from the loaded PATH and probe them.
2. Resolve their canonical executable paths and available companion tools.
3. Prepare `.direnv/envlet/rust/<tool-mapping-hash>/bin` inside the project. It contains
   only symbolic links to those programs. Different projects receive different homes;
   changed tool mappings receive new immutable directories.
4. Validate the SDK, then publish an in-memory project binding and update Rust settings.
5. The WSL provider claims only that exact published home while its environment is
   still current, the project is trusted, and Envlet/Rust management remain enabled.

All POSIX projects use this layout, including devenv and plain direnv. It replaces
the `.devenv/profile/bin` special case rather than adding a second directory exception.
Native Linux uses the ordinary `RsLocalToolchain`; Windows/WSL uses the existing EEL
adapter, including the bounded Linux helper correction and POSIX PATH behavior.
Native Windows retains discovery from a common directory without requiring symlink
privileges. No global Rust registry or shared Nix installation is changed.

Only necessary SDK paths are persisted as links and the IDE's selected home. Environment
values remain in memory. No shell launcher or environment export file is generated.
Filesystem preparation runs in the existing background toolchain synchronization job,
not the EDT. Publication rechecks the environment and settings before applying results.

Envlet writes `.direnv/envlet/.gitignore` only if absent, leaving the project's root
ignore file alone. Conflicting files or redirected cache directories are rejected
without overwriting them. Repeated preparation of the same mapping is idempotent.
Old cache entries are retained to avoid disrupting running processes. After restoring
SDK selections and closing projects, `.direnv/envlet` can be removed independently
of other direnv cache data; no automatic cache garbage collection is implemented.

## Regression evidence

The previous investigation was converted to an asserting regression **before** the
production fix. With 0.1.2-dev it reported no SDK for split tools, exit 101/missing cc
for common tools, and `Plain direnv Rust acceptance failed`.

The production 0.1.3-dev build passed the same two fixtures in the actual installed
Windows IDEA, using the isolated profile:

| Check | split-tools | common-tools |
| --- | --- | --- |
| direnv load / ordinary process compiler probe | Passed | Passed |
| Automatic SDK | EnvletWslRustToolchain | EnvletWslRustToolchain |
| Cargo workspace metadata | UpToDate | UpToDate |
| Cargo native build-script evaluation | UpToDate | UpToDate |
| Cargo build, ordinary mode | Exit 0 | Exit 0 |
| Cargo build, terminal mode | Exit 0 | Exit 0 |

No SDK was assigned by the script. Existing project settings from 0.1.2 were allowed
to migrate automatically. The projects were open together and used the same underlying
rustc. Their SDK homes differed. Disabling Envlet or Rust management in one project
stopped its provider ownership without affecting the other. A shared Nix store tool
directory was not claimed.

The script explicitly revoked approval of the newly authored split-tools fixture,
confirmed the provider stopped claiming its SDK while the second project remained
managed, and restored the fixture's original approval in `finally`. The restored
environment reacquired its SDK. Production code never approves an envrc automatically.

The original devenv fixture also passed after automatic migration to the new home:
Cargo metadata/build scripts, both build modes, all ENV-11 PATH checks, custom wrapper
preservation, provider disable/scope checks and Rust standard library reference resolution.

The suite contains 169 passing tests, including five new filesystem tests for separate
tools, immutable mapping changes, project isolation, conflicting entries and redirected
cache/name rejection. A separate real Windows IDEA probe confirmed that Java NIO
creates usable WSL symlinks without native Windows symlink privileges.

## Limits and replay

The new helper can be unit-tested on the local filesystem, but full native NixOS desktop
IDE validation remains outstanding. Debugger sessions, every Cargo extension and rustup
channel switching have not been validated. Projects need writable, non-redirected cache
directories. Failed preparation preserves the existing SDK selection and emits a bounded
diagnostic without process output/environment values.

Replay with the isolated profile settings described in [ENV-11](validation-env11.md):

- `scripts/verify-plain-direnv-rust.groovy`, pointing at the parent of the authored
  split-tools/common-tools fixtures. It now asserts acceptance and exercises fixture
  approval revoke/restore, so do not substitute ordinary user projects.
- `scripts/verify-rust-wsl.groovy`, pointing at the existing approved devenv fixture.

The SDK cache implementation introduces no environment hook or remote shell wrapper.
Rust's helper-selection compatibility workaround remains a version-dependent part of
the WSL adapter, so supported IDEA/Rust builds still require runtime verification.

Plugin Verifier reports Compatible with IU-262.10968.63, with 5 deprecated and 140
experimental API usages and no Internal API usages. Final packaging includes the
updated changelog and documentation; production behavior is unchanged from the
real-IDE acceptance build.

Artifact: `build/distributions/envlet-0.1.3-dev.zip`.
SHA-256: `7ea0cadea6e943f2628d105da0f49f93709c990f4e16493805e1657deee57eb5`.
A matching ZIP is staged in Windows `Downloads/Envlet`; installation/restart of the
ordinary IDEA remains a user action. Older development ZIPs are retained.
