# Envlet

A small IntelliJ plugin for project environments from direnv and devenv.

Envlet is a personal fork of [direnv Everywhere](https://github.com/salatmaster/direnv-jetbrains-plugin)
by salatmaster. It focuses on Windows IntelliJ IDEA with NixOS WSL. Go/Rust toolchain synchronization and fish compatibility have been exercised in
the installed IDEA 2026.2.3 using an isolated profile. This is a development build
for personal use; see [validation and limits](docs/validation.md).

## Scope

The inherited plugin loads approved `.envrc` environments for IDE processes,
terminals and Gradle, and offers Java/Node toolchains. Envlet adds automatic
Go/Rust setup for the owner's workflow. Progress and acceptance criteria are in
[the implementation plan](docs/plan.md).

Envlet requires a trusted IDE project and an approved `.envrc`. Review the file
and run `direnv allow` yourself. Environment values are kept in memory; they are
not copied into project settings. SDK and source paths necessarily belong to IDE
configuration.

## Install and use

Build the ZIP below, then use **Settings → Plugins → gear → Install Plugin from Disk**.
Disable direnv Everywhere, install `envlet-0.1.8-dev.zip`, and restart IDEA.
Go support requires JetBrains' Go plugin. Rust support requires JetBrains' Rust
plugin and its Native Debugging Support dependency. Envlet does not replace them.

Open the project through its WSL path. Under **Settings → Tools → Envlet**,
automatic Go/Rust management and terminal shell-hook mode default to enabled.
Keep your existing fish `direnv hook` and turn on IDEA's **Terminal → Shell integration**.
The terminal loads through its own hook, preserving devenv's startup output;
IDE processes and SDK selection use Envlet's in-memory environment cache.
Background process launches without IDE read/write locks resolve an uncached working
directory before starting, including ordinary subdirectories after a reload. Calls
on the UI thread or under IDE locks remain cache-only and schedule background warming;
their first uncached call can still miss the environment. Failed directories retry
on a later automatic attempt after 60 seconds; manual reload and watched file/approval
changes bypass that delay. See [ENV-14](docs/validation-env14.md) for provenance and checks.

On a successful environment load, Envlet queries `go env` for GOROOT/GOPATH and
selects the corresponding WSL SDK. For Rust on WSL or native Linux, it independently
discovers rustc and cargo from PATH and creates a project-owned SDK under
`.direnv/envlet/rust/<tool-mapping-hash>/bin`. This directory contains links to the
selected executables, including available companion tools; no environment snapshots
or launcher scripts are written. Standard library sources are configured separately.
On Windows/WSL this SDK uses EEL so Cargo sync and native dependency builds receive
the loaded compiler environment. See [ENV-13 validation](docs/validation-env13.md).
Root environment changes repeat synchronization. Independent child-directory loading
or failure does not cancel or repeat root Go/Rust setup. See [ENV-15 validation](docs/validation-env15.md).
Environment invalidation also rejects exports already in flight; see
[ENV-16 validation](docs/validation-env16.md). Toolchain failures log safe language,
stage and reason fields. Invalid Go discovery retains the current SDK; Rust can
configure a compiler without standard library sources, with a diagnostic. See
[ENV-17 validation](docs/validation-env17.md) for toolchain checks. Shared-directory
watch ownership and automatic approval revocation/recovery are covered by
[ENV-18 validation](docs/validation-env18.md).
Import Go/Cargo projects as usual.
Turning off automatic management leaves the last SDK paths in place; you can then
change them manually. Envlet does not create language run/debug configurations.

To roll back, disable or uninstall Envlet and restore any previous SDK selections.
Its settings use `envlet.xml`; upstream settings remain separate.
Generated SDKs require a writable project cache. Envlet writes an ignore file only
inside `.direnv/envlet`, preserving the project's `.gitignore`. Tool changes create
a new directory, retaining old SDK links for in-flight builds. After restoring SDK
selections and closing the project, this Envlet cache can be removed independently
of other `.direnv` contents.

## direnv, devenv and host platforms

Environment loading uses `direnv export json`; devenv is optional. An approved
`.envrc` may use ordinary exports, nix-direnv or devenv. Go discovery queries the
selected `go` executable and does not require a devenv profile.

Rust discovery no longer requires a common directory for rustc and cargo on WSL or
native Linux. Each project's generated SDK has its own identity even when projects
share Nix store packages. The provider claims only a successfully prepared SDK bound
to that project's current loaded environment. Disabling management or revoking the
environment ends that ownership; the last selected SDK path remains as described above.

| Host / environment | Current scope |
| --- | --- |
| Windows IDEA + WSL + devenv | Real SDK, Cargo native build, PATH and classic terminal checks |
| Windows IDEA + WSL + plain direnv | Split and common tool layouts both pass automatic Rust SDK selection, Cargo sync and bundled SQLite builds; project isolation and revocation/restore verified. Go discovery is generic |
| Native Linux IDEA + direnv, with or without devenv | Implemented local environment/Go/Rust paths; Rust uses `RsLocalToolchain` and does not need the WSL workaround. Full NixOS desktop IDE validation remains outstanding |

The former `.devenv/profile/bin` restriction was removed in 0.1.3-dev. Native Linux still requires a working
IDE installation, a discoverable direnv executable (or its configured absolute path),
and the appropriate JetBrains language plugins.

## Development

Use JDK 25 for IDEA 2026.2.3:

```sh
./gradlew test
./gradlew buildPlugin
```

To compile against an existing IDE installation, pass
`-PlocalIdePath=/absolute/path/to/idea`. Running platform tests also requires an
installation for the host operating system. ZIP artifacts are in
`build/distributions/`.

The fork has its own plugin ID (`io.github.lying200.envlet`) and settings file
(`envlet.xml`). Disable direnv Everywhere before testing Envlet, so both plugins
do not inject environments into the same processes.

Read [CONTRIBUTING.md](CONTRIBUTING.md) for implementation conventions and
[API evidence](docs/research/language-apis.md) for the current Go/Rust integration
constraints. Compatibility is bounded to IDEA build 262 pending verification of
later builds. No Marketplace publication is configured for this development work.

## License and origin

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
This README and the Envlet implementation are modified from the upstream project;
the fork baseline is recorded in NOTICE. Kotlin package names are retained to
keep the fork small and upstream changes easier to inspect.
