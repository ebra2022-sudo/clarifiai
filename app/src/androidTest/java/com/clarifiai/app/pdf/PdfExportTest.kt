package com.clarifiai.app.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarifiai.app.data.Kpis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders sample reports. Besides the assertions, the PDFs are copied to the app's external files dir so they can be
 * inspected: adb pull /sdcard/Android/data/com.clarifiai.app/files/pdf-preview
 */
@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.getExternalFilesDir(null), "pdf-preview").apply { mkdirs() }

    private val kpis = Kpis(
        healthScore = 64, totalSessions = 18_432, rageClickCount = 1_287, rageSessionPct = 4.2,
        deadClickCount = 3_904, deadClickSessionPct = 11.6, quickbackSessionPct = 6.1,
        rapidScrollSessionPct = 2.3, scriptErrorSessionPct = 0.8, errorClickCount = 212,
    )

    private val markdown = """
        ### 🚨 Immediate Hotfixes (High Frustration)
        - **Checkout `/checkout/payment`**: 412 rage clicks on the "Pay now" button. Likely cause: the button stays disabled while the card form validates asynchronously, with no loading state. Fix: show a spinner and disable double-submits; surface validation errors inline.
        - **Search results `/search`**: dead clicks on product thumbnails (1,120). Thumbnails aren't wrapped in links. Fix: make the whole card clickable.
        - **Account `/account/:id/orders`**: script errors in 0.8% of sessions break the order list. Fix: guard against null `shippingAddress` in the order renderer.

        ### 📉 Friction Trends & User Drop-off
        - **Mobile users quick-back from `/pricing`** at twice the desktop rate; the comparison table overflows narrow screens.
          - 61% of quick-backs happen within 5 seconds.
          - Most affected: Android Chrome, 360–412px widths.
        - Excessive scrolling on `/docs/getting-started` suggests users can't find the install step.
        - Rage clicks cluster on the cookie banner's "Manage" link, which opens nothing on iOS Safari.

        ### 🚀 Strategic Next-Sprint Product Roadmap
        1. **One-tap checkout** with saved payment methods to cut payment-step drop-off.
        2. **Responsive pricing table** with a plan switcher on mobile.
        3. **Docs quick-start card** pinned at the top of getting-started.
        4. **Error monitoring** on the account area with alerting on new script errors.
    """.trimIndent()

    private fun render(name: String, md: String, meta: PdfExportUtility.ReportMeta): PdfExportUtility.Rendered = runBlocking {
        PdfExportUtility.generate(context, md, meta).also { it.file.copyTo(File(out, "$name.pdf"), overwrite = true) }
    }

    @Test
    fun rendersTypicalReport() {
        val r = render("typical", markdown, PdfExportUtility.ReportMeta(
            projectName = "Northwind Storefront", timeframeLabel = "Last 7 days", kpis = kpis,
            notes = listOf("Covers 5 of 7 requested days. Clarity only exposes the last 3 days."), whiteLabel = false,
        ))
        assertTrue(r.file.length() > 1_000)
        assertTrue(r.pages in 1..3)
    }

    @Test
    fun longReportFlowsAcrossPagesAndCountsThem() {
        val long = markdown + "\n\n" + (1..6).joinToString("\n\n") { markdown.replace("###", "####") }
        val r = render("long", long, PdfExportUtility.ReportMeta(
            projectName = "A very long project name that should be truncated neatly on the cover page", timeframeLabel = "Custom range",
            kpis = kpis.copy(healthScore = 88), notes = emptyList(), whiteLabel = true,
        ))
        assertTrue(r.pages >= 3)
    }

    @Test
    fun savedReportsAreListedNewestFirstAndDeletable(): Unit = runBlocking {
        val store = ReportStore(context)
        store.list().forEach { store.delete(it.id) }
        val rendered = PdfExportUtility.generate(context, markdown, PdfExportUtility.ReportMeta("P", "Today", null, emptyList(), false))
        store.save(rendered.file, SavedReport("a", "P", "Today", createdAt = 1, pages = rendered.pages))
        val second = PdfExportUtility.generate(context, markdown, PdfExportUtility.ReportMeta("Q", "Today", null, emptyList(), false))
        store.save(second.file, SavedReport("b", "Q", "Today", createdAt = 2, pages = second.pages))
        assertEquals(listOf("b", "a"), store.list().map { it.id })
        store.delete("b")
        assertEquals(listOf("a"), store.list().map { it.id })
        store.delete("a")
    }
}
