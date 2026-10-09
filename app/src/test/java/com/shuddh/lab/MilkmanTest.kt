package com.shuddh.lab

import com.shuddh.lab.core.Milkman
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class MilkmanTest {
    private fun t(day: Int) = Calendar.getInstance().apply { set(2026, 9, day, 8, 0) }.timeInMillis

    @Test fun monthBillAndOverpay() {
        val days = (1..30).map { Milkman.Day(20261000 + it, 1.0) }
        // Water tests: 20 % on the 5th (covers 5–8), 30 % on the 20th (covers 20–23).
        val tests = listOf(Milkman.Test(t(5), 20.0), Milkman.Test(t(20), 30.0))
        val m = Milkman.month(days, tests, 60.0, 20261001)
        assertEquals(30.0, m.litres, 1e-9)
        assertEquals(1800.0, m.billed, 1e-9)
        assertEquals(8, m.wateredDays)
        assertEquals(4 * 0.2 + 4 * 0.3, m.waterLitres, 1e-9)
        assertEquals(120.0, m.overpaid, 1e-6)
    }
}
