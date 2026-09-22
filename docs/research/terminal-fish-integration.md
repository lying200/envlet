# Fish integration compatibility: IDEA 262 startup order

Investigated 2026-09-22 against installed Windows IDEA 262.10968.63. This is a binary/API investigation; no IDE files or Envlet implementation files were changed.

## Implemented choice (updated after implementation)

Envlet uses the **deprecated public `LocalTerminalCustomizer`**, returning a command list.
The Internal modern setter described below was removed after Plugin Verifier rejected it.
The resulting ZIP is compatible with IU-262.10968.63, with deprecated/experimental warnings.
The exact script hash gates the patch; both companion scripts are preserved.
Real fish regression tests reproduce the original scratch-variable collision and pass with
local, unexported scratch variables. Actual Windows IDEA classic-terminal validation subsequently passed: patch invocation,
devenv welcome, loaded hook, and expected Go/Rust versions. See [validation](../validation.md).
The implementation targets a WSL project and shell in the same distribution; a Windows
project with a WSL terminal is outside the current validated scope.

## Original API investigation conclusion

**The startup customizer runs after JetBrains injects the fish source argument, so command replacement can technically work. However, the setter required to do so is explicitly `ApiStatus.Internal` in 262.** It is not a clean public-API fix for a Marketplace release.

The actual argument emitted in this version is one string:

```text
--init-command=source <POSIX-quoted-path>/fish-integration.fish
```

Do not implement a matcher that only handles the equivalent `-C source ...` form.

## Exact binary evidence

All classes below are from the user's installed `plugins/terminal/lib/terminal.jar`, except the explicitly identified frontend/backend modules. `javap -c -p` and `javap -v -p` dumps are in `/home/echoyn/temp/envlet-api`.

1. `LocalTerminalDirectRunner.configureStartupOptions`:
   - bytecode offset **151** invokes `LocalShellIntegrationInjector.injectShellIntegration`;
   - offset **166** applies PowerShell-specific configuration;
   - offset **175** invokes `TerminalExecOptionsCustomizationKt.applyExecOptionsCustomizers`.
   This is definitive ordering: Envlet sees the command after shell integration injection.
2. `com.intellij.terminal.frontend.session.ReworkedLocalTerminalRunner` in `intellij.terminal.frontend.jar` extends `LocalTerminalDirectRunner` and does not override `configureStartupOptions`; the inspected reworked local runner follows the same sequence.
3. `LocalShellIntegrationInjector.injectShellIntegration` at offsets **336–363** handles fish, calls `CommandLineUtil.posixQuote` on the integration path and appends the argument. `javap -v` bootstrap entry 0 has literal `--init-command=source \u0001`.
4. `TerminalExecOptionsCustomizationKt.applyExecOptionsCustomizers` constructs the customization request from `ShellStartupOptions.getShellCommand`; after the backend response, offsets **230–233** pass `response.getShellCommand()` to `ShellStartupOptions.Builder.shellCommand(...)`.
5. `com.intellij.terminal.backend.rpc.TerminalExecOptionsCustomizationRemoteApiImplKt` in `intellij.terminal.backend.jar` constructs a `ShellExecCommandImpl` from that command. Its customizer lambda calls `customizeExecOptions` at offset **47**, then reads `MutableShellExecOptionsImpl.getExecCommand` at offset **55** for the resulting command. No discarded local-only mutation problem exists here.
6. `MutableShellExecOptionsImpl.setExecCommand` logs the change, then writes `_execCommand` at offset **29**. But `MutableShellExecOptions.setExecCommand` has `RuntimeInvisibleAnnotations: org.jetbrains.annotations.ApiStatus$Internal`. The getter, `ShellExecCommand`, and the enclosing options/customizer API are Experimental. Avoid describing setter use as just Experimental.

The public source counterpart can be inspected at the [matching IntelliJ source tag](https://github.com/JetBrains/intellij-community/tree/idea/262.10968.63/plugins/terminal); the actual installed bytecode is authoritative for the conclusions above. JetBrains says [Internal APIs must not be used by third-party plugins](https://plugins.jetbrains.com/docs/intellij/goland-extension-point-list.html).

## Why the JEDITERM hook is not a replacement hook

`ShellIntegrationConfigurerImpl.sourceShellScriptAtShellStartup` writes `JEDITERM_SOURCE`, using its translator for the script path. The installed fish integration reads that variable and sources it **before** declaring and invoking `override_jb_variables`. A replacement function declared by that pre-hook would be overwritten by the original declaration. The hook is also one slot: setting it can replace another customizer's script.

Installed `fish-integration.fish` SHA-256: `aa88f5dfd44a3b16a8a6307aacfcc7c92a29a8207d3b7793b1d496e4e93b70c2`.

The original file sources `command-block-support.fish` and `command-block-support-reworked.fish` relative to its own directory after environment restoration. A standalone copied `fish-integration.fish` in a new directory silently loses that integration unless the sibling scripts are copied or their source paths are deliberately preserved.

## Available implementation choices

### Scoped self-use workaround using command replacement

Technically feasible in the already authorized self-use fork, with the Internal API dependency explicitly recorded:

1. Gate to fish, enabled integration, and the exact known integration file shape/hash. Read the already-injected source argument; preserve every unrelated command argument.
2. Create an Envlet-owned cached copy of the matching integration directory in the shell's execution environment (WSL in this case). Apply only the local-variable scope fix to `override_jb_variables`. Do not modify the IDE installation or the original temporary integration directory.
3. Keep the command-block companion scripts from the same IDE build beside the patched copy. Deriving this from installed resources avoids shipping a frozen full copy that drifts as IDE integration evolves.
4. Replace exactly the identified integration source argument through `execCommand`, with proper fish/POSIX quoting. Keep the same shell identity. The path inside this argument must be a **Linux/WSL path**, not `C:/...` or a Windows UNC path, because fish interprets it.
5. On unknown script/build, skip the patch and expose a concrete diagnostic. Do not silently inject an old whole integration script into a newer IDE.
6. Validate in a fresh real IDEA terminal with markers for every scratch-variable collision, with command blocks both enabled and disabled. Check shell hooks still run and values are restored exactly.

This removes manual IDE-file patching but keeps a build-sensitive Internal API seam. It must not be presented as a maintenance-free Marketplace solution, and suppressing the annotation in source does not resolve verifier policy.

### Deprecated LocalTerminalCustomizer

This older customizer can return a replacement command list without calling the Internal setter. It is still present but **Deprecated**. The backend calls it only if the process EEL descriptor equals the project's EEL descriptor (`shouldApplyLocalTerminalCustomizers`, offsets 11–21). A Windows project with a WSL shell can fail that condition; a WSL project and WSL shell may pass. Therefore this is not an unconditional workaround for WSL, and it exchanges one maintenance issue for another. It may be suitable only after verifying that the user's exact project/terminal pair enters it.

### Upstream fixed script / terminal-native environment mode

A JetBrains-fixed integration script is the clean end state. Separately, letting the terminal use its native direnv hook can avoid plugin-generated FORCE markers, but does not repair the underlying script's use of global scratch variable names, and should not be claimed to fix all environment collisions. Pre-hook tricks which erase markers and restore values later also change timing and are more fragile than the direct scope fix.
