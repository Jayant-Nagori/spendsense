package com.jayant.spendsense.orders

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jayant.spendsense.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The periodic background job (~3h, network required): order sync for connected delivery apps and the Splitwise
 * sync. Only scheduled while at least one of them is connected.
 */
class OrderSyncJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return false
        if (!wanted(applicationContext)) { cancel(applicationContext); return false }
        job = AppScope.launch {
            runCatching { OrderSync.syncAll(applicationContext) }
            runCatching { com.jayant.spendsense.splitwise.SplitwiseSync.sync(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 4711

        private fun wanted(c: Context) =
            OrderSync.anyConnected(c) || runCatching { com.jayant.spendsense.splitwise.SplitwiseSync.isConnected(c) }.getOrDefault(false)

        /** Schedules the job when something is connected, cancels it otherwise. Never throws. */
        fun sync(context: Context) {
            runCatching {
                if (!wanted(context)) return cancel(context)
                val js = context.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, OrderSyncJobService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(3 * 60 * 60 * 1000L)
                        .build(),
                )
            }
        }

        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }
}
