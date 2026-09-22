# Plain direnv Rust validation — 2026-09-23

This records the failing 0.1.2-dev baseline. Both failures are fixed in 0.1.3-dev;
see [ENV-13 implementation and acceptance](validation-env13.md). The linked script
has since become an asserting regression with project-isolation and fixture
approval revoke/restore checks.

Tested Envlet 0.1.2-dev in the installed Windows IDEA IU-262.10968.63, Rust plugin
262.10968.75, with NixOS WSL `legion-wsl`. The ordinary user IDE was not modified;
the existing isolated validation profile was used.

## Fixtures

Two independent Rust packages were created under
`/home/echoyn/code/envlet-direnv-smoke`:

- `split-tools`: `.envrc` adds separate installed Nix store bin directories for
  rustc, cargo, the C compiler and binutils.
- `common-tools`: `.rust-tools/bin` contains symlinks to the same rustc, cargo and
  rustdoc. `.envrc` adds this directory and the same C compiler/binutils directories.

Both use ordinary `PATH_add` and exports for `CC=clang`, `AR`, `RUST_SRC_PATH` and
a harmless fixture marker. Neither invokes devenv, has devenv configuration or a
`.devenv` directory, or uses `use nix` / `use flake`. This specifically tests plain
direnv exports, not every nix-direnv configuration. The existing Nix store packages
avoid downloading/rebuilding toolchains and must remain available for replay.

Each package depends on `libsqlite3-sys = 0.35.0` with `bundled` enabled. Its main
function calls SQLite's native version function and prints the Rust target OS.
Only the newly authored fixtures were approved with `direnv allow` for this test.

## CLI control

With a controlled base PATH, each project ran:

```sh
direnv exec . cargo generate-lockfile --offline
direnv exec . cargo run --locked --offline
```

Both commands succeeded for both layouts. Run took 6.93 seconds for split tools and
6.76 seconds for common tools, with output `platform=linux sqlite=3050002`.
Those build outputs were moved to `target-cli` before IDE tests, so the IDE could
not reuse the successful native build to mask a missing compiler environment.

## Actual IDEA observations

[The investigation script](../scripts/verify-plain-direnv-rust.groovy) opens both
projects, loads Envlet, and attaches Cargo manifests as normal import setup. It
does not select or edit a Rust SDK. A regular IDE child process checks the fixture
marker, compiler availability and absence of `DEVENV_ROOT`; it does not print the
environment. The script then records automatic SDK selection, awaits Cargo refresh
where a SDK exists, and runs Rust's generated Cargo command lines in both terminal
modes. It classifies missing-compiler errors without persisting arbitrary output.

| Observation | split-tools | common-tools |
| --- | --- | --- |
| direnv environment | Loaded | Loaded |
| Generic IDE child process receives environment/compiler | Yes | Yes |
| Automatic Rust toolchain | None | `RsWslToolchain`, `.rust-tools/bin` |
| Cargo workspace | NeedsUpdate; no toolchain | UpToDate |
| Cargo build scripts | NeedsUpdate; no toolchain | UpdateFailed |
| Cargo build, ordinary mode | Not run; no toolchain | Exit 101, missing `cc` |
| Cargo build, terminal mode | Not run; no toolchain | Exit 101, missing `cc` |

The SDK/build outcomes repeated after resetting only these new projects' IDE
settings. The second run explicitly awaited a refresh after SDK selection before
reading sync statuses. The first run's intermediate `NeedsUpdate` for common tools
was therefore not misreported as a completed sync failure.

## Interpretation and limits

The CLI control proves the fixture's pure-direnv compiler environment works. The
generic IDE process probe proves Envlet loaded and injected it without devenv.
The failures are in Rust integration:

1. Current discovery searches for one directory exposing both PATH-selected rustc
   and cargo. The separate store directories do not meet that condition.
2. A common directory is discovered, but the EEL adapter currently recognizes only
   a matching project `.devenv/profile/bin`. This fixture therefore uses Rust's
   legacy WSL toolchain, reproducing the compiler-environment loss from ENV-11.

This is a completed investigation with failing feature coverage, not a claim that
plain-direnv WSL Rust builds are supported. No production behavior was changed in
this task. A follow-up must remove the devenv-specific ownership constraint and
handle split tool directories while preserving project isolation. Native Linux
IDE execution was not tested here.

To replay, use the same isolated profile setup as [ENV-11](validation-env11.md), but
set `envlet.validation.project` to the WSL parent containing `split-tools` and
`common-tools`, and run `scripts/verify-plain-direnv-rust.groovy`. The script reports
all outcomes; `FINISHED` means the investigation completed, not that builds passed.
