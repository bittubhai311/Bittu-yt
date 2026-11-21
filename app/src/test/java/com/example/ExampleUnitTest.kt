package com.example

import com.example.data.local.AppEntity
import com.example.data.local.LocalJsonStorage
import com.example.data.local.VersionHistoryEntity
import com.example.data.repository.AppRepository
import com.example.data.repository.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testAppEntityProperties() {
        val app = AppEntity(
            packageName = "com.sample.app",
            appName = "Sample App",
            versionName = "2.4.1",
            versionCode = 241L,
            firstInstallTime = 1600000000000L,
            lastUpdateTime = 1600000000000L,
            isSystemApp = false,
            isInstalled = true
        )
        assertEquals("com.sample.app", app.packageName)
        assertEquals("Sample App", app.appName)
        assertEquals("2.4.1", app.versionName)
        assertEquals(241L, app.versionCode)
        assertEquals(false, app.isSystemApp)
        assertEquals(true, app.isInstalled)
    }

    @Test
    fun testVersionHistoryEntityProperties() {
        val history = VersionHistoryEntity(
            id = 1L,
            packageName = "com.sample.app",
            appName = "Sample App",
            oldVersionName = "2.4.0",
            oldVersionCode = 240L,
            newVersionName = "2.4.1",
            newVersionCode = 241L,
            detectedTime = 1700000000000L,
            systemUpdateTime = 1700000000000L,
            changeType = "UPDATED"
        )

        assertEquals("com.sample.app", history.packageName)
        assertEquals("2.4.0", history.oldVersionName)
        assertEquals(240L, history.oldVersionCode)
        assertEquals("2.4.1", history.newVersionName)
        assertEquals(241L, history.newVersionCode)
        assertEquals("UPDATED", history.changeType)
    }

    @Test
    fun testBaselineHistoryCreation() {
        val baseline = VersionHistoryEntity(
            id = 0L,
            packageName = "com.new.app",
            appName = "New App",
            oldVersionName = null,
            oldVersionCode = null,
            newVersionName = "1.0.0",
            newVersionCode = 100L,
            detectedTime = 1700000000000L,
            systemUpdateTime = 1700000000000L,
            changeType = "INSTALLED"
        )
        assertNull(baseline.oldVersionName)
        assertNull(baseline.oldVersionCode)
        assertEquals("INSTALLED", baseline.changeType)
    }

    @Test
    fun testScanResultDefaults() {
        val change = VersionHistoryEntity(
            id = 2L,
            packageName = "com.sample.app",
            appName = "Sample App",
            oldVersionName = "1.0",
            oldVersionCode = 1L,
            newVersionName = "2.0",
            newVersionCode = 2L,
            detectedTime = 1700000050000L,
            systemUpdateTime = 1700000040000L,
            changeType = AppRepository.CHANGE_UPDATED
        )
        val scanSuccess = ScanResult.Success(
            updatedOrDowngraded = listOf(change),
            totalScanned = 42,
            scanTimestamp = 1700000050000L
        )
        assertEquals(42, scanSuccess.totalScanned)
        assertEquals(1, scanSuccess.totalChangesDetected)
        assertEquals(1700000050000L, scanSuccess.scanTimestamp)
    }

    @Test
    fun testLocalJsonStorageWritesSingleFileWithAllAppsAndCleansLegacyFolders() {
        val tempRoot = Files.createTempDirectory("appinfo_single_json_test").toFile()
        try {
            // Simulate a legacy per-app subfolder that should be cleaned up
            val legacyFolder = File(tempRoot, "apps/com.whatsapp")
            legacyFolder.mkdirs()
            File(legacyFolder, "app_info.json").writeText("{}")

            val storage = LocalJsonStorage(tempRoot, hostPackageName = "com.appinfo.android")
            val appA = AppEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                versionName = "2.24.5",
                versionCode = 2245L,
                firstInstallTime = 1700000000000L,
                lastUpdateTime = 1700000100000L,
                isSystemApp = false,
                isInstalled = true
            )
            val appB = AppEntity(
                packageName = "com.android.chrome",
                appName = "Chrome",
                versionName = "128.0",
                versionCode = 1280L,
                firstInstallTime = 1690000000000L,
                lastUpdateTime = 1700000200000L,
                isSystemApp = true,
                isInstalled = true
            )
            val historyA = VersionHistoryEntity(
                id = 10L,
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                oldVersionName = "2.24.1",
                oldVersionCode = 2241L,
                newVersionName = "2.24.5",
                newVersionCode = 2245L,
                detectedTime = 1700000150000L,
                systemUpdateTime = 1700000100000L,
                changeType = "UPDATED"
            )

            val saved = storage.saveAllData(
                apps = listOf(appA, appB),
                allHistory = listOf(historyA),
                updatedAt = 1700000200000L
            )
            assertTrue(saved)

            // Only one single JSON file should exist, with no per-app subfolders
            val jsonFile = File(tempRoot, LocalJsonStorage.DATA_FILE_NAME)
            assertTrue(jsonFile.isFile)
            assertFalse(File(tempRoot, "apps").exists())
            assertEquals(1, tempRoot.listFiles()?.size)

            val content = jsonFile.readText(Charsets.UTF_8)
            assertTrue(content.contains("\"appPackage\": \"com.appinfo.android\""))
            assertTrue(content.contains("\"totalApps\": 2"))
            assertTrue(content.contains("\"appName\": \"WhatsApp\""))
            assertTrue(content.contains("\"packageName\": \"com.whatsapp\""))
            assertTrue(content.contains("\"appName\": \"Chrome\""))
            assertTrue(content.contains("\"packageName\": \"com.android.chrome\""))
            assertTrue(content.contains("\"newVersionName\": \"2.24.5\""))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
