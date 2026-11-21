package com.example.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.data.local.AppDao
import com.example.data.local.AppEntity
import com.example.data.local.LocalJsonStorage
import com.example.data.local.PackageChangeCount
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.VersionHistoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class ScanResult {
    data class Success(
        val updatedOrDowngraded: List<VersionHistoryEntity>,
        val totalScanned: Int,
        val totalChangesDetected: Int = updatedOrDowngraded.size,
        val scanTimestamp: Long = System.currentTimeMillis()
    ) : ScanResult()

    data class Error(val message: String, val cause: Throwable? = null) : ScanResult()
}

class AppRepository(
    private val context: Context,
    private val appDao: AppDao
) {
    private val localJsonStorage = LocalJsonStorage.create(context.applicationContext)

    init {
        ensureLastScanTimeInitialized(context.applicationContext)
    }

    val allApps: Flow<List<AppEntity>> = appDao.getAllApps()
    val allHistory: Flow<List<VersionHistoryEntity>> = appDao.getAllHistory()
    val changeCounts: Flow<List<PackageChangeCount>> = appDao.getChangeCountsPerPackage()
    val recentSearches: Flow<List<SearchHistoryEntity>> = appDao.getRecentSearches()
    val lastScanTime: StateFlow<Long> = _lastScanTimeFlow.asStateFlow()

    fun getLocalJsonFilePath(): String = localJsonStorage.getDisplayFilePath()

    fun getApp(packageName: String): Flow<AppEntity?> = appDao.getAppFlow(packageName)
    fun getHistoryForPackage(packageName: String): Flow<List<VersionHistoryEntity>> =
        appDao.getHistoryForPackage(packageName)

    fun getPersistedLastScanTime(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_SUCCESSFUL_SCAN_TIME, 0L)
    }

    private fun saveLastScanTime(timestamp: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_SCAN_TIME, timestamp).apply()
        _lastScanTimeFlow.value = timestamp
    }

    suspend fun saveSearch(query: String) {
        if (query.isNotBlank()) {
            appDao.insertSearch(SearchHistoryEntity(query.trim(), System.currentTimeMillis()))
        }
    }

    suspend fun deleteSearch(query: String) = appDao.deleteSearch(query)
    suspend fun clearSearches() = appDao.clearSearches()

    suspend fun performScan(): ScanResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            try {
                val pm = context.packageManager
                val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(0)
                }

                if (installedPackages.isEmpty()) {
                    val errorMsg = "PackageManager returned an empty package list"
                    Log.e(TAG, errorMsg)
                    return@withLock ScanResult.Error(errorMsg)
                }

                val existingApps = appDao.getAllAppsSync()
                val allExistingHistory = appDao.getAllHistorySync()

                // Map latest history entry per package (getAllHistorySync is ordered by detectedTime DESC, id DESC)
                val latestHistoryByPkg = HashMap<String, VersionHistoryEntity>(existingApps.size)
                for (history in allExistingHistory) {
                    if (!latestHistoryByPkg.containsKey(history.packageName)) {
                        latestHistoryByPkg[history.packageName] = history
                    }
                }

                val isFirstScan = existingApps.isEmpty()
                val existingMap = existingApps.associateBy { it.packageName }
                val currentPkgNames = HashSet<String>(installedPackages.size)

                val appsToUpsert = mutableListOf<AppEntity>()
                val historyToInsert = mutableListOf<VersionHistoryEntity>()
                val notifiableChanges = mutableListOf<VersionHistoryEntity>()
                val now = System.currentTimeMillis()

                if (isFirstScan) {
                    // Save baseline: one INSTALLED row per app using OS firstInstallTime
                    for (pkg in installedPackages) {
                        val pkgName = pkg.packageName ?: continue
                        if (!currentPkgNames.add(pkgName)) continue

                        val appName = extractAppName(pm, pkg.applicationInfo, pkgName)
                        val isSystemApp = isSystemPackage(pkg.applicationInfo)
                        val versionName = normalizeVersionName(pkg.versionName)
                        val versionCode = extractVersionCode(pkg)
                        val firstInstall = pkg.firstInstallTime
                        val lastUpdate = pkg.lastUpdateTime

                        val appEntity = AppEntity(
                            packageName = pkgName,
                            appName = appName,
                            versionName = versionName,
                            versionCode = versionCode,
                            firstInstallTime = firstInstall,
                            lastUpdateTime = lastUpdate,
                            isSystemApp = isSystemApp,
                            isInstalled = true
                        )
                        appsToUpsert.add(appEntity)

                        val baselineHistory = VersionHistoryEntity(
                            packageName = pkgName,
                            appName = appName,
                            oldVersionName = null,
                            oldVersionCode = null,
                            newVersionName = versionName,
                            newVersionCode = versionCode,
                            detectedTime = now,
                            systemUpdateTime = if (firstInstall > 0L) firstInstall else lastUpdate,
                            changeType = CHANGE_INSTALLED
                        )
                        if (shouldInsertHistory(latestHistoryByPkg[pkgName], baselineHistory)) {
                            historyToInsert.add(baselineHistory)
                            latestHistoryByPkg[pkgName] = baselineHistory
                        }
                    }

                    appDao.commitScanChanges(appsToUpsert, historyToInsert)
                    localJsonStorage.saveAllData(
                        apps = appsToUpsert,
                        allHistory = appDao.getAllHistorySync(),
                        updatedAt = now
                    )
                    saveLastScanTime(now)
                    Log.d(TAG, "Initial baseline scan completed: ${appsToUpsert.size} apps indexed")
                    return@withLock ScanResult.Success(
                        updatedOrDowngraded = emptyList(),
                        totalScanned = appsToUpsert.size,
                        totalChangesDetected = 0,
                        scanTimestamp = now
                    )
                }

                // Subsequent scans: compare current installed state with stored database state
                for (pkg in installedPackages) {
                    val pkgName = pkg.packageName ?: continue
                    if (!currentPkgNames.add(pkgName)) continue

                    val appName = extractAppName(pm, pkg.applicationInfo, pkgName)
                    val isSystemApp = isSystemPackage(pkg.applicationInfo)
                    val versionName = normalizeVersionName(pkg.versionName)
                    val versionCode = extractVersionCode(pkg)
                    val firstInstall = pkg.firstInstallTime
                    val lastUpdate = pkg.lastUpdateTime

                    val existing = existingMap[pkgName]
                    if (existing == null) {
                        // Newly installed app
                        val newApp = AppEntity(
                            packageName = pkgName,
                            appName = appName,
                            versionName = versionName,
                            versionCode = versionCode,
                            firstInstallTime = firstInstall,
                            lastUpdateTime = lastUpdate,
                            isSystemApp = isSystemApp,
                            isInstalled = true
                        )
                        appsToUpsert.add(newApp)

                        val priorHistory = latestHistoryByPkg[pkgName]
                        val oldVerName = priorHistory?.let {
                            if (it.changeType == CHANGE_UNINSTALLED) it.oldVersionName else it.newVersionName
                        }
                        val oldVerCode = priorHistory?.let {
                            if (it.changeType == CHANGE_UNINSTALLED) it.oldVersionCode else it.newVersionCode
                        }

                        val history = VersionHistoryEntity(
                            packageName = pkgName,
                            appName = appName,
                            oldVersionName = oldVerName,
                            oldVersionCode = oldVerCode,
                            newVersionName = versionName,
                            newVersionCode = versionCode,
                            detectedTime = now,
                            systemUpdateTime = if (lastUpdate > 0L) lastUpdate else firstInstall,
                            changeType = CHANGE_INSTALLED
                        )
                        if (shouldInsertHistory(priorHistory, history)) {
                            historyToInsert.add(history)
                            latestHistoryByPkg[pkgName] = history
                        }
                    } else if (!existing.isInstalled) {
                        // Previously uninstalled app re-installed
                        val resolvedFirstInstall = if (firstInstall > 0L) firstInstall else existing.firstInstallTime
                        val reinstalledApp = existing.copy(
                            appName = appName,
                            versionName = versionName,
                            versionCode = versionCode,
                            firstInstallTime = resolvedFirstInstall,
                            lastUpdateTime = lastUpdate,
                            isSystemApp = isSystemApp,
                            isInstalled = true
                        )
                        appsToUpsert.add(reinstalledApp)

                        val history = VersionHistoryEntity(
                            packageName = pkgName,
                            appName = appName,
                            oldVersionName = existing.versionName,
                            oldVersionCode = existing.versionCode,
                            newVersionName = versionName,
                            newVersionCode = versionCode,
                            detectedTime = now,
                            systemUpdateTime = if (lastUpdate > 0L) lastUpdate else resolvedFirstInstall,
                            changeType = CHANGE_INSTALLED
                        )
                        if (shouldInsertHistory(latestHistoryByPkg[pkgName], history)) {
                            historyToInsert.add(history)
                            latestHistoryByPkg[pkgName] = history
                        }
                    } else {
                        // App already in DB and marked installed -> check version code & version name
                        val changeType = when {
                            versionCode > existing.versionCode -> CHANGE_UPDATED
                            versionCode < existing.versionCode -> CHANGE_DOWNGRADED
                            versionName != existing.versionName -> CHANGE_UPDATED
                            else -> null
                        }

                        val resolvedFirstInstall = if (firstInstall > 0L) firstInstall else existing.firstInstallTime

                        if (changeType != null) {
                            val changedApp = existing.copy(
                                appName = appName,
                                versionName = versionName,
                                versionCode = versionCode,
                                firstInstallTime = resolvedFirstInstall,
                                lastUpdateTime = lastUpdate,
                                isSystemApp = isSystemApp,
                                isInstalled = true
                            )
                            appsToUpsert.add(changedApp)

                            val history = VersionHistoryEntity(
                                packageName = pkgName,
                                appName = appName,
                                oldVersionName = existing.versionName,
                                oldVersionCode = existing.versionCode,
                                newVersionName = versionName,
                                newVersionCode = versionCode,
                                detectedTime = now,
                                systemUpdateTime = lastUpdate,
                                changeType = changeType
                            )
                            if (shouldInsertHistory(latestHistoryByPkg[pkgName], history)) {
                                historyToInsert.add(history)
                                notifiableChanges.add(history)
                                latestHistoryByPkg[pkgName] = history
                            }
                        } else if (
                            appName != existing.appName ||
                            lastUpdate != existing.lastUpdateTime ||
                            resolvedFirstInstall != existing.firstInstallTime ||
                            isSystemApp != existing.isSystemApp
                        ) {
                            // Metadata changed without a version change -> update app row only, no fake history
                            appsToUpsert.add(
                                existing.copy(
                                    appName = appName,
                                    firstInstallTime = resolvedFirstInstall,
                                    lastUpdateTime = lastUpdate,
                                    isSystemApp = isSystemApp
                                )
                            )
                        }
                    }
                }

                // Check for uninstalled packages (present & marked installed in DB, but absent from current scan)
                for (existing in existingApps) {
                    if (existing.isInstalled && existing.packageName !in currentPkgNames) {
                        if (!isPackageStillInstalled(pm, existing.packageName)) {
                            appsToUpsert.add(existing.copy(isInstalled = false))
                            val history = VersionHistoryEntity(
                                packageName = existing.packageName,
                                appName = existing.appName,
                                oldVersionName = existing.versionName,
                                oldVersionCode = existing.versionCode,
                                newVersionName = "Uninstalled",
                                newVersionCode = 0L,
                                detectedTime = now,
                                systemUpdateTime = existing.lastUpdateTime,
                                changeType = CHANGE_UNINSTALLED
                            )
                            if (shouldInsertHistory(latestHistoryByPkg[existing.packageName], history)) {
                                historyToInsert.add(history)
                                latestHistoryByPkg[existing.packageName] = history
                            }
                        }
                    }
                }

                appDao.commitScanChanges(appsToUpsert, historyToInsert)

                // Keep the single local JSON file in sync whenever data changed or file doesn't exist yet
                if (appsToUpsert.isNotEmpty() || historyToInsert.isNotEmpty() || !localJsonStorage.hasJsonFile()) {
                    val latestAppsSnapshot = if (appsToUpsert.isNotEmpty()) {
                        appDao.getAllAppsSync()
                    } else {
                        existingApps
                    }
                    val latestHistorySnapshot = if (historyToInsert.isNotEmpty()) {
                        appDao.getAllHistorySync()
                    } else {
                        allExistingHistory
                    }
                    localJsonStorage.saveAllData(
                        apps = latestAppsSnapshot,
                        allHistory = latestHistorySnapshot,
                        updatedAt = now
                    )
                }

                saveLastScanTime(now)
                Log.d(
                    TAG,
                    "Scan completed: ${currentPkgNames.size} apps checked, ${historyToInsert.size} history changes recorded"
                )

                ScanResult.Success(
                    updatedOrDowngraded = notifiableChanges,
                    totalScanned = currentPkgNames.size,
                    totalChangesDetected = historyToInsert.size,
                    scanTimestamp = now
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan installed packages", e)
                ScanResult.Error(e.localizedMessage ?: e.message ?: "Failed to scan packages", e)
            }
        }
    }

    private fun shouldInsertHistory(
        latest: VersionHistoryEntity?,
        candidate: VersionHistoryEntity
    ): Boolean {
        if (latest == null) return true
        if (candidate.changeType == CHANGE_UNINSTALLED && latest.changeType == CHANGE_UNINSTALLED) {
            return false
        }
        return !(
            latest.changeType == candidate.changeType &&
                latest.newVersionCode == candidate.newVersionCode &&
                latest.newVersionName == candidate.newVersionName &&
                latest.oldVersionCode == candidate.oldVersionCode &&
                latest.oldVersionName == candidate.oldVersionName
            )
    }

    private fun extractAppName(
        pm: PackageManager,
        appInfo: ApplicationInfo?,
        fallbackPackageName: String
    ): String {
        if (appInfo == null) return fallbackPackageName
        return try {
            val label = pm.getApplicationLabel(appInfo).toString().trim()
            label.ifEmpty { fallbackPackageName }
        } catch (e: Exception) {
            fallbackPackageName
        }
    }

    private fun isSystemPackage(appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return false
        return (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }

    private fun normalizeVersionName(rawVersionName: String?): String {
        val trimmed = rawVersionName?.trim()
        return if (trimmed.isNullOrEmpty()) "Unknown" else trimmed
    }

    private fun extractVersionCode(pkg: android.content.pm.PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
    }

    private fun isPackageStillInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "AppRepository"
        private const val PREFS_NAME = "version_tracker_prefs"
        private const val KEY_LAST_SUCCESSFUL_SCAN_TIME = "last_successful_scan_time"

        const val CHANGE_INSTALLED = "INSTALLED"
        const val CHANGE_UPDATED = "UPDATED"
        const val CHANGE_DOWNGRADED = "DOWNGRADED"
        const val CHANGE_UNINSTALLED = "UNINSTALLED"

        private val scanMutex = Mutex()
        private val _lastScanTimeFlow = MutableStateFlow(0L)
        @Volatile
        private var prefsInitialized = false

        private fun ensureLastScanTimeInitialized(appContext: Context) {
            if (!prefsInitialized) {
                synchronized(this) {
                    if (!prefsInitialized) {
                        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        _lastScanTimeFlow.value = prefs.getLong(KEY_LAST_SUCCESSFUL_SCAN_TIME, 0L)
                        prefsInitialized = true
                    }
                }
            }
        }
    }
}
