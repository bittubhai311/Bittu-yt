package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "version_history",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["detectedTime"])
    ]
)
data class VersionHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val oldVersionName: String?,
    val oldVersionCode: Long?,
    val newVersionName: String,
    val newVersionCode: Long,
    val detectedTime: Long,
    val systemUpdateTime: Long,
    val changeType: String // "INSTALLED", "UPDATED", "DOWNGRADED", "UNINSTALLED"
)
