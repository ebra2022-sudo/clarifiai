package com.clarifiai.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.clarifiai.app.data.ProjectDto

private const val CLARITY_EXPORT_DOCS =
    "https://learn.microsoft.com/en-us/clarity/setup-and-installation/clarity-data-export-api#obtaining-access-tokens"

@Composable
internal fun clarityFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon, unfocusedBorderColor = Border, focusedLabelColor = Neon,
    unfocusedLabelColor = TextMuted, cursorColor = Neon,
)

/**
 * Lets a user connect their own Clarity project by pasting its Data Export API token, or, when [reconnecting] is set,
 * re-authenticate a saved project whose token Clarity no longer accepts.
 */
@Composable
fun ConnectProjectDialog(
    connecting: Boolean,
    error: String?,
    onConnect: (name: String, token: String, clarityProjectId: String) -> Unit,
    onDismiss: () -> Unit,
    reconnecting: ProjectDto? = null,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var clarityId by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val fieldColors = clarityFieldColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (reconnecting != null) "Reconnect ${reconnecting.name}" else "Connect Clarity project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reconnecting != null) {
                    Text(
                        "Clarity no longer accepts this project's token (it was revoked or expired). Paste a new one; " +
                            "the project keeps its name and report history.",
                        color = Warn, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text("Get an API token from Microsoft Clarity:", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                listOf(
                    "Open your project at clarity.microsoft.com",
                    "Settings → Data Export → Generate new API token",
                    "Name it (e.g. clarity-ai) and copy the token",
                ).forEachIndexed { i, step ->
                    Text("${i + 1}. $step", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Only project admins can create tokens. Microsoft allows 10 data requests per project per day, " +
                        "so reports refresh up to 9 times a day.",
                    color = TextMuted, style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = { uriHandler.openUri(CLARITY_EXPORT_DOCS) }) {
                    Icon(Icons.Outlined.OpenInNew, null, tint = Neon, modifier = Modifier.size(16.dp))
                    Text("Microsoft's guide", Modifier.padding(start = 6.dp), color = Neon)
                }
                if (reconnecting == null) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true, enabled = !connecting,
                        label = { Text("Project name") }, placeholder = { Text("My website") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    )
                }
                OutlinedTextField(
                    value = token, onValueChange = { token = it.trim() }, singleLine = true, enabled = !connecting,
                    label = { Text("Clarity API token") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (showToken) "Hide token" else "Show token", tint = TextMuted)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                )
                Text("Your token is stored encrypted on our server and never shown again.",
                    color = TextMuted, style = MaterialTheme.typography.labelMedium)
                if (reconnecting == null) ClarityProjectIdField(clarityId, { clarityId = it }, !connecting, fieldColors)
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(name, token, clarityId) }, enabled = !connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Neon)
                        Text("Checking…", Modifier.padding(start = 8.dp), color = Neon)
                    } else {
                        Text(if (reconnecting != null) "Reconnect" else "Connect", color = Neon, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !connecting) { Text("Cancel", color = TextMuted) } },
    )
}

/** Optional: Clarity's own project ID, so the app can open session recordings (which the export API can't return). */
@Composable
internal fun ClarityProjectIdField(
    value: String, onValueChange: (String) -> Unit, enabled: Boolean, colors: TextFieldColors,
) {
    OutlinedTextField(
        value = value, onValueChange = { onValueChange(it.trim().take(32)) }, singleLine = true, enabled = enabled,
        label = { Text("Clarity project ID (optional)") }, placeholder = { Text("e.g. k3x9abc12d") },
        supportingText = {
            Text("From your Clarity URL: clarity.microsoft.com/projects/view/<ID>/… Lets you jump to session recordings.",
                color = TextMuted)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(), colors = colors,
    )
}
