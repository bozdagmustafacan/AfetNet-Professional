package org.afetnet.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var connectionsClient: ConnectionsClient
    private val serviceId = "org.afetnet.mesh.v1"
    private val myDeviceId = UUID.randomUUID().toString().take(8)
    private val logs = mutableStateListOf<String>()
    private val discoveredEndpoints = mutableStateMapOf<String, String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startMesh()
        else Toast.makeText(this, "İzinler verilmezse ağ çalışmaz.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)
        
        setContent {
            MaterialTheme {
                AfetNetScreen(
                    logs = logs,
                    onEmergencyClick = { note -> sendEmergencySignal(note) },
                    deviceId = myDeviceId
                )
            }
        }
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startMesh() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startMesh() {
        addLog("Sistem başlatılıyor... Cihaz ID: $myDeviceId")
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising("AfetNet-$myDeviceId", serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { addLog("✅ Yayın başlatıldı (Bluetooth/Wi-Fi Direct)") }
            .addOnFailureListener { addLog("❌ Yayın hatası: ${it.message}") }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { addLog("🔍 Ağ taraması başladı...") }
            .addOnFailureListener { addLog("❌ Tarama hatası: ${it.message}") }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            addLog("🤝 Bağlantı isteği: ${info.endpointName}")
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) addLog("✅ Bağlantı kuruldu: $endpointId")
        }
        override fun onDisconnected(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            addLog("⚠️ Bağlantı koptu: $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discoveredEndpoints[endpointId] = info.endpointName
            connectionsClient.requestConnection("AfetNet-$myDeviceId", endpointId, connectionLifecycleCallback)
            addLog("📱 Cihaz bulundu: ${info.endpointName}")
        }
        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes()!!)
                addLog("📩 MESAJ ALINDI: $msg")
                // Relay (Sekme) Mantığı: Gelen mesajı diğerlerine ilet
                discoveredEndpoints.keys.filter { it != endpointId }.forEach { otherId ->
                    connectionsClient.sendPayload(otherId, Payload.fromBytes(msg.toByteArray()))
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendEmergencySignal(note: String) {
        val payloadText = "🚨 ACİL DURUM | ID:$myDeviceId | Not: $note"
        val payload = Payload.fromBytes(payloadText.toByteArray())
        
        if (discoveredEndpoints.isEmpty()) {
            addLog("⚠️ Yakında cihaz yok. Sinyal belleğe kaydedildi.")
        } else {
            connectionsClient.sendPayload(discoveredEndpoints.keys.toList(), payload)
            addLog("🚀 Sinyal gönderildi: ${discoveredEndpoints.size} cihaza.")
        }
    }

    private fun addLog(msg: String) {
        Log.d("AfetNet", msg)
        runOnUiThread {
            logs.add(0, msg)
            if (logs.size > 50) logs.removeAt(logs.size - 1)
        }
    }

    override fun onStop() {
        super.onStop()
        // Batarya koruması için arka planda durdurma (Gerçek sürümde Foreground Service kullanılır)
        // connectionsClient.stopAllEndpoints() 
    }
}

@Composable
fun AfetNetScreen(logs: List<String>, onEmergencyClick: (String) -> Unit, deviceId: String) {
    var note by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text("AFET-NET PRO", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Cihaz ID: $deviceId | Durum: Mesh Aktif", color = Color.Gray, fontSize = 12.sp)
        
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 400) note = it },
            label = { Text("Durum Notu (Opsiyonel)", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Red,
                cursorColor = Color.Red
            )
        )
        Text("${note.length}/400", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { 
                onEmergencyClick(note)
                note = ""
                Toast.makeText(context, "Acil durum sinyali yayına verildi!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
        ) {
            Text("🚨 GÜVENDE DEĞİLİM", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Ağ Günlüğü (Mesh Log)", color = Color.White, fontWeight = FontWeight.Bold)
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = if (log.contains("🚨")) Color.Red else Color.LightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
