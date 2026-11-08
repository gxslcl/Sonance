package com.sonance.player.data

import java.util.concurrent.CountDownLatch

/** 序列化异步清理与新写入，防止重扫把刚写好的封面删掉。 */
internal class CacheClearCoordinator {
    private val lock = Any()
    @Volatile private var barrier = CountDownLatch(0)
    @Volatile private var generation = 0L

    class Ticket internal constructor(internal val previous: CountDownLatch, internal val done: CountDownLatch)

    fun prepareClear(): Ticket = synchronized(lock) {
        val ticket = Ticket(barrier, CountDownLatch(1))
        barrier = ticket.done
        generation++
        ticket
    }

    fun completeClear(ticket: Ticket, clear: () -> Unit) {
        try {
            ticket.previous.await()
            clear()
        } finally {
            ticket.done.countDown()
        }
    }

    fun awaitReady() = barrier.await()
    fun currentGeneration(): Long = generation
    fun awaitGeneration(expected: Long): Boolean {
        awaitReady()
        return generation == expected
    }
    fun <T : Any> ifCurrent(expected: Long, block: () -> T): T? = synchronized(lock) {
        if (generation == expected) block() else null
    }
}
