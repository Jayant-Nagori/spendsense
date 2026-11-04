package com.jayant.spendsense.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayeeTest {
    @Test
    fun vpaBecomesAName() {
        assertEquals("Chai", Payee.display("chai@okaxis"))
        assertEquals("chai@okaxis", Payee.vpaForMeta("chai@okaxis"))
        assertEquals("Rahul Sharma", Payee.display("rahul.sharma_1@okicici"))
    }

    @Test
    fun unreadableHandlesStayAsIs() {
        assertEquals("9876543210@ybl", Payee.display("9876543210@ybl"))
        assertNull(Payee.vpaForMeta("9876543210@ybl"))
        assertEquals("paytmqr2810050501@paytm", Payee.display("paytmqr2810050501@paytm"))
    }

    @Test
    fun namesAreUntouched() {
        assertEquals("Punjabi Grill", Payee.display("Punjabi Grill"))
        assertNull(Payee.vpaForMeta("Punjabi Grill"))
        assertNull(Payee.display("  "))
    }
}
