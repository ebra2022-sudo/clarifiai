package com.clarifiai.app.pdf

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.clarifiai.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** A PDF report kept on the device so it can be opened, shared or saved again later. */
@Serializable
data class SavedReport(
    val id: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("timeframe_label") val timeframeLabel: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("health_score") val healthScore: Int? = null,
    val pages: Int = 1,
) {
    /** Name used when the user saves or shares the file. */
    val fileName: String get() {
        val slug = projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }
        return "clarity-ai-$slug-$id.pdf"
    }
}

/**
 * Generated reports live in the app's private files dir (`files/reports`): `<id>.pdf` plus an `<id>.json` sidecar with
 * what the list shows. They stay until the user deletes them.
 */
class ReportStore(private val context: Context) {
    private val dir = File(context.filesDir, "reports").apply { mkdirs() }

    fun pdfFile(id: String) = File(dir, "$id.pdf")
    private fun metaFile(id: String) = File(dir, "$id.json")

    suspend fun save(pdf: File, report: SavedReport): SavedReport = withContext(Dispatchers.IO) {
        pdf.copyTo(pdfFile(report.id), overwrite = true)
        if (pdf.absolutePath != pdfFile(report.id).absolutePath) pdf.delete()
        metaFile(report.id).writeText(AppJson.encodeToString(report))
        report
    }

    /** Newest first. Reports whose PDF or sidecar is missing or unreadable are skipped. */
    suspend fun list(): List<SavedReport> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { AppJson.decodeFromString<SavedReport>(f.readText()) }.getOrNull() }
            .filter { pdfFile(it.id).exists() }
            .sortedByDescending { it.createdAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        pdfFile(id).delete()
        metaFile(id).delete()
    }

    /** Copy a report to a location the user picked (e.g. Downloads) via the system file picker. */
    suspend fun copyTo(id: String, target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target) ?: error("Can't write to the chosen location.")
        out.use { o -> pdfFile(id).inputStream().use { it.copyTo(o) } }
    }

    private fun uri(report: SavedReport): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile(report.id), report.fileName)

    /** Opens the PDF in the user's viewer. Returns false when no app can display PDFs. */
    fun open(report: SavedReport): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri(report), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun share(report: SavedReport) {
        val uri = uri(report)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "UX audit: ${report.projectName}")
            putExtra(Intent.EXTRA_TEXT, "UX audit report for ${report.projectName} (${report.timeframeLabel}) attached.")
            clipData = ClipData.newRawUri(report.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
