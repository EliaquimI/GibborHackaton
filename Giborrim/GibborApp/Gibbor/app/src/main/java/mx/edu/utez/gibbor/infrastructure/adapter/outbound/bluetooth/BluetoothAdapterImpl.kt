package mx.edu.utez.gibbor.infrastructure.adapter.outbound.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import mx.edu.utez.gibbor.domain.port.output.BluetoothConnector
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothAdapterImpl(
    private val context: Context,
    private val nativeAdapter: BluetoothAdapter?,
    private val onPermissionsRequired: () -> Unit,
    private val onEnableRequired: () -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onLogMessage: (String) -> Unit,
    private val onTriggerDetected: () -> Unit
) : BluetoothConnector {

    companion object {
        private const val DEVICE_NAME = "GIBBOR_ESP32"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var readThread: Thread? = null
    @Volatile override var isConnecting: Boolean = false
        private set

    var pendingConnect = false
    var pendingEnable = false

    override fun isAvailable(): Boolean = nativeAdapter != null

    override fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val hasLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            hasConnect && hasScan && hasLocation
        } else {
            true
        }
    }

    override fun isEnabled(): Boolean = nativeAdapter?.isEnabled == true

    override fun enable() {
        if (nativeAdapter == null) { onLogMessage("No Bluetooth adapter."); return }
        if (nativeAdapter.isEnabled) { onLogMessage("Bluetooth already enabled."); return }
        if (!hasPermissions()) {
            pendingEnable = true
            onPermissionsRequired()
            return
        }
        onEnableRequired()
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        if (isConnecting) { onLogMessage("Connection already in progress."); return }

        val adapter = nativeAdapter ?: run { onLogMessage("No Bluetooth adapter available."); return }

        if (!hasPermissions()) {
            pendingConnect = true
            onPermissionsRequired()
            return
        }

        if (!adapter.isEnabled) {
            pendingConnect = true
            enable()
            return
        }

        val device = adapter.bondedDevices.firstOrNull { it.name == DEVICE_NAME }
        if (device == null) {
            onLogMessage("'$DEVICE_NAME' not found among paired devices.")
            onLogMessage("Pair the ESP32 first from Settings > Bluetooth.")
            return
        }

        disconnectInternal(silent = true)
        isConnecting = true
        onLogMessage("BT INIT: Target=${device.name}, MAC=${device.address}, BondState=${device.bondState}")

        readThread = thread(start = true) {
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    mainHandler.post {
                        isConnecting = false
                        onStatusChanged("Status: Not paired")
                        onLogMessage("BT ERROR: Device NOT paired (bondState=${device.bondState}). Aborting.")
                    }
                    return@thread
                }

                adapter.cancelDiscovery()
                mainHandler.post { onLogMessage("BT: Discovery cancelled. Purging stack...") }
                Thread.sleep(1500)

                var socket: BluetoothSocket? = null
                var connectSuccess = false

                // Intento 1: SPP Inseguro
                try {
                    mainHandler.post { onLogMessage("BT [Intento 1]: SPP Inseguro (createInsecureRfcommSocketToServiceRecord)") }
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    mainHandler.post { onLogMessage("BT [Intento 1]: Socket Creado. Iniciando connect()...") }
                    socket.connect()
                    connectSuccess = true
                } catch (e: Exception) {
                    mainHandler.post { onLogMessage("BT [Intento 1] Falló: ${e.message ?: e.javaClass.simpleName}") }
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }

                // Intento 2: Reflection Fallback (Puerto 1)
                if (!connectSuccess) {
                    Thread.sleep(2000); adapter.cancelDiscovery()
                    try {
                        mainHandler.post { onLogMessage("BT [Intento 2]: Fallback Reflection (createRfcommSocket, canal 1)") }
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                        socket = method.invoke(device, 1) as BluetoothSocket
                        mainHandler.post { onLogMessage("BT [Intento 2]: Socket Creado. Iniciando connect()...") }
                        socket.connect(); connectSuccess = true
                    } catch (e: Exception) {
                        mainHandler.post { onLogMessage("BT [Intento 2] Falló: ${e.message ?: e.javaClass.simpleName}") }
                        try { socket?.close() } catch (_: Exception) {}; socket = null
                    }
                }

                // Intento 3: SPP Seguro Estándar
                if (!connectSuccess) {
                    Thread.sleep(2000); adapter.cancelDiscovery()
                    try {
                        mainHandler.post { onLogMessage("BT [Intento 3]: SPP Seguro (createRfcommSocketToServiceRecord)") }
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        mainHandler.post { onLogMessage("BT [Intento 3]: Socket Creado. Iniciando connect()...") }
                        socket.connect(); connectSuccess = true
                    } catch (e: Exception) {
                        mainHandler.post { onLogMessage("BT [Intento 3] Falló: ${e.message ?: e.javaClass.simpleName}") }
                        try { socket?.close() } catch (_: Exception) {}; socket = null
                    }
                }

                // Intento 4: Reflection canales 2-3
                if (!connectSuccess) {
                    for (channel in 2..3) {
                        Thread.sleep(1500); adapter.cancelDiscovery()
                        try {
                            mainHandler.post { onLogMessage("BT [Intento 4.$channel]: Reflection canal $channel") }
                            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                            socket = method.invoke(device, channel) as BluetoothSocket
                            socket.connect(); connectSuccess = true; break
                        } catch (e: Exception) {
                            mainHandler.post { onLogMessage("BT [Intento 4.$channel] Falló: ${e.message ?: e.javaClass.simpleName}") }
                            try { socket?.close() } catch (_: Exception) {}; socket = null
                        }
                    }
                }

                if (!connectSuccess || socket == null) {
                    mainHandler.post {
                        isConnecting = false
                        onStatusChanged("Status: BT critical failure")
                        onLogMessage("BT FATAL: All 3 methods exhausted. Could not open RFCOMM channel.")
                    }
                    return@thread
                }

                bluetoothSocket = socket
                mainHandler.post {
                    isConnecting = false
                    onStatusChanged("Status: Connected to ${device.name}")
                    onLogMessage("BT SUCCESS: SPP channel open. Listening...")
                }
                listenForMessages(socket)

            } catch (e: SecurityException) {
                mainHandler.post {
                    isConnecting = false
                    onStatusChanged("Status: Permissions error")
                    onLogMessage("Permissions error: ${e.message}")
                }
            } catch (e: IOException) {
                mainHandler.post {
                    isConnecting = false
                    onStatusChanged("Status: Connection error")
                    onLogMessage("Could not connect: ${e.message}")
                }
                safeCloseSocket()
            } catch (e: Exception) {
                mainHandler.post {
                    isConnecting = false
                    onStatusChanged("Status: Unexpected error")
                    onLogMessage("Unexpected error: ${e.message}")
                }
                safeCloseSocket()
            }
        }
    }

    private fun listenForMessages(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            while (!Thread.currentThread().isInterrupted) {
                val line = reader.readLine() ?: break
                mainHandler.post {
                    onLogMessage("RX -> $line")
                    if (line.contains("Boton", ignoreCase = true) ||
                        line.contains("GIBBOR_PANIC_TRIGGER", ignoreCase = true)) {
                        onTriggerDetected()
                        onLogMessage("🚨 Trigger detectado.")
                    }
                }
            }
            mainHandler.post {
                onStatusChanged("Status: Disconnected")
                onLogMessage("BT: Connection closed by remote device.")
            }
        } catch (e: IOException) {
            mainHandler.post {
                onStatusChanged("Status: Disconnected")
                onLogMessage("BT: Read stopped (socket broken or closed).")
            }
        } finally {
            safeCloseSocket()
            mainHandler.post { isConnecting = false }
        }
    }

    override fun disconnect() = disconnectInternal(silent = false)

    private fun disconnectInternal(silent: Boolean) {
        val thread = readThread; readThread = null
        safeCloseSocket()
        thread?.let {
            it.interrupt()
            try { it.join(2000) } catch (_: InterruptedException) {}
        }
        isConnecting = false
        onStatusChanged("Status: Disconnected")
        if (!silent) onLogMessage("Manually disconnected.")
    }

    private fun safeCloseSocket() {
        val socket = bluetoothSocket; bluetoothSocket = null
        if (socket == null) return
        try {
            try { socket.inputStream?.close() } catch (_: IOException) {}
            try { socket.outputStream?.close() } catch (_: IOException) {}
            socket.close()
        } catch (_: IOException) {}
    }
}
