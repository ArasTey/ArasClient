package com.aras.client.handler

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Serializes subscription workflows both in-process and across the UI/daemon processes. */
object SubscriptionWorkflowLock {
    private const val LOCK_DIRECTORY = "subscription-workflow-locks"
    private val processLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(
        context: Context,
        subscriptionId: String,
        block: suspend () -> T,
    ): T {
        val key = subscriptionId.ifBlank { "all" }
        val processLock = processLocks.computeIfAbsent(key) { Mutex() }
        return processLock.withLock {
            val directory = File(context.applicationContext.filesDir, LOCK_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                error("Unable to create subscription workflow lock directory")
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val file = RandomAccessFile(File(directory, "$digest.lock"), "rw")
            val fileLock = try {
                runInterruptible(Dispatchers.IO) { file.channel.lock() }
            } catch (error: Throwable) {
                file.close()
                throw error
            }
            try {
                block()
            } finally {
                runCatching { fileLock.release() }
                file.close()
            }
        }
    }
}
