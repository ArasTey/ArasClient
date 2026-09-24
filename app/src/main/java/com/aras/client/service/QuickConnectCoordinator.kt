package com.aras.client.service

import java.util.concurrent.atomic.AtomicBoolean

object QuickConnectCoordinator {
    private val running = AtomicBoolean(false)

    fun tryStart(): Boolean = running.compareAndSet(false, true)
    fun finish() {
        running.set(false)
    }
    fun isRunning(): Boolean = running.get()
}
