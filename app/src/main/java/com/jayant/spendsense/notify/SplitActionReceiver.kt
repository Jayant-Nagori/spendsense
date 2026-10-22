package com.jayant.spendsense.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// OWNER: UI agent. Handles the "Fully mine" notification action without opening the app.
class SplitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
