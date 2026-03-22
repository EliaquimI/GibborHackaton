package mx.edu.utez.gibbor

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mx.edu.utez.gibbor.ui.theme.GibborBg
import mx.edu.utez.gibbor.ui.theme.GibborBorder
import mx.edu.utez.gibbor.ui.theme.GibborBlue
import mx.edu.utez.gibbor.ui.theme.GibborCharcoal
import mx.edu.utez.gibbor.ui.theme.GibborDark
import mx.edu.utez.gibbor.ui.theme.GibborGreen
import mx.edu.utez.gibbor.ui.theme.GibborLight
import mx.edu.utez.gibbor.ui.theme.GibborMid
import mx.edu.utez.gibbor.ui.theme.GibborNavy
import mx.edu.utez.gibbor.ui.theme.GibborRed
import mx.edu.utez.gibbor.ui.theme.GibborRedBorder
import mx.edu.utez.gibbor.ui.theme.GibborRedLight
import mx.edu.utez.gibbor.ui.theme.GibborSurface
import mx.edu.utez.gibbor.ui.theme.GibborTheme
import androidx.core.content.ContextCompat
import com.crossmint.kotlin.compose.CrossmintSDKProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {

    companion object {
        private const val DEVICE_NAME = "GIBBOR_ESP32"
        private const val STELLAR_CONTRACT_ID =
            "CB36FLNBESA7WJIQTJMVKUAE63IAGZ75Q65XER4MD4MF5VCAAO4RGMLE"

        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    data class IncidentDraft(
        val incidentId: String,
        val timestamp: Long,
        val latE7: Int,
        val lonE7: Int,
        val initialHash: String
    )

    data class BackendResult(
        val success: Boolean,
        val status: String,
        val txId: String,
        val txHash: String,
        val explorerLink: String,
        val error: String,
        val rawJson: String
    )

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var statusText by mutableStateOf("Status: Disconnected")
    private var logText by mutableStateOf("Esperando...\n")
    private var triggerCounter by mutableIntStateOf(0)

    // ─── Bluetooth state ──────────────────────────────────────────────────

    private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var readThread: Thread? = null
    @Volatile private var isConnecting = false
    private var pendingConnect = false

    // ─── Audio recording state ─────────────────────────────────────────────

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var isRecording by mutableStateOf(false)
    private var lastIncidentId: String? = null

    // ─── Auth state (separado de UI) ──────────────────────────────────────

    @Volatile private var isAuthenticated = false
    private var authenticatedEmail = ""

    // ─── Contexto de sesión accesible desde receivers ─────────────────────

    @Volatile private var lastBackendUrl: String = ""
    @Volatile private var lastIncidentForEvidence: String = ""

    // ─── Video recording broadcast receiver ───────────────────────────────

    private val recordingDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id   = intent?.getStringExtra(RecordingService.EXTRA_INCIDENT_ID) ?: return
            val path = intent.getStringExtra(RecordingService.EXTRA_FILE_PATH) ?: ""
            val hash = intent.getStringExtra(RecordingService.EXTRA_SHA256) ?: ""
            appendLog("VIDEO: Recording finished [$id]")
            appendLog("VIDEO: File: ${File(path).name}")
            appendLog("VIDEO: SHA-256: $hash")

            if (hash.isNotEmpty() && lastBackendUrl.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val result = sendEvidenceToBackend(lastBackendUrl, id, "video", hash)
                    withContext(Dispatchers.Main) {
                        if (result) appendLog("VIDEO: Hash anchored on blockchain")
                        else        appendLog("VIDEO: Error anchoring hash on blockchain")
                    }
                }
            }
        }
    }

    // ─── Permission launchers ─────────────────────────────────────────────

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter?.isEnabled == true) {
                appendLog("Bluetooth enabled.")
                if (pendingConnect) {
                    pendingConnect = false
                    connectToEsp32()
                }
            } else {
                pendingConnect = false
                appendLog("User did not enable Bluetooth.")
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                appendLog("Bluetooth permissions granted.")
                if (pendingConnect) {
                    pendingConnect = false
                    connectToEsp32()
                }
            } else {
                pendingConnect = false
                appendLog("Bluetooth permissions denied: ${denied.joinToString()}")
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                appendLog("Location permission granted.")
            } else {
                appendLog("Location permission denied.")
            }
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            statusText = "Status: This device does not support Bluetooth"
            appendLog("Bluetooth not supported on this device.")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        ContextCompat.registerReceiver(
            this,
            recordingDoneReceiver,
            IntentFilter(RecordingService.BROADCAST_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            CrossmintSDKProvider
                .Builder(BuildConfig.CROSSMINT_API_KEY)
                .build {
                    GibborTheme { AppScreen() }
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioRecording()
        disconnectFromEsp32()
        unregisterReceiver(recordingDoneReceiver)
    }

    // ─── Logging ──────────────────────────────────────────────────────────

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logText += "[$time] $message\n"
    }

    // ─── Bluetooth permissions ────────────────────────────────────────────

    private fun ensureBluetoothEnabled() {
        val adapter = bluetoothAdapter ?: run {
            appendLog("No Bluetooth adapter.")
            return
        }

        if (adapter.isEnabled) {
            appendLog("Bluetooth already enabled.")
            return
        }

        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableIntent)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val hasScan = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val hasLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            hasConnect && hasScan && hasLocation
        } else {
            true
        }
    }

    private fun requestBluetoothPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    // ─── Bluetooth connection (CORREGIDO) ─────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectToEsp32() {
        // Guard: evitar conexiones simultáneas
        if (isConnecting) {
            appendLog("Connection already in progress.")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            appendLog("No Bluetooth adapter available.")
            return
        }

        if (!hasBluetoothPermissions()) {
            pendingConnect = true
            requestBluetoothPermissions()
            return
        }

        if (!adapter.isEnabled) {
            pendingConnect = true
            ensureBluetoothEnabled()
            return
        }

        val bondedDevices = adapter.bondedDevices
        val device = bondedDevices.firstOrNull { it.name == DEVICE_NAME }

        if (device == null) {
            appendLog("'$DEVICE_NAME' not found among paired devices.")
            appendLog("Pair the ESP32 first from Settings > Bluetooth.")
            return
        }

        // Primero limpiar cualquier conexión anterior
        disconnectFromEsp32Internal(silent = true)

        isConnecting = true
        appendLog("BT INIT: Target=${device.name}, MAC=${device.address}, BondState=${device.bondState}")

        readThread = thread(start = true) {
            try {
                // 1) Verificar que el dispositivo sí esté emparejado (BOND_BONDED = 12)
                if (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    runOnUiThread {
                        isConnecting = false
                        statusText = "Status: Not paired"
                        appendLog("BT ERROR: Device NOT paired (bondState=${device.bondState}). Aborting.")
                    }
                    return@thread
                }

                // 2) Siempre cancelar discovery antes de conectar y esperar
                adapter.cancelDiscovery()
                runOnUiThread { appendLog("BT: Discovery cancelled. Purging stack...") }
                Thread.sleep(1500) // Cooldown generoso para que el stack BT se estabilice

                var socket: BluetoothSocket? = null
                var connectSuccess = false

                // ─── Intento 1: SPP Inseguro (más compatible con ESP32) ───
                try {
                    runOnUiThread { appendLog("BT [Intento 1]: SPP Inseguro (createInsecureRfcommSocketToServiceRecord)") }
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    runOnUiThread { appendLog("BT [Intento 1]: Socket Creado. Iniciando connect()...") }
                    socket.connect()
                    connectSuccess = true
                } catch (e: Exception) {
                    val err = e.message ?: e.javaClass.simpleName
                    runOnUiThread { appendLog("BT [Intento 1] Falló: $err") }
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }

                // ─── Intento 2: Reflection Fallback (Puerto 1) ───
                if (!connectSuccess) {
                    Thread.sleep(2000)
                    adapter.cancelDiscovery() // Re-cancelar por seguridad
                    try {
                        runOnUiThread { appendLog("BT [Intento 2]: Fallback Reflection (createRfcommSocket, canal 1)") }
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                        socket = method.invoke(device, 1) as BluetoothSocket
                        runOnUiThread { appendLog("BT [Intento 2]: Socket Creado. Iniciando connect()...") }
                        socket.connect()
                        connectSuccess = true
                    } catch (e: Exception) {
                        val err = e.message ?: e.javaClass.simpleName
                        runOnUiThread { appendLog("BT [Intento 2] Falló: $err") }
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                // ─── Intento 3: SPP Seguro Estándar ───
                if (!connectSuccess) {
                    Thread.sleep(2000)
                    adapter.cancelDiscovery()
                    try {
                        runOnUiThread { appendLog("BT [Intento 3]: SPP Seguro (createRfcommSocketToServiceRecord)") }
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        runOnUiThread { appendLog("BT [Intento 3]: Socket Creado. Iniciando connect()...") }
                        socket.connect()
                        connectSuccess = true
                    } catch (e: Exception) {
                        val err = e.message ?: e.javaClass.simpleName
                        runOnUiThread { appendLog("BT [Intento 3] Falló: $err") }
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                // ─── Intento 4: Reflection canales 2-3 ───
                if (!connectSuccess) {
                    for (channel in 2..3) {
                        Thread.sleep(1500)
                        adapter.cancelDiscovery()
                        try {
                            runOnUiThread { appendLog("BT [Intento 4.$channel]: Reflection canal $channel") }
                            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                            socket = method.invoke(device, channel) as BluetoothSocket
                            socket.connect()
                            connectSuccess = true
                            break
                        } catch (e: Exception) {
                            val err = e.message ?: e.javaClass.simpleName
                            runOnUiThread { appendLog("BT [Intento 4.$channel] Falló: $err") }
                            try { socket?.close() } catch (_: Exception) {}
                            socket = null
                        }
                    }
                }

                // ─── Evaluar Resultado ───
                if (!connectSuccess || socket == null) {
                    runOnUiThread {
                        isConnecting = false
                        statusText = "Status: BT critical failure"
                        appendLog("BT FATAL: All 3 methods exhausted. Could not open RFCOMM channel.")
                    }
                    return@thread
                }

                bluetoothSocket = socket

                runOnUiThread {
                    isConnecting = false
                    statusText = "Status: Connected to ${device.name}"
                    appendLog("BT SUCCESS: SPP channel open. Listening...")
                }

                // Arrancar el reader solo si hubo conexión exitosa
                listenForMessages(socket)
            } catch (e: SecurityException) {
                runOnUiThread {
                    isConnecting = false
                    statusText = "Status: Permissions error"
                    appendLog("Permissions error: ${e.message}")
                }
            } catch (e: IOException) {
                runOnUiThread {
                    isConnecting = false
                    statusText = "Status: Connection error"
                    appendLog("Could not connect: ${e.message}")
                }
                safeCloseSocket()
            } catch (e: Exception) {
                runOnUiThread {
                    isConnecting = false
                    statusText = "Status: Unexpected error"
                    appendLog("Unexpected error: ${e.message}")
                }
                safeCloseSocket()
            }
        }
    }

    private fun listenForMessages(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            // Loop se detiene si se interrumpe el hilo o la capa base BT corta conexión
            while (!Thread.currentThread().isInterrupted) {
                // readLine bloqueará hasta recibir '/n'. 
                // Si retorna null o lanza IOException, se cayó la conexión.
                val line = reader.readLine() ?: break

                runOnUiThread {
                    appendLog("RX -> $line")

                    if (line.contains("Boton", ignoreCase = true) || line.contains("GIBBOR_PANIC_TRIGGER", ignoreCase = true)) {
                        triggerCounter++
                        appendLog("🚨 Trigger detectado.")
                    }
                }
            }

            runOnUiThread {
                statusText = "Status: Disconnected"
                appendLog("BT: Connection closed by remote device.")
            }
        } catch (e: IOException) {
            runOnUiThread {
                statusText = "Status: Disconnected"
                appendLog("BT: Read stopped (socket broken or closed).")
            }
        } finally {
            safeCloseSocket()
            runOnUiThread {
                isConnecting = false
            }
        }
    }

    /**
     * Desconexión limpia — cierra streams, socket, y espera a que el hilo muera.
     */
    private fun disconnectFromEsp32() {
        disconnectFromEsp32Internal(silent = false)
    }

    private fun disconnectFromEsp32Internal(silent: Boolean) {
        val thread = readThread
        readThread = null

        // Cerrar socket primero para desbloquear el readLine()
        safeCloseSocket()

        // Esperar a que el hilo termine (max 2s)
        thread?.let {
            it.interrupt()
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
                // OK
            }
        }

        isConnecting = false
        statusText = "Status: Disconnected"
        if (!silent) {
            appendLog("Manually disconnected.")
        }
    }

    private fun safeCloseSocket() {
        val socket = bluetoothSocket
        bluetoothSocket = null

        if (socket == null) return

        try {
            // Cerrar streams explícitamente antes del socket
            try { socket.inputStream?.close() } catch (_: IOException) {}
            try { socket.outputStream?.close() } catch (_: IOException) {}
            socket.close()
        } catch (_: IOException) {
            // Ya cerrado
        }
    }

    // ─── Audio Recording ──────────────────────────────────────────────────

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording(incidentId: String) {
        if (!hasAudioPermission()) {
            appendLog("AUDIO: Missing RECORD_AUDIO permission")
            return
        }

        // Detener grabación previa si existe
        stopAudioRecording()

        val audioDir = File(filesDir, "evidence")
        audioDir.mkdirs()
        val audioFile = File(audioDir, "audio_${incidentId}.m4a")
        currentAudioFile = audioFile
        lastIncidentId = incidentId

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            appendLog("AUDIO: Recording -> ${audioFile.name}")

            // Iniciar grabación de video en paralelo (ForegroundService con CameraX)
            if (hasCameraPermission()) {
                val svcIntent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_INCIDENT_ID, incidentId)
                }
                ContextCompat.startForegroundService(this, svcIntent)
                appendLog("VIDEO: Starting video recording...")
            } else {
                appendLog("VIDEO: No CAMERA permission — audio only")
            }
        } catch (e: Exception) {
            appendLog("AUDIO ERROR: ${e.message}")
            isRecording = false
            mediaRecorder = null
        }
    }

    private fun stopAudioRecording(): String? {
        val recorder = mediaRecorder ?: return null
        val file = currentAudioFile

        try {
            recorder.stop()
            recorder.release()
        } catch (e: Exception) {
            appendLog("AUDIO: Error stopping: ${e.message}")
        }

        mediaRecorder = null
        isRecording = false

        // Detener grabación de video
        stopService(Intent(this, RecordingService::class.java))

        if (file != null && file.exists() && file.length() > 0) {
            val hash = sha256File(file)
            appendLog("AUDIO: Recording stopped. File: ${file.name}")
            appendLog("AUDIO: Size: ${file.length()} bytes")
            appendLog("AUDIO: SHA-256: $hash")
            return hash
        }

        appendLog("AUDIO: File empty or not found.")
        return null
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ─── Location ─────────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private suspend fun getCurrentPhoneLocation(): Location = suspendCancellableCoroutine { cont ->
        if (!hasLocationPermission()) {
            cont.resumeWithException(SecurityException("Falta ACCESS_FINE_LOCATION"))
            return@suspendCancellableCoroutine
        }

        fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    cont.resumeWithException(
                        IllegalStateException("Could not get location. Enable GPS and try again.")
                    )
                } else {
                    cont.resume(location)
                }
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    // ─── Incident draft ───────────────────────────────────────────────────

    private fun toE7(value: Double): Int {
        return (value * 10_000_000.0).toInt()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildIncidentDraft(location: Location): IncidentDraft {
        val incidentId = "inc-${System.currentTimeMillis()}"
        val timestamp = System.currentTimeMillis() / 1000L
        val latE7 = toE7(location.latitude)
        val lonE7 = toE7(location.longitude)
        val initialHash = sha256("$incidentId|$timestamp|$latE7|$lonE7")

        return IncidentDraft(
            incidentId = incidentId,
            timestamp = timestamp,
            latE7 = latE7,
            lonE7 = lonE7,
            initialHash = initialHash
        )
    }

    // ─── Backend HTTP calls (reemplazo de Crossmint directo) ──────────────

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = try {
            connection.inputStream
        } catch (_: Exception) {
            connection.errorStream
        }

        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    /**
     * POST /api/incident/:incidentId/evidence
     * Ancla el hash del archivo de evidencia en el contrato Soroban.
     * Retorna true si se ancló correctamente.
     */
    private suspend fun sendEvidenceToBackend(
        backendUrl: String,
        incidentId: String,
        mediaType: String,
        mediaHash: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("mediaType", mediaType)
                .put("mediaHash", mediaHash)

            val connection = (URL("$backendUrl/api/incident/$incidentId/evidence")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 60000
                doInput = true
                doOutput = true
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            val raw  = readResponseBody(connection)
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }

            val ok = code in 200..299 && json.optBoolean("success", false)
            if (ok) {
                val txHash = json.optString("txHash", "")
                appendLog("EVIDENCE [$mediaType]: TX=$txHash")
            } else {
                appendLog("EVIDENCE [$mediaType] ERROR: ${json.optString("error", "HTTP $code")}")
            }
            ok
        } catch (e: Exception) {
            appendLog("EVIDENCE [$mediaType] EXCEPTION: ${e.message}")
            false
        }
    }

    /**
     * Envía el incidente al BACKEND (no a Crossmint directamente).
     * El backend usa la server-side key para crear wallet + transacción.
     */
    private suspend fun sendIncidentToBackend(
        backendUrl: String,
        email: String,
        draft: IncidentDraft
    ): BackendResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email)
            .put("incidentId", draft.incidentId)
            .put("timestamp", draft.timestamp)
            .put("latE7", draft.latE7)
            .put("lonE7", draft.lonE7)
            .put("initialHash", draft.initialHash)

        val connection = (URL("$backendUrl/api/incident").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 60000  // 60s — el backend puede tardar en crear wallet + tx
            doInput = true
            doOutput = true
        }

        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
        }

        val raw = readResponseBody(connection)
        val code = connection.responseCode

        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().put("raw", raw)
        }

        if (code !in 200..299) {
            val errorMsg = json.optString("error", "HTTP $code")
            BackendResult(
                success = false,
                status = "error",
                txId = "",
                txHash = "",
                explorerLink = "",
                error = errorMsg,
                rawJson = json.toString(2)
            )
        } else {
            BackendResult(
                success = json.optBoolean("success", false),
                status = json.optString("status", "unknown"),
                txId = json.optString("txId", ""),
                txHash = json.optString("txHash", ""),
                explorerLink = json.optString("explorerLink", ""),
                error = json.optString("error", ""),
                rawJson = json.toString(2)
            )
        }
    }

    /**
     * Polling del estado de TX al backend.
     */
    private suspend fun pollBackendStatus(
        backendUrl: String,
        email: String,
        txId: String,
        maxAttempts: Int = 10,
        delayMs: Long = 3000L
    ): BackendResult = withContext(Dispatchers.IO) {
        var lastResult: BackendResult? = null

        repeat(maxAttempts) { attempt ->
            delay(delayMs)

            try {
                val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
                val encodedTxId = java.net.URLEncoder.encode(txId, "UTF-8")
                val url = "$backendUrl/api/incident/$encodedTxId?email=$encodedEmail"

                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doInput = true
                }

                val raw = readResponseBody(connection)
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }

                lastResult = BackendResult(
                    success = json.optBoolean("success", false),
                    status = json.optString("status", "unknown"),
                    txId = json.optString("txId", txId),
                    txHash = json.optString("txHash", ""),
                    explorerLink = json.optString("explorerLink", ""),
                    error = json.optString("error", ""),
                    rawJson = json.toString(2)
                )

                if (lastResult!!.status in listOf("success", "failed")) {
                    return@withContext lastResult!!
                }
            } catch (e: Exception) {
                lastResult = BackendResult(
                    success = false,
                    status = "polling-error",
                    txId = txId,
                    txHash = "",
                    explorerLink = "",
                    error = "Polling attempt ${attempt + 1}: ${e.message}",
                    rawJson = ""
                )
            }
        }

        lastResult ?: BackendResult(
            success = false,
            status = "timeout",
            txId = txId,
            txHash = "",
            explorerLink = "",
            error = "Polling timeout after $maxAttempts attempts",
            rawJson = ""
        )
    }

    // ─── UI ───────────────────────────────────────────────────────────────

    @Composable
    private fun AppScreen() {
        // Valor por defecto desde local.properties (BACKEND_URL)
        // Emulador: 10.0.2.2:3001 | Dispositivo físico: IP de la laptop en WiFi
        // Producción: https://api.tudominio.com
        var backendUrl by remember { mutableStateOf(BuildConfig.BACKEND_URL) }
        var email by remember { mutableStateOf("") }
        var authStatus by remember { mutableStateOf("No autenticado") }
        var busy by remember { mutableStateOf(false) }
        var lastHandledTrigger by remember { mutableIntStateOf(0) }
        var incidentPreview by remember { mutableStateOf("Ninguno") }
        var onChainStatus by remember { mutableStateOf("") }

        /**
         * Flujo completo: ubicación → draft → backend → on-chain
         * authStatus se usa SOLO para display. isAuthenticated controla el flujo.
         */
        suspend fun createAndSendIncident(source: String) {
            if (!hasAudioPermission() || !hasCameraPermission()) {
                appendLog("$source -> Missing recording permissions. Grant them and try again.")
                requestBluetoothPermissions()
                return
            }

            if (!hasLocationPermission()) {
                appendLog("$source -> missing location permission")
                requestLocationPermission()
                return
            }

            // --- Paso 1: Generar incidente local ---
            val location = getCurrentPhoneLocation()
            val draft = buildIncidentDraft(location)

            incidentPreview =
                "id=${draft.incidentId}\n" +
                        "timestamp=${draft.timestamp}\n" +
                        "lat_e7=${draft.latE7}\n" +
                        "lon_e7=${draft.lonE7}\n" +
                        "hash=${draft.initialHash}"

            appendLog("$source -> incident_id=${draft.incidentId}")
            appendLog("$source -> lat_e7=${draft.latE7}, lon_e7=${draft.lonE7}")
            appendLog("$source -> hash=${draft.initialHash}")

            // --- Paso 2: Enviar al backend ---
            authStatus = "Sending to backend..."
            onChainStatus = "Sending..."
            appendLog("$source -> Sending incident to $backendUrl...")

            try {
                val result = sendIncidentToBackend(backendUrl, authenticatedEmail, draft)

                appendLog("$source -> Backend response: success=${result.success}")
                appendLog("$source -> status=${result.status}")
                appendLog("$source -> txId=${result.txId}")
                appendLog("$source -> txHash=${result.txHash}")
                appendLog("$source -> explorer=${result.explorerLink}")

                if (!result.success) {
                    onChainStatus = "Error: ${result.error}"
                    authStatus = "Backend error"
                    appendLog("$source -> BACKEND ERROR: ${result.error}")
                    appendLog("$source -> Raw: ${result.rawJson}")
                    return
                }

                // --- Paso 3: Polling si está pendiente ---
                var finalResult = result
                if (result.status in listOf("pending", "awaiting-approval")) {
                    appendLog("$source -> TX pending, polling backend...")
                    onChainStatus = "TX pending, waiting for confirmation..."
                    finalResult = pollBackendStatus(backendUrl, authenticatedEmail, result.txId)
                    appendLog("$source -> Polling final: status=${finalResult.status}")
                }

                // --- Paso 4: Resultado final ---
                val emoji = when (finalResult.status) {
                    "success" -> "✅"
                    "failed" -> "❌"
                    else -> "⏳"
                }
                onChainStatus =
                    "$emoji Status: ${finalResult.status}\n" +
                    "TX: ${finalResult.txHash}\n" +
                    "Explorer: ${finalResult.explorerLink}"

                authStatus = when (finalResult.status) {
                    "success" -> "Incident registered on-chain"
                    "failed" -> "TX failed on-chain"
                    else -> "TX: ${finalResult.status}"
                }
                // ⚠️ isAuthenticated NO se toca — sigue siendo true

                // --- Paso 5: Iniciar grabación de audio automáticamente ---
                if (finalResult.status == "success") {
                    appendLog("$source -> TX confirmed. Starting evidence collection...")
                    lastBackendUrl = backendUrl
                    lastIncidentForEvidence = draft.incidentId
                    startAudioRecording(draft.incidentId)
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                onChainStatus = "❌ Error: $errorMsg"
                authStatus = "❌ Error: $errorMsg"
                appendLog("$source -> EXCEPTION: $errorMsg")
                // ⚠️ isAuthenticated NO se toca — sigue siendo true
            }
        }

        // ─── Trigger ESP32 ────────────────────────────────────────────────

        LaunchedEffect(triggerCounter) {
            if (triggerCounter <= 0 || triggerCounter == lastHandledTrigger) return@LaunchedEffect

            lastHandledTrigger = triggerCounter

            // Usa isAuthenticated (boolean), no authStatus (string de display)
            if (!isAuthenticated) {
                appendLog("ESP32 -> authenticate first")
                return@LaunchedEffect
            }

            busy = true
            try {
                createAndSendIncident("ESP32")
            } catch (e: Exception) {
                appendLog("ESP32 -> error: ${e.message}")
                // isAuthenticated NO se toca
            } finally {
                busy = false
            }
        }

        // ─── UI State ─────────────────────────────────────────────────────

        var otpCode         by remember { mutableStateOf("") }
        var otpRequested    by remember { mutableStateOf(false) }
        var configExpanded  by remember { mutableStateOf(false) }
        var btExpanded      by remember { mutableStateOf(false) }
        var logExpanded     by remember { mutableStateOf(false) }

        val scrollState = rememberScrollState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = GibborBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(scrollState)
            ) {

                Spacer(Modifier.height(48.dp))

                // ── Header ──────────────────────────────────────────────

                Text(
                    "GIBBOR",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 10.sp,
                    color = GibborNavy
                )
                Text(
                    "EMERGENCY SYSTEM",
                    fontSize = 10.sp,
                    letterSpacing = 3.sp,
                    color = GibborMid,
                    fontWeight = FontWeight.Normal
                )

                Spacer(Modifier.height(36.dp))

                // ── Autenticacion ────────────────────────────────────

                if (!isAuthenticated) {

                    GibborCard {
                        GibborSectionLabel("Access")
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                otpRequested = false
                                otpCode = ""
                            },
                            label = { Text("Institutional email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )

                        Spacer(Modifier.height(12.dp))

                        GibborPrimaryButton(
                            text = "Get wallet",
                            onClick = {
                                val code = (100000..999999).random().toString()
                                otpCode = code
                                otpRequested = true
                                appendLog("OTP generated for ${email.trim()}")
                            },
                            enabled = email.isNotBlank() && !otpRequested
                        )

                        if (otpRequested) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = otpCode,
                                onValueChange = {},
                                label = { Text("Verification code (6 digits)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                readOnly = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(Modifier.height(12.dp))
                            GibborPrimaryButton(
                                text = "Sign in",
                                onClick = {
                                    val norm = email.trim()
                                        .replace("[^a-zA-Z0-9@._-]".toRegex(), "")
                                        .lowercase()
                                    if (norm.isNotBlank()) {
                                        isAuthenticated = true
                                        authenticatedEmail = norm
                                        authStatus = "Session started"
                                        appendLog("Auth: session started as $norm")
                                    }
                                },
                                enabled = otpRequested && email.isNotBlank()
                            )
                        }
                    }

                } else {

                    // ── Sesion activa ──────────────────────────────────

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "ACTIVE SESSION",
                                fontSize = 10.sp,
                                letterSpacing = 1.5.sp,
                                color = GibborMid
                            )
                            Text(
                                authenticatedEmail,
                                fontSize = 13.sp,
                                color = GibborNavy,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(GibborGreen)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // ── Accion principal ───────────────────────────────

                    GibborPrimaryButton(
                        text = if (busy) "Processing..." else "CREATE INCIDENT",
                        onClick = {
                            CoroutineScope(Dispatchers.Main).launch {
                                busy = true
                                try { createAndSendIncident("MANUAL") }
                                catch (e: Exception) { appendLog("MANUAL -> error: ${e.message}") }
                                finally { busy = false }
                            }
                        },
                        enabled = !busy
                    )
                }

                // ── Grabacion activa ──────────────────────────────────

                if (isRecording) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = GibborRedLight),
                        border = BorderStroke(1.dp, GibborRedBorder)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(GibborRed)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "RECORDING EVIDENCE",
                                    fontSize = 11.sp,
                                    letterSpacing = 1.5.sp,
                                    color = GibborRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            GibborDangerButton(
                                text = "STOP RECORDING",
                                onClick = {
                                    val hash = stopAudioRecording()
                                    if (hash != null && lastBackendUrl.isNotEmpty() && lastIncidentForEvidence.isNotEmpty()) {
                                        appendLog("AUDIO: Anchoring hash on blockchain...")
                                        CoroutineScope(Dispatchers.Main).launch {
                                            val ok = sendEvidenceToBackend(lastBackendUrl, lastIncidentForEvidence, "audio", hash)
                                            if (ok) appendLog("AUDIO: Hash anchored on blockchain")
                                            else    appendLog("AUDIO: Error anchoring hash on blockchain")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // ── Estado on-chain ───────────────────────────────────

                if (onChainStatus.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    GibborCard {
                        GibborSectionLabel("Transaction status")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            onChainStatus
                                .replace("✅", "").replace("❌", "").replace("⏳", "").trim(),
                            fontSize = 12.sp,
                            color = GibborDark,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // ── Ultimo incidente ──────────────────────────────────

                if (isAuthenticated && incidentPreview != "Ninguno") {
                    Spacer(Modifier.height(16.dp))
                    GibborCard {
                        GibborSectionLabel("Last incident")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            incidentPreview,
                            fontSize = 11.sp,
                            color = GibborDark,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                // ── Menu: Configuracion ───────────────────────────────

                GibborCollapsible(
                    title = "Configuration",
                    expanded = configExpanded,
                    onToggle = { configExpanded = !configExpanded }
                ) {
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text("Backend URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // ── Menu: Bluetooth ───────────────────────────────────

                GibborCollapsible(
                    title = "Bluetooth",
                    expanded = btExpanded,
                    onToggle = { btExpanded = !btExpanded }
                ) {
                    Text(
                        statusText,
                        fontSize = 12.sp,
                        color = GibborMid
                    )
                    Spacer(Modifier.height(12.dp))
                    GibborOutlinedButton(
                        text = "Enable Bluetooth",
                        onClick = { ensureBluetoothEnabled() }
                    )
                    Spacer(Modifier.height(8.dp))
                    GibborOutlinedButton(
                        text = if (isConnecting) "Connecting..." else "Gibby Button",
                        onClick = { connectToEsp32() },
                        enabled = !isConnecting
                    )
                    Spacer(Modifier.height(8.dp))
                    GibborOutlinedButton(
                        text = "Disconnect",
                        onClick = { disconnectFromEsp32() }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // ── Menu: Registro ────────────────────────────────────

                GibborCollapsible(
                    title = "Activity log",
                    expanded = logExpanded,
                    onToggle = { logExpanded = !logExpanded }
                ) {
                    Text(
                        logText,
                        fontSize = 11.sp,
                        color = GibborCharcoal,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }

    // ─── Design System Components ─────────────────────────────────────────

    @Composable
    private fun GibborCard(content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GibborSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) { content() }
        }
    }

    @Composable
    private fun GibborSectionLabel(text: String) {
        Text(
            text.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Medium,
            color = GibborMid
        )
    }

    @Composable
    private fun GibborPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GibborNavy,
                disabledContainerColor = GibborBorder
            )
        ) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        }
    }

    @Composable
    private fun GibborDangerButton(text: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GibborRed)
        ) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        }
    }

    @Composable
    private fun GibborOutlinedButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, GibborBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GibborNavy)
        ) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp)
        }
    }

    @Composable
    private fun GibborCollapsible(
        title: String,
        expanded: Boolean,
        onToggle: () -> Unit,
        content: @Composable () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title.uppercase(),
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = GibborLight
                )
                Text(if (expanded) "—" else "+", fontSize = 16.sp, color = GibborLight)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(GibborBorder)
            )
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun OTPDialog(
    onOTPSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var otpCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter OTP Code") },
        text = {
            OutlinedTextField(
                value = otpCode,
                onValueChange = { otpCode = it },
                label = { Text("OTP") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onOTPSubmit(otpCode) }) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}