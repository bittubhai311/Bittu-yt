package com.example.data.local

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Stores all tracked app information and version history in a single local JSON file
 * inside the App Info package directory:
 *   Android/data/com.appinfo.android/files/app_info_data.json
 * (falling back to internal filesDir/app_info_data.json if external app storage is unavailable).
 *
 * Requires zero storage permissions and writes atomically to avoid file corruption.
 */
class LocalJsonStorage(
    private val storageDir: File,
    private val hostPackageName: String = DEFAULT_HOST_PACKAGE
) {
    fun getJsonFile(): File {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File(storageDir, DATA_FILE_NAME)
    }

    fun hasJsonFile(): Boolean {
        return try {
            File(storageDir, DATA_FILE_NAME).isFile
        } catch (_: Exception) {
            false
        }
    }

    fun getDisplayFilePath(): String {
        val rawPath = File(storageDir, DATA_FILE_NAME).path
        val androidMarker = "/Android/data/"
        val idx = rawPath.indexOf(androidMarker)
        return if (idx >= 0) {
            rawPath.substring(idx + 1)
        } else {
            rawPath
        }
    }

    /**
     * Atomically writes all apps and their version history into the single JSON file.
     * Also cleans up any legacy per-package 'apps/' subfolder if present.
     */
    fun saveAllData(
        apps: List<AppEntity>,
        allHistory: List<VersionHistoryEntity>,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            if (!storageDir.exists() && !storageDir.mkdirs()) {
                safeLogW("Could not create storage directory: ${storageDir.path}")
                return false
            }

            // Remove any legacy per-app folders so only the single JSON file exists
            val legacyAppsDir = File(storageDir, LEGACY_APPS_FOLDER)
            if (legacyAppsDir.exists() && legacyAppsDir.isDirectory) {
                runCatching { legacyAppsDir.deleteRecursively() }
            }

            val jsonContent = formatSingleJson(
                hostPackageName = hostPackageName,
                apps = apps,
                allHistory = allHistory,
                updatedAt = updatedAt
            )
            writeFileAtomically(File(storageDir, DATA_FILE_NAME), jsonContent)
            true
        } catch (e: Exception) {
            safeLogE("Failed to write local JSON data file", e)
            false
        }
    }

    private fun writeFileAtomically(target: File, content: String) {
        val parent = target.parentFile ?: throw IOException("Missing parent directory for ${target.path}")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent, "${target.name}.tmp")
        tempFile.writeText(content, Charsets.UTF_8)
        if (!tempFile.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                tempFile.delete()
                throw IOException("Failed to replace existing JSON file: ${target.path}")
            }
            if (!tempFile.renameTo(target)) {
                tempFile.delete()
                throw IOException("Failed to move temporary JSON file into place: ${target.path}")
            }
        }
    }

    private fun safeLogW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun safeLogE(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }

    companion object {
        private const val TAG = "LocalJsonStorage"
        private const val LEGACY_APPS_FOLDER = "apps"
        private const val DEFAULT_HOST_PACKAGE = "com.appinfo.android"
        const val SCHEMA_VERSION = 1
        const val DATA_FILE_NAME = "app_info_data.json"

        fun create(context: Context): LocalJsonStorage {
            val appContext = context.applicationContext
            val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            return LocalJsonStorage(
                storageDir = baseDir,
                hostPackageName = appContext.packageName
            )
        }

        internal fun formatSingleJson(
            hostPackageName: String,
            apps: List<AppEntity>,
            allHistory: List<VersionHistoryEntity>,
            updatedAt: Long
        ): String {
            val historyByPkg = allHistory.groupBy { it.packageName }
            val appsByPkg = apps.associateBy { it.packageName }

            // Include every app from apps table plus any history-only uninstalled packages
            val orderedPackages = LinkedHashSet<String>(apps.size + historyByPkg.size)
            apps.sortedWith(
                compareBy<AppEntity> { it.appName.lowercase() }.thenBy { it.packageName }
            ).forEach { orderedPackages.add(it.packageName) }
            historyByPkg.keys.sorted().forEach { orderedPackages.add(it) }

            return buildString {
                append("{\n")
                append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
                append("  \"appPackage\": ").append(jsonString(hostPackageName)).append(",\n")
                append("  \"updatedAt\": ").append(updatedAt).append(",\n")
                append("  \"totalApps\": ").append(orderedPackages.size).append(",\n")
                append("  \"totalHistoryRecords\": ").append(allHistory.size).append(",\n")
                append("  \"apps\": [")

                if (orderedPackages.isNotEmpty()) {
                    append("\n")
                    val pkgList = orderedPackages.toList()
                    pkgList.forEachIndexed { appIndex, pkgName ->
                        val app = appsByPkg[pkgName]
                        val pkgHistory = historyByPkg[pkgName].orEmpty().sortedWith(
                            compareByDescending<VersionHistoryEntity> { it.detectedTime }
                                .thenByDescending { it.id }
                        )
                        val latestHist = pkgHistory.firstOrNull()

                        val appName = app?.appName ?: latestHist?.appName ?: pkgName
                        val versionName = app?.versionName ?: latestHist?.newVersionName ?: "Unknown"
                        val versionCode = app?.versionCode ?: latestHist?.newVersionCode ?: 0L
                        val firstInstallTime = app?.firstInstallTime ?: pkgHistory.lastOrNull()?.systemUpdateTime ?: 0L
                        val lastUpdateTime = app?.lastUpdateTime ?: latestHist?.systemUpdateTime ?: 0L
                        val isSystemApp = app?.isSystemApp ?: false
                        val isInstalled = app?.isInstalled ?: false

                        append("    {\n")
                        append("      \"packageName\": ").append(jsonString(pkgName)).append(",\n")
                        append("      \"appName\": ").append(jsonString(appName)).append(",\n")
                        append("      \"versionName\": ").append(jsonString(versionName)).append(",\n")
                        append("      \"versionCode\": ").append(versionCode).append(",\n")
                        append("      \"firstInstallTime\": ").append(firstInstallTime).append(",\n")
                        append("      \"lastUpdateTime\": ").append(lastUpdateTime).append(",\n")
                        append("      \"isSystemApp\": ").append(isSystemApp).append(",\n")
                        append("      \"isInstalled\": ").append(isInstalled).append(",\n")
                        append("      \"versionHistory\": [")
                        if (pkgHistory.isNotEmpty()) {
                            append("\n")
                            pkgHistory.forEachIndexed { histIndex, item ->
                                append("        {\n")
                                append("          \"id\": ").append(item.id).append(",\n")
                                append("          \"oldVersionName\": ").append(jsonNullableString(item.oldVersionName)).append(",\n")
                                append("          \"oldVersionCode\": ").append(item.oldVersionCode?.toString() ?: "null").append(",\n")
                                append("          \"newVersionName\": ").append(jsonString(item.newVersionName)).append(",\n")
                                append("          \"newVersionCode\": ").append(item.newVersionCode).append(",\n")
                                append("          \"detectedTime\": ").append(item.detectedTime).append(",\n")
                                append("          \"systemUpdateTime\": ").append(item.systemUpdateTime).append(",\n")
                                append("          \"changeType\": ").append(jsonString(item.changeType)).append("\n")
                                append("        }")
                                if (histIndex < pkgHistory.lastIndex) {
                                    append(",")
                                }
                                append("\n")
                            }
                            append("      ]\n")
                        } else {
                            append("]\n")
                        }
                        append("    }")
                        if (appIndex < pkgList.lastIndex) {
                            append(",")
                        }
                        append("\n")
                    }
                    append("  ]\n")
                } else {
                    append("]\n")
                }
                append("}\n")
            }
        }

        private fun jsonNullableString(value: String?): String {
            return if (value == null) "null" else jsonString(value)
        }

        private fun jsonString(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append(String.format("\\u%04x", ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
