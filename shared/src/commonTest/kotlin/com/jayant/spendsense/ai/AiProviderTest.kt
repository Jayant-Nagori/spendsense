package com.jayant.spendsense.ai

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class AiProviderTest {
    @Test fun detectsProviderByPrefix() {
        assertEquals(AiProvider.ANTHROPIC, detectProvider("sk-ant-api03-abc"))
        assertEquals(AiProvider.OPENROUTER, detectProvider("sk-or-v1-abc"))
        assertEquals(AiProvider.GROQ, detectProvider("gsk_abc123"))
        assertEquals(AiProvider.GEMINI, detectProvider("AIzaSyAbc"))
        assertEquals(AiProvider.OPENAI, detectProvider("sk-proj-abc"))
        assertEquals(AiProvider.OPENAI, detectProvider("sk-abc"))
        assertEquals(AiProvider.CUSTOM, detectProvider("random-token"))
        assertEquals(AiProvider.CUSTOM, detectProvider(""))
    }

    @Test fun detectionIgnoresSurroundingWhitespace() {
        assertEquals(AiProvider.ANTHROPIC, detectProvider("  sk-ant-xyz\n"))
    }

    @Test fun resolveUsesDefaultsAndOverrides() {
        assertNull(resolveAiConfig("  ", null, "", ""))
        val auto = resolveAiConfig("gsk_abc", null, "", "ignored")!!
        assertEquals(AiProvider.GROQ, auto.provider)
        assertEquals("llama-3.3-70b-versatile", auto.model)
        assertEquals("https://api.groq.com/openai/v1", auto.baseUrl)

        val manual = resolveAiConfig("sk-abc", AiProvider.OPENROUTER, " my/model ", "")!!
        assertEquals(AiProvider.OPENROUTER, manual.provider)
        assertEquals("my/model", manual.model)

        val custom = resolveAiConfig("tok", null, "", " https://llm.example.com/v1 ")!!
        assertEquals(AiProvider.CUSTOM, custom.provider)
        assertEquals("https://llm.example.com/v1", custom.baseUrl)
        assertEquals("llm.example.com", custom.recipient)
    }

    @Test fun anthropicDefaultsStayOnTheExistingModel() {
        assertEquals("claude-sonnet-5-5", AiProvider.ANTHROPIC.defaultModel)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", AiProvider.GEMINI.defaultBaseUrl)
    }

    @Test fun openCodeProviders() {
        val go = AiProvider.OPENCODE_GO
        assertEquals("OpenCode Go", go.displayName)
        assertEquals("https://opencode.ai/zen/go/v1", go.defaultBaseUrl)
        assertEquals("deepseek-v4.1-flash", go.defaultModel)
        assertEquals("OpenCode API key", go.keyHint)
        assertEquals(listOf("deepseek-v4.1-flash", "deepseek-v4-pro", "glm-5.3", "kimi-k3", "mimo-v2.6-flash"), go.modelHints)
        assertTrue(go.modelFieldHint.startsWith("deepseek-v4.1-flash, deepseek-v4-pro"))
        val zen = AiProvider.OPENCODE_ZEN
        assertEquals("https://opencode.ai/zen/v1", zen.defaultBaseUrl)
        assertEquals("OpenCode API key", zen.keyHint)
        assertTrue(go.needsSessionHeader && zen.needsSessionHeader)
        assertFalse(AiProvider.OPENAI.needsSessionHeader)
        assertEquals("gpt-4.1-mini", AiProvider.OPENAI.modelFieldHint)
        assertEquals(AiProvider.OPENCODE_GO, AiProvider.fromName("OPENCODE_GO"))
        // Keys have no recognisable prefix, so OpenCode must be picked explicitly; the base URL then follows it.
        assertEquals("https://opencode.ai/zen/go/v1", resolveAiConfig("oc-key", AiProvider.OPENCODE_GO, "", "")!!.baseUrl)
        assertEquals("OpenCode Zen", resolveAiConfig("oc-key", AiProvider.OPENCODE_ZEN, "", "")!!.recipient)
    }

    @Test fun httpsOnly() {
        assertTrue(isHttpsUrl("https://api.example.com/v1"))
        assertFalse(isHttpsUrl("http://api.example.com/v1"))
        assertFalse(isHttpsUrl("ftp://x"))
        assertFalse(isHttpsUrl("not a url"))
        assertFalse(isHttpsUrl(""))
    }

    @Test fun configToStringNeverLeaksTheKey() {
        val c = resolveAiConfig("sk-secret-123", null, "", "")!!
        assertFalse(c.toString().contains("secret"))
    }
}
