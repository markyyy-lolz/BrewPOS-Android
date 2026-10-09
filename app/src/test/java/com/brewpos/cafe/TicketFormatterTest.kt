package com.brewpos.cafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketFormatterTest {
    private val sale = Sale(
        id = 125, receiptNo = "BP-20261009-00125", createdAt = 1791550000000L,
        service = "Dine-in", payment = "Cash", subtotal = 26000,
        discount = 1000, total = 25000, tendered = 30000, change = 5000,
        notes = "Table 3; extra hot; no sugar"
    )
    private val drinks = listOf(
        SaleLine(1, "Caramel Latte", "Large, Oat milk, Extra shot", 2, 12000, 24000),
        SaleLine(2, "Espresso", "Regular", 1, 2000, 2000)
    )

    @Test fun baristaSlipShowsPreparationDetailsWithoutPrices() {
        val text = TicketFormatter.baristaText("My Coffee Shop", sale, drinks, 32)
        assertTrue(text.contains("BARISTA ORDER SLIP"))
        assertTrue(text.contains(sale.receiptNo))
        assertTrue(text.contains("DINE-IN"))
        assertTrue(text.contains("2x Caramel Latte"))
        assertTrue(text.contains("Large"))
        assertTrue(text.contains("Oat") && text.contains("milk"))
        assertTrue(text.contains("Table 3"))
        for (forbidden in listOf("PHP", "Subtotal", "Discount", "Tendered", "Change", "Cash", "250.00")) {
            assertFalse("Found money field $forbidden in barista ticket", text.contains(forbidden))
        }
    }

    @Test fun customerReceiptStillHasAccountingAndPrices() {
        val text = TicketFormatter.customerText("My Coffee Shop", "Thanks", sale, drinks, 32)
        assertTrue(text.contains("SALES RECEIPT"))
        assertTrue(text.contains("PHP 250.00"))
        assertTrue(text.contains("Discount"))
        assertTrue(text.contains("Payment").not()) // method is printed on service line
        assertTrue(text.contains("Cash"))
    }

    @Test fun oneCombinedJobHasBothSlipsAndTwoCutsIfEnabled() {
        val bytes = TicketFormatter.both("Shop", "Thanks", sale, drinks, 32, true)
        val text = bytes.toString(Charsets.US_ASCII)
        assertTrue(text.indexOf("SALES RECEIPT") < text.indexOf("BARISTA ORDER SLIP"))
        assertEquals(2, bytes.asList().windowed(3).count { it == listOf(0x1D.toByte(), 0x56.toByte(), 0x00.toByte()) })
    }

    @Test fun noAutoCutterProvidesTearSeparator() {
        val text = TicketFormatter.both("Shop", "Thanks", sale, drinks, 48, false).toString(Charsets.US_ASCII)
        assertTrue(text.contains("TEAR HERE / NEXT SLIP"))
        assertFalse(text.contains("\u001dV\u0000"))
    }

    @Test fun longNotesAreWrappedFor58mm() {
        val text = TicketFormatter.baristaText("Cafe", sale.copy(notes = "Do not add sweetener and use extra hot water for the customer at the front counter"), drinks, 32)
        assertTrue(text.lines().all { it.length <= 32 })
    }
}
