package com.clarifiai.app.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextUtils
import android.text.TextPaint
import com.clarifiai.app.data.Kpis
import com.clarifiai.app.data.reviewedRecordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the markdown audit to a designed A4 PDF with Android's PdfDocument. */
object PdfExportUtility {

    data class ReportMeta(
        val projectName: String,
        val timeframeLabel: String,
        val kpis: Kpis?,
        val notes: List<String>,
        val whiteLabel: Boolean,
        val generatedAt: Date = Date(),
    )

    data class Rendered(val file: File, val pages: Int)

    /** Shown at the end of every report: Clarity's export API has no recordings or heatmaps. */
    const val DATA_SCOPE_NOTE = "Based on aggregated Microsoft Clarity metrics. Session recordings and heatmaps are " +
        "not available through Clarity's export API and were not analysed; review the flagged pages in Clarity's " +
        "recordings before acting."

    suspend fun generate(context: Context, markdown: String, meta: ReportMeta): Rendered = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val blocks = parse(markdown)
        // Pass 1 lays out without drawing to learn the page count for "Page X of Y".
        val total = Renderer(null, meta, 0).render(blocks)
        val file = File(dir, "render-${System.currentTimeMillis()}.pdf")
        val doc = PdfDocument()
        try {
            Renderer(doc, meta, total).render(blocks)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        Rendered(file, total)
    }

    // ---------------------------------------------------------------- markdown → blocks

