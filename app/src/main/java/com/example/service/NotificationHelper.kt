package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.local.VersionHistoryEntity
import com.example.data.repository.AppRepository

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    private const val PREFS_NAME = "version_tracker_prefs"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_PERMISSION_REQUESTED_ONCE = "notification_permission_requested_once"
    private const val KEY_LAST_NOTIFICATION_SIGNATURE = "last_notification_signature"

    const val CHANNEL_ID = "version_tracker_updates"
    const val CHANNEL_NAME = "App Version Updates"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when installed app versions are updated or downgraded"
                enableLights(true)
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Checks whether runtime POST_NOTIFICATIONS permission is granted (always true below Android 13).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Checks whether app-level notifications and the version update channel are enabled in Android OS settings.
     */
    fun areSystemNotificationsEnabled(context: Context): Boolean {
        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = manager?.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    /**
     * Returns user's in-app preference toggle value.
     */
    fun isUserPreferenceEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
    }

    /**
     * Returns true only when the in-app toggle is ON, runtime permission is granted,
     * and system/channel notifications are not blocked.
     */
    fun isNotificationEnabled(context: Context): Boolean {
        return isUserPreferenceEnabled(context) &&
            hasNotificationPermission(context) &&
            areSystemNotificationsEnabled(context)
    }

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun hasRequestedInitialPermission(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PERMISSION_REQUESTED_ONCE, false)
    }

    fun markInitialPermissionRequested(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED_ONCE, true).apply()
    }

    /**
     * Provides a human-readable status message when notifications cannot be delivered.
     */
    fun getNotificationAvailabilityNote(context: Context): String? {
        if (!hasNotificationPermission(context)) {
            return "Notification permission is not granted. Enable permission to receive alerts."
        }
        if (!areSystemNotificationsEnabled(context)) {
            return "Notifications or the '$CHANNEL_NAME' channel are disabled in Android system settings."
        }
        return null
    }

    fun notifyVersionChanges(context: Context, changes: List<VersionHistoryEntity>) {
        // Filter strictly to genuine UPDATED or DOWNGRADED version changes and deduplicate within batch
        val genuineChanges = changes
            .filter { change ->
                (change.changeType == AppRepository.CHANGE_UPDATED ||
                    change.changeType == AppRepository.CHANGE_DOWNGRADED) &&
                    change.oldVersionName != null &&
                    (change.oldVersionCode != change.newVersionCode ||
                        change.oldVersionName != change.newVersionName)
            }
            .distinctBy { change ->
                "${change.packageName}|${change.changeType}|${change.oldVersionCode}|${change.newVersionCode}|${change.newVersionName}"
            }

        if (genuineChanges.isEmpty() || !isNotificationEnabled(context)) return

        // Ensure channel exists before posting
        createNotificationChannel(context)

        // Prevent duplicate notifications for identical change sets
        val signature = genuineChanges
            .sortedBy { it.packageName }
            .joinToString(";") {
                "${it.packageName}:${it.changeType}:${it.oldVersionCode}->${it.newVersionCode}:${it.newVersionName}"
            }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSignature = prefs.getString(KEY_LAST_NOTIFICATION_SIGNATURE, null)
        if (signature == lastSignature) {
            Log.d(TAG, "Skipping duplicate notification for already-notified version change set")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            pendingIntentFlags
        )

        val firstChange = genuineChanges.first()
        val title = if (genuineChanges.size == 1) {
            "${firstChange.appName} ${firstChange.changeType.lowercase()}"
        } else {
            "${genuineChanges.size} App Version Changes Detected"
        }

        val singleLine = if (genuineChanges.size == 1) {
            val oldVer = firstChange.oldVersionName ?: "?"
            "${firstChange.appName} ${firstChange.changeType.lowercase()}: $oldVer -> ${firstChange.newVersionName}"
        } else {
            genuineChanges.joinToString(", ") { "${it.appName} (${it.newVersionName})" }
        }

        val inboxStyle = NotificationCompat.InboxStyle()
        genuineChanges.take(6).forEach { change ->
            val oldVer = change.oldVersionName ?: "?"
            inboxStyle.addLine("${change.appName}: $oldVer -> ${change.newVersionName}")
        }
        if (genuineChanges.size > 6) {
            inboxStyle.setSummaryText("+${genuineChanges.size - 6} more apps")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(singleLine)
            .setStyle(inboxStyle)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            prefs.edit().putString(KEY_LAST_NOTIFICATION_SIGNATURE, signature).apply()
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification permission denied while posting update notification", se)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post version change notification", e)
        }
    }
}
