package com.example

import com.example.data.local.AppEntity
import com.example.data.local.VersionHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun testEmptyBackupValidationRejectsBlankInput() {
        val result = com.example.data.repository.AppRepository.parseAndValidateBackupJson("   ")
        org.junit.Assert.assertTrue(result is com.example.data.repository.BackupValidationResult.Invalid)
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
            changeType = com.example.data.repository.AppRepository.CHANGE_UPDATED
        )
        val scanSuccess = com.example.data.repository.ScanResult.Success(
            updatedOrDowngraded = listOf(change),
            totalScanned = 42,
            scanTimestamp = 1700000050000L
        )
        assertEquals(42, scanSuccess.totalScanned)
        assertEquals(1, scanSuccess.totalChangesDetected)
        assertEquals(1700000050000L, scanSuccess.scanTimestamp)
    }
}
