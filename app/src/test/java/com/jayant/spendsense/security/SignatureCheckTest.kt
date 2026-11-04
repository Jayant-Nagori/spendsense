package com.jayant.spendsense.security

import com.jayant.spendsense.security.SignatureCheck.Verdict
import com.jayant.spendsense.ui.NOT_GENUINE_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureCheckTest {
    private val release = "adf4b21eb88f680422f6679ba10adc87435a8a03bb3514c8110b5b95c79c9fc7"
    private val other = "1111111111111111111111111111111111111111111111111111111111111111"

    @Test fun matchingReleaseCertIsGenuine() =
        assertEquals(Verdict.GENUINE, SignatureCheck.verdict(listOf(release), release, debugBuild = false))

    @Test fun colonSeparatedUpperCaseFormsCompareEqual() {
        val pretty = release.uppercase().chunked(2).joinToString(":")
        assertEquals(Verdict.GENUINE, SignatureCheck.verdict(listOf(pretty), release, debugBuild = false))
        assertEquals(Verdict.GENUINE, SignatureCheck.verdict(listOf(release), pretty, debugBuild = false))
    }

    @Test fun repackagedApkIsNotGenuine() =
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf(other), release, debugBuild = false))

    @Test fun anExtraSignerIsNotGenuine() =
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf(release, other), release, debugBuild = false))

    @Test fun noSignersIsNotGenuine() =
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(emptyList(), release, debugBuild = false))

    @Test fun missingOrMalformedExpectedValueFailsClosed() {
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf(release), "", debugBuild = false))
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf(release), null, debugBuild = false))
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf("abc"), "abc", debugBuild = false))
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf(release.take(63) + "z"), release.take(63) + "z", debugBuild = false))
    }

    @Test fun prefixOrSubstringIsNotEnough() =
        assertEquals(Verdict.NOT_GENUINE, SignatureCheck.verdict(listOf(release + "00"), release, debugBuild = false))

    @Test fun debugBuildsSkipTheCheck() =
        assertEquals(Verdict.SKIPPED, SignatureCheck.verdict(listOf(other), release, debugBuild = true))

    @Test fun sha256HexIsLowercaseHex() {
        // SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SignatureCheck.sha256Hex("abc".toByteArray()))
    }

    @Test fun warningWordingIsExact() {
        assertEquals(
            "This copy of SpendSense isn't genuine — it may have been modified to steal your data. " +
                "Uninstall it and install only from the official link.",
            NOT_GENUINE_MESSAGE,
        )
        assertTrue(NOT_GENUINE_MESSAGE.contains("official link"))
    }
}
