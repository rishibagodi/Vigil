package com.rishi.vigil

import android.Manifest
import android.content.Intent
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
        val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    Text("Vigil Setup", style = MaterialTheme.typography.headlineMedium)
                    Button({ askMic.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.fillMaxWidth()) {
                        Text("1. Allow microphone")
                    }
                    Button({ startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }, Modifier.fillMaxWidth()) {
                        Text("2. Set Vigil as Digital assistant")
                    }
                }
            }
        }
    }
}