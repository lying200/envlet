// Modified for ENV-18: one shared file can invalidate multiple environment scopes.
package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.direnv.DirenvWatch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps every file direnv reported as an environment input back to the directory whose environment
 * depends on it.
 *
 * Kept free of platform types so the matching logic can be tested directly. The service that
 * subscribes to the virtual file system only translates events into calls on this registry.
 *
 * Watching `.envrc` alone would be insufficient: direnv also reports `flake.nix`, `flake.lock`,
 * `.env`, `~/.config/direnv/direnvrc` and its own allow/deny stamps. The stamps are what let an
 * external `direnv allow` be noticed by the IDE.
 */
class DirenvWatchRegistry {

    /** Scope → immutable watch snapshot; replacing one scope cannot steal another's files. */
    private val watchesByTarget = ConcurrentHashMap<Path, Map<Path, DirenvWatch>>()

    fun replace(loadedFor: Path, watches: List<DirenvWatch>) {
        watchesByTarget[loadedFor.normalise()] = watches.associate { watch ->
            val path = watch.path.normalise()
            path to watch.copy(path = path)
        }
    }

    /** Poll each scope's baseline, including shared dependencies and external approval stamps. */
    fun staleTargets(): Set<Path> = watchesByTarget.entries.filterTo(mutableListOf()) { (_, watches) ->
        watches.any { (path, recorded) -> currentStateOf(path) != (recorded.exists to recorded.modtime) }
    }.mapTo(mutableSetOf()) { it.key }

    /** Do IO on snapshots; don't overwrite a concurrent replacement with an older baseline. */
    fun rebaseline() {
        for ((target, recorded) in watchesByTarget) {
            val updated = recorded.mapValues { (path, watch) ->
                val (exists, modtime) = currentStateOf(path)
                watch.copy(exists = exists, modtime = modtime)
            }
            watchesByTarget.replace(target, recorded, updated)
        }
    }

    private fun currentStateOf(path: Path): Pair<Boolean, Long> = try {
        if (Files.exists(path)) {
            true to Files.getLastModifiedTime(path).toInstant().epochSecond
        } else {
            false to 0L
        }
    } catch (e: Exception) {
        false to 0L
    }

    /** Every dependent scope must reload; there is no last-writer-wins target. */
    fun reloadTargetsFor(changedPath: Path): Set<Path> {
        val path = changedPath.normalise()
        return watchesByTarget.entries.filterTo(mutableListOf()) { path in it.value }
            .mapTo(mutableSetOf()) { it.key }
    }

    fun allWatchedPaths(): Set<Path> = watchesByTarget.values.flatMapTo(mutableSetOf()) { it.keys }

    fun clear() = watchesByTarget.clear()

    private fun Path.normalise(): Path = toAbsolutePath().normalize()
}
