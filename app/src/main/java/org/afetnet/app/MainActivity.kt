package org.afetnet.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class MainActivity : ComponentActivity() {

    companion object {
        // AFAD Komuta Merkezi ile paylaşılan ana anahtar tohumu.
        // Gerçek sistemde bu, AFAD'ın RSA public key'i ile değiştirilir.
        const val MASTER_KEY_SEED = "AFETNET-AFAD-MASTER-KEY-V1"
    }

    private lateinit var connectionsClient: ConnectionsClient
    private val serviceId = "org.afetnet.mesh.v2"
    private lateinit var myDid: String
    private lateinit var afadKey: SecretKey

    private val logs = mutableStateListOf<String>()
    private val discoveredEndpoints = mutableStateMapOf<String, String>()
    private val messageQueue = mutableStateListOf<String>()
    private val seenIds = mutableSetOf<String>()
    private var serverIp = mutableStateOf("192.168.1.100")
    private var currentLocation = mutableStateOf<Pair<Double, Double>?>(null)
    private var locationText = mutableStateOf("📍 Konum bekleniyor...")
    private var systemError = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) initSystem()
        else systemError.value = "Gerekli izinler (Konum, Bluetooth) verilmedi."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)

        try {
            initIdentity()
        } catch (e: Exception) {
            systemError.value = "Kimlik hatası: ${e.message}"
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD32F2F), background = Color(0xFF0A0A0A))) {
                if (systemError.value != null) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF400000))) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text("⚠️ SİSTEM HATASI", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Spacer(Modifier.height(16.dp))
                                Text(systemError.value ?: "", color = Color.White)
                            }
                        }
                    }
                } else {
                    AfetNetProApp(
                        logs = logs,
                        myDid = myDid,
                        locationText = locationText.value,
                        onEmergency = { note, needs -> sendEmergency(note, needs) },
                        serverIp = serverIp,
                        onIpChange = { serverIp.value = it },
                        queueSize = messageQueue.size
                    )
                }
            }
        }

        try {
            checkPermissions()
        } catch (e: Exception) {
            systemError.value = "İzin kontrol hatası: ${e.message}"
        }
    }

    private fun initIdentity() {
        val androidId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) { "unknown" }

        myDid = "did:afet:" + MessageDigest.getInstance("SHA-256")
            .digest(("afetnet::$androidId").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(MASTER_KEY_SEED.toByteArray(Charsets.UTF_8))
        afadKey = SecretKeySpec(keyBytes, "AES")
    }

    private fun checkPermissions() {
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
        if (needed.isEmpty()) initSystem() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun initSystem() {
        addLog("🛡️ DID Aktif: $myDid")
        startLocationTracking()
        startMesh()
    }

    private fun startLocationTracking() {
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)

            // 1) Hemen "son bilinen konumu" çek (beklemeyi önler)
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    currentLocation.value = Pair(loc.latitude, loc.longitude)
                    locationText.value = "📍 %.4f, %.4f".format(loc.latitude, loc.longitude)
                    addLog("📍 Son bilinen konum alındı")
                }
            }

            // 2) Sürekli güncel konum takibi
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
            client.requestLocationUpdates(req, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let {
                        currentLocation.value = Pair(it.latitude, it.longitude)
                        locationText.value = "📍 %.4f, %.4f".format(it.latitude, it.longitude)
                    }
                }
            }, Looper.getMainLooper())
            addLog("📍 Konum servisi başlatıldı")
        } catch (e: SecurityException) {
            addLog("⚠️ Konum izni yok")
        } catch (e: Exception) {
            addLog("⚠️ Konum hatası: ${e.message}")
        }
    }

    private fun startMesh() {
        try {
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            connectionsClient.startAdvertising("AfetNet-${myDid.take(6)}", serviceId, connectionLifecycleCallback, options)
            connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            addLog("📡 Mesh Ağı Aktif")
        } catch (e: Exception) {
            addLog("❌ Mesh hatası: ${e.message}")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) addLog("🔗 Düğüm Bağlandı")
        }
        override fun onDisconnected(endpointId: String) { discoveredEndpoints.remove(endpointId) }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discoveredEndpoints[endpointId] = info.endpointName
            connectionsClient.requestConnection("AfetNet-${myDid.take(6)}", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) { discoveredEndpoints.remove(endpointId) }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val str = String(payload.asBytes()!!)
            try {
                val env = JSONObject(str)
                val id = env.optString("id")
                if (id.isEmpty() || !seenIds.add(id)) {
                    addLog("🔁 Bilinen paket, yoksayıldı")
                    return
                }
                addLog("📩 Şifreli AFAD paketi alındı (içerik okunamaz)")
                // Store-and-forward: diğer düğümlere ilet
                discoveredEndpoints.keys.filter { it != endpointId }.forEach {
                    connectionsClient.sendPayload(it, Payload.fromBytes(str.toByteArray()))
                }
                messageQueue.add(str)
                tryUplinkToServer()
            } catch (e: Exception) {
                addLog("⚠️ Bozuk paket yoksayıldı")
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendEmergency(note: String, needs: List<String>) {
        try {
            val loc = currentLocation.value
            if (loc == null) addLog("⚠️ GPS kilidi yok, konum boş gönderilecek")

            val inner = JSONObject().apply {
                put("did", myDid)
                put("note", note)
                put("needs", needs)
                if (loc != null) { put("lat", loc.first); put("lng", loc.second) }
                put("ts", System.currentTimeMillis())
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, afadKey)
            val ct = cipher.doFinal(inner.toString().toByteArray(Charsets.UTF_8))

            val envelope = JSONObject().apply {
                put("v", 2)
                put("id", UUID.randomUUID().toString())
                put("sender", myDid)
                put("recipient", "did:afet:afad")
                put("kind", "EMERGENCY")
                put("ts", System.currentTimeMillis())
                put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            }

            val str = envelope.toString()
            seenIds.add(envelope.getString("id"))

            if (discoveredEndpoints.isEmpty()) {
                addLog("⚠️ Yakında cihaz yok, paket kuyruğa alındı")
            } else {
                connectionsClient.sendPayload(discoveredEndpoints.keys.toList(), Payload.fromBytes(str.toByteArray()))
                addLog("🚀 Şifreli SOS yayınlandı (${discoveredEndpoints.size} düğüm)")
            }
            messageQueue.add(str)
            tryUplinkToServer()
        } catch (e: Exception) {
            addLog("❌ Gönderim hatası: ${e.message}")
        }
    }

    private fun tryUplinkToServer() {
        if (messageQueue.isEmpty()) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

            CoroutineScope(Dispatchers.IO).launch {
                if (messageQueue.isEmpty()) return@launch
                val msg = messageQueue.removeAt(0)
                try {
                    val url = URL("http://${serverIp.value}:8000/api/ingest")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream).use { it.write(msg) }
                    if (conn.responseCode == 200) {
                        withContext(Dispatchers.Main) { addLog("🛰️ Komuta Merkezine aktarıldı") }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { addLog("❌ Sunucu hatası: ${e.message?.take(40)}") }
                }
            }
        } catch (e: Exception) { }
    }

    private fun addLog(msg: String) {
        runOnUiThread {
            logs.add(0, msg)
            if (logs.size > 100) logs.removeAt(logs.size - 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfetNetProApp(
    logs: List<String>,
    myDid: String,
    locationText: String,
    onEmergency: (String, List<String>) -> Unit,
    serverIp: MutableState<String>,
    onIpChange: (String) -> Unit,
    queueSize: Int
) {
    var currentTab by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    val needsOptions = listOf("Enkaz", "Tıbbi", "Yangın", "Su", "Güvenlik")
    var selectedNeeds by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111111)) {
                val tabs = listOf("Acil Durum" to Icons.Filled.Warning, "Ağ" to Icons.Filled.Hub, "Ayarlar" to Icons.Filled.Settings)
                tabs.forEachIndexed { index, (title, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title, fontSize = 10.sp) },
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Red, unselectedIconColor = Color.Gray)
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0A0A0A))) {
            when (currentTab) {
                0 -> EmergencyScreen(note, { note = it }, needsOptions, selectedNeeds, { selectedNeeds = it }, onEmergency, myDid, locationText)
                1 -> NetworkScreen(logs, queueSize)
                2 -> SettingsScreen(serverIp.value, onIpChange)
            }
        }
    }
}

