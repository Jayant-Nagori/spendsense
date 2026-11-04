package com.jayant.spendsense.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jayant.spendsense.PlatformContext
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun llmHttpEngine(): HttpClientEngineFactory<*> = OkHttp

actual fun platformCaptureIssues(context: PlatformContext): List<String> = buildList {
    val app = context.applicationContext
    val sms = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }
    if (!sms) add("bank SMS access")
    if (!NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) {
        add("GPay/PhonePe notification access")
    }
}
