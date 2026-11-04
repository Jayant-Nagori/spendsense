package com.jayant.spendsense.ai

import com.jayant.spendsense.PlatformContext
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun llmHttpEngine(): HttpClientEngineFactory<*> = Darwin

/** iOS has no SMS/notification capture to switch off; payments arrive through the Shortcuts automation. */
actual fun platformCaptureIssues(context: PlatformContext): List<String> = emptyList()
