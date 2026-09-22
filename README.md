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
Disable direnv Everywhere, install `envlet-0.1.2-dev.zip`, and restart IDEA.
Go support requires JetBrains' Go plugin. Rust support requires JetBrains' Rust
plugin and its Native Debugging Support dependency. Envlet does not replace them.

Open the project through its WSL path. Under **Settings → Tools → Envlet**,
automatic Go/Rust management and terminal shell-hook mode default to enabled.
Keep your existing fish `direnv hook` and turn on IDEA's **Terminal → Shell integration**.
The terminal loads through its own hook, preserving devenv's startup output;
IDE processes and SDK selection use Envlet's in-memory environment cache.

On a successful environment load, Envlet queries `go env` for GOROOT/GOPATH and
selects the corresponding WSL SDK. For Rust, it prefers the project's `.devenv/profile/bin`
when it exposes the same rustc and cargo selected by PATH, then configures the toolchain
and available standard library sources. On Windows/WSL this profile uses the platform's
EEL process launcher so Cargo sync and native dependency builds receive the loaded
compiler environment. Other toolchain directories retain their existing behavior;
see the [ENV-11 investigation and validation](docs/validation-env11.md).
Environment reloads repeat synchronization. Import Go/Cargo projects as usual.
Turning off automatic management leaves the last SDK paths in place; you can then
change them manually. Envlet does not create language run/debug configurations.

To roll back, disable or uninstall Envlet and restore any previous SDK selections.
Its settings use `envlet.xml`; upstream settings remain separate.

## direnv, devenv and host platforms

Environment loading uses `direnv export json`; devenv is optional. An approved
`.envrc` may use ordinary exports, nix-direnv or devenv. Go discovery queries the
selected `go` executable and does not require a devenv profile.

Rust discovery currently needs a directory exposing both PATH-selected `rustc`
and `cargo`. A devenv profile supplies that directory, but another PATH directory
can work too. Separate Nix packages with no common directory may require additional
toolchain setup; Envlet does not generate a synthetic toolchain directory.

| Host / environment | Current scope |
| --- | --- |
| Windows IDEA + WSL + devenv | Real SDK, Cargo native build, PATH and classic terminal checks |
| Windows IDEA + WSL + plain direnv | Environment loading and Go discovery are generic; the Rust EEL/native-build fix currently requires the matching project `.devenv/profile/bin` |
| Native Linux IDEA + direnv, with or without devenv | Implemented local environment/Go/Rust paths; Rust uses `RsLocalToolchain` and does not need the WSL workaround. Full NixOS desktop IDE validation remains outstanding |

The `.devenv/profile/bin` restriction belongs to the WSL Rust compatibility adapter,
not to Envlet's general environment loading. Native Linux still requires a working
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
