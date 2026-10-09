package com.brewpos.cafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CartMathTest {
    private val latte = Product(id = 1, name = "Latte", category = "Coffee", priceCents = 15000, stock = 10)

    @Test fun modifiersAndQuantityAreCalculatedExactly() {
        val line = CartLine(latte, "Large", 3000, listOf(Extra("Oat milk", 3000)), 2)
        assertEquals(21000, line.unitCents)
        assertEquals(42000, line.lineCents)
        assertEquals("Large, Oat milk", line.options)
    }

    @Test fun discountIsClampedAndNeverMakesNegativeTotal() {
        val line = CartLine(latte)
        val totals = CartMath.totals(listOf(line), 999999)
        assertEquals(15000, totals.subtotal)
        assertEquals(15000, totals.discount)
        assertEquals(0, totals.payable)
    }

    @Test fun cashRequiresFullTenderAndComputesChange() {
        assertThrows(IllegalArgumentException::class.java) { CartMath.change(15000, 14000, "Cash") }
        assertEquals(5000, CartMath.change(15000, 20000, "Cash"))
        assertEquals(0, CartMath.change(15000, 0, "GCash"))
    }

    @Test fun moneyInputRejectsInvalidOrFractionalCentavos() {
        assertEquals(12345, parseMoney("123.45"))
        assertEquals(null, parseMoney("12.345"))
        assertEquals(null, parseMoney("-10"))
    }
}
