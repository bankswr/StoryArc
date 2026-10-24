package com.storyarc.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.storyarc.app.AppContainer
import com.storyarc.app.StoryArcApp
import com.storyarc.app.domain.undo.UndoState

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as StoryArcApp).container

/** Single-line text prompt; [onConfirm] receives the trimmed text, blank only if [allowBlank]. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "Save",
    allowBlank: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = allowBlank || value.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun UndoRedoActions(state: UndoState, onUndo: () -> Unit, onRedo: () -> Unit) {
    IconButton(onClick = onUndo, enabled = state.canUndo) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = state.undoLabel?.let { "Undo $it" } ?: "Undo")
    }
    IconButton(onClick = onRedo, enabled = state.canRedo) {
        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = state.redoLabel?.let { "Redo $it" } ?: "Redo")
    }
}

/**
 * [AlertDialog] sized by Material's own 280–560dp limits rather than the platform's
 * percentage-of-window default width. With the platform width, a landscape dialog's window
 * and content kept re-measuring each other between two widths and never settled.
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        modifier = Modifier.padding(horizontal = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}
