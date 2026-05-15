package mx.edu.utez.gibbor.application.usecase

import android.location.Location
import mx.edu.utez.gibbor.domain.model.Incident
import mx.edu.utez.gibbor.domain.model.TransactionResult
import mx.edu.utez.gibbor.domain.port.input.CreateIncidentUseCase
import mx.edu.utez.gibbor.domain.port.output.IncidentRepository
import mx.edu.utez.gibbor.domain.port.output.LocationProvider
import java.security.MessageDigest

class CreateIncidentUseCaseImpl(
    private val locationProvider: LocationProvider,
    private val incidentRepository: IncidentRepository
) : CreateIncidentUseCase {

    override suspend fun execute(email: String): Pair<Incident, TransactionResult> {
        val location = locationProvider.getCurrentLocation()
        val incident = buildIncident(location)

        var result = incidentRepository.createIncident(email, incident)

        if (result.success && result.status in listOf("pending", "awaiting-approval")) {
            result = incidentRepository.pollStatus(email, result.txId)
        }

        return Pair(incident, result)
    }

    private fun buildIncident(location: Location): Incident {
        val incidentId = "inc-${System.currentTimeMillis()}"
        val timestamp = System.currentTimeMillis() / 1000L
        val latE7 = toE7(location.latitude)
        val lonE7 = toE7(location.longitude)
        val initialHash = sha256("$incidentId|$timestamp|$latE7|$lonE7")
        return Incident(incidentId, timestamp, latE7, lonE7, initialHash)
    }

    private fun toE7(value: Double): Int = (value * 10_000_000.0).toInt()

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
