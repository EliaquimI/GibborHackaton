package mx.edu.utez.gibbor.infrastructure.adapter.outbound.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import mx.edu.utez.gibbor.domain.port.output.AudioRecorder
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class AudioRecordingAdapterImpl(private val context: Context) : AudioRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var recording = false

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    override fun isRecording(): Boolean = recording

    override fun start(incidentId: String) {
        if (!hasPermission()) return
        stop()

        val audioDir = File(context.filesDir, "evidence").also { it.mkdirs() }
        val audioFile = File(audioDir, "audio_${incidentId}.m4a")
        currentAudioFile = audioFile

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            recording = true
        } catch (e: Exception) {
            recording = false
            mediaRecorder = null
        }
    }

    override fun stop(): String? {
        val recorder = mediaRecorder ?: return null
        val file = currentAudioFile

        try {
            recorder.stop()
            recorder.release()
        } catch (_: Exception) {}

        mediaRecorder = null
        recording = false

        if (file != null && file.exists() && file.length() > 0) {
            return sha256File(file)
        }
        return null
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
