package com.pedroperez.cadencepower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    private val vm: MainViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* nothing — UI just re-renders */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(
                        vm = vm,
                        onStart = {
                            startService(Intent(this, BridgeService::class.java))
                            vm.start()
                        },
                        onStop = {
                            vm.stop()
                            stopService(Intent(this, BridgeService::class.java))
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
private fun AppScreen(vm: MainViewModel, onStart: () -> Unit, onStop: () -> Unit) {
    val cadence by vm.cadenceScanner.cadenceRpm.collectAsState()
    val power by vm.power.collectAsState()
    val speed by vm.speedKmh.collectAsState()
    val running by vm.running.collectAsState()
    val subs by vm.subscribers.collectAsState()
    val gear by vm.gearFactor.collectAsState()
    val sensorState by vm.cadenceScanner.state.collectAsState()
    val sensorName by vm.cadenceScanner.deviceName.collectAsState()
    val advertising by vm.powerAdvertiser.advertising.collectAsState()
    val advError by vm.powerAdvertiser.lastError.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CadencePower", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Cadence \u2192 Virtual Power for Zwift", color = MaterialTheme.colorScheme.onSurfaceVariant)

        BigCard("Power", "${power} W")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCard("Cadence", "${cadence.toInt()} rpm", modifier = Modifier.weight(1f))
            BigCard("Speed", "%.1f km/h".format(speed), modifier = Modifier.weight(1f))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Virtual gear: %.1f m / crank rev".format(gear))
                Slider(
                    value = gear.toFloat(),
                    valueRange = 2f..12f,
                    onValueChange = { vm.setGear(it.toDouble()) }
                )
                Text(
                    "Lower = easier (less power per rpm). 6.0 m/rev \u2248 a typical road bike in mid gear.",
                    fontSize = 12.sp
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CSC sensor: ${sensorName ?: "(none)"} \u2014 $sensorState")
                Text("Advertising power meter: " + when {
                    advertising -> "YES"
                    advError != null -> "NO (error $advError)"
                    !running -> "stopped"
                    else -> "starting\u2026"
                })
                Text("Zwift clients connected: $subs")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Big, color-coded Start / Stop toggle
        Button(
            onClick = { if (running) onStop() else onStart() },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Color(0xFFB00020) else Color(0xFF1B873B),
                contentColor = Color.White
            )
        ) {
            Text(
                if (running) "STOP" else "START",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BigCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
    }
}
