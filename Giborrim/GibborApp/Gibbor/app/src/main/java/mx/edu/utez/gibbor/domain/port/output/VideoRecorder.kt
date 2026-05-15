package mx.edu.utez.gibbor.domain.port.output

interface VideoRecorder {
    fun start(incidentId: String)
    fun stop()
    fun hasPermission(): Boolean
}
