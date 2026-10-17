package com.petertroll.quiethome

/**
 * Issues monotonically increasing request tokens that are valid only for the current active
 * lifecycle window. Starting or stopping a window invalidates every previously issued token.
 *
 * Methods are synchronized because requests may be issued and checked from different framework
 * callbacks even though current callers normally marshal results to the main thread.
 */
class ActiveRequestGate {
    private var active = false
    private var generation = 0L

    @Synchronized
    fun start() {
        generation++
        active = true
    }

    @Synchronized
    fun stop() {
        active = false
        generation++
    }

    @Synchronized
    fun newRequest(): Long? {
        if (!active) return null
        generation++
        return generation
    }

    @Synchronized
    fun accepts(token: Long): Boolean = active && token == generation

    @Synchronized
    fun isActive(): Boolean = active
}
