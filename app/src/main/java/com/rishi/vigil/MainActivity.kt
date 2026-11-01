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
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    Text("Vigil Setup", style = MaterialTheme.typography.headlineMedium)

                    Button({
                        askPerms.launch(arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        ))
                    }, Modifier.fillMaxWidth()) {
                        Text("1. Allow permissions (mic, contacts, phone, SMS, calendar)")
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
                }
            }
        }
    }
}