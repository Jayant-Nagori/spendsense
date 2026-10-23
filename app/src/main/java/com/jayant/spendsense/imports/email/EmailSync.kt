package com.jayant.spendsense.imports.email

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface EmailSyncState {
    data object Idle : EmailSyncState
    data class Running(val done: Int, val total: Int) : EmailSyncState
    data class Done(val counts: ImportCounts) : EmailSyncState
    data class Failed(val failure: EmailFailure) : EmailSyncState
}

/** Orchestrates one sync at a time and exposes progress. All entry points are blocking unless noted. */
object EmailSync {
    private const val JOB_ID = 7311
    private const val PERIOD_MS = 6L * 60 * 60 * 1000
    private const val MIN_AUTO_GAP_MS = 60L * 60 * 1000

    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow<EmailSyncState>(EmailSyncState.Idle)
    val state: StateFlow<EmailSyncState> = _state.asStateFlow()

    /** Blocking. Returns null if another sync is already running or nothing is configured. */
    fun run(context: Context): EmailSyncState? {
        val app = context.applicationContext
        val creds = EmailCredentials(app)
        if (!creds.isConfigured) return null
        if (!running.compareAndSet(false, true)) return null
        _state.value = EmailSyncState.Running(0, 0)
        val password = creds.password()
        val result = try {
            if (password == null) throw EmailImportException(EmailFailure.WRONG_PASSWORD)
            val now = System.currentTimeMillis()
            val since = EmailAlertParser.computeSince(now, creds.rangeMonths, creds.lastSyncMillis, creds.coveredFromMillis)
            val counts = GmailImapImporter.import(app, creds.address, password, since) { d, t ->
                _state.value = EmailSyncState.Running(d, t)
            }
            creds.lastSyncMillis = now
            val rangeStart = now - creds.rangeMonths * 30L * 24 * 3600 * 1000
            if (creds.coveredFromMillis <= 0 || rangeStart < creds.coveredFromMillis) creds.coveredFromMillis = rangeStart
            EmailSyncState.Done(counts)
        } catch (e: EmailImportException) {
            EmailSyncState.Failed(e.failure)
        } catch (e: Throwable) {
            EmailSyncState.Failed(EmailFailure.classify(e))
        } finally {
            password?.fill('\u0000')
            running.set(false)
        }
        _state.value = result
        return result
    }

    /** Non-blocking: call from Application/Activity start. Schedules the 6h job and syncs now if the last run is stale. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        val creds = EmailCredentials(app)
        if (!creds.enabled) return
        schedule(app)
        if (System.currentTimeMillis() - creds.lastSyncMillis > MIN_AUTO_GAP_MS) {
            Thread({ run(app) }, "email-sync").start()
        }
    }

    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, EmailSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIOD_MS)
            .setPersisted(false)
            .build()
        // Never let scheduling take the app down; the sync still runs on app start and from "Sync now".
        runCatching { js.schedule(info) }
    }

    fun cancelSchedule(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun resetState() { _state.value = EmailSyncState.Idle }
}
