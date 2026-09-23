// Added for Envlet: bound automatic retries independently for each queried directory.
package io.github.salatmaster.direnv

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** In-memory retry policy, confined to DirenvCache's metadata lock. Explicit reloads bypass it. */
internal class DirenvLoadFailures(private val nanoTime: () -> Long = System::nanoTime) {
    private class Failure(val state: DirenvState, val at: Long)
    private val entries = mutableMapOf<Path, Failure>()

    fun record(directory: Path, state: DirenvState) {
        entries[directory] = Failure(state, nanoTime())
    }

    fun recent(directory: Path): DirenvState? = entries[directory]?.let {
        if (nanoTime() - it.at < TimeUnit.SECONDS.toNanos(60)) it.state else null
    }

    fun remove(directory: Path) { entries.remove(directory) }
    fun clear() { entries.clear() }
}
