package com.jayant.spendsense.imports

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImportUriTest {
    private val pkg = "com.jayant.spendsense"
    private fun ok(scheme: String?, authority: String?) = ShareImportActivity.isAcceptableShareUri(scheme, authority, pkg)

    @Test fun acceptsOtherAppsContentUris() {
        assertTrue(ok("content", "com.android.providers.downloads.documents"))
        assertTrue(ok("CONTENT", "com.google.android.gm.sapi"))
    }

    @Test fun rejectsFileUrisAndOurOwnAuthority() {
        assertFalse(ok("file", null))
        assertFalse(ok("file", ""))
        assertFalse(ok("content", null))
        assertFalse(ok("content", pkg))
        assertFalse(ok("content", "$pkg.fileprovider"))
        assertFalse(ok("http", "evil.test"))
        assertFalse(ok(null, "x"))
    }
}
