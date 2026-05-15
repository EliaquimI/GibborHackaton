package mx.edu.utez.gibbor.domain.port.input

import mx.edu.utez.gibbor.domain.model.Incident
import mx.edu.utez.gibbor.domain.model.TransactionResult

interface CreateIncidentUseCase {
    suspend fun execute(email: String): Pair<Incident, TransactionResult>
}
