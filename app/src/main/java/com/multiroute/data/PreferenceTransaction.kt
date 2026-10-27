package com.multiroute.data

/** A partial import owns only its changed keys, including when a failed commit needs rollback. */
internal class PreferenceTransaction<K>(private val updates: Map<K, Map<String, Any?>>) {
    private val attempted = mutableListOf<Pair<K, Map<String, Any?>>>()

    fun apply(read: (K) -> Map<String, *>, write: (K, Map<String, Any?>) -> Boolean) {
        for ((store, values) in updates) {
            if (values.isEmpty()) continue
            val current = read(store)
            // Capture immediately before writing, and include a commit that returns false:
            // SharedPreferences may already have updated its in-memory values in that case.
            attempted += store to values.keys.associateWith { current[it] }
            check(write(store, values)) { "Preference commit failed" }
        }
    }

    fun rollback(write: (K, Map<String, Any?>) -> Boolean): Boolean =
        attempted.asReversed().map { (store, values) ->
            runCatching { write(store, values) }.getOrDefault(false)
        }.all { it }

    companion object {
        /** Null means remove; SharedPreferences does not store null values. */
        fun changesBetween(before: Map<String, *>, after: Map<String, *>): Map<String, Any?> =
            (before.keys + after.keys).filter { before[it] != after[it] }
                .associateWith { after[it] }
    }
}
