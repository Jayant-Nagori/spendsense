package com.jayant.spendsense.imports.email

import android.app.job.JobParameters
import android.app.job.JobService

/** Periodic (~6h, network required) Gmail alert import. Declared in the manifest by the integrator. */
class EmailSyncJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val creds = EmailCredentials(applicationContext)
        if (!creds.enabled) return false
        val t = Thread({
            val r = runCatching { EmailSync.run(applicationContext) }.getOrNull()
            // Retry later (backoff) only on transient network trouble.
            val retry = (r as? EmailSyncState.Failed)?.failure == EmailFailure.NO_NETWORK
            jobFinished(params, retry)
        }, "email-sync-job")
        worker = t
        t.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
