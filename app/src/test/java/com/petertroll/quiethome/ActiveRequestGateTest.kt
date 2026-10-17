package com.petertroll.quiethome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRequestGateTest {
    @Test
    fun acceptsOnlyCurrentRequestWhileActive() {
        val gate = ActiveRequestGate()

        gate.start()
        val first = gate.newRequest()
        assertNotNull(first)
        assertTrue(gate.accepts(first!!))

        val second = gate.newRequest()
        assertNotNull(second)
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second!!))
    }

    @Test
    fun rejectsRequestAfterStop() {
        val gate = ActiveRequestGate()
        gate.start()
        val request = gate.newRequest()!!

        gate.stop()

        assertFalse(gate.accepts(request))
        assertFalse(gate.isActive())
        assertFalse(gate.newRequest() != null)
    }

    @Test
    fun acceptsNewRequestAfterRestartButNotOldRequest() {
        val gate = ActiveRequestGate()
        gate.start()
        val oldRequest = gate.newRequest()!!
        gate.stop()

        gate.start()
        val newRequest = gate.newRequest()!!

        assertFalse(gate.accepts(oldRequest))
        assertTrue(gate.accepts(newRequest))
    }
}
