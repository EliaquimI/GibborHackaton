package mx.edu.utez.gibbor.domain.port.output

interface AudioRecorder {
    fun start(incidentId: String)
    fun stop(): String?
    fun hasPermission(): Boolean
    fun isRecording(): Boolean
}
