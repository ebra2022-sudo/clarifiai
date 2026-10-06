package com.clarifiai.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.clarifiai.app.data.ProjectDto

/** Rename a saved project and set the Clarity project ID used for the "Watch recordings" link. */
@Composable
fun EditProjectDialog(
    project: ProjectDto,
    saving: Boolean,
    error: String?,
    onSave: (name: String, clarityProjectId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    var clarityId by rememberSaveable(project.id) { mutableStateOf(project.clarityProjectId.orEmpty()) }
    val fieldColors = clarityFieldColors()
    val focus = LocalFocusManager.current
    val save = {
        focus.clearFocus()
        onSave(name, clarityId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(dismissOnClickOutside = false, decorFitsSystemWindows = false),
        containerColor = Surface1,
        title = { Text("Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true, enabled = !saving,
                    label = { Text("Project name") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                )
                ClarityProjectIdField(clarityId, { clarityId = it }, !saving, fieldColors, onDone = { if (name.isNotBlank()) save() })
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = !saving && name.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Neon)
                    Text("Save", Modifier.padding(start = if (saving) 8.dp else 0.dp), color = Neon, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel", color = TextMuted) } },
    )
}
