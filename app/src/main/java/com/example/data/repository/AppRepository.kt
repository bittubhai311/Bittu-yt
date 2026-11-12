package com.example.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.local.AppDao
import com.example.data.local.AppEntity
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

sealed class ScanResult {
    data class Success(
        val updatedOrDowngraded: List<VersionHistoryEntity>,
        val totalScanned: Int,
        val totalChangesDetected: Int = updatedOrDowngraded.size,
        val scanTimestamp: Long = System.currentTimeMillis()
    ) : ScanResult()

    data class Error(val message: String, val cause: Throwable? = null) : ScanResult()
}

enum class RestoreMode {
    MERGE,
    REPLACE
}

data class ExportResult(
    val success: Boolean,
    val appsExported: Int,
    val historyExported: Int,
    val message: String
)

data class ImportResult(
    val success: Boolean,
    val importedCount: Int,
    val message: String
)

sealed class BackupValidationResult {
    data class Valid(
        val schemaVersion: Int,
        val exportedAt: Long,
        val apps: List<AppEntity>,
        val history: List<VersionHistoryEntity>
    ) : BackupValidationResult()

    data class Invalid(val reason: String) : BackupValidationResult()
}

class AppRepository(
    private val context: Context,
    private val appDao: AppDao
) {
    init {
        ensureLastScanTimeInitialized(context.applicationContext)
    }

    val allApps: Flow<List<AppEntity>> = appDao.getAllApps()
    val allHistory: Flow<List<VersionHistoryEntity>> = appDao.getAllHistory()
    val changeCounts: Flow<List<PackageChangeCount>> = appDao.getChangeCountsPerPackage()
    val recentSearches: Flow<List<SearchHistoryEntity>> = appDao.getRecentSearches()
    val lastScanTime: StateFlow<Long> = _lastScanTimeFlow.asStateFlow()

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

    suspend fun exportBackupWithDetails(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            try {
                val apps = appDao.getAllAppsSync()
                val history = appDao.getAllHistorySync()

                val rootJson = serializeBackupToJson(apps, history, System.currentTimeMillis())

                val outputStream = try {
                    context.contentResolver.openOutputStream(uri, "wt")
                } catch (_: Exception) {
                    context.contentResolver.openOutputStream(uri)
                } ?: return@withLock ExportResult(
                    success = false,
                    appsExported = 0,
                    historyExported = 0,
                    message = "Failed to export: could not open destination file"
                )

                outputStream.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(rootJson.toString(2))
                        writer.flush()
                    }
                }

                val message = if (apps.isEmpty() && history.isEmpty()) {
                    "Exported empty backup (0 apps, 0 history records)"
                } else {
                    "Backup exported: ${apps.size} apps, ${history.size} history records"
                }
                ExportResult(
                    success = true,
                    appsExported = apps.size,
                    historyExported = history.size,
                    message = message
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export backup", e)
                ExportResult(
                    success = false,
                    appsExported = 0,
                    historyExported = 0,
                    message = "Failed to export backup: ${e.localizedMessage ?: "I/O error"}"
                )
            }
        }
    }

    suspend fun exportBackup(uri: Uri): Boolean = exportBackupWithDetails(uri).success

    suspend fun importBackup(
        uri: Uri,
        mode: RestoreMode = RestoreMode.MERGE
    ): ImportResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            try {
                val content = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            content.append(line).append("\n")
                            line = reader.readLine()
                        }
                    }
                } ?: return@withLock ImportResult(false, 0, "Unable to read selected backup file")

                val validation = parseAndValidateBackupJson(content.toString())
                if (validation is BackupValidationResult.Invalid) {
                    Log.w(TAG, "Rejected invalid backup file: ${validation.reason}")
                    return@withLock ImportResult(false, 0, validation.reason)
                }

                val validBackup = validation as BackupValidationResult.Valid
                val backupApps = validBackup.apps
                val backupHistory = validBackup.history

                val existingApps = appDao.getAllAppsSync()
                val existingHistory = appDao.getAllHistorySync()

                // Handle empty backup files safely
                if (backupApps.isEmpty() && backupHistory.isEmpty()) {
                    return@withLock if (mode == RestoreMode.REPLACE && (existingApps.isNotEmpty() || existingHistory.isNotEmpty())) {
                        ImportResult(
                            success = false,
                            importedCount = 0,
                            message = "Restore aborted: backup file is empty and would erase existing local data"
                        )
                    } else {
                        ImportResult(
                            success = true,
                            importedCount = 0,
                            message = "Backup file is empty (0 records imported)"
                        )
                    }
                }

                val pm = context.packageManager

                if (mode == RestoreMode.REPLACE) {
                    val existingAppsMap = existingApps.associateBy { it.packageName }
                    val reconciledApps = backupApps.map { backupApp ->
                        val currentlyInstalled = isPackageStillInstalled(pm, backupApp.packageName)
                        val liveLocal = existingAppsMap[backupApp.packageName]
                        // If app is currently installed and local record is newer than the backup,
                        // keep the live version metadata so the next scan does not log a fake update.
                        if (currentlyInstalled && liveLocal != null && liveLocal.isInstalled &&
                            (liveLocal.versionCode > backupApp.versionCode ||
                                (liveLocal.versionCode == backupApp.versionCode && liveLocal.lastUpdateTime > backupApp.lastUpdateTime))
                        ) {
                            val earliestInstall = if (backupApp.firstInstallTime in 1 until liveLocal.firstInstallTime) {
                                backupApp.firstInstallTime
                            } else {
                                liveLocal.firstInstallTime
                            }
                            liveLocal.copy(firstInstallTime = earliestInstall, isInstalled = true)
                        } else {
                            backupApp.copy(isInstalled = backupApp.isInstalled && currentlyInstalled)
                        }
                    }

                    appDao.replaceAllDataWithBackup(reconciledApps, backupHistory)
                    val total = reconciledApps.size + backupHistory.size
                    return@withLock ImportResult(
                        success = true,
                        importedCount = total,
                        message = "Database replaced: $total records restored (${reconciledApps.size} apps, ${backupHistory.size} history)"
                    )
                }

                // RestoreMode.MERGE: preserve newer local data and avoid duplicates
                val existingAppsMap = existingApps.associateBy { it.packageName }
                val existingHistoryExactKeys = HashSet<String>(existingHistory.size * 2)
                val existingHistoryTransitionKeys = HashSet<String>(existingHistory.size * 2)
                for (h in existingHistory) {
                    existingHistoryKeysAdd(existingHistoryExactKeys, existingHistoryTransitionKeys, h)
                }

                val appsToUpsert = mutableListOf<AppEntity>()
                for (backupApp in backupApps) {
                    val pkgName = backupApp.packageName
                    val currentlyInstalled = isPackageStillInstalled(pm, pkgName)
                    val existingApp = existingAppsMap[pkgName]

                    if (existingApp == null) {
                        appsToUpsert.add(
                            backupApp.copy(isInstalled = backupApp.isInstalled && currentlyInstalled)
                        )
                    } else if (existingApp.isInstalled) {
                        // Local app is currently installed: never overwrite its live version with older backup data.
                        // Only backfill an earlier firstInstallTime if the backup has a valid older install timestamp.
                        if (backupApp.firstInstallTime > 0L &&
                            (existingApp.firstInstallTime <= 0L || backupApp.firstInstallTime < existingApp.firstInstallTime)
                        ) {
                            appsToUpsert.add(existingApp.copy(firstInstallTime = backupApp.firstInstallTime))
                        }
                    } else {
                        // Local app is marked uninstalled: only update if backup record has newer version/timestamp
                        val isBackupNewer = backupApp.versionCode > existingApp.versionCode ||
                            (backupApp.versionCode == existingApp.versionCode && backupApp.lastUpdateTime > existingApp.lastUpdateTime)
                        if (isBackupNewer) {
                            val earliestInstall = if (existingApp.firstInstallTime in 1 until backupApp.firstInstallTime) {
                                existingApp.firstInstallTime
                            } else {
                                backupApp.firstInstallTime
                            }
                            appsToUpsert.add(
                                backupApp.copy(
                                    firstInstallTime = earliestInstall,
                                    isInstalled = backupApp.isInstalled && currentlyInstalled
                                )
                            )
                        }
                    }
                }

                val historyToInsert = mutableListOf<VersionHistoryEntity>()
                for (h in backupHistory) {
                    val exactKey = "${h.packageName}|${h.newVersionCode}|${h.detectedTime}"
                    val transitionKey = "${h.packageName}|${h.changeType}|${h.oldVersionCode}|${h.newVersionCode}|${h.newVersionName}|${h.systemUpdateTime}"

                    val isNewExact = !existingHistoryExactKeys.contains(exactKey)
                    val isNewTransition = if (h.systemUpdateTime > 0L) {
                        !existingHistoryTransitionKeys.contains(transitionKey)
                    } else {
                        true
                    }

                    if (isNewExact && isNewTransition &&
                        appDao.countExactHistory(h.packageName, h.newVersionCode, h.detectedTime) == 0
                    ) {
                        existingHistoryKeysAdd(existingHistoryExactKeys, existingHistoryTransitionKeys, h)
                        historyToInsert.add(h)
                    }
                }

                appDao.commitScanChanges(appsToUpsert, historyToInsert)

                val importedApps = appsToUpsert.size
                val importedHistory = historyToInsert.size
                val total = importedApps + importedHistory
                val msg = if (total == 0) {
                    "0 records imported (all backup records already exist locally)"
                } else {
                    "$total records imported ($importedApps apps, $importedHistory history)"
                }
                ImportResult(true, total, msg)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import backup", e)
                ImportResult(false, 0, "Failed to import: ${e.localizedMessage ?: "Invalid backup file"}")
            }
        }
    }

    companion object {
        private const val TAG = "AppRepository"
        private const val PREFS_NAME = "version_tracker_prefs"
        private const val KEY_LAST_SUCCESSFUL_SCAN_TIME = "last_successful_scan_time"

        const val BACKUP_FORMAT_ID = "versiontracker_backup"
        const val CURRENT_BACKUP_SCHEMA_VERSION = 1

        const val CHANGE_INSTALLED = "INSTALLED"
        const val CHANGE_UPDATED = "UPDATED"
        const val CHANGE_DOWNGRADED = "DOWNGRADED"
        const val CHANGE_UNINSTALLED = "UNINSTALLED"

        private val VALID_CHANGE_TYPES = setOf(
            CHANGE_INSTALLED,
            CHANGE_UPDATED,
            CHANGE_DOWNGRADED,
            CHANGE_UNINSTALLED
        )

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

        private fun existingHistoryKeysAdd(
            exactKeys: MutableSet<String>,
            transitionKeys: MutableSet<String>,
            h: VersionHistoryEntity
        ) {
            exactKeys.add("${h.packageName}|${h.newVersionCode}|${h.detectedTime}")
            if (h.systemUpdateTime > 0L) {
                transitionKeys.add(
                    "${h.packageName}|${h.changeType}|${h.oldVersionCode}|${h.newVersionCode}|${h.newVersionName}|${h.systemUpdateTime}"
                )
            }
        }

        fun serializeBackupToJson(
            apps: List<AppEntity>,
            history: List<VersionHistoryEntity>,
            exportedAt: Long = System.currentTimeMillis()
        ): JSONObject {
            return JSONObject().apply {
                put("format", BACKUP_FORMAT_ID)
                put("schemaVersion", CURRENT_BACKUP_SCHEMA_VERSION)
                put("version", CURRENT_BACKUP_SCHEMA_VERSION)
                put("exportedAt", exportedAt)

                val appsArray = JSONArray()
                for (app in apps) {
                    val obj = JSONObject().apply {
                        put("packageName", app.packageName)
                        put("appName", app.appName)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                        put("firstInstallTime", app.firstInstallTime)
                        put("lastUpdateTime", app.lastUpdateTime)
                        put("isSystemApp", app.isSystemApp)
                        put("isInstalled", app.isInstalled)
                    }
                    appsArray.put(obj)
                }
                put("apps", appsArray)

                val historyArray = JSONArray()
                for (h in history) {
                    val obj = JSONObject().apply {
                        put("packageName", h.packageName)
                        put("appName", h.appName)
                        put("oldVersionName", h.oldVersionName ?: JSONObject.NULL)
                        put("oldVersionCode", h.oldVersionCode ?: JSONObject.NULL)
                        put("newVersionName", h.newVersionName)
                        put("newVersionCode", h.newVersionCode)
                        put("detectedTime", h.detectedTime)
                        put("systemUpdateTime", h.systemUpdateTime)
                        put("changeType", h.changeType)
                    }
                    historyArray.put(obj)
                }
                put("history", historyArray)
            }
        }

        /**
         * Strictly validates the entire backup JSON string in memory before any database modification.
         */
        fun parseAndValidateBackupJson(rawJson: String): BackupValidationResult {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) {
                return BackupValidationResult.Invalid("Invalid backup: file is empty")
            }

            val rootJson = try {
                JSONObject(trimmed)
            } catch (e: JSONException) {
                return BackupValidationResult.Invalid("Invalid backup: malformed JSON format")
            }

            if (rootJson.has("format")) {
                val format = rootJson.optString("format", "")
                if (format != BACKUP_FORMAT_ID) {
                    return BackupValidationResult.Invalid("Incompatible backup format: '$format'")
                }
            }

            val schemaVersion = when {
                rootJson.has("schemaVersion") -> rootJson.optInt("schemaVersion", -1)
                rootJson.has("version") -> rootJson.optInt("version", -1)
                else -> 1
            }

            if (schemaVersion < 1 || schemaVersion > CURRENT_BACKUP_SCHEMA_VERSION) {
                return BackupValidationResult.Invalid(
                    "Unsupported backup schema version: $schemaVersion (supported: 1..$CURRENT_BACKUP_SCHEMA_VERSION)"
                )
            }

            val hasAppsKey = rootJson.has("apps") && !rootJson.isNull("apps")
            val hasHistoryKey = rootJson.has("history") && !rootJson.isNull("history")
            if (!hasAppsKey && !hasHistoryKey) {
                return BackupValidationResult.Invalid("Invalid backup format: missing 'apps' and 'history' arrays")
            }

            val appsArray = if (hasAppsKey) {
                rootJson.optJSONArray("apps")
                    ?: return BackupValidationResult.Invalid("Invalid backup format: 'apps' must be a JSON array")
            } else {
                null
            }

            val historyArray = if (hasHistoryKey) {
                rootJson.optJSONArray("history")
                    ?: return BackupValidationResult.Invalid("Invalid backup format: 'history' must be a JSON array")
            } else {
                null
            }

            // Validate and deduplicate apps within the backup
            val validatedAppsByPkg = LinkedHashMap<String, AppEntity>()
            if (appsArray != null) {
                for (i in 0 until appsArray.length()) {
                    val obj = appsArray.optJSONObject(i)
                        ?: return BackupValidationResult.Invalid("Corrupt backup: app entry at index $i is not a JSON object")

                    val pkgName = obj.optString("packageName", "").trim()
                    if (pkgName.isEmpty() || obj.isNull("packageName")) {
                        return BackupValidationResult.Invalid("Corrupt backup: app entry at index $i is missing packageName")
                    }

                    val versionCode = obj.optLong("versionCode", -1L)
                    if (versionCode < 0L) {
                        return BackupValidationResult.Invalid("Corrupt backup: app '$pkgName' has invalid versionCode")
                    }

                    val firstInstallTime = obj.optLong("firstInstallTime", 0L)
                    val lastUpdateTime = obj.optLong("lastUpdateTime", 0L)
                    if (firstInstallTime < 0L || lastUpdateTime < 0L) {
                        return BackupValidationResult.Invalid("Corrupt backup: app '$pkgName' has negative timestamp")
                    }

                    val rawAppName = if (obj.isNull("appName")) pkgName else obj.optString("appName", pkgName).trim()
                    val rawVerName = if (obj.isNull("versionName")) "Unknown" else obj.optString("versionName", "Unknown").trim()

                    val candidate = AppEntity(
                        packageName = pkgName,
                        appName = rawAppName.ifEmpty { pkgName },
                        versionName = rawVerName.ifEmpty { "Unknown" },
                        versionCode = versionCode,
                        firstInstallTime = firstInstallTime,
                        lastUpdateTime = lastUpdateTime,
                        isSystemApp = obj.optBoolean("isSystemApp", false),
                        isInstalled = obj.optBoolean("isInstalled", true)
                    )

                    val prev = validatedAppsByPkg[pkgName]
                    if (prev == null ||
                        candidate.versionCode > prev.versionCode ||
                        (candidate.versionCode == prev.versionCode && candidate.lastUpdateTime > prev.lastUpdateTime)
                    ) {
                        validatedAppsByPkg[pkgName] = candidate
                    }
                }
            }

            // Validate and deduplicate history entries within the backup
            val validatedHistory = mutableListOf<VersionHistoryEntity>()
            val seenHistoryKeys = HashSet<String>()
            if (historyArray != null) {
                for (i in 0 until historyArray.length()) {
                    val obj = historyArray.optJSONObject(i)
                        ?: return BackupValidationResult.Invalid("Corrupt backup: history entry at index $i is not a JSON object")

                    val pkgName = obj.optString("packageName", "").trim()
                    if (pkgName.isEmpty() || obj.isNull("packageName")) {
                        return BackupValidationResult.Invalid("Corrupt backup: history entry at index $i is missing packageName")
                    }

                    if (!obj.has("newVersionCode") || obj.isNull("newVersionCode")) {
                        return BackupValidationResult.Invalid("Corrupt backup: history entry for '$pkgName' at index $i is missing newVersionCode")
                    }
                    val newVersionCode = obj.optLong("newVersionCode", -1L)
                    if (newVersionCode < 0L) {
                        return BackupValidationResult.Invalid("Corrupt backup: history entry for '$pkgName' has invalid newVersionCode")
                    }

                    val detectedTime = obj.optLong("detectedTime", -1L)
                    if (detectedTime <= 0L) {
                        return BackupValidationResult.Invalid("Corrupt backup: history entry for '$pkgName' at index $i has invalid detectedTime")
                    }

                    val changeType = obj.optString("changeType", CHANGE_UPDATED).trim().uppercase()
                    if (changeType !in VALID_CHANGE_TYPES) {
                        return BackupValidationResult.Invalid("Corrupt backup: history entry for '$pkgName' has invalid changeType '$changeType'")
                    }

                    val oldName = if (!obj.has("oldVersionName") || obj.isNull("oldVersionName")) {
                        null
                    } else {
                        obj.optString("oldVersionName").trim().ifEmpty { null }
                    }
                    val oldCode = if (!obj.has("oldVersionCode") || obj.isNull("oldVersionCode")) {
                        null
                    } else {
                        val parsed = obj.optLong("oldVersionCode", -1L)
                        if (parsed < 0L) {
                            return BackupValidationResult.Invalid("Corrupt backup: history entry for '$pkgName' has negative oldVersionCode")
                        }
                        parsed
                    }

                    val systemUpdateTime = obj.optLong("systemUpdateTime", detectedTime)
                    if (systemUpdateTime < 0L) {
                        return BackupValidationResult.Invalid("Corrupt backup: history entry for '$pkgName' has negative systemUpdateTime")
                    }

                    val rawAppName = if (obj.isNull("appName")) pkgName else obj.optString("appName", pkgName).trim()
                    val rawNewVerName = if (obj.isNull("newVersionName")) "Unknown" else obj.optString("newVersionName", "Unknown").trim()

                    val dedupKey = "$pkgName|$newVersionCode|$detectedTime|$changeType"
                    if (seenHistoryKeys.add(dedupKey)) {
                        validatedHistory.add(
                            VersionHistoryEntity(
                                packageName = pkgName,
                                appName = rawAppName.ifEmpty { pkgName },
                                oldVersionName = oldName,
                                oldVersionCode = oldCode,
                                newVersionName = rawNewVerName.ifEmpty { "Unknown" },
                                newVersionCode = newVersionCode,
                                detectedTime = detectedTime,
                                systemUpdateTime = systemUpdateTime,
                                changeType = changeType
                            )
                        )
                    }
                }
            }

            val exportedAt = rootJson.optLong("exportedAt", 0L)
            return BackupValidationResult.Valid(
                schemaVersion = schemaVersion,
                exportedAt = exportedAt,
                apps = validatedAppsByPkg.values.toList(),
                history = validatedHistory
            )
        }
    }
}
