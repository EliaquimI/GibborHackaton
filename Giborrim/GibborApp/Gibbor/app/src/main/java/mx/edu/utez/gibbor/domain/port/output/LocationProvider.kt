package mx.edu.utez.gibbor.domain.port.output

import android.location.Location

interface LocationProvider {
    suspend fun getCurrentLocation(): Location
    fun hasPermission(): Boolean
}
