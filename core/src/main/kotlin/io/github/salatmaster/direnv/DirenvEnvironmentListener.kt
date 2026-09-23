// Added for ENV-16: environment availability is independent of the last UI load result.
package io.github.salatmaster.direnv

import com.intellij.util.messages.Topic
import java.nio.file.Path

/** Safe metadata only. Null scope means all directories were invalidated.
 * Delivery happens outside the cache lock; subscribers must read the current snapshot,
 * since a newer transition may already have committed when a callback runs.
 */
data class DirenvEnvironmentChange(val scope: Path?, val revision: Long)

interface DirenvEnvironmentListener {
    fun environmentChanged(change: DirenvEnvironmentChange)

    companion object {
        @JvmField
        val TOPIC: Topic<DirenvEnvironmentListener> =
            Topic.create("direnv environment changed", DirenvEnvironmentListener::class.java)
    }
}
