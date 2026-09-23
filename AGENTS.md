# Envlet development

Envlet targets the owner's Windows IDEA + NixOS WSL direnv/devenv workflow. Keep
Go and Rust integrations optional and isolate product APIs in their modules.

Read CONTRIBUTING.md before implementation. When changing Go/Rust SDK discovery,
WSL paths, or project refresh, read docs/research/language-apis.md for the inspected
IDE build and API constraints. Retain upstream attribution and mark modified files.

Track work in the Envlet Kaneo project linked from docs/plan.md. Record actual
test and IDE observations separately; a successful build does not establish that
indexing or terminal integration works. Update affected tasks as work progresses.

Loaded environment values stay in memory; persist only explicit user settings and
necessary SDK paths. Project trust and direnv approval remain required. Use a
throwaway project for end-to-end validation and keep installation rollback files
outside the repository.
