package mx.edu.utez.gibbor.application.usecase

import mx.edu.utez.gibbor.domain.port.input.RecordEvidenceUseCase
import mx.edu.utez.gibbor.domain.port.output.AudioRecorder
import mx.edu.utez.gibbor.domain.port.output.IncidentRepository
import mx.edu.utez.gibbor.domain.port.output.VideoRecorder

class RecordEvidenceUseCaseImpl(
    private val audioRecorder: AudioRecorder,
    private val videoRecorder: VideoRecorder,
    private val incidentRepository: IncidentRepository
) : RecordEvidenceUseCase {

    override fun startRecording(incidentId: String) {
        audioRecorder.start(incidentId)
        if (videoRecorder.hasPermission()) {
            videoRecorder.start(incidentId)
        }
    }

    override suspend fun stopAndAnchor(incidentId: String): Boolean {
        val hash = audioRecorder.stop() ?: return false
        return incidentRepository.anchorEvidence(incidentId, "audio", hash)
    }
}
