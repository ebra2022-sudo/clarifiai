package com.clarifiai.app.pdf

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.clarifiai.app.data.Kpis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the markdown audit to an A4 PDF with Android's PdfDocument and shares it via FileProvider. */
object PdfExportUtility {

    data class ReportMeta(
        val projectId: String,
        val timeframeLabel: String,
        val kpis: Kpis?,
        val notes: List<String>,
        val whiteLabel: Boolean,
    )

    private const val PAGE_W = 595   // A4 @ 72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val BANNER_H = 118f
    private const val SLIM_H = 38f
    private const val FOOTER_H = 44f

    private val NAVY = Color.parseColor("#0F172A")
    private val ACCENT = Color.parseColor("#38BDF8")
    private val INK = Color.parseColor("#1E293B")
    private val MUTED = Color.parseColor("#64748B")
    private val PANEL = Color.parseColor("#F1F5F9")
    private val RED = Color.parseColor("#EF4444")
    private val AMBER = Color.parseColor("#F59E0B")
    private val BLUE = Color.parseColor("#0EA5E9")

    suspend fun generate(context: Context, markdown: String, meta: ReportMeta): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        // housekeeping: drop reports older than 7 days
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 24 * 3600 * 1000L }?.forEach { it.delete() }

        val blocks = parse(markdown)
        val stamp = SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()).format(Date())

        // Pass 1 (dry run) to learn the page count for "Page X of Y".
        val total = Renderer(null, meta, stamp, 0).render(blocks)

        val file = File(dir, "clarity-report-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.pdf")
        val doc = PdfDocument()
        try {
            Renderer(doc, meta, stamp, total).render(blocks)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Clarity AI Executive Report")
            putExtra(Intent.EXTRA_TEXT, "Executive UX audit report attached.")
            clipData = ClipData.newRawUri("Clarity AI report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share report").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    // ---------------------------------------------------------------- markdown parsing

    private sealed interface Block {
        data class Heading(val text: String) : Block
        data class Bullet(val text: String, val indent: Int, val marker: String?) : Block
        data class Para(val text: String) : Block
        data object Rule : Block
    }

    private val emoji = Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]")
    private val numbered = Regex("^(\\d+[.)])\\s+(.*)$")

    private fun parse(md: String): List<Block> = md.lines().mapNotNull { raw ->
        val indent = raw.takeWhile { it == ' ' }.length / 2
        val t = raw.trim()
        when {
            t.isEmpty() -> null
            t.matches(Regex("^[-*_]{3,}$")) -> Block.Rule
            t.startsWith("#") -> Block.Heading(t.trimStart('#').trim().replace(emoji, "").trim())
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> Block.Bullet(t.drop(2).replace(emoji, "").trim(), indent, null)
            numbered.matches(t) -> numbered.matchEntire(t)!!.let { Block.Bullet(it.groupValues[2].replace(emoji, ""), indent, it.groupValues[1]) }
            else -> Block.Para(t.replace(emoji, "").trim())
        }
    }

    private fun spans(text: String): List<Pair<String, Boolean>> {
        val cleaned = text
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace("`", "")
            .replace(Regex("(?<!\\*)\\*(?!\\*)([^*]+)\\*(?!\\*)"), "$1")
        return cleaned.split("**").mapIndexed { i, s -> s to (i % 2 == 1) }
    }

    // ---------------------------------------------------------------- renderer

    private class Renderer(
        private val doc: PdfDocument?,
        private val meta: ReportMeta,
        private val stamp: String,
        private val totalPages: Int,
    ) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var pageNo = 0
        private var y = 0f
        private val contentRight = PAGE_W - MARGIN
        private val bottom = PAGE_H - FOOTER_H - 8f
        private val paints = HashMap<Triple<Float, Int, Boolean>, Paint>()

        private fun paint(size: Float, color: Int, bold: Boolean) = paints.getOrPut(Triple(size, color, bold)) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

        fun render(blocks: List<Block>): Int {
            startPage()
            for (b in blocks) {
                when (b) {
                    is Block.Heading -> heading(b.text)
                    is Block.Bullet -> bullet(b)
                    is Block.Para -> { paragraph(b.text, MARGIN, contentRight - MARGIN, 10.5f, INK, false); y += 4f }
                    Block.Rule -> { ensure(14f); canvas?.drawRect(MARGIN, y + 6, contentRight, y + 7, fill(PANEL)); y += 14f }
                }
            }
            if (meta.notes.isNotEmpty()) {
                ensure(40f); y += 10f
                paragraph("Data notes: " + meta.notes.joinToString(" "), MARGIN, contentRight - MARGIN, 8.5f, MUTED, false)
            }
            finishPage()
            return pageNo
        }

        private fun startPage() {
            finishPage()
            pageNo++
            if (doc != null) {
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                canvas = page!!.canvas
            }
            y = if (pageNo == 1) banner() else slimHeader()
        }

        private fun finishPage() {
            val p = page ?: return
            footer()
            doc!!.finishPage(p)
            page = null
        }

        private fun ensure(h: Float) { if (y + h > bottom) startPage() }

        private fun banner(): Float {
            val c = canvas
            c?.drawRect(0f, 0f, PAGE_W.toFloat(), BANNER_H, fill(NAVY))
            c?.drawRect(0f, BANNER_H - 4f, PAGE_W.toFloat(), BANNER_H, fill(ACCENT))
            val title = paint(21f, Color.WHITE, true).apply { letterSpacing = 0.06f }
            c?.drawText("CLARITY AI EXECUTIVE REPORT", MARGIN, 52f, title)
            val sub = paint(10f, Color.parseColor("#CBD5E1"), false)
            val project = if (meta.projectId.isBlank()) "-" else meta.projectId
            c?.drawText("Project: $project   |   Period: ${meta.timeframeLabel}", MARGIN, 76f, sub)
            c?.drawText("Generated: $stamp", MARGIN, 92f, sub)
            var top = BANNER_H + 20f
            meta.kpis?.let { top = kpiStrip(it, top) }
            return top
        }

        private fun kpiStrip(k: Kpis, top: Float): Float {
            val items = listOf(
                "SESSION HEALTH" to "${k.healthScore}/100",
                "RAGE CLICKS" to "%,d".format(k.rageClickCount),
                "DEAD-CLICK SESSIONS" to "${k.deadClickSessionPct}%",
                "SESSIONS ANALYSED" to "%,d".format(k.totalSessions),
            )
            val gap = 10f
            val w = (PAGE_W - 2 * MARGIN - gap * (items.size - 1)) / items.size
            items.forEachIndexed { i, (label, value) ->
                val x = MARGIN + i * (w + gap)
                canvas?.drawRoundRect(RectF(x, top, x + w, top + 56f), 6f, 6f, fill(PANEL))
                canvas?.drawRect(x, top + 10f, x + 3f, top + 46f, fill(ACCENT))
                canvas?.drawText(value, x + 12f, top + 28f, paint(17f, NAVY, true))
                canvas?.drawText(label, x + 12f, top + 44f, paint(6.8f, MUTED, true))
            }
            return top + 56f + 20f
        }

        private fun slimHeader(): Float {
            canvas?.drawRect(0f, 0f, PAGE_W.toFloat(), SLIM_H, fill(NAVY))
            canvas?.drawRect(0f, SLIM_H - 2f, PAGE_W.toFloat(), SLIM_H, fill(ACCENT))
            canvas?.drawText("CLARITY AI EXECUTIVE REPORT", MARGIN, 24f, paint(9.5f, Color.WHITE, true))
            return SLIM_H + 22f
        }

        private fun footer() {
            val c = canvas ?: return
            c.drawRect(MARGIN, PAGE_H - FOOTER_H, contentRight, PAGE_H - FOOTER_H + 0.8f, fill(PANEL))
            val left = buildString {
                append("Confidential | $stamp")
                if (!meta.whiteLabel) append(" | Powered by Clarity AI")
            }
            c.drawText(left, MARGIN, PAGE_H - FOOTER_H + 18f, paint(8f, MUTED, false))
            val right = "Page $pageNo of $totalPages"
            val p = paint(8f, MUTED, true)
            c.drawText(right, contentRight - p.measureText(right), PAGE_H - FOOTER_H + 18f, p)
        }

        private fun heading(text: String) {
            ensure(64f)
            y += 14f
            val l = text.lowercase()
            val color = when {
                "hotfix" in l -> RED
                "friction" in l || "trend" in l || "drop" in l -> AMBER
                "roadmap" in l || "strategic" in l -> BLUE
                else -> NAVY
            }
            canvas?.drawRoundRect(RectF(MARGIN, y, MARGIN + 5f, y + 20f), 2f, 2f, fill(color))
            paragraph(text, MARGIN + 14f, contentRight - MARGIN - 14f, 13.5f, NAVY, true, lineFactor = 1.35f)
            canvas?.drawRect(MARGIN, y + 1f, contentRight, y + 1.8f, fill(PANEL))
            y += 10f
        }

        private fun bullet(b: Block.Bullet) {
            val x0 = MARGIN + 6f + b.indent * 14f
            ensure(16f)
            if (b.marker == null) {
                canvas?.drawCircle(x0 + 2.5f, y + 7.5f, 2.2f, fill(ACCENT))
            } else {
                canvas?.drawText(b.marker, x0, y + 10.5f, paint(10.5f, MUTED, true))
            }
            val indentW = if (b.marker == null) 14f else 20f
            paragraph(b.text, x0 + indentW, contentRight - (x0 + indentW), 10.5f, INK, false)
            y += 3f
        }

        /** Word-wrapped text with **bold** spans; flows across pages. */
        private fun paragraph(raw: String, x0: Float, maxW: Float, size: Float, color: Int, baseBold: Boolean, lineFactor: Float = 1.5f) {
            val regular = paint(size, color, false)
            val bold = paint(size, color, true)
            val lineH = size * lineFactor
            val space = regular.measureText(" ")
            val words = spans(raw).flatMap { (t, b) ->
                t.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it to (b || baseBold) }
            }
            ensure(lineH)
            var x = x0
            for ((w, isBold) in words) {
                val p = if (isBold) bold else regular
                val ww = p.measureText(w)
                if (x > x0 && x + ww > x0 + maxW) {
                    y += lineH; ensure(lineH); x = x0
                }
                canvas?.drawText(w, x, y + size, p)
                x += ww + space
            }
            y += lineH
        }
    }
}
