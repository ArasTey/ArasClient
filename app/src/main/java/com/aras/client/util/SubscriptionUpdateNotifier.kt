package com.aras.client.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow

/**
 * Tiny broadcast bus: fires whenever any subscription updates, so UI
 * (traffic bar etc.) re-reads fresh values from MMKV immediately.
 */
object SubscriptionUpdateNotifier {

    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val flow: SharedFlow<String> = updates

    fun notify(subId: String) {
        updates.tryEmit(subId)
    }
}
