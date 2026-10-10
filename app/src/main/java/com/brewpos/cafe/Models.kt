package com.brewpos.cafe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Product(
    val id: Long = 0,
    val name: String,
    val category: String,
    val priceCents: Int,
    val stock: Int,
    val trackStock: Boolean = true,
    val active: Boolean = true,
    val icon: String = "☕",
    val cloudId: String? = null,
    val cloudVersion: Long = 0
)

data class Extra(val name: String, val priceCents: Int)

val drinkExtras = listOf(
    Extra("Extra espresso shot", 4000), Extra("Oat milk", 3000),
    Extra("Soy milk", 2500), Extra("Vanilla syrup", 2000),
    Extra("Caramel syrup", 2000), Extra("Whipped cream", 2500)
)

data class CartLine(
    val product: Product,
    val size: String = "Regular",
    val sizeDelta: Int = 0,
    val extras: List<Extra> = emptyList(),
    val quantity: Int = 1,
    val key: String = UUID.randomUUID().toString()
) {
    val unitCents get() = (product.priceCents + sizeDelta + extras.sumOf { it.priceCents }).coerceAtLeast(0)
    val lineCents get() = unitCents * quantity
    val options get() = buildList {
        if (this@CartLine.size != "One size") add(this@CartLine.size)
        addAll(extras.map { it.name })
    }.joinToString(", ")
}

data class Totals(val subtotal: Int, val discount: Int, val payable: Int)

object CartMath {
    fun totals(lines: List<CartLine>, discountCents: Int): Totals {
        require(lines.all { it.quantity > 0 && it.unitCents >= 0 }) { "Invalid quantity or price" }
        val subtotal = lines.sumOf { it.lineCents }
        val discount = discountCents.coerceIn(0, subtotal)
        return Totals(subtotal, discount, subtotal - discount)
    }
    fun change(payable: Int, tendered: Int, paymentMethod: String): Int {
        require(paymentMethod != "Cash" || tendered >= payable) { "Insufficient cash tendered" }
        return if (paymentMethod == "Cash") tendered - payable else 0
    }
}

data class Sale(
    val id: Long,
    val receiptNo: String,
    val createdAt: Long,
    val service: String,
    val payment: String,
    val subtotal: Int,
    val discount: Int,
    val total: Int,
    val tendered: Int,
    val change: Int,
    val notes: String,
    val status: String = "Queued"
)

data class SaleLine(
    val productId: Long,
    val productName: String,
    val options: String,
    val quantity: Int,
    val unitCents: Int,
    val lineCents: Int
)

data class DailyStats(val orders: Int, val revenueCents: Long, val items: Int)

data class TopProduct(val name: String, val sold: Int)

fun peso(cents: Long): String = "₱" + String.format(Locale.US, "%,.2f", cents / 100.0)
fun dateTime(millis: Long): String = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US).format(Date(millis))
fun parseMoney(input: String): Int? {
    val amount = input.trim().toBigDecimalOrNull() ?: return null
    val cents = amount.multiply(java.math.BigDecimal(100))
    if (cents.signum() < 0 || cents > java.math.BigDecimal(Int.MAX_VALUE)) return null
    return try { cents.intValueExact() } catch (_: ArithmeticException) { null }
}
