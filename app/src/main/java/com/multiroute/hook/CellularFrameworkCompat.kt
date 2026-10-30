package com.multiroute.hook

import java.lang.reflect.Method

internal object CellularFrameworkCompat {
    /** The handler implementation remains available on ROMs which removed the old entry point. */
    fun refreshMethod(type: Class<*>): Method? {
        for (name in listOf("handleMobileDataPreferredUidsChanged", "updateMobileDataPreferredUids")) {
            var current: Class<*>? = type
            while (current != null) {
                current.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
                }?.let { return it.apply { isAccessible = true } }
                current = current.superclass
            }
        }
        return null
    }

    /** Call on the service Handler. Newer ROMs skip rebuilding when only the SIM changed. */
    fun refresh(service: Any) {
        val method = refreshMethod(service.javaClass) ?: error("No cellular refresh entry point")
        if (method.name != "handleMobileDataPreferredUidsChanged") {
            method.invoke(service)
            return
        }
        val field = generateSequence(service.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.firstOrNull {
                it.name == "mMobileDataPreferredUids" && Set::class.java.isAssignableFrom(it.type)
            }?.apply { isAccessible = true } ?: error("No cellular preference comparison cache")
        val previous = field.get(service)
        // Never returned by the getter or passed to the request factory. Native code replaces it
        // with the real set before constructing requests. Revert if it fails before replacing it.
        val invalidated = java.util.HashSet<Int>().apply { add(Int.MIN_VALUE) }
        try {
            field.set(service, invalidated)
            method.invoke(service)
        } finally {
            if (field.get(service) === invalidated) field.set(service, previous)
        }
    }
}
