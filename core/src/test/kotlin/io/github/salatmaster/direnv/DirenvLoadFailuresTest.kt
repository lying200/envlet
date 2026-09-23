package io.github.salatmaster.direnv

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class DirenvLoadFailuresTest {
    @Test
    fun `failure delays only that directory and expires without an external event`() {
        var now = 0L
        val failures = DirenvLoadFailures { now }
        val first = Paths.get("first")
        val second = Paths.get("second")
        val blocked = DirenvState.Blocked("first/.envrc")
        failures.record(first, blocked)
        assertThat(failures.recent(first)).isSameAs(blocked)
        assertThat(failures.recent(second)).isNull()
        now = TimeUnit.SECONDS.toNanos(59)
        assertThat(failures.recent(first)).isSameAs(blocked)
        now = TimeUnit.SECONDS.toNanos(60)
        assertThat(failures.recent(first)).isNull()
    }

    @Test
    fun `invalidation permits an immediate retry without clearing unrelated failures`() {
        val failures = DirenvLoadFailures { 0L }
        val first = Paths.get("first")
        val second = Paths.get("second")
        failures.record(first, DirenvState.Failed("failed"))
        failures.record(second, DirenvState.ExecutableMissing("direnv"))
        failures.remove(first)
        assertThat(failures.recent(first)).isNull()
        assertThat(failures.recent(second)).isNotNull()
        failures.clear()
        assertThat(failures.recent(second)).isNull()
    }
}
