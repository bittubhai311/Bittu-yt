package com.example.service

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.repository.AppRepository
import com.example.data.repository.ScanResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ScanWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getInstance(appContext)
            val repository = AppRepository(appContext, database.appDao())

            when (val result = repository.performScan()) {
                is ScanResult.Success -> {
                    Log.d(
                        TAG,
                        "Periodic scan succeeded: ${result.totalScanned} apps scanned, ${result.totalChangesDetected} changes"
                    )
                    if (result.updatedOrDowngraded.isNotEmpty()) {
                        NotificationHelper.notifyVersionChanges(appContext, result.updatedOrDowngraded)
                    }
                    Result.success()
                }
                is ScanResult.Error -> {
                    val isPermanent = isPermanentFailure(result.cause)
                    Log.e(
                        TAG,
                        "Periodic scan failed (attempt $runAttemptCount, permanent=$isPermanent): ${result.message}",
                        result.cause
                    )
                    if (!isPermanent && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val isPermanent = isPermanentFailure(e)
            Log.e(TAG, "Unexpected failure in ScanWorker (attempt $runAttemptCount, permanent=$isPermanent)", e)
            if (!isPermanent && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun isPermanentFailure(cause: Throwable?): Boolean {
        if (cause == null) return false
        return cause is SecurityException ||
            cause is SQLiteDatabaseCorruptException ||
            cause is SQLiteFullException ||
            cause is IllegalArgumentException
    }

    companion object {
        private const val TAG = "ScanWorker"
        private const val MAX_RETRY_ATTEMPTS = 3
        const val UNIQUE_WORK_NAME = "VersionTrackerPeriodicScan"

        fun schedule(context: Context) {
            // Use Android's minimum supported periodic interval (15 minutes) without
            // restrictive battery/charging/idle constraints so scans can run during normal maintenance windows.
            val workRequest = PeriodicWorkRequestBuilder<ScanWorker>(
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
            )
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
