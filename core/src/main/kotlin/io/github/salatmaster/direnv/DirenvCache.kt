// Modified for ENV-18: watches retain explicit scope ownership independently of cache aliases.
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
        check(active == null) { "Exports must be serialized by the service" }
        // Retained failure-recovery watches still know their scope after aliases are gone.
        // This is an invalidation hint only; cached() never trusts watch metadata for injection.
        val knownScopes = watches.entries.mapValues { it.value.scope } + aliases
        val key = knownScopes[directory] ?: directory
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
        if (environment != null) {
            environments[key] = environment
            aliases[load.directory] = key
        } else {
            failures.record(load.directory, state)
        }
        if (newWatches != null) {
            // A reload invalidated all aliases in this scope. Replace its old watch records
            // too; otherwise orphan queried directories survive with obsolete dependencies.
            // First discovery of a NEW alias into an existing scope keeps the other aliases'
            // watches, including missing intermediate .envrc files.
            val retained = if (key == load.invalidatedScope) watches.entries.filterValues { it.scope != key }
                else watches.entries
            val nextRevision = watches.revision + 1
            watches = Watches(nextRevision, retained +
                (load.directory to WatchSet(key, nextRevision, newWatches.toList())))
        }
        changed(if (environment != null) key else null, state, environmentChanged = environment != null)
        return true
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
        val directories = aliases.filterValues { it == key }.keys +
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
