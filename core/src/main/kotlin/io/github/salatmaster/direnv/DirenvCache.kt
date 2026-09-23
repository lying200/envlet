// Modified for ENV-22: scope cleanup includes failed directories retained in watch metadata.
package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvWatch
import java.nio.file.Path

/** No IO, suspension or callbacks under this lock. The service serializes exports separately.
 * Queries never create aliases. Only a committed export establishes directory scope.
 * Each Load is a unique generation ticket; invalidations mark that ticket rather than
 * retaining an unbounded history of per-directory generations.
 */
internal class DirenvCache {
    class Load internal constructor(
        val directory: Path, internal val invalidatedScope: Path, internal val knownScopes: Map<Path, Path>,
    ) {
        internal var rejected = false
        internal val invalidatedScopes = mutableSetOf<Path>()
    }
    class Change(val revision: Long, val environment: DirenvEnvironmentChange?, val state: DirenvState)
    class WatchSet(val scope: Path, val revision: Long, val files: List<DirenvWatch>)
    class Watches(val revision: Long, val entries: Map<Path, WatchSet>)

    private val environments = mutableMapOf<Path, DirenvEnvironment>()
    private val aliases = mutableMapOf<Path, Path>()
    private val failures = DirenvLoadFailures()
    private val pending = ArrayDeque<Change>()
    private var active: Load? = null
    private var revision = 0L
    private var uiState: DirenvState = DirenvState.NotLoaded
    private var watches = Watches(0, emptyMap())

    @Synchronized fun cached(directory: Path): DirenvEnvironment? =
        environments[aliases[directory] ?: directory]

    @Synchronized fun state(): DirenvState = uiState
    @Synchronized fun recentFailure(directory: Path): DirenvState? = failures.recent(directory)
    @Synchronized fun watchSnapshot(): Watches = watches
    @Synchronized fun hasChanges(): Boolean = pending.isNotEmpty()
    @Synchronized fun nextChange(): Change? = pending.removeFirstOrNull()
    @Synchronized fun isLatest(change: Change): Boolean = revision == change.revision

    @Synchronized fun begin(directory: Path): Load {
        // Retained failure-recovery watches still know their scope after aliases are gone.
        // This is an invalidation hint only; cached() never trusts watch metadata for injection.
        val knownScopes = knownScopes()
        return begin(directory, knownScopes[directory] ?: directory, knownScopes)
    }

    /** A scope is an invalidation unit, not necessarily a CLI working directory.
     * The project is a persistent consumer. Other invalidated aliases resolve on demand.
     * Choosing a directory never restores its alias: only its next export can do that.
     */
    @Synchronized fun beginRefresh(scope: Path, projectDirectory: Path?): Load? {
        val directories = watches.entries.filterValues { it.scope == scope }.keys
        if (directories.isEmpty()) return null // The queued watch no longer owns an environment.
        val knownScopes = knownScopes()
        val preferred = projectDirectory?.takeIf {
            it == scope || knownScopes[it] == scope || (knownScopes[it] == null && it.startsWith(scope))
        }
        val directory = preferred ?: directories.minWith(compareBy<Path> { it.nameCount }.thenBy { it.toString() })
        return begin(directory, scope, knownScopes)
    }

    private fun knownScopes(): Map<Path, Path> = watches.entries.mapValues { it.value.scope } + aliases

    private fun begin(directory: Path, key: Path, knownScopes: Map<Path, Path>): Load {
        check(active == null) { "Exports must be serialized by the service" }
        val load = Load(directory, key, knownScopes)
        removeScope(key, directory, removeWatches = false)
        active = load
        changed(key, DirenvState.Loading)
        return load
    }

    /** Reject obsolete results before touching ANY cache, watch, retry or UI metadata. */
    @Synchronized fun complete(
        load: Load, environment: DirenvEnvironment?, state: DirenvState,
        newWatches: List<DirenvWatch>?, resolvedScope: Path? = null,
    ): Boolean {
        if (active !== load) return false
        active = null
        val key = environment?.loadedRcPath?.parent?.toAbsolutePath()?.normalize()
            ?: resolvedScope ?: if (environment == null) load.knownScopes[load.directory] ?: load.directory else load.directory
        val unknownFailedScope = environment == null && resolvedScope == null && load.knownScopes[load.directory] == null
        if (load.rejected || key in load.invalidatedScopes ||
            (unknownFailedScope && load.invalidatedScopes.any { load.directory.startsWith(it) })) return false

        if (environment == null && state.needsApproval && resolvedScope != null) {
            // A first-time child can discover refusal of an already cached parent scope.
            // Revoke that scope before publishing its new watch baseline or UI state.
            removeScope(key, load.directory, removeWatches = false)
            failures.record(load.directory, state)
            updateWatches(load.directory, key, newWatches, replaceScope = true)
            changed(key, state)
            return true
        }

        if (environment != null) {
            environments[key] = environment
            aliases[load.directory] = key
        } else {
            failures.record(load.directory, state)
        }
        updateWatches(load.directory, key, newWatches, replaceScope = key == load.invalidatedScope)
        changed(if (environment != null) key else null, state, environmentChanged = environment != null)
        return true
    }

    private fun updateWatches(directory: Path, key: Path, newWatches: List<DirenvWatch>?, replaceScope: Boolean) {
        if (newWatches != null) {
            // A reload invalidated all aliases in this scope. Replace its old watch records
            // too; otherwise orphan queried directories survive with obsolete dependencies.
            // First discovery of a NEW alias into an existing scope keeps the other aliases'
            // watches, including missing intermediate .envrc files.
            val retained = if (replaceScope) watches.entries.filterValues { it.scope != key }
                else watches.entries
            val nextRevision = watches.revision + 1
            watches = Watches(nextRevision, retained +
                (directory to WatchSet(key, nextRevision, newWatches.toList())))
        }
    }

    @Synchronized fun cancel(load: Load) {
        if (active !== load) return
        active = null
        if (!load.rejected) changed(null, DirenvState.NotLoaded, environmentChanged = false)
    }

    @Synchronized fun invalidate(directory: Path?) {
        if (directory == null) {
            active?.rejected = true
            environments.clear()
            aliases.clear()
            failures.clear()
            watches = Watches(watches.revision + 1, emptyMap())
            changed(null, DirenvState.NotLoaded)
            return
        }
        val key = aliases[directory] ?: active?.knownScopes?.get(directory) ?: watches.entries[directory]?.scope ?: directory
        active?.let {
            // Unknown children may resolve to this scope after the export finishes.
            it.invalidatedScopes.add(key)
            if (it.directory == key || it.knownScopes[it.directory] == key) it.rejected = true
        }
        removeScope(key, directory, removeWatches = true)
        changed(key, DirenvState.NotLoaded)
    }

    private fun removeScope(key: Path, directory: Path, removeWatches: Boolean) {
        // Refused directories have no live alias; their recovery watches still own the scope.
        // Clear their cooldown before those watch records are replaced by a successful reload.
        val directories = knownScopes().filterValues { it == key }.keys +
            active?.knownScopes.orEmpty().filterValues { it == key }.keys + key + directory
        environments.remove(key)
        aliases.entries.removeIf { it.value == key }
        directories.forEach(failures::remove)
        if (removeWatches) watches = Watches(watches.revision + 1,
            watches.entries.filter { (queried, record) -> record.scope != key && queried !in directories })
    }

    private fun changed(scope: Path?, state: DirenvState, environmentChanged: Boolean = true) {
        revision++
        uiState = state
        pending.addLast(Change(revision, if (environmentChanged) DirenvEnvironmentChange(scope, revision) else null, state))
    }
}
