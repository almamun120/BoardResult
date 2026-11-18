package com.boardresult

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boardresult.core.Graph
import com.boardresult.core.Security
import com.boardresult.ui.AdminRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0B6E4F), secondary = Color(0xFF8A5A00))) {
                Surface(Modifier.fillMaxSize()) { PinGate { AdminRoot() } }
            }
        }
    }
}

@Composable
private fun PinGate(content: @Composable () -> Unit) {
    var unlocked by rememberSaveable { mutableStateOf(false) }
    if (unlocked) { content(); return }

    val first = Graph.prefs.adminPinHash.isEmpty()
    var pin by rememberSaveable { mutableStateOf("") }
    var err by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (first) "Set an admin PIN (4+ characters)" else "Enter admin PIN", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = pin, onValueChange = { pin = it; err = "" }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button(modifier = Modifier.padding(top = 16.dp), onClick = {
            if (first) {
                if (pin.length < 4) err = "At least 4 characters." else { Graph.prefs.adminPinHash = Security.hash(pin); unlocked = true }
            } else if (Security.verify(pin, Graph.prefs.adminPinHash)) unlocked = true else err = "Wrong PIN."
        }) { Text(if (first) "Save PIN" else "Unlock") }
    }
}