    internal sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String, val indent: Int, val marker: String?) : Block
        data class Para(val text: String) : Block
        data object Rule : Block
    }

    private val emoji = Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]")
    private val numbered = Regex("^(\\d+)[.)]\\s+(.*)$")
    private val tableDivider = Regex("^\\|?\\s*:?-{3,}.*$")

    private fun clean(s: String) = s.replace(emoji, "").replace(Regex("\\s{2,}"), " ").trim()

    internal fun parse(md: String): List<Block> = md.lines().mapNotNull { raw ->
        val indent = raw.takeWhile { it == ' ' }.length / 2
        val t = raw.trim()
        when {
            t.isEmpty() || tableDivider.matches(t) -> null
            t.matches(Regex("^[-*_]{3,}$")) -> Block.Rule
            t.startsWith("#") -> Block.Heading(t.takeWhile { it == '#' }.length, clean(t.trimStart('#')))
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> Block.Bullet(clean(t.drop(2)), indent, null)
            numbered.matches(t) -> numbered.matchEntire(t)!!.let { Block.Bullet(clean(it.groupValues[2]), indent, it.groupValues[1]) }
            t.startsWith(">") -> Block.Para(clean(t.trimStart('>')))
            t.startsWith("|") -> Block.Para(clean(t.trim('|').split('|').joinToString("  ·  ") { it.trim() }))
            else -> Block.Para(clean(t))
        }
    }

    // ---------------------------------------------------------------- inline text

    private enum class Kind { NORMAL, BOLD, CODE }

    /** A run of text with one style; `spaceBefore` is false when it is glued to the previous run ("**Cart**:"). */
    private data class Token(val text: String, val kind: Kind, val spaceBefore: Boolean)

    private val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    private val code = Regex("`([^`]+)`")
    private val pieces = Regex("\\s+|\\S+")

    private fun tokens(raw: String, baseBold: Boolean): List<Token> {
        val text = raw
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?<![*\\w])[*_]([^*_]+)[*_](?![*\\w])"), "$1")
        val out = ArrayList<Token>()
        var pendingSpace = false
        fun add(s: String, kind: Kind) {
            for (m in pieces.findAll(s)) {
                if (m.value.isBlank()) pendingSpace = true
                else {
                    out += Token(m.value, if (kind == Kind.NORMAL && baseBold) Kind.BOLD else kind, pendingSpace && out.isNotEmpty())
                    pendingSpace = false
                }
            }
        }
        var last = 0
        for (m in inline.findAll(text)) {
            add(text.substring(last, m.range.first), Kind.NORMAL)
            if (m.groupValues[1].isEmpty()) add(m.groupValues[2], Kind.CODE)
            else {
                // Code inside bold ("**Checkout `/pay`**") keeps its code styling.
                val bold = m.groupValues[1]
                var i = 0
                for (c in code.findAll(bold)) {
                    add(bold.substring(i, c.range.first), Kind.BOLD)
                    add(c.groupValues[1], Kind.CODE)
                    i = c.range.last + 1
                }
                add(bold.substring(i), Kind.BOLD)
            }
            last = m.range.last + 1
        }
        add(text.substring(last), Kind.NORMAL)
        return out
    }

    // ---------------------------------------------------------------- design tokens

    private const val PAGE_W = 595f  // A4 at 72 dpi
    private const val PAGE_H = 842f
    private const val M = 50f        // side margin
    private const val CONTENT_W = PAGE_W - 2 * M
    private const val COVER_H = 172f
    private const val CONT_TOP = 66f
    private const val FOOTER_Y = PAGE_H - 40f

    private val NAVY = Color.parseColor("#0B1220")
    private val INK = Color.parseColor("#0F172A")
    private val BODY = Color.parseColor("#334155")
    private val MUTED = Color.parseColor("#64748B")
    private val FAINT = Color.parseColor("#94A3B8")
    private val RULE = Color.parseColor("#E2E8F0")
    private val PANEL = Color.parseColor("#F8FAFC")
    private val CODE_BG = Color.parseColor("#F1F5F9")
    private val SKY = Color.parseColor("#38BDF8")
    private val BLUE = Color.parseColor("#0369A1")
    private val RED = Color.parseColor("#DC2626")
    private val RED_TINT = Color.parseColor("#FEF2F2")
    private val AMBER = Color.parseColor("#D97706")
    private val GREEN = Color.parseColor("#059669")
    private val ON_NAVY = Color.parseColor("#CBD5E1")
    private val ON_NAVY_MUTED = Color.parseColor("#7C8BA1")

    private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val MONO: Typeface = Typeface.MONOSPACE

    private data class Style(val size: Float, val color: Int, val face: Typeface, val tracking: Float = 0f)

    private enum class Section(val color: Int, val tint: Int?) {
        HOTFIX(RED, RED_TINT), FRICTION(AMBER, null), ROADMAP(GREEN, null), OTHER(BLUE, null);

        companion object {
            fun of(title: String): Section = title.lowercase().let { l ->
                when {
                    "hotfix" in l || "immediate" in l -> HOTFIX
                    "friction" in l || "drop" in l || "trend" in l -> FRICTION
                    "roadmap" in l || "strategic" in l || "sprint" in l -> ROADMAP
                    else -> OTHER
                }
            }
        }
    }

    // ---------------------------------------------------------------- renderer

    private class Renderer(private val doc: PdfDocument?, private val meta: ReportMeta, private val totalPages: Int) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var pageNo = 0
        private var y = 0f
        private var atPageTop = true
        private val bottom = FOOTER_Y - 18f
        private val paints = HashMap<Style, TextPaint>()
        private var section = Section.OTHER
        private var sectionNo = 0
        private var cardNo = 0
        private val dateLabel = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(meta.generatedAt)
        private val project = meta.projectName.ifBlank { "Untitled project" }

        private fun paint(s: Style) = paints.getOrPut(s) {
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = s.size; color = s.color; typeface = s.face; letterSpacing = s.tracking
            }
        }

        private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND
        }

        private fun text(s: String, x: Float, baseline: Float, st: Style) = canvas?.drawText(s, x, baseline, paint(st))
        private fun textRight(s: String, right: Float, baseline: Float, st: Style) =
            text(s, right - paint(st).measureText(s), baseline, st)
        private fun textCenter(s: String, cx: Float, baseline: Float, st: Style) =
            text(s, cx - paint(st).measureText(s) / 2, baseline, st)
        private fun ellipsize(s: String, st: Style, w: Float) =
            TextUtils.ellipsize(s, paint(st), w, TextUtils.TruncateAt.END).toString()

        fun render(blocks: List<Block>): Int {
            startPage()
            meta.kpis?.let { atAGlance(it) }
            blocks.forEachIndexed { i, b ->
                when (b) {
                    is Block.Heading -> if (b.level <= 3) sectionHeading(b.text) else subHeading(b.text)
                    is Block.Bullet -> if (section == Section.HOTFIX && b.indent == 0) card(b) else bullet(b, blocks.getOrNull(i + 1))
                    is Block.Para -> { space(2f); paragraph(b.text, M, CONTENT_W, 10f, BODY); space(6f) }
                    Block.Rule -> { ensure(16f); canvas?.drawRect(M, y + 7f, M + CONTENT_W, y + 7.6f, fill(RULE)); y += 16f }
                }
            }
            aboutThisData()
            finishPage()
            return pageNo
        }

        // ------------------------------------------------------------ pages

        private fun startPage() {
            finishPage()
            pageNo++
            if (doc != null) {
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create())
                canvas = page!!.canvas
            }
            y = if (pageNo == 1) cover() else continuationHeader()
            atPageTop = true
        }

        private fun finishPage() {
            val p = page ?: return
            footer()
            doc!!.finishPage(p)
            page = null
            canvas = null
        }

        private fun ensure(h: Float) { if (y + h > bottom) startPage() }

        /** Vertical space that is dropped at the top of a page. */
        private fun space(h: Float) { if (!atPageTop) y += h }

        private fun cover(): Float {
            val c = canvas
            c?.drawRect(0f, 0f, PAGE_W, COVER_H, fill(NAVY))
            c?.drawRect(0f, COVER_H, PAGE_W, COVER_H + 3f, fill(SKY))

            val brand = Style(8f, SKY, MEDIUM, 0.22f)
            text(if (meta.whiteLabel) "UX AUDIT" else "CLARITY AI  ·  UX AUDIT", M, 44f, brand)
            textRight(dateLabel, PAGE_W - M, 44f, Style(8.5f, ON_NAVY_MUTED, REGULAR))

            text("UX Audit Report", M, 86f, Style(28f, Color.WHITE, LIGHT))
            val projectStyle = Style(14f, ON_NAVY, MEDIUM)
            text(ellipsize(project, projectStyle, CONTENT_W), M, 110f, projectStyle)

            val label = Style(6.8f, ON_NAVY_MUTED, MEDIUM, 0.18f)
            val value = Style(10.5f, Color.WHITE, MEDIUM)
            val cols = listOfNotNull(
                "PERIOD" to meta.timeframeLabel.ifBlank { "-" },
                meta.kpis?.let { "SESSIONS ANALYSED" to "%,d".format(it.totalSessions) },
                "GENERATED" to dateLabel,
            )
            cols.forEachIndexed { i, (l, v) ->
                val x = M + i * 150f
                text(l, x, 140f, label)
                text(ellipsize(v, value, 140f), x, 156f, value)
            }
            return COVER_H + 34f
        }

        private fun continuationHeader(): Float {
            val st = Style(7.5f, MUTED, MEDIUM, 0.04f)
            text(ellipsize("UX Audit Report  ·  $project", st, CONTENT_W * 0.7f), M, 34f, st)
            textRight(meta.timeframeLabel, PAGE_W - M, 34f, Style(7.5f, MUTED, REGULAR))
            canvas?.drawRect(M, 42f, PAGE_W - M, 42.6f, fill(RULE))
            return CONT_TOP
        }

        private fun footer() {
            canvas?.drawRect(M, FOOTER_Y, PAGE_W - M, FOOTER_Y + 0.6f, fill(RULE))
            val left = if (meta.whiteLabel) "Confidential" else "Generated by Clarity AI  ·  Confidential"
            text(left, M, FOOTER_Y + 16f, Style(7.5f, FAINT, REGULAR))
            textRight("Page $pageNo of $totalPages", PAGE_W - M, FOOTER_Y + 16f, Style(7.5f, MUTED, MEDIUM))
        }

        // ------------------------------------------------------------ at a glance

        private fun eyebrow(s: String, color: Int = MUTED) {
            text(s, M, y + 8f, Style(7.5f, color, MEDIUM, 0.2f))
            y += 18f
        }

        private fun atAGlance(k: Kpis) {
            eyebrow("AT A GLANCE")
            atPageTop = false
            val top = y
            val ringW = 148f
            val h = 132f
            val c = canvas

            // Health score ring
            c?.drawRoundRect(RectF(M, top, M + ringW, top + h), 8f, 8f, fill(PANEL))
            val score = k.healthScore.coerceIn(0, 100)
            val (scoreColor, verdict) = when {
                score >= 80 -> GREEN to "Healthy"
                score >= 60 -> AMBER to "Needs attention"
                else -> RED to "Critical"
            }
            val cx = M + ringW / 2
            val cy = top + 52f
            val r = 32f
            val arc = RectF(cx - r, cy - r, cx + r, cy + r)
            c?.drawArc(arc, 0f, 360f, false, stroke(RULE, 6f))
            c?.drawArc(arc, -90f, 360f * score / 100f, false, stroke(scoreColor, 6f))
            textCenter("$score", cx, cy + 8f, Style(22f, INK, BOLD))
            textCenter("SESSION HEALTH", cx, top + h - 26f, Style(6.8f, MUTED, MEDIUM, 0.18f))
            textCenter(verdict, cx, top + h - 12f, Style(9.5f, scoreColor, MEDIUM))

            // Metric tiles: 3 × 2
            val gap = 8f
            val gx = M + ringW + 12f
            val tileW = (M + CONTENT_W - gx - 2 * gap) / 3
            val tileH = (h - gap) / 2
            // Same product-first metrics as the app: reach, engagement, depth, then where users struggled.
            val tiles = listOf(
                Tile("SESSIONS", "%,d".format(k.totalSessions), "%,d users".format(k.totalUsers), null),
                Tile("ENGAGED TIME", duration(k.engagedSeconds), "of ${duration(k.sessionSeconds)} / session", null),
                Tile("VIEWS / SESSION", "%.1f".format(k.viewsPerSession), "screens or pages", null),
                Tile("FRUSTRATED", pct(k.frustratedSessionPct), "rage or dead taps", sev(k.frustratedSessionPct, 3.0, 10.0)),
                Tile("QUICK BACKS", pct(k.quickbackSessionPct), "of sessions", sev(k.quickbackSessionPct, 5.0, 10.0)),
                Tile("ERRORS", pct(k.scriptErrorSessionPct), "of sessions", sev(k.scriptErrorSessionPct, 2.0, 5.0)),
            )
            tiles.forEachIndexed { i, t ->
                val x = gx + (i % 3) * (tileW + gap)
                val ty = top + (i / 3) * (tileH + gap)
                c?.drawRoundRect(RectF(x, ty, x + tileW, ty + tileH), 8f, 8f, fill(PANEL))
                text(t.label, x + 11f, ty + 17f, Style(6.5f, MUTED, MEDIUM, 0.16f))
                t.severity?.let { c?.drawCircle(x + tileW - 12f, ty + 14.5f, 3f, fill(it)) }
                val valueStyle = Style(17f, INK, BOLD)
                text(ellipsize(t.value, valueStyle, tileW - 22f), x + 11f, ty + 40f, valueStyle)
                text(ellipsize(t.sub, Style(7.5f, MUTED, REGULAR), tileW - 22f), x + 11f, ty + 54f, Style(7.5f, MUTED, REGULAR))
            }
            y = top + h + 10f
            legend()
            y += 6f
        }

        private fun legend() {
            var x = M + 148f + 12f
            val st = Style(7f, FAINT, REGULAR)
            listOf(GREEN to "Low", AMBER to "Elevated", RED to "High").forEach { (color, l) ->
                canvas?.drawCircle(x + 3f, y + 5.5f, 2.5f, fill(color))
                text(l, x + 9f, y + 8f, st)
                x += 9f + paint(st).measureText(l) + 12f
            }
            text("share of sessions affected", x, y + 8f, st)
            y += 14f
        }

        private data class Tile(val label: String, val value: String, val sub: String, val severity: Int?)

        private fun duration(seconds: Int) = if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)

        private fun pct(v: Double) = if (v == Math.floor(v)) "${v.toInt()}%" else "%.1f%%".format(v)
        private fun sev(v: Double, warn: Double, bad: Double) = when {
            v >= bad -> RED
            v >= warn -> AMBER
            else -> GREEN
        }

        // ------------------------------------------------------------ report body

        private fun sectionHeading(title: String) {
            section = Section.of(title)
            sectionNo++
            cardNo = 0
            val titleStyle = Style(15f, INK, MEDIUM)
            val lines = wrap(tokens(title, false), CONTENT_W - 30f, titleStyle.size, MEDIUM)
            ensure(lines.size * 20f + 60f) // keep the heading with the start of its content
            space(22f)
            atPageTop = false
            text("%02d".format(sectionNo), M, y + 14f, Style(10f, section.color, BOLD, 0.05f))
            lines.forEach { line -> drawLine(line, M + 30f, y + 14f, 15f, INK, base = MEDIUM); y += 20f }
            y += 4f
            canvas?.drawRect(M, y, M + 30f, y + 2f, fill(section.color))
            canvas?.drawRect(M + 30f, y + 0.7f, M + CONTENT_W, y + 1.3f, fill(RULE))
            y += 14f
        }

        private fun subHeading(title: String) {
            Section.of(title).takeIf { it != Section.OTHER }?.let { section = it; cardNo = 0 }
            ensure(44f)
            space(8f)
            atPageTop = false
            paragraph(title, M, CONTENT_W, 11.5f, INK, bold = true)
            y += 2f
        }

        /** Hotfixes are the priority list: each one is a tinted card with its rank. */
        private fun card(b: Block.Bullet) {
            cardNo++
            val pad = 11f
            val badgeW = 22f
            val size = 10f
            val lineH = size * 1.5f
            val lines = wrap(tokens(b.text, false), CONTENT_W - 2 * pad - badgeW - 4f, size)
            val h = lines.size * lineH + 2 * pad - 2f
            if (h < bottom - CONT_TOP) ensure(h + 8f)
            atPageTop = false
            val top = y
            canvas?.drawRoundRect(RectF(M, top, M + CONTENT_W, top + h), 7f, 7f, fill(section.tint ?: PANEL))
            canvas?.drawRoundRect(RectF(M, top, M + 3f, top + h), 1.5f, 1.5f, fill(section.color))
            val bx = M + pad + 9f
            canvas?.drawCircle(bx, top + pad + 7f, 9f, fill(section.color))
            textCenter("${b.marker ?: cardNo}", bx, top + pad + 10.5f, Style(8.5f, Color.WHITE, BOLD))
            y = top + pad - 1f
            lines.forEach { line ->
                if (y + lineH > bottom) { startPage(); atPageTop = false }
                drawLine(line, M + pad + badgeW + 4f, y + size, size, BODY)
                y += lineH
            }
            y = maxOf(y, top + h) + 8f
        }

        private fun bullet(b: Block.Bullet, next: Block?) {
            val x0 = M + 2f + b.indent * 16f
            val textX = x0 + if (b.marker == null) 14f else 20f
            val size = if (b.indent > 0) 9.5f else 10f
            val lines = wrap(tokens(b.text, false), M + CONTENT_W - textX, size)
            ensure(size * 1.5f)
            atPageTop = false
            if (b.marker == null) {
                if (b.indent == 0) canvas?.drawCircle(x0 + 3f, y + size * 0.62f, 2.4f, fill(section.color))
                else canvas?.drawCircle(x0 + 3f, y + size * 0.62f, 2f, stroke(FAINT, 0.9f))
            } else {
                text("${b.marker}.", x0, y + size, Style(size, section.color, BOLD))
            }
            lines.forEach { line ->
                ensure(size * 1.5f)
                drawLine(line, textX, y + size, size, BODY)
                y += size * 1.5f
            }
            y += when {
                next is Block.Bullet && next.indent > b.indent -> 2f
                b.indent > 0 && next is Block.Bullet && next.indent == b.indent -> 3f
                else -> 7f
            }
        }

        private fun aboutThisData() {
            val recordings = reviewedRecordings(meta.notes)
            val scope = if (recordings > 0) {
                "Combines aggregated Microsoft Clarity metrics with the timelines of $recordings real session recordings " +
                    "pulled from Clarity. Heatmaps are not available through Clarity's API."
            } else DATA_SCOPE_NOTE
            val notes = meta.notes + scope
            val size = 8.5f
            val lineH = size * 1.5f
            val pad = 14f
            val wrapped = notes.map { wrap(tokens(it, false), CONTENT_W - 2 * pad - 12f, size) }
            val h = 2 * pad + 16f + wrapped.sumOf { it.size } * lineH + (wrapped.size - 1) * 4f
            space(18f)
            if (h < bottom - CONT_TOP) ensure(h)
            val top = y
            canvas?.drawRoundRect(RectF(M, top, M + CONTENT_W, top + h), 8f, 8f, fill(PANEL))
            canvas?.drawRoundRect(RectF(M, top, M + CONTENT_W, top + h), 8f, 8f, stroke(RULE, 0.6f))
            text("ABOUT THIS DATA", M + pad, top + pad + 7f, Style(7f, MUTED, MEDIUM, 0.2f))
            y = top + pad + 16f
            wrapped.forEach { lines ->
                canvas?.drawCircle(M + pad + 2f, y + size * 0.62f, 1.8f, fill(FAINT))
                lines.forEach { line -> drawLine(line, M + pad + 12f, y + size, size, MUTED); y += lineH }
                y += 4f
            }
            y = top + h
        }

        // ------------------------------------------------------------ text layout

        private data class Placed(val token: Token, val x: Float)

        private fun styleFor(kind: Kind, size: Float, color: Int, base: Typeface = REGULAR) = when (kind) {
            Kind.NORMAL -> Style(size, color, base)
            Kind.BOLD -> Style(size, INK, if (base == MEDIUM) BOLD else MEDIUM)
            Kind.CODE -> Style(size * 0.9f, INK, MONO)
        }

        private fun width(t: Token, size: Float, base: Typeface) =
            paint(styleFor(t.kind, size, BODY, base)).measureText(t.text) + if (t.kind == Kind.CODE) 6f else 0f

        /** Greedy line breaking; tokens glued to their neighbour (no space) are kept on the same line. */
        private fun wrap(tokens: List<Token>, maxW: Float, size: Float, base: Typeface = REGULAR): List<List<Placed>> {
            val space = paint(Style(size, BODY, base)).measureText(" ")
            val words = ArrayList<MutableList<Token>>()
            tokens.forEach { t -> if (t.spaceBefore || words.isEmpty()) words += mutableListOf(t) else words.last() += t }
            val lines = ArrayList<List<Placed>>()
            var line = ArrayList<Placed>()
            var x = 0f
            for (word in words) {
                val ww = word.sumOf { width(it, size, base).toDouble() }.toFloat()
                if (line.isNotEmpty() && x + space + ww > maxW) { lines += line; line = ArrayList(); x = 0f }
                if (line.isNotEmpty()) x += space
                word.forEach { t -> line += Placed(t, x); x += width(t, size, base) }
            }
            if (line.isNotEmpty()) lines += line
            return lines.ifEmpty { listOf(emptyList()) }
        }

        private fun drawLine(line: List<Placed>, x0: Float, baseline: Float, size: Float, color: Int, base: Typeface = REGULAR) {
            val c = canvas ?: return
            for ((t, x) in line) {
                val st = styleFor(t.kind, size, color, base)
                if (t.kind == Kind.CODE) {
                    val w = paint(st).measureText(t.text)
                    c.drawRoundRect(RectF(x0 + x, baseline - size * 0.82f, x0 + x + w + 6f, baseline + size * 0.28f), 2.5f, 2.5f, fill(CODE_BG))
                    c.drawText(t.text, x0 + x + 3f, baseline, paint(st))
                } else {
                    c.drawText(t.text, x0 + x, baseline, paint(st))
                }
            }
        }

        private fun paragraph(raw: String, x0: Float, maxW: Float, size: Float, color: Int, bold: Boolean = false) {
            val lineH = size * 1.55f
            atPageTop = false
            wrap(tokens(raw, false), maxW, size, if (bold) MEDIUM else REGULAR).forEach { line ->
                ensure(lineH)
                drawLine(line, x0, y + size, size, if (bold) INK else color, base = if (bold) MEDIUM else REGULAR)
                y += lineH
            }
        }
    }
}
