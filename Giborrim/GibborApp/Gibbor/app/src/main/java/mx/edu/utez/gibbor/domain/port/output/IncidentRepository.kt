package mx.edu.utez.gibbor.domain.port.output

import mx.edu.utez.gibbor.domain.model.Incident
import mx.edu.utez.gibbor.domain.model.TransactionResult

interface IncidentRepository {
    suspend fun createIncident(email: String, incident: Incident): TransactionResult
    suspend fun pollStatus(
        email: String,
        txId: String,
        maxAttempts: Int = 10,
        delayMs: Long = 3000L
    ): TransactionResult
    suspend fun anchorEvidence(incidentId: String, mediaType: String, mediaHash: String): Boolean
}
