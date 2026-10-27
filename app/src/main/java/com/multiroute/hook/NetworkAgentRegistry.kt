package com.multiroute.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Mainline builds use either a netId SparseArray or a Map of registered agents. */
internal class NetworkAgentRegistry {
    private val fields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun agents(service: Any, indexedValues: (Any) -> List<Any>): List<Any> {
        val candidates = fields.getOrPut(service.javaClass) {
            generateSequence(service.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) &&
                    it.name in setOf("mNetworkForNetId", "mNetworkAgentInfos") }
                .onEach { it.isAccessible = true }.toList()
        }
        return candidates.flatMap { field ->
            when (val registry = field.get(service)) {
                is Map<*, *> -> registry.values.filterNotNull()
                null -> emptyList()
                else -> indexedValues(registry)
            }
        }
    }
}
