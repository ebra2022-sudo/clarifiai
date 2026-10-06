package com.clarifiai.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clarifiai.app.pdf.SavedReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every PDF the user exported, kept on the device: open, share, save to a folder of their choice, or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedReportsSheet(
    reports: List<SavedReport>,
    onOpen: (SavedReport) -> Unit,
    onShare: (SavedReport) -> Unit,
    onSaveTo: (SavedReport, android.net.Uri) -> Unit,
    onDelete: (SavedReport) -> Unit,
    onDismiss: () -> Unit,
) {
    var toDelete by remember { mutableStateOf<SavedReport?>(null) }
    var toSave by remember { mutableStateOf<SavedReport?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val r = toSave
        if (uri != null && r != null) onSaveTo(r, uri)
        toSave = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
        shape = SheetShape,
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Saved reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "PDFs you export are kept on this device until you delete them.",
                color = InkMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (reports.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.PictureAsPdf, null, tint = InkMuted, modifier = Modifier.size(40.dp))
                Text("No reports yet", fontWeight = FontWeight.SemiBold)
                Text(
                    "Run an audit, then tap the PDF button at the top to export it.",
                    color = InkMuted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(reports, key = { it.id }) { r ->
                    ReportRow(
                        r, onOpen = { onOpen(r) }, onShare = { onShare(r) },
                        onSave = { toSave = r; saveAs.launch(r.fileName) }, onDelete = { toDelete = r },
                    )
                }
            }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = SheetColor, shape = DialogShape,
            icon = { Icon(Icons.Outlined.Delete, null, tint = Bad) },
            title = { Text("Delete report?") },
            text = {
                Text(
                    "Are you sure you want to delete the ${r.timeframeLabel.lowercase()} report for \"${r.projectName}\" " +
                        "from ${formatDate(r.createdAt)}? Copies you shared or saved elsewhere aren't affected.",
                    color = InkMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(r); toDelete = null }) { Text("Delete", color = Bad, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = Accent) } },
        )
    }
}

@Composable
private fun ReportRow(r: SavedReport, onOpen: () -> Unit, onShare: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clip(GlassShapes.Tile).clickable(onClick = onOpen),
        shape = GlassShapes.Tile,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(0.8.dp, Hairline),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(GlassShapes.Control).background(Bad.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PictureAsPdf, null, tint = Bad) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.projectName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${r.timeframeLabel} · ${r.pages} ${if (r.pages == 1) "page" else "pages"}",
                    color = InkMuted, style = MaterialTheme.typography.labelMedium,
                )
                Text(formatDate(r.createdAt), color = InkMuted, style = MaterialTheme.typography.labelMedium)
            }
            r.healthScore?.let { HealthChip(it) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Report actions", tint = InkMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Panel, shape = GlassShapes.Control) {
                    MenuItem("Open", Icons.Outlined.OpenInNew) { menu = false; onOpen() }
                    MenuItem("Share", Icons.Outlined.Share) { menu = false; onShare() }
                    MenuItem("Save to device", Icons.Outlined.Download) { menu = false; onSave() }
                    MenuItem("Delete", Icons.Outlined.Delete, Bad) { menu = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, color: Color = Ink, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(icon, null, tint = color) },
        onClick = onClick,
    )
}

@Composable
private fun HealthChip(score: Int) {
    val color = when { score >= 80 -> Good; score >= 60 -> Warn; else -> Bad }
    Text(
        "$score", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()).format(Date(millis))
