package mx.edu.utez.gibbor.infrastructure.adapter.outbound.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import mx.edu.utez.gibbor.domain.port.output.VideoRecorder
import mx.edu.utez.gibbor.infrastructure.service.RecordingService

class VideoRecordingAdapterImpl(private val context: Context) : VideoRecorder {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun start(incidentId: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_INCIDENT_ID, incidentId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        context.stopService(Intent(context, RecordingService::class.java))
    }
}
