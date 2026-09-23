// Added for ENV-16: scope/generation rules are tested without the IDE or filesystem IO.
package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvWatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class DirenvCacheTest {
    private val root = Path.of("project").toAbsolutePath().normalize()
    private val cache = DirenvCache()
    private val loaded = DirenvState.Loaded(DirenvEnvironment.empty(root).diffAgainst(emptyMap()))

    private fun environment(directory: Path, scope: Path = directory) = DirenvEnvironment(
        mapOf("SECRET" to "env16-private-canary"), emptyList(), scope.resolve(".envrc"), directory, Instant.EPOCH,
    )
    private fun commit(directory: Path, scope: Path = directory): DirenvEnvironment {
        val env = environment(directory, scope)
        assertThat(cache.complete(cache.begin(directory), env, loaded,
            listOf(DirenvWatch(scope.resolve(".envrc"), 0, false)))).isTrue()
        return env
    }
    private fun changes(): List<DirenvCache.Change> = buildList {
        while (true) add(cache.nextChange() ?: break)
    }

    @Test fun `global invalidation rejects cache aliases watches failures and success events`() {
        val child = root.resolve("child")
        val load = cache.begin(child)
        cache.invalidate(null)
        changes()
        assertThat(cache.complete(load, environment(child, root), loaded,
            listOf(DirenvWatch(root.resolve("old.lock"), 0, false)))).isFalse()
        assertThat(cache.cached(root)).isNull()
        assertThat(cache.cached(child)).isNull()
        assertThat(cache.watchSnapshot().entries).isEmpty()
        assertThat(cache.state()).isSameAs(DirenvState.NotLoaded)
        assertThat(changes()).isEmpty()
        val retry = commit(child, root)
        assertThat(cache.cached(root)).isSameAs(retry)
    }

    @Test fun `obsolete failure cannot throttle a new automatic attempt`() {
        val load = cache.begin(root)
        cache.invalidate(null)
        assertThat(cache.complete(load, null, DirenvState.Blocked("old/.envrc"), emptyList())).isFalse()
        assertThat(cache.recentFailure(root)).isNull()
        assertThat(cache.state()).isSameAs(DirenvState.NotLoaded)
    }

    @Test fun `independent invalidation does not reject an active load`() {
        val first = root.resolve("first")
        val second = root.resolve("second")
        commit(second)
        val load = cache.begin(first)
        cache.invalidate(second)
        assertThat(cache.complete(load, environment(first), loaded, emptyList())).isTrue()
        assertThat(cache.cached(first)).isNotNull()
        assertThat(cache.cached(second)).isNull()
    }

    @Test fun `parent invalidation does not reject a child resolving to its own envrc`() {
        commit(root)
        val child = root.resolve("independent")
        val load = cache.begin(child)
        cache.invalidate(root)
        assertThat(cache.complete(load, environment(child), loaded, emptyList())).isTrue()
        assertThat(cache.cached(child)).isNotNull()
        assertThat(cache.cached(root)).isNull()
    }

    @Test fun `root invalidation rejects a child whose shared scope has not been discovered yet`() {
        val child = root.resolve("unknown")
        val load = cache.begin(child)
        cache.invalidate(root)
        assertThat(cache.complete(load, environment(child, root), loaded, emptyList())).isFalse()
        assertThat(cache.cached(root)).isNull()
    }

    @Test fun `invalidating a previously resolved sibling rejects a shared reload`() {
        val first = root.resolve("first")
        val second = root.resolve("second")
        commit(first, root)
        commit(second, root)
        val load = cache.begin(first)
        cache.invalidate(second)
        assertThat(cache.complete(load, environment(first, root), loaded, emptyList())).isFalse()
        assertThat(cache.cached(root)).isNull()
        assertThat(cache.watchSnapshot().entries).isEmpty()
    }

    @Test fun `shared invalidation removes all aliases but preserves independent scopes`() {
        val first = root.resolve("first")
        val second = root.resolve("second")
        val nested = root.resolve("independent")
        commit(first, root)
        commit(second, root)
        val independent = commit(nested)
        changes()
        cache.invalidate(first)
        assertThat(cache.cached(first)).isNull()
        assertThat(cache.cached(second)).isNull()
        assertThat(cache.cached(root)).isNull()
        assertThat(cache.cached(nested)).isSameAs(independent)
        assertThat(changes().single().environment?.scope).isEqualTo(root)
    }

    @Test fun `only verified directories reuse shared cache and queries produce no mutations`() {
        val child = root.resolve("child")
        val env = commit(child, root)
        changes()
        repeat(3) {
            assertThat(cache.cached(root)).isSameAs(env)
            assertThat(cache.cached(child)).isSameAs(env)
            assertThat(cache.cached(root.resolve("unknown"))).isNull()
        }
        assertThat(changes()).isEmpty()
        cache.invalidate(root)
        assertThat(cache.cached(child)).isNull()
    }

    @Test fun `events carry resolved scope and ordered revisions without environment values`() {
        commit(root.resolve("child"), root)
        cache.invalidate(null)
        val events = changes().mapNotNull { it.environment }
        assertThat(events.map { it.scope }).containsExactly(root.resolve("child"), root, null)
        assertThat(events.map { it.revision }).isSorted().doesNotHaveDuplicates()
        assertThat(events.toString()).doesNotContain("env16-private-canary", "SECRET")
    }

    @Test fun `successful root refresh removes orphan child watches by scope`() {
        commit(root)
        val child = root.resolve("shared")
        commit(child, root)
        assertThat(cache.watchSnapshot().entries.keys).contains(root, child)
        commit(root)
        assertThat(cache.cached(child)).isNull()
        assertThat(cache.watchSnapshot().entries.keys).containsExactly(root)
        assertThat(cache.watchSnapshot().entries[root]?.scope).isEqualTo(root)
    }

    @Test fun `failed reload retains recovery watches whose scope survives alias removal`() {
        val child = root.resolve("shared")
        commit(child, root)
        assertThat(cache.complete(cache.begin(root), null, DirenvState.Failed("synthetic"), null)).isTrue()
        assertThat(cache.cached(child)).isNull()
        assertThat(cache.watchSnapshot().entries[child]?.scope).isEqualTo(root)
        cache.invalidate(child)
        assertThat(cache.watchSnapshot().entries).isEmpty()
    }

    @Test fun `cancellation releases the active load and cannot overwrite a newer invalidation`() {
        val load = cache.begin(root)
        cache.invalidate(null)
        changes()
        cache.cancel(load)
        assertThat(changes()).isEmpty()
        val replacement = commit(root)
        cache.cancel(load)
        assertThat(cache.cached(root)).isSameAs(replacement)
    }

    @Test fun `scope refresh revalidates the project directory without restoring shared aliases`() {
        val parent = root.parent
        val child = root.resolve("shared")
        commit(root, parent)
        commit(child, parent)
        val load = cache.beginRefresh(parent, root)!!
        assertThat(load.directory).isEqualTo(root)
        assertThat(cache.cached(root)).isNull()
        assertThat(cache.cached(child)).isNull()
        val replacement = environment(root, parent)
        assertThat(cache.complete(load, replacement, loaded, emptyList())).isTrue()
        assertThat(cache.cached(root)).isSameAs(replacement)
        assertThat(cache.cached(child)).isNull()
    }

    @Test fun `project consumer survives removal of its alias and watch during shared child reload`() {
        val parent = root.parent
        val child = root.resolve("shared")
        commit(root, parent)
        commit(child, parent)
        commit(child, parent)
        assertThat(cache.cached(root)).isNull()
        assertThat(cache.watchSnapshot().entries.keys).containsExactly(child)
        val load = cache.beginRefresh(parent, root)!!
        assertThat(load.directory).isEqualTo(root)
        cache.cancel(load)
    }

    @Test fun `refresh of an independent child uses its actual directory and preserves the root`() {
        val original = commit(root)
        val child = root.resolve("nested")
        val working = child.resolve("app")
        commit(working, child)
        val load = cache.beginRefresh(child, root)!!
        assertThat(load.directory).isEqualTo(working)
        assertThat(cache.cached(root)).isSameAs(original)
        cache.cancel(load)
    }

    @Test fun `known independent project scope is not selected for an ancestor refresh`() {
        val original = commit(root)
        val sibling = root.parent.resolve("sibling")
        commit(sibling, root.parent)
        val load = cache.beginRefresh(root.parent, root)!!
        assertThat(load.directory).isEqualTo(sibling)
        assertThat(cache.cached(root)).isSameAs(original)
        cache.cancel(load)
    }

    @Test fun `scope refresh result is rejected when invalidated during export`() {
        commit(root, root.parent)
        val load = cache.beginRefresh(root.parent, root)!!
        cache.invalidate(root.parent)
        assertThat(cache.complete(load, environment(root, root.parent), loaded, emptyList())).isFalse()
        assertThat(cache.cached(root)).isNull()
        assertThat(cache.watchSnapshot().entries).isEmpty()
    }

    @Test fun `obsolete queued scope refresh cannot resurrect an invalidated environment`() {
        commit(root, root.parent)
        cache.invalidate(null)
        assertThat(cache.beginRefresh(root.parent, root)).isNull()
    }
}
