package com.aras.client.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.ConnectionTestResult
import com.aras.client.dto.GroupMapItem
import com.aras.client.dto.LocateTarget
import com.aras.client.dto.TestServiceMessage
import com.aras.client.handler.ArasExportImportManager
import com.aras.client.handler.FreeSubManager
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.SubscriptionUpdater
import com.aras.client.handler.SubscriptionWorkflowLock
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.ServersCache
import com.aras.client.dto.entities.SubscriptionCache
import com.aras.client.extension.isComplexType
import com.aras.client.extension.matchesPattern
import com.aras.client.extension.moveItem
import com.aras.client.ui.base.BaseViewModel
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import com.aras.client.extension.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import com.aras.client.dto.CheckUpdateResult
import com.aras.client.dto.QuickConnectFailure
import com.aras.client.dto.QuickConnectResult
import com.aras.client.dto.SubscriptionUpdateResult
import com.aras.client.handler.UpdateCheckerManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

internal fun pingCountryLabel(country: String?, fallback: String): String =
    com.aras.client.util.CountryResolver.flagForCountry(country).ifBlank { country ?: fallback }

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val updateAnnouncement = MutableStateFlow<CheckUpdateResult?>(null)
    val updateAnnouncementFlow: StateFlow<CheckUpdateResult?> = updateAnnouncement.asStateFlow()

    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Main-thread-only presentation lifetime. Service/test data continues updating while hidden.
    private var statusMessageJob: Job? = null
    private var mainScreenActive = false
    private var acceptCurrentPingMessages = false

    fun setMainScreenActive(active: Boolean) {
        mainScreenActive = active
        if (!active) {
            clearStatusMessage()
            _uiState.update {
                if (it.status == MainStatus.Testing && !it.isTesting) {
                    it.copy(status = if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected)
                } else it
            }
        }
    }

    private fun clearStatusMessage() {
        statusMessageJob?.cancel()
        statusMessageJob = null
        acceptCurrentPingMessages = false
        _uiState.update { it.copy(statusMessageVisible = false) }
    }

    private fun showStatusMessage() {
        statusMessageJob?.cancel()
        if (!mainScreenActive) return
        _uiState.update { it.copy(statusMessageVisible = true) }
        statusMessageJob = viewModelScope.launch {
            delay(5_000)
            clearStatusMessage()
        }
    }

    internal fun connectedServerTitle(): String =
        uiState.value.selectedGuid?.let { dataSource.decodeServerConfig(it)?.remarks }
            ?.takeIf { it.isNotBlank() } ?: dataSource.getString(R.string.app_name)

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupPageFlows = ConcurrentHashMap<String, MutableStateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null

    // Round state and job ownership are confined to the main dispatcher.
    private val testRounds = SmartConnectRoundTracker()
    private var testPreparationJob: Job? = null
    private var testFinishJob: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()
    private var initializeStarted = false

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            MainServiceEvent.StateStartFailure -> {
                toastError(R.string.toast_services_failure)
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                // The daemon may send a second, delayed GeoIP enrichment. Neither it nor
                // a result from a previous screen visit may resurrect a dismissed message.
                if (mainScreenActive && acceptCurrentPingMessages && uiState.value.isRunning) {
                    val firstResult = uiState.value.status !is MainStatus.ConnectionTest
                    _uiState.update { it.copy(status = MainStatus.ConnectionTest(event.result)) }
                    if (firstResult) showStatusMessage()
                    if (firstResult || !event.result.country.isNullOrBlank()) {
                        val groupId = _uiState.value.selectedGroupId
                        viewModelScope.launch {
                            val loaded = withContext(ioDispatcher) {
                                loadGroup(groupId, forceRefresh = true)
                            }
                            updateGroupUi(groupId, loaded)
                        }
                    }
                }
            }

            MainServiceEvent.MeasureConfigSuccess -> {
                val round = testRounds.current ?: return
                if (!round.dispatched || round.finishing) return
                viewModelScope.launch {
                    val loaded = withContext(ioDispatcher) {
                        loadGroup(round.groupId, forceRefresh = true)
                    }
                    // A cancelled/replaced round must not publish a late cache refresh.
                    if (testRounds.isCurrent(round) && !round.finishing) {
                        updateGroupUi(round.groupId, loaded)
                    }
                }
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                val round = testRounds.current
                if (round != null && round.dispatched && !round.finishing) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                onTestsFinished()
            }

            MainServiceEvent.QuickConnectStarted -> cancelAllPing()
            is MainServiceEvent.QuickConnectFinished -> handleQuickConnectFinished(event.result)
        }
    }

    private fun handleQuickConnectFinished(result: QuickConnectResult) {
        if (!result.success && result.reason !in setOf(
                QuickConnectFailure.STOP_FAILED,
                QuickConnectFailure.NO_SUBSCRIPTION,
                QuickConnectFailure.ALREADY_RUNNING,
            )
        ) {
            updateRunningState(false)
        }
        viewModelScope.launch {
            setupGroupTab(
                forceRefresh = true,
                preferredSelectedGroupId = result.subscriptionId.takeIf { it.isNotBlank() },
            ).join()
            refreshSelectedGuid()
            refreshGeoIPIfDue(force = true)
            if (result.success) {
                toastSuccess(R.string.quick_connect_success)
            } else if (result.reason == QuickConnectFailure.NO_SERVERS ||
                result.reason == QuickConnectFailure.NO_SUBSCRIPTION
            ) {
                toastError(R.string.quick_connect_no_server)
            } else if (result.reason == QuickConnectFailure.VPN_PERMISSION_REQUIRED) {
                toastError(R.string.quick_connect_vpn_permission)
            } else {
                toastError(R.string.quick_connect_failure)
            }
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        val country = pingCountryLabel(result.country, unknown)
        return "$status\n($country) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupPageFlows.computeIfAbsent(groupId) { MutableStateFlow(emptyList()) }
            .asStateFlow()

    private fun mutableServersForGroup(groupId: String): MutableStateFlow<List<ServersCache>> =
        groupPageFlows.computeIfAbsent(groupId) { MutableStateFlow(emptyList()) }

    private fun currentServers(): List<ServersCache> =
        mutableServersForGroup(uiState.value.selectedGroupId).value

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.PingSelectedSubscription -> pingSelectedSubscription()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.SmartConnect -> startSmartConnect()
            is MainAction.SingleTestServer -> testSingleServer(action.guid)
            is MainAction.ShareTxtFile -> Unit // handled by the Activity
            is MainAction.ShareArascFile -> Unit // handled by the Activity
            is MainAction.ExportGroupTxt -> Unit // handled by the Activity
            MainAction.UpdateSubscriptions -> {
                importConfigViaSub()
                viewModelScope.launch(ioDispatcher) {
                    delay(15_000) // let the subs finish fetching, then refresh flags
                    refreshGeoIPIfDue(force = true)
                }
            }
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            MainAction.ImportArascFile,
            MainAction.ImportManualMenu,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Update announcement ----------
    private fun maybeAnnounceUpdate() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val result = UpdateCheckerManager.checkForUpdate()
                if (result.hasUpdate) {
                    updateAnnouncement.value = result
                }
            } catch (e: Exception) {
                LogUtil.i(AppConfig.TAG, "Update announcement check skipped: ${e.message}")
            }
        }
    }

    fun dismissUpdateAnnouncement() {
        updateAnnouncement.value = null
    }

    // ---------- Initialization ----------
    fun initialize() {
        if (initializeStarted) return
        initializeStarted = true
        maybeAnnounceUpdate()

        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                FreeSubManager.sync(getApplication())  // suspend, syncMutex-serialized
                // The Free group is created by the sync above — refresh tabs
                setupGroupTab(forceRefresh = true).join()
                dataSource.syncSubscriptions()
                FreeSubManager.protectAll()  // covers sub-fetch imports
                refreshGeoIPIfDue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_CONNECT_FASTEST, false)
                && !_uiState.value.isRunning
            ) {
                startSmartConnect()
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L,
                testCountryCode = affiliation?.countryCode,
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            // Global sort for the "All" tab: with auto-sort enabled, rank every
            // config across ALL subscriptions by measured delay (stable sort
            // keeps per-sub order for untested servers). Without this the All
            // tab stays grouped subscription-by-subscription.
            val ordered = if (groupId.isEmpty() &&
                MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)
            ) {
                servers.sortedBy { cache ->
                    val d = cache.testDelayMillis
                    if (d > 0L) d else Long.MAX_VALUE
                }
            } else {
                servers
            }
            cacheMutex.withLock { groupDataCache[groupId] = ordered }
            ordered
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        mutableServersForGroup(groupId).value = applyKeywordFilter(servers)
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(
        groups: List<GroupMapItem>,
        preferredSelectedGroupId: String? = null,
    ): String {
        val current = preferredSelectedGroupId ?: uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(
        forceRefresh: Boolean = false,
        preferredSelectedGroupId: String? = null,
    ): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(id = it.guid, remarks = it.subscription.remarks)
                }
                val selectedGroup = resolveSelectedGroup(groups, preferredSelectedGroupId)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupPageFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer()
                    )
                }
                groups.forEach { mutableServersForGroup(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val selectedGroupId = uiState.value.selectedGroupId
                    val targetGroupId = if (FreeSubManager.isFreeSubId(selectedGroupId)) {
                        AppConfig.DEFAULT_SUBSCRIPTION_ID
                    } else {
                        selectedGroupId
                    }
                    val imported = dataSource.importBatchConfig(configText, targetGroupId, true)
                    val firstNewSubscriptionId = imported.newSubscriptionIds.firstOrNull()
                    if (firstNewSubscriptionId != null) {
                        setupGroupTab(
                            forceRefresh = true,
                            preferredSelectedGroupId = firstNewSubscriptionId,
                        ).join()
                    } else {
                        setupGroupTab(forceRefresh = true)
                    }

                    if (imported.configCount > 0) {
                        toast(dataSource.getString(R.string.title_import_config_count, imported.configCount))
                    }
                    if (imported.newSubscriptionIds.isNotEmpty()) {
                        val updateResult = imported.newSubscriptionIds.fold(
                            SubscriptionUpdateResult()
                        ) { accumulated, subId ->
                            accumulated + updateSubscriptionInternal(subId)
                        }
                        showSubscriptionUpdateResult(updateResult)
                    } else if (imported.totalCount == 0) {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    fun openSubscriptionAfterAdd(subId: String) {
        openSubscriptionsAfterAdd(listOf(subId))
    }

    fun openSubscriptionsAfterAdd(subIds: List<String>) {
        val validIds = subIds.filter { it.isNotBlank() }.distinct()
        val firstId = validIds.firstOrNull() ?: return
        viewModelScope.launch {
            if (_uiState.value.isTesting) cancelAllPing()
            val selected = setupGroupTab(
                forceRefresh = true,
                preferredSelectedGroupId = firstId,
            ).join().let { uiState.value.selectedGroupId == firstId }
            if (!selected) return@launch
            launchLoading {
                withContext(ioDispatcher) {
                    val result = validIds.fold(SubscriptionUpdateResult()) { accumulated, subId ->
                        accumulated + updateSubscriptionInternal(subId)
                    }
                    showSubscriptionUpdateResult(result)
                }
            }
        }
    }

    fun removeSubscription(subId: String) {
        if (subId.isEmpty() || FreeSubManager.isFreeSubId(subId)) return
        if (uiState.value.isTesting || (uiState.value.isRunning &&
                uiState.value.selectedGuid in MmkvManager.decodeServerList(subId))) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            SubscriptionWorkflowLock.withLock(getApplication(), subId) {
                MmkvManager.removeSubscription(subId)
            }
            withContext(Dispatchers.Main) {
                refreshSelectedGuid()
                setupGroupTab(forceRefresh = true)
                toast(dataSource.getString(R.string.toast_success))
            }
        }
    }

    fun copySubscriptionUrl(subId: String) {
        val url = dataSource.getSubscriptionItem(subId)?.url
        if (url.isNullOrBlank()) {
            toastError(R.string.toast_failure)
            return
        }
        Utils.setClipboard(getApplication(), url)
        toast(dataSource.getString(R.string.toast_success))
    }

    fun importConfigViaSub(subId: String = uiState.value.selectedGroupId) {
        if (_uiState.value.isTesting) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    showSubscriptionUpdateResult(updateSubscriptionInternal(subId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private suspend fun updateSubscriptionInternal(subId: String): SubscriptionUpdateResult {
        if (FreeSubManager.isFreeSubId(subId)) {
            FreeSubManager.applyUrl(getApplication())
        }
        val result = if (subId.isEmpty()) {
            dataSource.updateConfigViaSubAll()
        } else {
            val item = dataSource.getSubscriptionItem(subId)
                ?: return SubscriptionUpdateResult(skipCount = 1)
            dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
        }
        if (FreeSubManager.isFreeSubId(subId)) {
            FreeSubManager.protectAll()
        }
        if (result.successCount > 0 && subId.isNotEmpty()) {
            SubscriptionUpdater.syncOne(getApplication(), subId)
        }
        return result
    }

    private fun showSubscriptionUpdateResult(result: SubscriptionUpdateResult) {
        when {
            result.successCount + result.failureCount + result.skipCount == 0 ->
                toast(R.string.title_update_subscription_no_subscription)

            result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                toast(dataSource.getString(R.string.title_update_config_count, result.configCount))

            else ->
                toast(
                    dataSource.getString(
                        R.string.title_update_subscription_result,
                        result.configCount,
                        result.successCount,
                        result.failureCount,
                        result.skipCount,
                    )
                )
        }
        if (result.configCount > 0) {
            setupGroupTab(forceRefresh = true)
            refreshSelectedGuid()
            refreshGeoIPIfDue(force = true)
        }
    }


    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                    reloadAllGroups(_uiState.value.groups.map { it.id })
                    reloadJob?.join()
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SCROLL_TO_TOP, true)) {
                        _uiState.update { it.copy(scrollToTopTick = it.scrollToTopTick + 1) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServersForGroup(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        if (guid != _uiState.value.selectedGuid) {
            clearStatusMessage()
            _uiState.update { it.withoutServerStatusMessage() }
        }
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val servers = mutableServersForGroup(groupId).value.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val guids = servers.map { it.guid }
        mutableServersForGroup(groupId).value = servers
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        testRounds.invalidate()
        testPreparationJob?.cancel()
        testFinishJob?.cancel()
        testPreparationJob = null
        testFinishJob = null
        clearStatusMessage()
        dataSource.cancelAllPing()
        _uiState.update {
            it.copy(
                isTesting = false,
                requestServiceStart = false,
                status = if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false) {
        startTestRound(uiState.value.selectedGroupId, currentServers(), onlyTcp, smartConnect = false)
    }

    fun pingSelectedSubscription() {
        viewModelScope.launch {
            if (_uiState.value.isTesting) return@launch
            val groupId = _uiState.value.selectedGroupId
            if (groupId.isBlank()) {
                toast(R.string.toast_select_subscription_for_ping)
                return@launch
            }
            val servers = withContext(ioDispatcher) {
                buildServersCache(dataSource.getGroupServerGuids(groupId))
            }
            if (_uiState.value.isTesting) return@launch
            if (servers.isEmpty()) {
                toast(R.string.toast_none_data)
                return@launch
            }
            startTestRound(groupId, servers, onlyTcp = false, smartConnect = false)
        }
    }

    private fun startTestRound(
        groupId: String,
        servers: List<ServersCache>,
        onlyTcp: Boolean,
        smartConnect: Boolean
    ) {
        cancelAllPing()
        val round = testRounds.begin(groupId, servers.map { it.guid }, smartConnect) ?: return
        mutableServersForGroup(groupId).update { current ->
            current.map { server ->
                if (server.guid in round.candidates) server.copy(testDelayMillis = 0L) else server
            }
        }
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        testPreparationJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    dataSource.clearAllTestDelayResults(round.candidates)
                    cacheMutex.withLock { groupDataCache.remove(groupId) }
                    try {
                        com.aras.client.util.GeoIPResolver.refresh(
                            servers.mapNotNull { it.profile.server?.takeIf { h -> h.isNotBlank() } }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "GeoIP pre-warm failed: ${e.message}")
                    }
                }
                ensureActive()
                if (!testRounds.isCurrent(round)) return@launch
                // Always send the captured list. An empty list tells the service to
                // expand to the subscription (or ALL subscriptions), not to test nothing.
                testRounds.dispatched(round)
                dataSource.sendMsg2TestService(
                    TestServiceMessage(
                        key = AppConfig.MSG_MEASURE_CONFIG_START,
                        subscriptionId = groupId,
                        serverGuids = round.candidates,
                        onlyTcp = onlyTcp
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (testRounds.isCurrent(round)) cancelAllPing()
                LogUtil.e(AppConfig.TAG, "Failed to prepare tests", e)
            }
        }
    }

    fun testCurrentServerRealPing() {
        clearStatusMessage()
        _uiState.value.selectedGuid?.let(MmkvManager::clearServerTestCountry)
        acceptCurrentPingMessages = mainScreenActive
        _uiState.update { it.copy(status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing()
    }

    /** Tests a single server without inheriting a previous Smart Connect intent. */
    fun testSingleServer(guid: String) {
        if (_uiState.value.isTesting || guid.isEmpty()) return
        val profile = dataSource.decodeServerConfig(guid) ?: return
        startTestRound(
            uiState.value.selectedGroupId,
            listOf(ServersCache(guid = guid, profile = profile.copy(), testDelayMillis = 0L)),
            onlyTcp = false,
            smartConnect = false
        )
    }

    /** Tests the captured visible candidates in the selected subscription only. */
    fun startSmartConnect() {
        // initialize() also calls this from IO; all round transitions belong on Main.
        viewModelScope.launch {
            if (_uiState.value.isTesting) return@launch
            val groupId = uiState.value.selectedGroupId
            if (groupId.isEmpty()) {
                // The All tab is not a selected subscription. Never choose globally.
                cancelAllPing()
                toast(R.string.smart_connect_none)
                return@launch
            }
            val members = dataSource.getServerGuidList(groupId).toSet()
            val servers = currentServers().filter {
                it.guid in members && dataSource.decodeServerConfig(it.guid)?.subscriptionId == groupId
            }
            startTestRound(groupId, servers, onlyTcp = false, smartConnect = true)
            if (servers.isEmpty()) toast(R.string.smart_connect_none)
        }
    }

    private fun bestTestedServerInRound(round: SmartConnectRoundTracker.Round): String? =
        bestSmartConnectCandidate(
            round,
            uiState.value.selectedGroupId,
            dataSource.getServerGuidList(round.groupId).toSet(),
            { dataSource.decodeServerConfig(it)?.subscriptionId },
            { dataSource.decodeAffiliationInfo(it)?.testDelayMillis }
        )

    private fun onTestsFinished() {
        // This only deduplicates local completion work. Broadcasts have no request ID,
        // so a late old finish AFTER a new dispatch is inherently indistinguishable.
        val round = testRounds.claimFinish() ?: return
        testFinishJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)) {
                        try {
                            val subs = if (round.groupId.isEmpty()) dataSource.getSubsList()
                                else listOf(round.groupId)
                            subs.forEach {
                                ensureActive()
                                dataSource.sortByTestResultsForSub(it)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "Auto sort after test failed", e)
                        }
                    }
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                if (!testRounds.isCurrent(round)) return@launch
                // Keep testing true until finalization: a new round may still explicitly
                // replace this one, but a duplicate finish must not start another job.
                reloadAllGroups(_uiState.value.groups.map { it.id })
                reloadJob?.join()
                ensureActive()
                if (!testRounds.isCurrent(round)) return@launch
                val smartTarget = if (round.smartConnect) bestTestedServerInRound(round) else null
                testRounds.invalidate()
                if (smartTarget != null) updateSelectedGuid(smartTarget)
                else if (round.smartConnect && uiState.value.selectedGroupId == round.groupId) {
                    toast(R.string.smart_connect_none)
                }
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        status = if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected,
                        requestServiceStart = smartTarget != null,
                        scrollToTopTick = it.scrollToTopTick +
                            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SCROLL_TO_TOP, true)) 1 else 0
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (testRounds.isCurrent(round)) cancelAllPing()
                LogUtil.e(AppConfig.TAG, "Failed to finish tests", e)
            }
        }
    }

    /**
     * Builds the TXT share block for every config in the selected group.
     */
    fun exportGroupText(): String {
        val groupId = _uiState.value.selectedGroupId
        val guids = dataSource.getGroupServerGuids(groupId)
        return dataSource.getShareTextForGuids(guids)
    }

    fun consumeServiceStartRequest() {
        _uiState.update { it.copy(requestServiceStart = false) }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- GeoIP auto refresh ----------

    /** Refreshes country lookups for every config, throttled to once/30min. */
    private fun refreshGeoIPIfDue(force: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            try {
                if (!force) {
                    val last = MmkvManager.decodeSettingsLong(
                        AppConfig.PREF_GEOIP_LAST_REFRESH, 0
                    )
                    if (System.currentTimeMillis() - last < 30 * 60 * 1000L) return@launch
                    MmkvManager.encodeSettings(
                        AppConfig.PREF_GEOIP_LAST_REFRESH, System.currentTimeMillis()
                    )
                }
                val hosts = MmkvManager.decodeAllServerList().mapNotNull { guid ->
                    MmkvManager.decodeServerConfig(guid)?.server
                        ?.takeIf { it.isNotBlank() }
                }
                com.aras.client.util.GeoIPResolver.refresh(hosts.distinct())
                reloadAllGroups(_uiState.value.groups.map { it.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "GeoIP auto refresh failed: ${e.message}")
            }
        }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        // Connection stats follow the UI state — this is the one trigger that
        // always fires, whatever path the service took to start or stop.
        if (running) {
            com.aras.client.util.ConnectionStatsManager.onSessionStarted()
        } else {
            com.aras.client.util.ConnectionStatsManager.onSessionStopped()
        }
        val connectionChanged = uiState.value.isRunning != running
        // Registration/state snapshots must not replay guidance or replace a ping result.
        if (!connectionChanged && !clearTestingText) return
        clearStatusMessage()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                status = if (!clearTestingText && state.isTesting) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }
        if (running && uiState.value.status == MainStatus.Connected) showStatusMessage()
    }

    override fun onCleared() {
        statusMessageJob?.cancel()
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
