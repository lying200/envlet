package io.github.salatmaster.direnv.python

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PythonRunEnvironmentTest {
    @Test fun `inherited unset cannot be represented by a WSL target overlay`() {
        assertThat(PythonRunEnvironment.requiresInheritedUnset(mapOf("PRIVATE" to null), emptyMap())).isTrue()
        assertThat(PythonRunEnvironment.requiresInheritedUnset(mapOf("PRIVATE" to null), mapOf("PRIVATE" to "explicit"))).isFalse()
        assertThat(PythonRunEnvironment.requiresInheritedUnset(mapOf("PRIVATE" to ""), emptyMap())).isFalse()
    }

    @Test fun `explicit pythonpath retains helpers already added by Python plugin`() {
        val original = mapOf("PYTHONPATH" to "/explicit:/helpers")
        val explicit = mapOf("PYTHONPATH" to "/explicit")
        val merged = PythonRunEnvironment.merge(original, mapOf("PYTHONPATH" to "/env"), explicit, ":")
        assertThat(merged).containsEntry("PYTHONPATH", "/explicit:/helpers")
        assertThat(explicit).containsEntry("PYTHONPATH", "/explicit")
    }

    @Test fun `direnv overrides inherited values removes unset overrides and keeps debugger paths`() {
        val original = mapOf("PATH" to "/system", "REMOVE" to "old", "PYTHONPATH" to "/helpers:/source", "PYCHARM_HOSTED" to "1")
        val merged = PythonRunEnvironment.merge(original,
            mapOf("PATH" to "/env/bin", "REMOVE" to null, "PYTHONPATH" to "/deps:/source"), emptyMap(), ":")
        assertThat(merged).containsEntry("PATH", "/env/bin").containsEntry("PYTHONPATH", "/deps:/source:/helpers")
            .containsEntry("PYCHARM_HOSTED", "1").doesNotContainKey("REMOVE")
        assertThat(original).containsEntry("REMOVE", "old").containsEntry("PATH", "/system")
    }

    @Test fun `explicit run configuration has precedence and is never mutated`() {
        val explicit = mapOf("MODE" to "debug", "KEEP" to "explicit")
        val merged = PythonRunEnvironment.merge(emptyMap(), mapOf("MODE" to "production", "KEEP" to null), explicit, ":")
        assertThat(merged).containsAllEntriesOf(explicit)
        assertThat(explicit).hasSize(2)
    }

    @Test fun `another run receives only its own directory environment`() {
        val first = PythonRunEnvironment.merge(emptyMap(), mapOf("PRIVATE" to "envlet-canary"), emptyMap(), ":")
        val next = PythonRunEnvironment.merge(emptyMap(), emptyMap(), emptyMap(), ":")
        assertThat(first).containsEntry("PRIVATE", "envlet-canary")
        assertThat(next).doesNotContainKey("PRIVATE")
    }
}
