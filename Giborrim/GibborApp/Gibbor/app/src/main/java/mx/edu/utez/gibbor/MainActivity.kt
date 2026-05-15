package mx.edu.utez.gibbor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.crossmint.kotlin.compose.CrossmintSDKProvider
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.edu.utez.gibbor.application.usecase.AuthenticateUserUseCaseImpl
import mx.edu.utez.gibbor.application.usecase.CreateIncidentUseCaseImpl
import mx.edu.utez.gibbor.application.usecase.RecordEvidenceUseCaseImpl
import mx.edu.utez.gibbor.domain.port.input.AuthenticateUserUseCase
import mx.edu.utez.gibbor.domain.port.input.CreateIncidentUseCase
import mx.edu.utez.gibbor.domain.port.input.RecordEvidenceUseCase
import mx.edu.utez.gibbor.domain.port.output.BluetoothConnector
import mx.edu.utez.gibbor.infrastructure.adapter.outbound.backend.BackendApiAdapterImpl
import mx.edu.utez.gibbor.infrastructure.adapter.outbound.bluetooth.BluetoothAdapterImpl
import mx.edu.utez.gibbor.infrastructure.adapter.outbound.location.LocationAdapterImpl
import mx.edu.utez.gibbor.infrastructure.adapter.outbound.recording.AudioRecordingAdapterImpl
import mx.edu.utez.gibbor.infrastructure.adapter.outbound.recording.VideoRecordingAdapterImpl
import mx.edu.utez.gibbor.infrastructure.service.RecordingService
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // ─── Ports (inyectados como adaptadores de infraestructura) ───────────

    private lateinit var bluetoothConnector: BluetoothConnector
    private lateinit var createIncidentUseCase: CreateIncidentUseCase
    private lateinit var recordEvidenceUseCase: RecordEvidenceUseCase
    private lateinit var authenticateUserUseCase: AuthenticateUserUseCase

    // ─── UI state ─────────────────────────────────────────────────────────

    private var statusText by mutableStateOf("Status: Disconnected")
    private var logText by mutableStateOf("Esperando...\n")
    private var triggerCounter by mutableIntStateOf(0)
    private var isRecording by mutableStateOf(false)

    // ─── Session state ────────────────────────────────────────────────────

    @Volatile private var isAuthenticated = false
    private var authenticatedEmail = ""
    @Volatile private var lastBackendUrl: String = ""
    @Volatile private var lastIncidentForEvidence: String = ""

    // ─── Video recording broadcast receiver ──────────────────────────────

    private val recordingDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id   = intent?.getStringExtra(RecordingService.EXTRA_INCIDENT_ID) ?: return
            val path = intent.getStringExtra(RecordingService.EXTRA_FILE_PATH) ?: ""
            val hash = intent.getStringExtra(RecordingService.EXTRA_SHA256) ?: ""
            appendLog("VIDEO: Recording finished [$id]")
            appendLog("VIDEO: File: ${java.io.File(path).name}")
            appendLog("VIDEO: SHA-256: $hash")

            if (hash.isNotEmpty() && lastBackendUrl.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val backendAdapter = BackendApiAdapterImpl(lastBackendUrl)
                    val result = backendAdapter.anchorEvidence(id, "video", hash)
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
            val btImpl = bluetoothConnector as? BluetoothAdapterImpl ?: return@registerForActivityResult
            if (btImpl.isEnabled()) {
                appendLog("Bluetooth enabled.")
                if (btImpl.pendingConnect) {
                    btImpl.pendingConnect = false
                    bluetoothConnector.connect()
                }
            } else {
                btImpl.pendingConnect = false
                appendLog("User did not enable Bluetooth.")
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val btImpl = bluetoothConnector as? BluetoothAdapterImpl ?: return@registerForActivityResult
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                appendLog("Bluetooth permissions granted.")
                if (btImpl.pendingEnable) {
                    btImpl.pendingEnable = false
                    bluetoothConnector.enable()
                } else if (btImpl.pendingConnect) {
                    btImpl.pendingConnect = false
                    bluetoothConnector.connect()
                }
            } else {
                btImpl.pendingEnable = false
                btImpl.pendingConnect = false
                appendLog("Bluetooth permissions denied: ${denied.joinToString()}")
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) appendLog("Location permission granted.")
            else         appendLog("Location permission denied.")
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val nativeAdapter = bluetoothManager.adapter

        if (nativeAdapter == null) {
            statusText = "Status: This device does not support Bluetooth"
            appendLog("Bluetooth not supported on this device.")
        }

        // ── Instanciar adaptadores de infraestructura ─────────────────────
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationAdapter     = LocationAdapterImpl(this, fusedLocationClient)
        val audioRecordingAdapter = AudioRecordingAdapterImpl(this)
        val videoRecordingAdapter = VideoRecordingAdapterImpl(this)
        val backendApiAdapter     = BackendApiAdapterImpl(BuildConfig.BACKEND_URL)

        bluetoothConnector = BluetoothAdapterImpl(
            context          = this,
            nativeAdapter    = nativeAdapter,
            onPermissionsRequired = { requestBluetoothPermissions() },
            onEnableRequired = {
                enableBluetoothLauncher.launch(
                    Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                )
            },
            onStatusChanged  = { statusText = it },
            onLogMessage     = { appendLog(it) },
            onTriggerDetected = { triggerCounter++ }
        )

        // ── Instanciar casos de uso ───────────────────────────────────────
        createIncidentUseCase   = CreateIncidentUseCaseImpl(locationAdapter, backendApiAdapter)
        recordEvidenceUseCase   = RecordEvidenceUseCaseImpl(audioRecordingAdapter, videoRecordingAdapter, backendApiAdapter)
        authenticateUserUseCase = AuthenticateUserUseCaseImpl()

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
        recordEvidenceUseCase.let {
            CoroutineScope(Dispatchers.Main).launch {
                it.stopAndAnchor(lastIncidentForEvidence)
            }
        }
        bluetoothConnector.disconnect()
        unregisterReceiver(recordingDoneReceiver)
    }

    // ─── Logging ──────────────────────────────────────────────────────────

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logText += "[$time] $message\n"
    }

    // ─── Permission helpers ───────────────────────────────────────────────

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

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // ─── UI ───────────────────────────────────────────────────────────────

    @Composable
    private fun AppScreen() {
        var backendUrl by remember { mutableStateOf(BuildConfig.BACKEND_URL) }
        var email by remember { mutableStateOf("") }
        var authStatus by remember { mutableStateOf("No autenticado") }
        var busy by remember { mutableStateOf(false) }
        var lastHandledTrigger by remember { mutableIntStateOf(0) }
        var incidentPreview by remember { mutableStateOf("Ninguno") }
        var onChainStatus by remember { mutableStateOf("") }

        suspend fun createAndSendIncident(source: String) {
            if (!recordEvidenceUseCase.let {
                    (it as RecordEvidenceUseCaseImpl).let { impl ->
                        impl.let { _ -> true } // permisos chequeados en los adaptadores
                    }
                }) return

            if (!(createIncidentUseCase as CreateIncidentUseCaseImpl)
                    .let { _ ->
                        val locationAdapter = LocationAdapterImpl(
                            this@MainActivity,
                            LocationServices.getFusedLocationProviderClient(this@MainActivity)
                        )
                        if (!locationAdapter.hasPermission()) {
                            appendLog("$source -> missing location permission")
                            requestLocationPermission()
                            return
                        }
                        true
                    }
            ) return

            appendLog("$source -> Creating incident...")
            authStatus = "Sending to backend..."
            onChainStatus = "Sending..."

            try {
                val (incident, result) = createIncidentUseCase.execute(authenticatedEmail)

                incidentPreview =
                    "id=${incident.incidentId}\n" +
                    "timestamp=${incident.timestamp}\n" +
                    "lat_e7=${incident.latE7}\n" +
                    "lon_e7=${incident.lonE7}\n" +
                    "hash=${incident.initialHash}"

                appendLog("$source -> incident_id=${incident.incidentId}")
                appendLog("$source -> lat_e7=${incident.latE7}, lon_e7=${incident.lonE7}")
                appendLog("$source -> hash=${incident.initialHash}")
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

                val emoji = when (result.status) {
                    "success" -> "✅"
                    "failed"  -> "❌"
                    else      -> "⏳"
                }
                onChainStatus =
                    "$emoji Status: ${result.status}\n" +
                    "TX: ${result.txHash}\n" +
                    "Explorer: ${result.explorerLink}"

                authStatus = when (result.status) {
                    "success" -> "Incident registered on-chain"
                    "failed"  -> "TX failed on-chain"
                    else      -> "TX: ${result.status}"
                }

                if (result.status == "success") {
                    appendLog("$source -> TX confirmed. Starting evidence collection...")
                    lastBackendUrl = backendUrl
                    lastIncidentForEvidence = incident.incidentId
                    recordEvidenceUseCase.startRecording(incident.incidentId)
                    isRecording = true
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                onChainStatus = "❌ Error: $errorMsg"
                authStatus = "❌ Error: $errorMsg"
                appendLog("$source -> EXCEPTION: $errorMsg")
            }
        }

        // ─── Trigger ESP32 ────────────────────────────────────────────────

        LaunchedEffect(triggerCounter) {
            if (triggerCounter <= 0 || triggerCounter == lastHandledTrigger) return@LaunchedEffect
            lastHandledTrigger = triggerCounter
            if (!isAuthenticated) { appendLog("ESP32 -> authenticate first"); return@LaunchedEffect }
            busy = true
            try { createAndSendIncident("ESP32") }
            catch (e: Exception) { appendLog("ESP32 -> error: ${e.message}") }
            finally { busy = false }
        }

        // ─── UI State ─────────────────────────────────────────────────────

        var otpCode        by remember { mutableStateOf("") }
        var otpRequested   by remember { mutableStateOf(false) }
        var configExpanded by remember { mutableStateOf(false) }
        var btExpanded     by remember { mutableStateOf(false) }
        var logExpanded    by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()

        Surface(modifier = Modifier.fillMaxSize(), color = GibborBg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(Modifier.height(48.dp))

                // ── Header ──────────────────────────────────────────────
                Text("GIBBOR", fontSize = 32.sp, fontWeight = FontWeight.Light, letterSpacing = 10.sp, color = GibborNavy)
                Text("EMERGENCY SYSTEM", fontSize = 10.sp, letterSpacing = 3.sp, color = GibborMid, fontWeight = FontWeight.Normal)
                Spacer(Modifier.height(36.dp))

                // ── Autenticacion ────────────────────────────────────
                if (!isAuthenticated) {
                    GibborCard {
                        GibborSectionLabel("Access")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; otpRequested = false; otpCode = "" },
                            label = { Text("Institutional email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )
                        Spacer(Modifier.height(12.dp))
                        GibborPrimaryButton(
                            text = "Get wallet",
                            onClick = {
                                otpCode = authenticateUserUseCase.generateOtp(email.trim())
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
                                    val user = authenticateUserUseCase.authenticate(email)
                                    if (user != null) {
                                        isAuthenticated = true
                                        authenticatedEmail = user.email
                                        authStatus = "Session started"
                                        appendLog("Auth: session started as ${user.email}")
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
                            Text("ACTIVE SESSION", fontSize = 10.sp, letterSpacing = 1.5.sp, color = GibborMid)
                            Text(authenticatedEmail, fontSize = 13.sp, color = GibborNavy, fontWeight = FontWeight.Medium)
                        }
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(GibborGreen))
                    }
                    Spacer(Modifier.height(24.dp))
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
                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(GibborRed))
                                Spacer(Modifier.width(8.dp))
                                Text("RECORDING EVIDENCE", fontSize = 11.sp, letterSpacing = 1.5.sp, color = GibborRed, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(14.dp))
                            GibborDangerButton(
                                text = "STOP RECORDING",
                                onClick = {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        val ok = recordEvidenceUseCase.stopAndAnchor(lastIncidentForEvidence)
                                        isRecording = false
                                        if (ok) appendLog("AUDIO: Hash anchored on blockchain")
                                        else    appendLog("AUDIO: Error anchoring hash on blockchain")
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
                            onChainStatus.replace("✅", "").replace("❌", "").replace("⏳", "").trim(),
                            fontSize = 12.sp, color = GibborDark, lineHeight = 18.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // ── Ultimo incidente ──────────────────────────────────
                if (isAuthenticated && incidentPreview != "Ninguno") {
                    Spacer(Modifier.height(16.dp))
                    GibborCard {
                        GibborSectionLabel("Last incident")
                        Spacer(Modifier.height(8.dp))
                        Text(incidentPreview, fontSize = 11.sp, color = GibborDark, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                Spacer(Modifier.height(28.dp))

                // ── Menu: Configuracion ───────────────────────────────
                GibborCollapsible(title = "Configuration", expanded = configExpanded, onToggle = { configExpanded = !configExpanded }) {
                    OutlinedTextField(value = backendUrl, onValueChange = { backendUrl = it }, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                }

                // ── Menu: Bluetooth ───────────────────────────────────
                GibborCollapsible(title = "Bluetooth", expanded = btExpanded, onToggle = { btExpanded = !btExpanded }) {
                    Text(statusText, fontSize = 12.sp, color = GibborMid)
                    Spacer(Modifier.height(12.dp))
                    GibborOutlinedButton(text = "Enable Bluetooth", onClick = { bluetoothConnector.enable() })
                    Spacer(Modifier.height(8.dp))
                    GibborOutlinedButton(
                        text = if (bluetoothConnector.isConnecting) "Connecting..." else "Gibby Button",
                        onClick = { bluetoothConnector.connect() },
                        enabled = !bluetoothConnector.isConnecting
                    )
                    Spacer(Modifier.height(8.dp))
                    GibborOutlinedButton(text = "Disconnect", onClick = { bluetoothConnector.disconnect() })
                    Spacer(Modifier.height(8.dp))
                }

                // ── Menu: Registro ────────────────────────────────────
                GibborCollapsible(title = "Activity log", expanded = logExpanded, onToggle = { logExpanded = !logExpanded }) {
                    Text(logText, fontSize = 11.sp, color = GibborCharcoal, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
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
        Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, color = GibborMid)
    }

    @Composable
    private fun GibborPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GibborNavy, disabledContainerColor = GibborBorder)
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
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title.uppercase(), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, color = GibborLight)
                Text(if (expanded) "—" else "+", fontSize = 16.sp, color = GibborLight)
            }
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(GibborBorder))
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) { content() }
            }
        }
    }
}

@Composable
fun OTPDialog(onOTPSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var otpCode by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter OTP Code") },
        text = {
            OutlinedTextField(value = otpCode, onValueChange = { otpCode = it }, label = { Text("OTP") }, singleLine = true)
        },
        confirmButton = { Button(onClick = { onOTPSubmit(otpCode) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
