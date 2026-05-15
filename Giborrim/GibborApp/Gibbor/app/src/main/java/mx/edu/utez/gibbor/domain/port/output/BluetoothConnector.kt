package mx.edu.utez.gibbor.domain.port.output

interface BluetoothConnector {
    val isConnecting: Boolean
    fun connect()
    fun disconnect()
    fun isAvailable(): Boolean
    fun hasPermissions(): Boolean
    fun isEnabled(): Boolean
    fun enable()
}
