package com.jayant.spendsense.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun small() = assertEquals("₹254", Money.inr(254.0))
    @Test fun thousands() = assertEquals("₹1,250.50", Money.inr(1250.5))
    @Test fun lakhs() = assertEquals("₹1,25,000", Money.inr(125000.0))
    @Test fun crores() = assertEquals("₹12,34,56,789", Money.inr(123456789.0))
    @Test fun negative() = assertEquals("-₹99.99", Money.inr(-99.99))
    @Test fun zero() = assertEquals("₹0", Money.inr(0.0))
}
