package mx.edu.utez.gibbor.infrastructure.adapter.outbound.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import mx.edu.utez.gibbor.domain.port.output.LocationProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationAdapterImpl(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) : LocationProvider {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    override suspend fun getCurrentLocation(): Location = suspendCancellableCoroutine { cont ->
        if (!hasPermission()) {
            cont.resumeWithException(SecurityException("Falta ACCESS_FINE_LOCATION"))
            return@suspendCancellableCoroutine
        }
        fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) cont.resumeWithException(
                    IllegalStateException("Could not get location. Enable GPS and try again.")
                ) else cont.resume(location)
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
