package com.jayant.spendsense

import android.app.Application
import com.jayant.spendsense.notify.SplitPromptNotifier

class SpendSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SplitPromptNotifier.createChannel(this)
    }
}
