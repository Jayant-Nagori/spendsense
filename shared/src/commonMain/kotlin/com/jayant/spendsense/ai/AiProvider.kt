package com.jayant.spendsense.ai

import com.jayant.spendsense.data.Settings

/** Which service an API key belongs to. Everything except [ANTHROPIC] speaks the OpenAI Chat Completions format. */
enum class AiProvider(
    val displayName: String,
    /** Short label for the Settings toggle. */
    val toggleLabel: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Placeholder shown in the key field when this provider is chosen. */
    val keyHint: String,
    /** Example chat/completions model ids shown as the Settings model-field hint. Empty = just the default. */
    val modelHints: List<String> = emptyList(),
) {
    ANTHROPIC("Anthropic", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5", "sk-ant-..."),
    OPENAI("OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", "sk-..."),
    GEMINI("Google Gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "AIza..."),
    OPENROUTER("OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4.1-mini", "sk-or-..."),
    GROQ("Groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk_..."),
    OPENCODE_GO(
        "OpenCode Go", "OpenCode Go", "https://opencode.ai/zen/go/v1", "deepseek-v4.1-flash", "OpenCode API key",
        listOf("deepseek-v4.1-flash", "deepseek-v4-pro", "glm-5.3", "kimi-k3", "mimo-v2.6-flash"),
    ),
    OPENCODE_ZEN(
        "OpenCode Zen", "OpenCode Zen", "https://opencode.ai/zen/v1", "deepseek-v4.1-flash", "OpenCode API key",
        listOf("deepseek-v4.1-flash", "deepseek-v4-pro", "glm-5.3", "kimi-k3"),
    ),
    CUSTOM("Custom", "Custom", "", "gpt-4.1-mini", "API key"),
    ;

    companion object {
        fun fromName(name: String?): AiProvider? = entries.firstOrNull { it.name == name }
    }

    /** True for providers that need a per-conversation `x-opencode-session` header. */
    val needsSessionHeader: Boolean get() = this == OPENCODE_GO || this == OPENCODE_ZEN

    /** Settings hint for the model field: "deepseek-v4.1-flash, deepseek-v4-pro, ..." or just the default. */
    val modelFieldHint: String get() = modelHints.ifEmpty { listOf(defaultModel) }.joinToString(", ")

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
            httpsHost(baseUrl) ?: "your custom AI endpoint"
        } else provider.displayName

    override fun toString() = "AiConfig(provider=$provider, model=$model, baseUrl=$baseUrl, apiKey=***)"
}

/** True only for well-formed https URLs with a host. Plain http is rejected so keys never cross the wire in clear. */
fun isHttpsUrl(url: String): Boolean = parseHttpUrl(url.trim())?.first == "https"

/** Host of a well-formed http(s) URL, else null (mirrors OkHttp's HttpUrl.host for the custom-endpoint label). */
private fun httpsHost(url: String): String? = parseHttpUrl(url.trim())?.second

/**
 * Minimal http/https URL check equivalent to OkHttp's toHttpUrlOrNull for the inputs that matter here:
 * scheme must be http or https (case-insensitive), followed by "://" and a non-empty valid host.
 * Returns (scheme, lowercase host) or null.
 */
internal fun parseHttpUrl(url: String): Pair<String, String>? {
    val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(.*)$").find(url) ?: return null
    val scheme = m.groupValues[1].lowercase()
    if (scheme != "http" && scheme != "https") return null
    var authority = m.groupValues[2].substringAfterLast('@')
    if (authority.any { it.isWhitespace() }) return null
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        val portPart = authority.substringAfter(':', "")
        if (authority.contains(':') && (portPart.isEmpty() || portPart.toIntOrNull()?.let { it in 1..65535 } != true)) return null
        authority.substringBefore(':')
    }
    if (host.isEmpty() || host == "[]") return null
    if (!host.startsWith("[") && !Regex("^[A-Za-z0-9._~%!$&'()*+,;=-]+$").matches(host)) return null
    if (host.startsWith(".") || host.endsWith("..")) return null
    return scheme to host.lowercase().trimEnd('.')
}

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
