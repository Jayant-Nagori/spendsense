package com.jayant.spendsense.ai

import com.jayant.spendsense.data.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Which service an API key belongs to. Everything except [ANTHROPIC] speaks the OpenAI Chat Completions format. */
enum class AiProvider(
    val displayName: String,
    /** Short label for the Settings toggle. */
    val toggleLabel: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Placeholder shown in the key field when this provider is chosen. */
    val keyHint: String,
) {
    ANTHROPIC("Anthropic", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5", "sk-ant-..."),
    OPENAI("OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", "sk-..."),
    GEMINI("Google Gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "AIza..."),
    OPENROUTER("OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4.1-mini", "sk-or-..."),
    GROQ("Groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk_..."),
    CUSTOM("Custom", "Custom", "", "gpt-4.1-mini", "API key"),
    ;

    companion object {
        fun fromName(name: String?): AiProvider? = entries.firstOrNull { it.name == name }
    }
}

/** Guess the provider from the key's prefix. Unknown shapes are treated as a custom OpenAI-compatible endpoint. */
fun detectProvider(key: String): AiProvider {
    val k = key.trim()
    return when {
        k.startsWith("sk-ant-") -> AiProvider.ANTHROPIC
        k.startsWith("sk-or-") -> AiProvider.OPENROUTER
        k.startsWith("gsk_") -> AiProvider.GROQ
        k.startsWith("AIza") -> AiProvider.GEMINI
        k.startsWith("sk-") -> AiProvider.OPENAI
        else -> AiProvider.CUSTOM
    }
}

/** A fully resolved provider setup. [toString] never includes the key. */
data class AiConfig(val provider: AiProvider, val apiKey: String, val model: String, val baseUrl: String) {
    /** Who receives the user's data, for privacy copy: the provider name, or the host for a custom endpoint. */
    val recipient: String
        get() = if (provider == AiProvider.CUSTOM) {
            baseUrl.toHttpUrlOrNull()?.host ?: "your custom AI endpoint"
        } else provider.displayName

    override fun toString() = "AiConfig(provider=$provider, model=$model, baseUrl=$baseUrl, apiKey=***)"
}

/** True only for well-formed https URLs with a host. Plain http is rejected so keys never cross the wire in clear. */
fun isHttpsUrl(url: String): Boolean = url.trim().toHttpUrlOrNull()?.isHttps == true

/**
 * Combine the stored key with the user's optional overrides. Returns null when there is no key.
 * [provider] null means auto-detect; blank [model] means the provider default; [customBaseUrl] only matters for CUSTOM.
 */
fun resolveAiConfig(key: String, provider: AiProvider?, model: String, customBaseUrl: String): AiConfig? {
    val k = key.trim()
    if (k.isEmpty()) return null
    val p = provider ?: detectProvider(k)
    return AiConfig(
        provider = p,
        apiKey = k,
        model = model.trim().ifEmpty { p.defaultModel },
        baseUrl = if (p == AiProvider.CUSTOM) customBaseUrl.trim() else p.defaultBaseUrl,
    )
}

fun Settings.aiConfig(): AiConfig? = resolveAiConfig(aiApiKey, aiProvider, aiModel, aiBaseUrl)
