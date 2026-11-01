package com.rishi.vigil

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var facts by mutableStateOf(listOf<String>())
    private var words by mutableStateOf("")
    private var history by mutableStateOf(listOf<Memory.Entry>())
    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

    override fun onResume() {
        super.onResume()
        facts = Memory.all(this)                       // refresh every time the app is opened
        words = Memory.words(this)
        history = Memory.history(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(Modifier.height(32.dp))
                    Text("Vigil Setup", style = MaterialTheme.typography.headlineMedium)

                    Button({
                        askPerms.launch(arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }, Modifier.fillMaxWidth()) {
                        Text("1. Allow permissions (mic, contacts, phone, SMS, calendar, location)")
                    }

                    Button({ startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }, Modifier.fillMaxWidth()) {
                        Text("2. Set Vigil as Digital assistant")
                    }

                    Button({ startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }, Modifier.fillMaxWidth()) {
                        Text("3. Allow Do Not Disturb access")
                    }

                    Button({
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    }, Modifier.fillMaxWidth()) {
                        Text("4. Allow brightness control")
                    }

                    Button({ startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, Modifier.fillMaxWidth()) {
                        Text("5. Allow notification access")
                    }

                    HorizontalDivider()

                    // Custom vocabulary for Whisper
                    Text("Words Vigil should recognise", style = MaterialTheme.typography.titleMedium)
                    Text("Places, names or terms it mishears, separated by commas.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = words,
                        onValueChange = { words = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Yelachenahalli, Koramangala, Royal Enfield") }
                    )
                    Button({
                        Memory.saveWords(this@MainActivity, words)
                        Toast.makeText(this@MainActivity, "Words saved", Toast.LENGTH_SHORT).show()
                    }, Modifier.fillMaxWidth()) {
                        Text("Save words")
                    }

                    HorizontalDivider()

                    // Long-term memory
                    Text("Vigil remembers (${facts.size})", style = MaterialTheme.typography.titleMedium)
                    if (facts.isEmpty()) Text("Nothing yet. Say \"Remember …\" to Vigil.")
                    else facts.forEach { Text("• $it") }

                    if (facts.isNotEmpty()) OutlinedButton({
                        Memory.save(this@MainActivity, emptyList())
                        facts = emptyList()
                    }, Modifier.fillMaxWidth()) {
                        Text("Forget everything")
                    }

                    HorizontalDivider()

                    // Command history
                    Text("Recent commands (${history.size})", style = MaterialTheme.typography.titleMedium)
                    if (history.isEmpty()) Text("No commands yet.")
                    else history.take(20).forEach { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(timeFmt),
                                    style = MaterialTheme.typography.labelSmall)
                                Text("You: ${e.said}", style = MaterialTheme.typography.bodyMedium)
                                if (e.tools.isNotEmpty())
                                    Text("Tools: ${e.tools}", style = MaterialTheme.typography.bodySmall)
                                Text("Vigil: ${e.reply}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (history.isNotEmpty()) OutlinedButton({
                        Memory.clearHistory(this@MainActivity)
                        history = emptyList()
                    }, Modifier.fillMaxWidth()) {
                        Text("Clear history")
                    }

                    HorizontalDivider()

                    // Data credits (required by Open-Meteo's free licence)
                    TextButton({ startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com"))) }) {
                        Text("Weather data by Open-Meteo.com", style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}