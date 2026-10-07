package com.clarifiai.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.clarifiai.app.data.RecordingAttachment
import com.clarifiai.app.data.RecordingFrames

/**
 * Clarity's export API has no session recordings, so users bring them: screen recordings of sessions (e.g. recorded
 * while playing them in Clarity) or screenshots. Their frames are analysed alongside the metrics.
 */
@Composable
fun RecordingsCard(
    recordings: List<RecordingAttachment>,
    attaching: Boolean,
    note: String,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    onNoteChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frames = recordings.sumOf { it.frames.size }
    GlassCard(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassBadge(Accent, size = 36.dp) { Icon(Icons.Outlined.VideoLibrary, null, tint = Accent, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Session recordings", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    if (recordings.isEmpty()) "Optional · lets the AI see what users actually did"
                    else "$frames of ${RecordingFrames.MAX_FRAMES} frames from ${recordings.size} item${if (recordings.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium, color = InkMuted,
                )
            }
            if (attaching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
        }

        if (recordings.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().glassControl().clickable(enabled = !attaching, onClick = onAdd).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, tint = Accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Attach a recording or screenshots", color = Ink, style = MaterialTheme.typography.bodyLarge)
                    Text("Screen-record a session while it plays in Clarity, then pick it here.",
                        color = InkMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recordings, key = { it.id }) { r -> Thumbnail(r, onRemove) }
                if (frames < RecordingFrames.MAX_FRAMES) item {
                    Box(
                        Modifier.size(72.dp).glassControl(GlassShapes.Control).clickable(enabled = !attaching, onClick = onAdd),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, "Add more", tint = Accent) }
                }
            }
            OutlinedTextField(
                value = note, onValueChange = onNoteChange,
                shape = GlassShapes.Control, colors = clarityFieldColors(),
                label = { Text("What were these users trying to do? (optional)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(), maxLines = 3,
            )
            Text("Frames are sent to the AI for this report only and aren't stored. Clarity masks typed text by default.",
                color = InkFaint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Thumbnail(r: RecordingAttachment, onRemove: (Long) -> Unit) {
    Box(Modifier.size(72.dp)) {
        Image(
            r.thumbnail, null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(GlassShapes.Control).border(0.8.dp, Hairline, GlassShapes.Control),
        )
        if (r.isVideo) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(11.dp))
                Text("${r.frames.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)).clickable { onRemove(r.id) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}
