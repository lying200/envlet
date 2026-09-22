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
