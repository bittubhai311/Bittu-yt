package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class PackageChangeCount(
    val packageName: String,
    val count: Int
)

@Dao
interface AppDao {
    // --- Apps Table ---
    @Query("SELECT * FROM apps ORDER BY appName COLLATE NOCASE ASC")
    fun getAllApps(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps WHERE packageName = :packageName LIMIT 1")
    fun getAppFlow(packageName: String): Flow<AppEntity?>

    @Query("SELECT * FROM apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getApp(packageName: String): AppEntity?

    @Query("SELECT * FROM apps")
    suspend fun getAllAppsSync(): List<AppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: AppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<AppEntity>)

    @Update
    suspend fun updateApp(app: AppEntity)

    @Query("UPDATE apps SET isInstalled = 0 WHERE packageName = :packageName")
    suspend fun markUninstalled(packageName: String)

    @Query("DELETE FROM apps")
    suspend fun deleteAllApps()

    // --- Version History Table ---
    @Query("SELECT * FROM version_history ORDER BY detectedTime DESC, id DESC")
    fun getAllHistory(): Flow<List<VersionHistoryEntity>>

    @Query("SELECT * FROM version_history WHERE packageName = :packageName ORDER BY detectedTime DESC, id DESC")
    fun getHistoryForPackage(packageName: String): Flow<List<VersionHistoryEntity>>

    @Query("SELECT * FROM version_history ORDER BY detectedTime DESC, id DESC")
    suspend fun getAllHistorySync(): List<VersionHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: VersionHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistories(items: List<VersionHistoryEntity>)

    @Query("SELECT COUNT(*) FROM version_history WHERE packageName = :packageName AND newVersionCode = :newVersionCode AND detectedTime = :detectedTime")
    suspend fun countExactHistory(packageName: String, newVersionCode: Long, detectedTime: Long): Int

    @Query("SELECT packageName, COUNT(*) as count FROM version_history GROUP BY packageName")
    fun getChangeCountsPerPackage(): Flow<List<PackageChangeCount>>

    @Query("DELETE FROM version_history")
    suspend fun deleteAllHistory()

    @Transaction
    suspend fun commitScanChanges(
        appsToUpsert: List<AppEntity>,
        historyToInsert: List<VersionHistoryEntity>
    ) {
        if (appsToUpsert.isNotEmpty()) {
            insertApps(appsToUpsert)
        }
        if (historyToInsert.isNotEmpty()) {
            insertHistories(historyToInsert)
        }
    }

    @Transaction
    suspend fun replaceAllDataWithBackup(
        appsToInsert: List<AppEntity>,
        historyToInsert: List<VersionHistoryEntity>
    ) {
        deleteAllApps()
        deleteAllHistory()
        if (appsToInsert.isNotEmpty()) {
            insertApps(appsToInsert)
        }
        if (historyToInsert.isNotEmpty()) {
            insertHistories(historyToInsert)
        }
    }

    // --- Search History ---
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSearches(limit: Int = 8): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearches()
}
