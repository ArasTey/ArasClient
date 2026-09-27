package com.aras.client.core

import com.aras.client.handler.MmkvManager
import com.tencent.mmkv.MMKV
import org.mockito.Mockito.mock

/**
 * MMKV has no JVM implementation, so anything that reads a setting in a unit test
 * NPEs. This forces [MmkvManager] to hand out a mocked MMKV instead; unstubbed reads
 * return the type default, which is what the production code already assumes for
 * "unset".
 */
object TestSettings {

    /**
     * MmkvManager is a Kotlin `object`, so `mockStatic` cannot intercept it — the
     * `by lazy` delegate is overwritten with the mock instead. SynchronizedLazyImpl
     * only calls its initializer while it holds the uninitialized sentinel, so
     * assigning the value is enough.
     */
    fun install() {
        val delegateField = MmkvManager::class.java.getDeclaredField("settingsStorage\$delegate")
        delegateField.isAccessible = true
        val lazy = delegateField.get(MmkvManager)
        lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }
            .set(lazy, mock(MMKV::class.java))
    }
}
