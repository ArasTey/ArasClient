package com.aras.client.service

import android.app.Service
import android.net.VpnService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.CoreNativeManager
import com.aras.client.core.CoreServiceManager
import com.aras.client.core.LauncherManager
import com.aras.client.dto.QuickConnectFailure
import com.aras.client.dto.QuickConnectMessage
import com.aras.client.dto.QuickConnectResult
import com.aras.client.dto.RealPingEvent
import com.aras.client.dto.entities.SubscriptionCache
import com.aras.client.enums.NotificationChannelType
import com.aras.client.extension.serializable
import com.aras.client.handler.AngConfigManager
import com.aras.client.handler.AppLocaleManager
import com.aras.client.handler.FreeSubManager
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.SettingsManager
import com.aras.client.handler.SubscriptionWorkflowLock
import com.aras.client.helper.MessageHelper
import com.aras.client.helper.NotificationHelper
import com.aras.client.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Daemon workflow behind the second Quick Settings tile. */
class QuickConnectService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var workflowStarted = false

    @Volatile
    private var activeWorker: RealPingWorkerService? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (workflowStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        workflowStarted = true
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.QUICK_CONNECT,
            getString(R.string.app_quick_connect_tile_name),
            getString(R.string.quick_connect_progress_stopping),
        )
        val message = intent?.serializable<QuickConnectMessage>("content")
        if (message == null || message.subscriptionId.isBlank()) {
            finishWith(
                QuickConnectResult(
                    subscriptionId = message?.subscriptionId.orEmpty(),
                    success = false,
                    reason = QuickConnectFailure.NO_SUBSCRIPTION,
                ),
                startId,
            )
            return START_NOT_STICKY
        }
        if (!QuickConnectCoordinator.isRunning()) QuickConnectCoordinator.tryStart()
        runWorkflow(message, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeWorker?.cancel()
        activeWorker = null
        serviceJob.cancel()
        QuickConnectCoordinator.finish()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    private fun runWorkflow(message: QuickConnectMessage, startId: Int) {
        serviceScope.launch {
            val result = try {
                execute(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Quick connect workflow failed", e)
                QuickConnectResult(
                    subscriptionId = message.subscriptionId,
                    success = false,
                    reason = QuickConnectFailure.UPDATE_FAILED,
                )
            }
            finishWith(result, startId)
        }
    }

    private suspend fun execute(message: QuickConnectMessage): QuickConnectResult {
        val subId = message.subscriptionId
        if (MmkvManager.decodeSubscription(subId) == null) {
            return failure(subId, QuickConnectFailure.NO_SUBSCRIPTION)
        }

        MessageHelper.sendMsg2UI(this, AppConfig.MSG_QUICK_CONNECT_STARTED, subId)
        sendProgress(subId, R.string.quick_connect_progress_stopping)
        if (!stopCurrentConnection()) {
            return failure(subId, QuickConnectFailure.STOP_FAILED)
        }
        stopService(Intent(this, CoreTestService::class.java))

        val outcome = SubscriptionWorkflowLock.withLock(this, subId) {
            if (FreeSubManager.isFreeSubId(subId)) FreeSubManager.applyUrl(this)
            val subscription = MmkvManager.decodeSubscription(subId)
                ?: return@withLock WorkflowOutcome(
                    failure = QuickConnectFailure.NO_SUBSCRIPTION
                )

            sendProgress(subId, R.string.quick_connect_progress_updating)
            val updateResult = AngConfigManager.updateConfigViaSubLocked(
                SubscriptionCache(subId, subscription)
            )
            if (updateResult.successCount == 0) {
                return@withLock WorkflowOutcome(failure = QuickConnectFailure.UPDATE_FAILED)
            }
            if (FreeSubManager.isFreeSubId(subId)) FreeSubManager.protectAll()

            val guids = MmkvManager.decodeServerList(subId).distinct()
            if (guids.isEmpty()) {
                return@withLock WorkflowOutcome(failure = QuickConnectFailure.NO_SERVERS)
            }

            MmkvManager.clearAllTestDelayResults(guids)
            sendProgress(subId, R.string.quick_connect_progress_pinging)
            if (!runRealPing(guids)) {
                return@withLock WorkflowOutcome(failure = QuickConnectFailure.TEST_FAILED)
            }

            sendProgress(subId, R.string.quick_connect_progress_sorting)
            AngConfigManager.sortByTestResultsForSub(subId)
            val fastest = selectFastestReachableServer(MmkvManager.decodeServerList(subId)) { guid ->
                MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis
            } ?: return@withLock WorkflowOutcome(failure = QuickConnectFailure.NO_SERVERS)

            MmkvManager.setSelectServer(fastest)
            WorkflowOutcome(guid = fastest)
        }

        val guid = outcome.guid
        if (guid == null) {
            return failure(subId, outcome.failure ?: QuickConnectFailure.UPDATE_FAILED)
        }

        sendProgress(subId, R.string.quick_connect_progress_connecting)
        if (SettingsManager.isVpnMode() && VpnService.prepare(this) != null) {
            return failure(subId, QuickConnectFailure.VPN_PERMISSION_REQUIRED)
        }
        if (!startAndAwaitCore(guid)) {
            return failure(subId, QuickConnectFailure.START_FAILED)
        }
        return QuickConnectResult(
            subscriptionId = subId,
            selectedGuid = guid,
            success = true,
        )
    }

    private suspend fun stopCurrentConnection(): Boolean {
        if (!CoreServiceManager.isRunning() && !CoreNativeManager.awgIsRunning()) return true
        val serviceControl = CoreServiceManager.serviceControl?.get()
        if (serviceControl != null) {
            serviceControl.stopService()
        } else {
            LauncherManager.stopService(this)
        }
        val stopped = withTimeoutOrNull(6_000L) {
            while (CoreServiceManager.isRunning() || CoreNativeManager.awgIsRunning()) {
                delay(100)
            }
            true
        } ?: false
        if (!stopped) LauncherManager.stopService(this)
        return stopped
    }

    private suspend fun startAndAwaitCore(guid: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        val rootMode = SettingsManager.isRootMode()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra("key", 0)) {
                    AppConfig.MSG_ROOT_SETUP_SUCCESS -> result.complete(true)
                    AppConfig.MSG_ROOT_SETUP_FAILURE,
                    AppConfig.MSG_STATE_START_FAILURE -> result.complete(false)
                    AppConfig.MSG_STATE_START_SUCCESS -> {
                        if (!rootMode) result.complete(true)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            if (!LauncherManager.startService(this, guid)) return false
            val completed = withTimeoutOrNull(20_000L) { result.await() } ?: false
            if (!completed) LauncherManager.stopService(this)
            completed
        } finally {
            runCatching { unregisterReceiver(receiver) }
        }
    }

    private suspend fun runRealPing(guids: List<String>): Boolean {
        val completed = CompletableDeferred<Unit>()
        lateinit var worker: RealPingWorkerService
        worker = RealPingWorkerService(
            context = this,
            guids = guids,
            onlyTcp = false,
            onEvent = { event ->
                when (event) {
                    is RealPingEvent.Progress -> sendProgress(
                        R.string.quick_connect_progress_pinging,
                        event.text,
                    )
                    is RealPingEvent.Result ->
                        MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                    is RealPingEvent.Finish -> completed.complete(Unit)
                }
            },
        )
        activeWorker = worker
        return try {
            worker.start()
            withTimeoutOrNull(6 * 60 * 1000L) {
                completed.await()
                true
            } ?: false
        } finally {
            if (!completed.isCompleted) worker.cancel()
            if (activeWorker === worker) activeWorker = null
        }
    }

    private fun sendProgress(subscriptionId: String, messageRes: Int) {
        sendProgress(messageRes, "")
    }

    private fun sendProgress(messageRes: Int, detail: String) {
        val content = getString(messageRes)
        NotificationHelper.updateNotification(
            NotificationChannelType.QUICK_CONNECT,
            this,
            getString(R.string.app_quick_connect_tile_name),
            if (detail.isBlank()) content else "$content ($detail)",
        )
        MessageHelper.sendMsg2UI(this, AppConfig.MSG_QUICK_CONNECT_PROGRESS, detail)
    }

    private fun finishWith(result: QuickConnectResult, startId: Int) {
        val messageId = if (result.success) {
            AppConfig.MSG_QUICK_CONNECT_SUCCESS
        } else {
            AppConfig.MSG_QUICK_CONNECT_FAILURE
        }
        MessageHelper.sendMsg2UI(this, messageId, result)
        QuickConnectCoordinator.finish()
        NotificationHelper.stopForeground(this)
        stopSelf(startId)
    }

    private fun failure(subscriptionId: String, reason: QuickConnectFailure) =
        QuickConnectResult(
            subscriptionId = subscriptionId,
            success = false,
            reason = reason,
        )

    private data class WorkflowOutcome(
        val guid: String? = null,
        val failure: QuickConnectFailure? = null,
    )
}
