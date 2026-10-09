package com.brewpos.cafe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exactly the same generated PDF bytes are used for Android printing and Save as PDF. */
object Receipts {
    fun pdf(shopName: String, footer: String, sale: Sale, items: List<SaleLine>): ByteArray {
        val width = 384
        val height = 540 + items.size * 75 + items.sumOf { if (it.options.isNotBlank()) 30 else 0 } +
            (sale.notes.length / 30) * 20 + (footer.length / 30) * 20
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 17f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            var y = 48f
            fun text(value: String, x: Float = 22f, bold: Boolean = false, size: Float = 16f) {
                paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                paint.textSize = size
                canvas.drawText(value, x, y, paint)
                y += size + 9f
            }
            fun right(value: String, right: Float = 362f, bold: Boolean = false, size: Float = 16f) {
                paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                paint.textSize = size
                canvas.drawText(value, right - paint.measureText(value), y - (size + 9f), paint)
            }
            fun divider() {
                canvas.drawLine(22f, y, 362f, y, paint.apply { strokeWidth = 1f; color = Color.LTGRAY })
                paint.color = Color.BLACK
                y += 22f
            }
            fun wrapped(value: String, maxChars: Int = 34, indent: Float = 22f, size: Float = 14f) {
                val words = value.split(" ")
                var line = ""
                for (word in words) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (candidate.length > maxChars && line.isNotEmpty()) { text(line, indent, false, size); line = word }
                    else line = candidate
                }
                if (line.isNotBlank()) text(line, indent, false, size)
            }
            text(shopName.take(34), 22f, true, 25f)
            text("SALES RECEIPT • ${sale.service.uppercase()}", size = 13f)
            divider()
            text(sale.receiptNo, bold = true)
            text(dateTime(sale.createdAt), size = 13f)
            text("Payment: ${sale.payment}", size = 14f)
            divider()
            items.forEach { line ->
                text("${line.quantity}× ${line.productName.take(28)}", bold = true, size = 16f)
                right(peso(line.lineCents.toLong()), size = 16f)
                y += 3f
                if (line.options.isNotBlank()) wrapped(line.options, 39, 38f, 13f)
            }
            divider()
            text("Subtotal")
            right(peso(sale.subtotal.toLong()))
            text("Discount")
            right("- " + peso(sale.discount.toLong()))
            divider()
            text("TOTAL", bold = true, size = 21f)
            right(peso(sale.total.toLong()), bold = true, size = 21f)
            y += 9f
            text("Tendered: ${peso(sale.tendered.toLong())}", size = 14f)
            text("Change: ${peso(sale.change.toLong())}", size = 14f)
            if (sale.notes.isNotBlank()) { y += 7f; wrapped("Note: ${sale.notes}") }
            divider()
            wrapped(footer.ifBlank { "Thank you for your purchase!" }, 40)
            text("BrewPOS • Transaction copy", size = 11f)
            text("NOT AN OFFICIAL BIR INVOICE", size = 11f)
            document.finishPage(page)
            return ByteArrayOutputStream().use { stream -> document.writeTo(stream); stream.toByteArray() }
        } finally { document.close() }
    }

    fun openPrintDialog(context: Context, bytes: ByteArray, receiptNo: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print("BrewPOS-$receiptNo", object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                                  cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: android.os.Bundle?) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(PrintDocumentInfo.Builder("$receiptNo.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>?, destination: ParcelFileDescriptor?,
                                 cancellationSignal: CancellationSignal?, callback: WriteResultCallback) {
                if (cancellationSignal?.isCanceled == true) { callback.onWriteCancelled(); return }
                try {
                    requireNotNull(destination)
                    FileOutputStream(destination.fileDescriptor).use { it.write(bytes) }
                    callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) { callback.onWriteFailed(e.message ?: "Print failed") }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build())
    }

    fun escPos(shop: String, footer: String, sale: Sale, items: List<SaleLine>, width: Int, cut: Boolean): ByteArray =
        TicketFormatter.customer(shop, footer, sale, items, width, cut)

}

object ThermalPrinter {
    // All print actions use the same printer; protect the connection against overlapping jobs.
    private val printLock = Mutex()
    fun permissionGranted(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<BluetoothDevice> {
        if (!permissionGranted(context)) return emptyList()
        return try { BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.sortedBy { it.name ?: "" } ?: emptyList() }
        catch (_: Exception) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    suspend fun print(context: Context, mac: String, bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        printLock.withLock { runCatching {
            require(permissionGranted(context)) { "Allow Nearby devices permission in Settings" }
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth unavailable")
            require(adapter.isEnabled) { "Enable Bluetooth first" }
            // Only devices already paired in Android Bluetooth Settings are accepted.
            val device = adapter.bondedDevices.firstOrNull { it.address == mac }
                ?: error("Printer is not paired or selected")
            val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            socket.use { it.connect(); it.outputStream.write(bytes); it.outputStream.flush() }
        } }
    }
}
