package com.boardresult.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class FKind { TEXT, NUMBER, DECIMAL, PASSWORD, DROPDOWN, SWITCH }

data class Fld(
    val key: String,
    val label: String,
    val kind: FKind = FKind.TEXT,
    val options: List<Pair<String, String>> = emptyList()
)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

@Composable
fun DropdownField(label: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == value }?.second ?: "— select —"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(text, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) ->
                    DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); open = false })
                }
            }
        }
    }
}

/** Generic add/edit dialog. onSave returns an error message, or null when saved. */
@Composable
fun FormDialog(
    title: String,
    fields: List<Fld>,
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: suspend (Map<String, String>) -> String?
) {
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(initial) } }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                fields.forEach { f ->
                    when (f.kind) {
                        FKind.DROPDOWN -> DropdownField(f.label, f.options, values[f.key] ?: "") { values[f.key] = it }
                        FKind.SWITCH -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.label, Modifier.weight(1f))
                            Switch(checked = values[f.key] == "true", onCheckedChange = { values[f.key] = it.toString() })
                        }
                        else -> OutlinedTextField(
                            value = values[f.key] ?: "",
                            onValueChange = { values[f.key] = it },
                            label = { Text(f.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = when (f.kind) {
                                    FKind.NUMBER -> KeyboardType.Number
                                    FKind.DECIMAL -> KeyboardType.Decimal
                                    FKind.PASSWORD -> KeyboardType.Password
                                    else -> KeyboardType.Text
                                }
                            ),
                            visualTransformation = if (f.kind == FKind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val e = try { onSave(values.toMap()) } catch (ex: Exception) { ex.message ?: "Error" }
                    busy = false
                    if (e == null) onDismiss() else error = e
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No") } }
    )
}

@Composable
fun ItemCard(
    title: String,
    subtitle: String = "",
    onClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Card(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}

@Composable
fun EditDeleteActions(onEdit: () -> Unit, onDelete: () -> Unit) {
    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
}

@Composable
fun <T> CrudList(items: List<T>, empty: String, onAdd: (() -> Unit)?, row: @Composable (T) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (items.isEmpty()) item { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items) { row(it) }
        }
        if (onAdd != null) {
            FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Default.Add, "Add")
            }
        }
    }
}
