package com.sonance.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CacheClearCoordinatorTest {
    @Test
    fun `new work waits for clear and old generation cannot publish`() {
        val gate = CacheClearCoordinator()
        val old = gate.currentGeneration()
        val ticket = gate.prepareClear()
        val current = gate.currentGeneration()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val done = executor.submit<Boolean> {
            started.countDown()
            gate.awaitGeneration(current)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse(done.isDone)
        assertNull(gate.ifCurrent(old) { "stale" })
        gate.completeClear(ticket) { }
        assertTrue(done.get(5, TimeUnit.SECONDS))
        executor.shutdown()
    }

    @Test
    fun `failed clear still releases waiting work`() {
        val gate = CacheClearCoordinator()
        val ticket = gate.prepareClear()
        runCatching { gate.completeClear(ticket) { error("disk failure") } }
        assertTrue(gate.awaitGeneration(gate.currentGeneration()))
    }
}
