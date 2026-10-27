package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAgentRegistryTest {
    private open class MapService {
        private val mNetworkAgentInfos = java.util.concurrent.ConcurrentHashMap<Int, String>()
        fun add(id: Int, agent: String) { mNetworkAgentInfos[id] = agent }
    }
    private class DerivedService : MapService()
    private class IndexedService { private val mNetworkForNetId = listOf("primary", "secondary") }
    private class Unsupported { private val unrelated = mapOf(1 to "wrong") }

    @Test fun mapIncludesNewlyRegisteredSecondaryAndInheritedFields() {
        val service = DerivedService(); val lookup = NetworkAgentRegistry()
        service.add(104, "primary")
        assertEquals(listOf("primary"), lookup.agents(service) { error("not indexed") })
        service.add(107, "secondary")
        assertEquals(setOf("primary", "secondary"), lookup.agents(service) { error("not indexed") }.toSet())
    }

    @Test fun indexedRegistryUsesPlatformAdapter() {
        assertEquals(listOf("primary", "secondary"), NetworkAgentRegistry().agents(IndexedService()) {
            @Suppress("UNCHECKED_CAST")
            it as List<Any>
        })
    }

    @Test fun unsupportedRegistryCachesAnEmptyResultWithoutNullInsertion() {
        val lookup = NetworkAgentRegistry(); val service = Unsupported()
        repeat(2) { assertEquals(emptyList<Any>(), lookup.agents(service) { error("unknown registry") }) }
    }
}
