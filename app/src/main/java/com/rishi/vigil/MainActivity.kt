package com.rishi.vigil

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

class MainActivity : ComponentActivity() {
    private var facts by mutableStateOf(listOf<String>())

    override fun onResume() {
        super.onResume()
        facts = Memory.all(this)                       // refresh every time the app is opened
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

                    HorizontalDivider()

                    Text("Vigil remembers (${facts.size})", style = MaterialTheme.typography.titleMedium)
                    if (facts.isEmpty()) Text("Nothing yet. Say \"Remember …\" to Vigil.")
                    else facts.forEach { Text("• $it") }

                    if (facts.isNotEmpty()) OutlinedButton({
                        Memory.save(this@MainActivity, emptyList())
                        facts = emptyList()
                    }, Modifier.fillMaxWidth()) {
                        Text("Forget everything")
                    }
                }
            }
        }
    }
}