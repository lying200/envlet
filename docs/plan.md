# Envlet self-use MVP

Source: https://github.com/lying200/envlet

The live plan and evidence are in [Kaneo](https://kaneo.fly-start.com/dashboard/workspace/mO2x6YHRzlnTVBPqCHIprRIHKIZWyzBU/project/yjxglu2qi3jjn1mgnd4bwrrv).

| Task | Outcome |
| --- | --- |
| ENV-1 | Self-use MVP: Windows IDEA + NixOS WSL, direnv/devenv, Go and Rust |
| ENV-2 | Independent identity, attribution, reproducible build |
| ENV-3 | Nested .envrc and invalidated environment cache correctness |
| ENV-4 | Concrete Go/Rust WSL API evidence |
| ENV-5 | Fish terminal integration compatibility |
| ENV-6 | Automatic Go SDK and environment synchronization |
| ENV-7 | Automatic Rust toolchain and source synchronization |
| ENV-8 | Packaged plugin and real IDEA validation with rollback |

Implement ENV-2 and ENV-4 first, then ENV-3, ENV-5, ENV-6 and ENV-7. Complete
ENV-8 against the resulting artifact. Marketplace publishing is outside this MVP.

Acceptance requires automatic discovery and refresh, separate environments per
project, revoked approval dropping old environments, and a fish terminal without
the `devenv-shell-env` variable-name error. Record limitations rather than claiming
support for untested products or IDE versions.

## First implementation checkpoint

ENV-2 through ENV-7 have implementation and test/API evidence. The packaged build
also passed isolated Windows IDEA / WSL runtime checks for automatic SDK selection,
standard library resolution, environment reload, and classic fish terminal startup.
See [the validation record](validation.md) for exact observations and remaining limits.
Cold first-load timeouts and reworked terminal UI validation are follow-up work;
Marketplace publication remains outside this plan.

## ENV-14 follow-up

The first-directory launch and cross-directory failure regressions introduced in
Envlet's scope-cache changes have a targeted fix for unlocked background launches.
UI/locked calls retain the upstream cache-only constraint. See
[provenance, implementation boundary and validation](validation-env14.md).

## ENV-15 follow-up

Go/Rust synchronization now follows the root environment identity rather than
project-wide display status. Tests and real gated compiler probes verify that an
independent blocked child does not interrupt SDK setup. See
[ENV-15 provenance and validation](validation-env15.md).

## ENV-16 follow-up

Cache commits and invalidation now share generation checks and one metadata lock;
language consumers receive environment changes separately from display status.
See [ENV-16 provenance and validation](validation-env16.md).

## ENV-17 follow-up

Go/Rust configuration decisions have focused tests, probe failures have safe
structured diagnostics, and both adapters guard the actual SDK publication point.
See [ENV-17 implementation and validation](validation-env17.md).
