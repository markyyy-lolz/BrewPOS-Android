package com.brewpos.cafe

import java.nio.charset.StandardCharsets

/**
 * Plain, Android-independent ESC/POS document formatter. A checkout can send
 * both documents over ONE paired Bluetooth printer connection, in this order:
 * customer receipt -> paper separation/cut -> barista preparation ticket.
 *
 * No price, discount, payment details, or change are printed on barista tickets.
 */
object TicketFormatter {
    private val reset = byteArrayOf(0x1B, 0x40) // ESC @
    private val cutPaper = byteArrayOf(0x1D, 0x56, 0x00) // GS V 0

    private fun ascii(value: String): String = value
        .replace("₱", "PHP ").replace('×', 'x').replace('•', '-')
        .replace(Regex("[^\\x20-\\x7E\\n]"), "")

    private fun wrap(input: String, width: Int, prefix: String = ""): String {
        val limit = (width - prefix.length).coerceAtLeast(1)
        val output = StringBuilder()
        ascii(input).lines().forEach { paragraph ->
            var current = ""
            paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                var part = word
                while (part.length > limit) {
                    if (current.isNotBlank()) {
                        output.append(prefix).append(current).append('\n')
                        current = ""
                    }
                    output.append(prefix).append(part.take(limit)).append('\n')
                    part = part.drop(limit)
                }
                if (part.isNotBlank()) {
                    val candidate = if (current.isEmpty()) part else "$current $part"
                    if (candidate.length > limit) {
                        output.append(prefix).append(current).append('\n')
                        current = part
                    } else current = candidate
                }
            }
            if (current.isNotBlank()) output.append(prefix).append(current).append('\n')
        }
        return output.toString()
    }

    private fun aligned(left: String, right: String, width: Int): String {
        val r = ascii(right).take(width)
        val remaining = (width - r.length - 1).coerceAtLeast(0)
        val l = ascii(left).take(remaining)
        return l.padEnd((width - r.length).coerceAtLeast(0)) + r + "\n"
    }

    fun customerText(shop: String, footer: String, sale: Sale, lines: List<SaleLine>, width: Int, copy: Boolean = false): String {
        require(width >= 24) { "Printer width too narrow" }
        val b = StringBuilder()
        b.append(wrap(shop, width))
        b.append(if (copy) "SALES RECEIPT - REPRINT\n" else "SALES RECEIPT\n")
        b.append("-".repeat(width)).append('\n')
        b.append(wrap(sale.receiptNo, width))
        b.append(wrap(dateTime(sale.createdAt), width))
        b.append(wrap("${sale.service} / ${sale.payment}", width))
        b.append("-".repeat(width)).append('\n')
        lines.forEach { item ->
            b.append(aligned("${item.quantity}x ${item.productName}", peso(item.lineCents.toLong()), width))
            if (item.options.isNotBlank()) b.append(wrap(item.options, width, "  "))
        }
        b.append("-".repeat(width)).append('\n')
        b.append(aligned("Subtotal", peso(sale.subtotal.toLong()), width))
        b.append(aligned("Discount", "-" + peso(sale.discount.toLong()), width))
        b.append(aligned("TOTAL", peso(sale.total.toLong()), width))
        b.append(aligned("Tendered", peso(sale.tendered.toLong()), width))
        b.append(aligned("Change", peso(sale.change.toLong()), width))
        if (sale.notes.isNotBlank()) b.append(wrap("Note: ${sale.notes}", width))
        b.append("-".repeat(width)).append('\n')
        b.append(wrap(footer.ifBlank { "Thank you for your purchase!" }, width))
        b.append("NOT AN OFFICIAL BIR INVOICE\n")
        return b.toString()
    }

    fun baristaText(shop: String, sale: Sale, lines: List<SaleLine>, width: Int, copy: Boolean = false): String {
        require(width >= 24) { "Printer width too narrow" }
        val b = StringBuilder()
        b.append("=".repeat(width)).append('\n')
        b.append("*** BARISTA ORDER SLIP ***\n")
        if (copy) b.append("*** REPRINT - CHECK ORDER ***\n")
        b.append(wrap(shop, width))
        b.append("=".repeat(width)).append('\n')
        b.append("ORDER: ").append(ascii(sale.receiptNo)).append('\n')
        b.append("SERVICE: ").append(ascii(sale.service).uppercase()).append('\n')
        b.append(wrap(dateTime(sale.createdAt), width))
        b.append("-".repeat(width)).append('\n')
        lines.forEachIndexed { index, item ->
            b.append(wrap("${index + 1}. ${item.quantity}x ${item.productName}", width))
            if (item.options.isNotBlank()) b.append(wrap("Size / Add-ons: ${item.options}", width, "  "))
            b.append('\n')
        }
        if (sale.notes.isNotBlank()) {
            b.append("-".repeat(width)).append('\n')
            b.append(wrap("SPECIAL INSTRUCTIONS / TABLE: ${sale.notes}", width))
        }
        b.append("-".repeat(width)).append('\n')
        b.append("PREPARE AND CHECK ITEMS\n")
        return b.toString()
    }

    private fun asDocument(text: String, width: Int, cut: Boolean): ByteArray {
        val separation = if (cut) "\n\n" else "\n" + "-".repeat(width) + "\nTEAR HERE / NEXT SLIP\n\n\n\n"
        return reset + (text + separation).toByteArray(StandardCharsets.US_ASCII) +
            (if (cut) cutPaper else byteArrayOf())
    }

    fun customer(shop: String, footer: String, sale: Sale, lines: List<SaleLine>, width: Int, cut: Boolean, copy: Boolean = false): ByteArray =
        asDocument(customerText(shop, footer, sale, lines, width, copy), width, cut)

    fun barista(shop: String, sale: Sale, lines: List<SaleLine>, width: Int, cut: Boolean, copy: Boolean = false): ByteArray =
        asDocument(baristaText(shop, sale, lines, width, copy), width, cut)

    fun both(shop: String, footer: String, sale: Sale, lines: List<SaleLine>, width: Int, cut: Boolean, copy: Boolean = false): ByteArray =
        customer(shop, footer, sale, lines, width, cut, copy) + barista(shop, sale, lines, width, cut, copy)
}
