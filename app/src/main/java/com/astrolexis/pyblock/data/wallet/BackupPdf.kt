package com.astrolexis.pyblock.data.wallet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.astrolexis.pyblock.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/**
 * Generates a printable PyBLØCK paper backup PDF of every saved vanity wallet:
 * per wallet — label, public address (+QR) and private key/WIF (+QR) — plus the
 * PayNym. Light background + black data (legible on any printer). Mirrors the iOS
 * `BackupPDF`. This is a COLD backup: it contains private keys.
 */
object BackupPdf {
    data class Entry(val label: String, val address: String, val wif: String, val balanceSats: Long)

    /** Wipe any exported backup PDFs (they contain plaintext WIFs) from the cache.
     *  Called on app launch so a shared backup never lingers on disk past the
     *  session that created it. Best-effort. */
    fun cleanup(ctx: Context) {
        runCatching { File(ctx.cacheDir, "backups").deleteRecursively() }
    }

    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 42f

    private val GREEN = Color.rgb(0, 184, 71)
    private val MAGENTA = Color.rgb(217, 0, 140)
    private val CYAN = Color.rgb(0, 158, 204)
    private val INK = Color.BLACK
    private val DIM = Color.rgb(115, 115, 115)
    private val RED = Color.rgb(200, 40, 40)

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint().apply {
        this.color = color; textSize = size; isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun stroke(color: Int, w: Float) = Paint().apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w; isAntiAlias = true
    }

    fun generate(ctx: Context, entries: List<Entry>, paynym: String?, cosmic: String?, dateText: String): File? = try {
        val doc = PdfDocument()
        val contentW = PAGE_W - MARGIN * 2
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var c = page.canvas
        var y = MARGIN

        // drawText baselines at y; emulate iOS top-left by offsetting by textSize.
        fun str(s: String, x: Float, top: Float, p: Paint) = c.drawText(s, x, top + p.textSize, p)
        fun rule(yy: Float, color: Int, w: Float = 1.5f) = c.drawLine(MARGIN, yy, PAGE_W - MARGIN, yy, stroke(color, w))
        fun box(x: Float, yy: Float, w: Float, h: Float, color: Int, sw: Float = 1f) = c.drawRect(x, yy, x + w, yy + h, stroke(color, sw))
        fun footer() {
            c.drawText(ctx.getString(R.string.backup_footer_tagline), MARGIN, PAGE_H - 30f, paint(7f, DIM))
            c.drawText(ctx.getString(R.string.backup_footer_page, pageNum), PAGE_W - MARGIN - 24f, PAGE_H - 30f, paint(7f, DIM))
        }
        fun wrap(s: String, x: Float, startY: Float, width: Float, size: Float): Float {
            val p = paint(size, INK)
            val per = maxOf(1, (width / (size * 0.62f)).toInt())
            var i = 0; var yy = startY
            while (i < s.length) {
                val end = minOf(i + per, s.length)
                c.drawText(s.substring(i, end), x, yy + size, p)
                yy += size + 2.5f; i = end
            }
            return yy
        }

        // header
        str("PyBLØCK", MARGIN, y, paint(34f, MAGENTA, true))
        str(ctx.getString(R.string.backup_header_subtitle), MARGIN + 2, y + 40, paint(10f, INK, true))
        str(dateText, PAGE_W - MARGIN - 150, y + 6, paint(8f, DIM))
        y += 56; rule(y, GREEN, 2f); y += 14

        // warning banner
        val warnH = 30f
        box(MARGIN, y, contentW, warnH, RED, 1f)
        str(ctx.getString(R.string.backup_warning_banner),
            MARGIN + 8, y + 8, paint(8f, RED, true))
        y += warnH + 16

        // PayNym block
        if (paynym != null) {
            val h = 108f
            box(MARGIN, y, contentW, h, GREEN, 1f)
            qrBitmap(paynym, 84)?.let { c.drawBitmap(it, MARGIN + 10, y + 12, null) }
            val tx = MARGIN + 108
            str(ctx.getString(R.string.backup_your_paynym), tx, y + 12, paint(11f, GREEN, true))
            str(cosmic ?: "", tx, y + 28, paint(18f, INK, true))
            wrap(paynym, tx, y + 56, PAGE_W - MARGIN - tx, 8f)
            y += h + 16
        }

        str(ctx.getString(R.string.backup_funded_addresses, entries.size), MARGIN, y, paint(10f, CYAN, true)); y += 18
        if (entries.isEmpty())
            str(ctx.getString(R.string.backup_no_funded), MARGIN, y, paint(8f, DIM)).also { y += 14 }

        for ((i, e) in entries.withIndex()) {
            val cardH = 150f
            if (y + cardH > PAGE_H - 44) {
                footer(); doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                c = page.canvas; y = MARGIN
            }
            box(MARGIN, y, contentW, cardH, Color.rgb(190, 190, 190), 1f)
            str("${i + 1}.  ${e.label}", MARGIN + 10, y + 8, paint(18f, MAGENTA, true))
            str(ctx.getString(R.string.backup_balance_sats, "%,d".format(e.balanceSats)), PAGE_W - MARGIN - 120, y + 12, paint(10f, GREEN, true))
            rule(y + 34, Color.rgb(217, 217, 217), 0.75f)
            val colTop = y + 44
            val half = contentW / 2
            // PUBLIC (left)
            str(ctx.getString(R.string.backup_public_address), MARGIN + 10, colTop, paint(8f, DIM, true))
            qrBitmap(e.address, 76)?.let { c.drawBitmap(it, MARGIN + 10, colTop + 14, null) }
            wrap(e.address, MARGIN + 94, colTop + 14, half - 104, 7.5f)
            // PRIVATE (right)
            val rx = MARGIN + half + 6
            str(ctx.getString(R.string.backup_private_key_wif), rx, colTop, paint(8f, RED, true))
            if (e.wif.isEmpty()) {
                str(ctx.getString(R.string.backup_key_not_readable), rx, colTop + 16, paint(8f, RED))
            } else {
                qrBitmap(e.wif, 76)?.let { c.drawBitmap(it, rx, colTop + 14, null) }
                wrap(e.wif, rx + 84, colTop + 14, PAGE_W - MARGIN - (rx + 84), 7.5f)
            }
            y += cardH + 12
        }
        footer(); doc.finishPage(page)

        val dir = File(ctx.cacheDir, "backups").apply { mkdirs() }
        val file = File(dir, "PyBLOCK-backup.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        file
    } catch (e: Exception) {
        null
    }

    /** Black-on-white QR (print-friendly), rendered at [side] px. */
    private fun qrBitmap(text: String, side: Int): Bitmap? = try {
        val px = side * 4
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        for (yy in 0 until px) for (xx in 0 until px) bmp.setPixel(xx, yy, if (m.get(xx, yy)) Color.BLACK else Color.WHITE)
        Bitmap.createScaledBitmap(bmp, side, side, false)
    } catch (e: Exception) {
        null
    }
}