@Composable
fun EmergencyScreen(note: String, onNoteChange: (String) -> Unit, needs: List<String>, selected: Set<String>, onNeedToggle: (Set<String>) -> Unit, onSend: (String, List<String>) -> Unit, did: String, locationText: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("ACİL DURUM MODU", color = Color.Red, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Kimlik: $did", color = Color.Gray, fontSize = 12.sp)
        Text(locationText, color = Color.Green, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))

        Text("İhtiyaç Durumu Seçin:", color = Color.White)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            needs.take(3).forEach { need ->
                val isSelected = selected.contains(need)
                AssistChip(
                    onClick = { onNeedToggle(if (isSelected) selected - need else selected + need) },
                    label = { Text(need, color = if (isSelected) Color.White else Color.Gray) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = if (isSelected) Color(0xFF8B0000) else Color(0xFF222222))
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            needs.drop(3).forEach { need ->
                val isSelected = selected.contains(need)
                AssistChip(
                    onClick = { onNeedToggle(if (isSelected) selected - need else selected + need) },
                    label = { Text(need, color = if (isSelected) Color.White else Color.Gray) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = if (isSelected) Color(0xFF8B0000) else Color(0xFF222222))
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = note, onValueChange = { if (it.length <= 400) onNoteChange(it) },
            label = { Text("Detaylı Durum (400 Karakter)", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Red, unfocusedTextColor = Color.White)
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onSend(note, selected.toList()); onNoteChange("") },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text("GÜVENDE DEĞİLİM", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
        }
    }
}

@Composable
fun NetworkScreen(logs: List<String>, queueSize: Int) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Mesh Ağ Durumu", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Kuyrukta Bekleyen Paket: $queueSize", color = Color.Yellow)
                Text("Şifreleme: AES-256-GCM (AFAD Ana Anahtarı)", color = Color.Green, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Sistem Günlüğü", color = Color.White, fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(logs) { log ->
                Text(text = "• $log", color = if (log.contains("🚀") || log.contains("🛰️")) Color.Green else Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(ip: String, onIpChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Komuta Merkezi Ayarları", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("Python Sunucusu IP Adresi:", color = Color.Gray)
        OutlinedTextField(
            value = ip, onValueChange = onIpChange,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        Text("Bilgisayarınızın yerel IP'sini girin (Örn: 192.168.1.34)", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(32.dp))
        Text("Afet-Net Professional v1.2", color = Color.LightGray, fontSize = 14.sp)
    }
}
