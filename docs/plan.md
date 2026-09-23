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

## ENV-18 follow-up

The shared-watch regression introduced by ENV-16 is fixed with explicit scope
ownership and all-dependent-scope refresh. The same regression test passes before
ENV-16 and fails on d3b0be3; external deny/allow and actual WSL process injection
are verified in IDEA. See [ENV-18 evidence](validation-env18.md).

## ENV-19 follow-up

Automatic refresh now distinguishes the invalidation scope from the direnv
working directory, preserving project-root synchronization when `.envrc` lives
above the opened project. See [ENV-19 contract and evidence](validation-env19.md).

## ENV-20/21 follow-up

Confirmed approval refusal now invalidates its actual scope even when first
discovered by an uncached child. Project Go/Rust probes explicitly use the IDEA
project directory, independent of the latest export's provenance. See
[state transitions, regression tests and actual IDEA evidence](validation-env20-21.md).

## ENV-22 follow-up

Scope cleanup includes failed directories retained only in watch metadata, so
re-approval restores immediate automatic child loading as well as root loading.
Independent cooldowns remain intact. See [ENV-22 evidence](validation-env22.md).


## Python — ENV-24–28

ENV-23 established that Python WSL Targets bypass the generic command-line
injector and that PythonCore alone does not provide the WSL interpreter factory.
ENV-24 tracks the implementation, with ENV-25 API boundaries, ENV-26 interpreter
selection, ENV-27 launch environment and ENV-28 validation/delivery.

The optional adapter follows the root environment for SDK discovery and each
run's actual working directory for WSL environment preparation. It reuses the
existing cache and environment-change protocol. No Python-specific cache, shell
wrapper or persisted environment map is added. See
[implementation and actual validation](validation-env24.md).

## ENV-29 follow-up

Python WSL Run resolves plain env-file values and direct settings into one
explicit override map for merging and unset handling. It uses the platform parser
without adding cache or lifecycle state. Environment scripts are explicitly
unsupported because replaying them would repeat side effects. See
[regression evidence and boundary](validation-env29.md).

## ENV-30 follow-up

Python helper-path merging preserves meaningful empty entries (cwd) while
ignoring whole empty values. This corrects the Python adapter introduced in
ENV-24, without changing the shared environment lifecycle. See
[path semantics and regression evidence](validation-env30.md).

## ENV-31 follow-up

WSL Python SDK construction initializes local-to-target mappings before
registering added paths. The SDK's normal replacement and background refresh
remain responsible for existing metadata; no command rewriting or retry is added.
See [cause and validation](validation-env31.md).
