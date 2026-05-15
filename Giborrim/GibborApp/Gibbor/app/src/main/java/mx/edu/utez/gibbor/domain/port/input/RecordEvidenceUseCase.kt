package mx.edu.utez.gibbor.domain.port.input

interface RecordEvidenceUseCase {
    fun startRecording(incidentId: String)
    suspend fun stopAndAnchor(incidentId: String): Boolean
}
