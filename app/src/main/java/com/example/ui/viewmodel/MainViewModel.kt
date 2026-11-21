package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.AppEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.VersionHistoryEntity
import com.example.data.repository.AppRepository
import com.example.data.repository.ScanResult
import com.example.service.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppFilter { ALL, USER, SYSTEM }
enum class AppSort { NAME, RECENTLY_UPDATED, INSTALL_DATE, VERSION_COUNT }
enum class HistoryFilter { ALL, UPDATED, DOWNGRADED, INSTALLED, UNINSTALLED }

data class AppWithHistoryCount(
    val app: AppEntity,
    val changeCount: Int
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val repository = AppRepository(application, database.appDao())

    // UI state toggles
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(AppFilter.ALL)
    val selectedFilter: StateFlow<AppFilter> = _selectedFilter.asStateFlow()

    private val _selectedSort = MutableStateFlow(AppSort.RECENTLY_UPDATED)
    val selectedSort: StateFlow<AppSort> = _selectedSort.asStateFlow()

    private val _historyFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyFilter: StateFlow<HistoryFilter> = _historyFilter.asStateFlow()

    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastScanError = MutableStateFlow<String?>(null)
    val lastScanError: StateFlow<String?> = _lastScanError.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(
        NotificationHelper.isNotificationEnabled(application)
    )
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _notificationAvailabilityNote = MutableStateFlow(
        NotificationHelper.getNotificationAvailabilityNote(application)
    )
    val notificationAvailabilityNote: StateFlow<String?> = _notificationAvailabilityNote.asStateFlow()

    // Persisted timestamp of last successful scan (0L if no scan has succeeded yet)
    val lastScanTime: StateFlow<Long> = repository.lastScanTime

    // Persistent search queries from Room
    val recentSearches: StateFlow<List<SearchHistoryEntity>> = repository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Raw streams from DB
    val allApps: StateFlow<List<AppEntity>> = repository.allApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allHistory: StateFlow<List<VersionHistoryEntity>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val changeCountsMap: StateFlow<Map<String, Int>> = repository.changeCounts
        .combine(repository.allApps) { counts, _ ->
            counts.associate { it.packageName to it.count }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Filtered & Sorted Apps for Home Screen
    val filteredApps: StateFlow<List<AppWithHistoryCount>> = combine(
        repository.allApps,
        changeCountsMap,
        _searchQuery,
        _selectedFilter,
        _selectedSort
    ) { apps, counts, query, filter, sort ->
        val trimmedQuery = query.trim()
        val filtered = apps.filter { app ->
            // Search filter
            val matchesQuery = trimmedQuery.isEmpty() ||
                app.appName.contains(trimmedQuery, ignoreCase = true) ||
                app.packageName.contains(trimmedQuery, ignoreCase = true) ||
                app.versionName.contains(trimmedQuery, ignoreCase = true)

            // Category filter
            val matchesCategory = when (filter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.isSystemApp
                AppFilter.SYSTEM -> app.isSystemApp
            }

            matchesQuery && matchesCategory
        }

        val mapped = filtered.map { app ->
            AppWithHistoryCount(
                app = app,
                changeCount = counts[app.packageName] ?: 1
            )
        }

        // Deterministic sort order with secondary tie-breakers on appName and packageName
        val comparator = when (sort) {
            AppSort.NAME -> compareBy<AppWithHistoryCount> { it.app.appName.lowercase(Locale.ROOT) }
                .thenBy { it.app.packageName }
            AppSort.RECENTLY_UPDATED -> compareByDescending<AppWithHistoryCount> { it.app.lastUpdateTime }
                .thenBy { it.app.appName.lowercase(Locale.ROOT) }
                .thenBy { it.app.packageName }
            AppSort.INSTALL_DATE -> compareByDescending<AppWithHistoryCount> { it.app.firstInstallTime }
                .thenBy { it.app.appName.lowercase(Locale.ROOT) }
                .thenBy { it.app.packageName }
            AppSort.VERSION_COUNT -> compareByDescending<AppWithHistoryCount> { it.changeCount }
                .thenByDescending { it.app.lastUpdateTime }
                .thenBy { it.app.appName.lowercase(Locale.ROOT) }
                .thenBy { it.app.packageName }
        }
        mapped.sortedWith(comparator)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Grouped History items for Global History Tab
    val groupedHistory: StateFlow<Map<String, List<VersionHistoryEntity>>> = combine(
        repository.allHistory,
        _historyFilter,
        _historySearchQuery
    ) { historyList, filter, query ->
        val trimmedQuery = query.trim()
        val filtered = historyList.filter { item ->
            val matchesQuery = trimmedQuery.isEmpty() ||
                item.appName.contains(trimmedQuery, ignoreCase = true) ||
                item.packageName.contains(trimmedQuery, ignoreCase = true) ||
                item.newVersionName.contains(trimmedQuery, ignoreCase = true) ||
                (item.oldVersionName?.contains(trimmedQuery, ignoreCase = true) == true)

            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.UPDATED -> item.changeType == AppRepository.CHANGE_UPDATED
                HistoryFilter.DOWNGRADED -> item.changeType == AppRepository.CHANGE_DOWNGRADED
                HistoryFilter.INSTALLED -> item.changeType == AppRepository.CHANGE_INSTALLED
                HistoryFilter.UNINSTALLED -> item.changeType == AppRepository.CHANGE_UNINSTALLED
            }

            matchesQuery && matchesFilter
        }

        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        val yesterdayStr = dateFormat.format(Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L))

        // Group by human readable date label
        filtered.groupBy { item ->
            val dateStr = dateFormat.format(Date(item.detectedTime))
            when (dateStr) {
                todayStr -> "Today"
                yesterdayStr -> "Yesterday"
                else -> dateStr
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // Initial scan on app startup
        refreshScan(isInitial = true)
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun commitSearch(query: String) {
        _searchQuery.value = query
        if (query.isNotBlank()) {
            viewModelScope.launch {
                repository.saveSearch(query)
            }
        }
    }

    fun deleteSearch(query: String) {
        viewModelScope.launch {
            repository.deleteSearch(query)
        }
    }

    fun clearSearches() {
        viewModelScope.launch {
            repository.clearSearches()
        }
    }

    fun onFilterSelected(filter: AppFilter) {
        _selectedFilter.value = filter
    }

    fun onSortSelected(sort: AppSort) {
        _selectedSort.value = sort
    }

    fun onHistoryFilterSelected(filter: HistoryFilter) {
        _historyFilter.value = filter
    }

    fun onHistorySearchQueryChanged(query: String) {
        _historySearchQuery.value = query
    }

    fun toggleNotifications(enabled: Boolean) {
        val app = getApplication<Application>()
        NotificationHelper.setNotificationEnabled(app, enabled)
        syncNotificationState()
        if (enabled && !_notificationsEnabled.value) {
            val note = _notificationAvailabilityNote.value
            if (note != null) {
                _snackbarMessage.value = note
            }
        }
    }

    fun syncNotificationState() {
        val app = getApplication<Application>()
        _notificationsEnabled.value = NotificationHelper.isNotificationEnabled(app)
        _notificationAvailabilityNote.value = NotificationHelper.getNotificationAvailabilityNote(app)
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun refreshScan(isInitial: Boolean = false) {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                when (val result = repository.performScan()) {
                    is ScanResult.Success -> {
                        _lastScanError.value = null
                        if (result.updatedOrDowngraded.isNotEmpty()) {
                            NotificationHelper.notifyVersionChanges(getApplication(), result.updatedOrDowngraded)
                            _snackbarMessage.value = "Scan complete: ${result.updatedOrDowngraded.size} version changes detected"
                        } else if (!isInitial && result.totalChangesDetected > 0) {
                            _snackbarMessage.value = "Scan complete: ${result.totalChangesDetected} package changes recorded"
                        } else if (!isInitial) {
                            _snackbarMessage.value = "Scan complete: ${result.totalScanned} apps checked (no new changes)"
                        }
                    }
                    is ScanResult.Error -> {
                        Log.e(TAG, "Package scan failed: ${result.message}", result.cause)
                        _lastScanError.value = result.message
                        _snackbarMessage.value = "Scan error: ${result.message}"
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception during package scan", e)
                val msg = e.localizedMessage ?: "Unknown error"
                _lastScanError.value = msg
                _snackbarMessage.value = "Failed to scan: $msg"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun getAppDetailFlow(packageName: String) = repository.getApp(packageName)
    fun getAppHistoryFlow(packageName: String) = repository.getHistoryForPackage(packageName)
    fun getLocalJsonFilePath(): String = repository.getLocalJsonFilePath()

    companion object {
        private const val TAG = "MainViewModel"
    }
}
