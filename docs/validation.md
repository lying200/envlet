# Self-use validation — 2026-09-22

This records the original 0.1.0-dev checks. Native Cargo dependencies were not covered
here; [ENV-11 follow-up](validation-env11.md) records that gap and the 0.1.1-dev fix.

## Environment

- Windows IntelliJ IDEA 2026.2.3, IU-262.10968.63, existing installation.
- NixOS WSL distribution `legion-wsl`, fish 4.9.3, direnv 2.37.1, devenv 2.3.1.
- Go plugin 262.10968.63; Rust 262.10968.75 with Native Debugging Support 262.10968.63.
- Separate IDEA config/system/plugins/log directories under `EnvletValidation`.
- Disposable WSL project containing a devenv environment, Go module and Cargo package.
  Only this authored fixture was approved with `direnv allow`.

The installed `idea64.exe ideScript` command opened the project and exercised actual
plugin services. The fixture was given a content module and its Cargo manifest was
imported, as project-opening from this scripting entry point does not perform the
ordinary import wizard. No SDK or GOPATH was manually selected by the script.
No existing project or NixOS configuration was changed.

## Observed results

| Check | Result |
| --- | --- |
| Build and tests | `test buildPlugin` successful; 164 tests, zero failures/errors/skips |
| Cache regression | Original code failed five new cases; patched code passes nested scope and failed/revoked-load cases |
| Fish regression | Actual fish reproduces the original invalid-name error and passes with the scoped patch |
| Plugin Verifier | Compatible with IU-262.10968.63; 5 deprecated and 140 experimental API usages, no Internal API usage |
| WSL environment | Envlet reaches `Loaded` through the IDE's EEL process API |
| IDE child process | A WSL-patched `GeneralCommandLine` receives the devenv GOPATH without explicit test injection |
| Automatic Go | SDK 1.26.7 from WSL Nix store; GOPATH points to the fixture's `.devenv/state/go` |
| Automatic Rust | WSL Nix toolchain selected, rustc 1.98.1/cargo 1.98.0, Nix standard library sources configured |
| Code resolution | Go `fmt.Println` resolves to SDK `src/fmt/print.go`; Rust `std::env::consts::OS` resolves to imported standard library source |
| Cargo import/refresh | One Cargo project imported; refresh completed |
| Environment reload | Changing the fixture's GOPATH and reloading updates the IDE's GOPATH automatically; fixture restored afterwards |
| Actual fish terminal | IDEA logs the Envlet compatibility patch; devenv welcome appears; direnv hook exists; Go/Rust versions match; no invalid-variable error |

The runtime checks exercised the same implementation as the final ZIP; subsequent
changes were documentation, comments and plugin description/change notes.

Artifact: `build/distributions/envlet-0.1.0-dev.zip`.
SHA-256: `2b036ccf41d089a1c415685844fb7b4574bc400eba5068ce2aad8bd2baf4aae7`.

## Limits and follow-up

- Two early environment exports timed out at 120 seconds. Later repeated IDE loads
  succeeded quickly. The cause of those initial timeouts is not established; do not
  claim cold Nix/devenv builds are fully covered. Prepare the environment in a normal
  terminal and reload, or adjust Envlet's timeout for a long first build.
- The real terminal widget was the classic implementation. Selecting REWORKED in
  this scripting context still returned the classic bridge; reworked command-block
  UI and debugger sessions have not been validated.
- The fish patch is hash-gated to the inspected script and uses a deprecated public
  extension point. It requires matching project/shell EEL descriptors. Unknown
  scripts are left unchanged with a warning; Windows projects with WSL terminals
  are outside the tested scope.
- Language SDKs are project-level. Nested `.envrc` files do not get independent
  module SDKs. The first process in an unseen subdirectory may start before its
  environment has been asynchronously resolved; it receives no cached parent env.
- Disabling automatic management or losing a tool from PATH retains the last SDK
  paths. Failed/revoked loads discard cached environment values, but do not restore
  earlier manual SDK settings or stop already-running processes.
- Go/Rust integrations require their JetBrains language plugins. No broad product,
  platform-version, Marketplace publication or full run/debug support claim is made.

Keep observations and remaining work in the [Kaneo plan](plan.md). The independent
validation profile can be removed without changing the ordinary IDEA profile.

## Personal installation

After validation, the final Envlet ZIP and the two official Rust dependency plugins
were copied into the owner's ordinary IDEA user plugin directory. Existing plugin
folders and settings were not overwritten. Go was already installed. No explicit
shell-integration override remained in the current user settings, so no terminal
setting was changed. The next IDEA startup loads these plugins.

An installation receipt is under the external `EnvletValidation/installation-20260922`
directory, and a ZIP copy is under the owner's Windows `Downloads/Envlet` directory.
The receipt lists the three created plugin directories for rollback with IDEA closed.
