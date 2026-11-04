package com.jayant.spendsense.notify

import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.withLock

/**
 * Thread-safe FIFO of transaction ids waiting for the payment pop-up. De-duplicates ids and keeps only the newest
 * [capacity] (same cap as Android's persisted queue), so a flood of payments can never grow it without bound or
 * chain hundreds of pop-ups.
 */
class PopupQueue(private val capacity: Int = 20) {
    private val ids = ArrayDeque<Long>()
    private val lock = ReentrantLock()

    /** Adds [id] unless already queued; drops the oldest entry when full. */
    fun offer(id: Long) = lock.withLock {
        if (id in ids) return@withLock
        ids.addLast(id)
        while (ids.size > capacity) ids.removeFirst()
    }

    fun poll(): Long? = lock.withLock { ids.removeFirstOrNull() }

    val size: Int get() = lock.withLock { ids.size }
}
