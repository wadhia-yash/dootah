package dev.dootah.runtime

import org.junit.Assert.*
import org.junit.Test

class DispatchPayloadTest {
    private val id = "dth1:" + "a".repeat(64)
    private fun payload(entries: List<Any?> = listOf(mapOf("functionId" to id, "title" to "Remote"))) =
        mapOf("type" to "dootah.dispatch.v1", "abi" to 1.0, "overrides" to entries)

    @Test fun validatesWholeEnvelopeBeforeActivation() {
        assertEquals(mapOf(id to "Remote"), DispatchPayload.parse(payload()))
        assertNull(DispatchPayload.parse(payload() + ("abi" to 2)))
        assertNull(DispatchPayload.parse(payload() + ("extra" to "ignored?")))
        assertNull(DispatchPayload.parse(payload(emptyList())))
        val entry = mapOf("functionId" to id, "title" to "Remote")
        assertNull(DispatchPayload.parse(payload(listOf(entry, entry))))
        assertNull(DispatchPayload.parse(payload(listOf(entry, mapOf("functionId" to "wrong", "title" to "Remote")))))
        assertNull(DispatchPayload.parse(payload(listOf(entry + ("title" to "x".repeat(257))))))
        assertNull(DispatchPayload.parse(payload(listOf(entry + ("title" to listOf("object"))))))
        assertNull(DispatchPayload.parse(payload(listOf(entry + ("receiver" to "forbidden")))))
    }
}
