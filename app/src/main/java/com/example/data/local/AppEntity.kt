package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val versionName: String, // "Unknown" if null
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val isSystemApp: Boolean,
    val isInstalled: Boolean = true
)
