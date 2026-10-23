package com.jayant.spendsense

import android.app.Application
import com.jayant.spendsense.notify.SplitPromptNotifier

class SpendSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SplitPromptNotifier.createChannel(this)
        // No-op unless the user connected Gmail; otherwise schedules the 6h job and syncs if stale.
        com.jayant.spendsense.imports.email.EmailSync.onAppStart(this)
    }
}
