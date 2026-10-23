package com.jayant.spendsense.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
