package com.aras.client.ui.main

import com.aras.client.BuildConfig

/** Internal hand-off used when an import creates a subscription before Main is shown. */
object SubscriptionNavigation {
    const val ACTION_OPEN_SUBSCRIPTION = "${BuildConfig.APPLICATION_ID}.action.OPEN_SUBSCRIPTION"
    const val EXTRA_SUBSCRIPTION_ID = "selected_subscription_id"
    const val EXTRA_SUBSCRIPTION_IDS = "selected_subscription_ids"
}
