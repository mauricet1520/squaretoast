package com.squaretoast.pos.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** Drains the outbox whenever the device has a network; unique so two drains never race. */
class OutboxDrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun drainer(): OutboxDrainer }

    override suspend fun doWork(): Result =
        when (EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).drainer().drain()) {
            is DrainOutcome.Drained -> Result.success()
            is DrainOutcome.RetryLater -> Result.retry()
        }

    companion object {
        private const val NAME = "outbox-drain"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
