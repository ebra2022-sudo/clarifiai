package com.clarifiai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A session recording (video) or screenshot the user attached, reduced to frames the AI can look at. */
data class RecordingAttachment(
    val id: Long,
    val isVideo: Boolean,
    /** Base64 JPEG frames in playback order. */
    val frames: List<String>,
    val thumbnail: ImageBitmap,
)

/**
 * Clarity has no recordings API, so users attach screen recordings of sessions (e.g. recorded while playing them in
 * Clarity) or screenshots. Videos are sampled evenly; every frame is scaled to [MAX_EDGE] px and JPEG-compressed so a
 * full set of [MAX_FRAMES] stays around 1 MB.
 */
object RecordingFrames {
    const val MAX_FRAMES = 12 // matches the backend's limit
    const val FRAMES_PER_VIDEO = 6
    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 72

    /** Turns one picked item into an attachment using at most [budget] frames, or null if it can't be read. */
    suspend fun extract(context: Context, uri: Uri, budget: Int): RecordingAttachment? = withContext(Dispatchers.IO) {
        if (budget <= 0) return@withContext null
        val type = context.contentResolver.getType(uri).orEmpty()
        val bitmaps = if (type.startsWith("video/")) videoFrames(context, uri, minOf(budget, FRAMES_PER_VIDEO)) else listOfNotNull(image(context, uri))
        if (bitmaps.isEmpty()) return@withContext null
        val thumb = scale(bitmaps.first(), 160).asImageBitmap()
        RecordingAttachment(
            id = System.nanoTime(),
            isVideo = type.startsWith("video/"),
            frames = bitmaps.map { encode(it) },
            thumbnail = thumb,
        )
    }

    private fun videoFrames(context: Context, uri: Uri, count: Int): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Evenly spaced through the session, skipping the very first and last instants.
            (0 until count).mapNotNull { i ->
                val atUs = durationMs * 1000 * (2 * i + 1) / (2 * count)
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE, MAX_EDGE)
                } else {
                    retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                frame?.let { scale(it, MAX_EDGE) }
            }
        } catch (_: RuntimeException) {
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun image(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { scale(it, MAX_EDGE) }
    }

    private fun scale(b: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxEdge) return b
        val f = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
    }

    private fun encode(b: Bitmap): String {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
