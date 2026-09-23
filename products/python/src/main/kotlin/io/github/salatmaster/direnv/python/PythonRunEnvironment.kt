package io.github.salatmaster.direnv.python

/** Merge one transient execution, never a saved run configuration. Explicit run values win.
 * PYTHONPATH also retains the IDE's content/helper paths required by testing and debugging.
 */
internal object PythonRunEnvironment {
    /** WSL Target overlays cannot delete variables inherited on the target machine. */
    fun requiresInheritedUnset(direnv: Map<String, String?>, explicit: Map<String, String>): Boolean =
        direnv.any { (name, value) -> value == null && name !in explicit }

    fun merge(
        original: Map<String, String>, direnv: Map<String, String?>, explicit: Map<String, String>,
        separator: String,
    ): Map<String, String> {
        val result = original.toMutableMap()
        for ((name, value) in direnv) {
            if (value == null) result.remove(name) else result[name] = value
        }
        if ("PYTHONPATH" !in explicit && direnv["PYTHONPATH"] != null) {
            val seenPaths = mutableSetOf<String>()
            // Whole empty values add no paths; empty entries in a nonempty value mean cwd.
            // Keep those entries, including repeats: collapsing ":" to "" changes semantics.
            result["PYTHONPATH"] = listOfNotNull(direnv["PYTHONPATH"], original["PYTHONPATH"])
                .filter(String::isNotEmpty).flatMap { it.split(separator) }
                .filter { it.isEmpty() || seenPaths.add(it) }.joinToString(separator)
        }
        result.putAll(explicit)
        // Python already incorporated explicit PYTHONPATH into its helper/content path list.
        if ("PYTHONPATH" in explicit && "PYTHONPATH" in original) result["PYTHONPATH"] = original.getValue("PYTHONPATH")
        return result
    }
}
