package com.pedroperez.cadencepower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* nothing — UI just re-renders */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        val appCtx = application as App

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(
                        app = appCtx,
                        onStart = {
                            val i = Intent(this, BridgeService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(i)
                            } else {
                                startService(i)
                            }
                        },
                        onStop = {
                            val i = Intent(this, BridgeService::class.java)
                                .setAction(BridgeService.ACTION_STOP)
                            startService(i)
                        }
                    )
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
}

@Composable
private fun AppScreen(app: App, onStart: () -> Unit, onStop: () -> Unit) {
    val cadence by app.cadenceScanner.cadenceRpm.collectAsState()
    val power by app.power.collectAsState()
    val speed by app.speedKmh.collectAsState()
    val running by app.running.collectAsState()
    val subs by app.subscribers.collectAsState()
    val gear by app.gearFactor.collectAsState()
    val sensorState by app.cadenceScanner.state.collectAsState()
    val sensorName by app.cadenceScanner.deviceName.collectAsState()
    val advertising by app.powerAdvertiser.advertising.collectAsState()
    val advError by app.powerAdvertiser.lastError.collectAsState()

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CadencePower", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Cadence \u2192 Virtual Power for Zwift / MyWhoosh",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Big START / STOP button at the top so it's always visible.
        Button(
            onClick = { if (running) onStop() else onStart() },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Color(0xFFB00020) else Color(0xFF1B873B),
                contentColor = Color.White
            )
        ) {
            Text(
                if (running) "STOP" else "START",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        BigCard("Power", "$power W")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BigCard("Cadence", "${cadence.toInt()} rpm", modifier = Modifier.weight(1f))
            BigCard("Speed", "%.1f km/h".format(speed), modifier = Modifier.weight(1f))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Virtual gear: %.1f m / crank rev".format(gear),
                    fontSize = 14.sp
                )
                Slider(
                    value = gear.toFloat(),
                    valueRange = 2f..12f,
                    onValueChange = { app.setGear(it.toDouble()) }
                )
                Text(
                    "Lower = easier (less power per rpm). 6.0 \u2248 road bike mid gear.",
                    fontSize = 11.sp
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("CSC sensor: ${sensorName ?: "(none)"} \u2014 $sensorState", fontSize = 13.sp)
                Text(
                    "Advertising: " + when {
                        advertising -> "YES"
                        advError != null -> "NO (error $advError)"
                        !running -> "stopped"
                        else -> "starting\u2026"
                    },
                    fontSize = 13.sp
                )
                Text("Connected clients: $subs", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun BigCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}
