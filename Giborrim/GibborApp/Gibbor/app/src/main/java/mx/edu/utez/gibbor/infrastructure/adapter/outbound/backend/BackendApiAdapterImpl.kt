package mx.edu.utez.gibbor.infrastructure.adapter.outbound.backend

import mx.edu.utez.gibbor.domain.model.Incident
import mx.edu.utez.gibbor.domain.model.TransactionResult
import mx.edu.utez.gibbor.domain.port.output.IncidentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class BackendApiAdapterImpl(private val backendUrl: String) : IncidentRepository {

    override suspend fun createIncident(email: String, incident: Incident): TransactionResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("email", email)
                .put("incidentId", incident.incidentId)
                .put("timestamp", incident.timestamp)
                .put("latE7", incident.latE7)
                .put("lonE7", incident.lonE7)
                .put("initialHash", incident.initialHash)

            val connection = (URL("$backendUrl/api/incident").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 60000
                doInput = true
                doOutput = true
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            val raw = readResponseBody(connection)
            val code = connection.responseCode
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }

            if (code !in 200..299) {
                TransactionResult(
                    success = false, status = "error", txId = "", txHash = "",
                    explorerLink = "", error = json.optString("error", "HTTP $code"), rawJson = json.toString(2)
                )
            } else {
                TransactionResult(
                    success = json.optBoolean("success", false),
                    status = json.optString("status", "unknown"),
                    txId = json.optString("txId", ""),
                    txHash = json.optString("txHash", ""),
                    explorerLink = json.optString("explorerLink", ""),
                    error = json.optString("error", ""),
                    rawJson = json.toString(2)
                )
            }
        }

    override suspend fun pollStatus(
        email: String,
        txId: String,
        maxAttempts: Int,
        delayMs: Long
    ): TransactionResult = withContext(Dispatchers.IO) {
        var lastResult: TransactionResult? = null

        repeat(maxAttempts) { attempt ->
            delay(delayMs)
            try {
                val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
                val encodedTxId = java.net.URLEncoder.encode(txId, "UTF-8")
                val connection = (URL("$backendUrl/api/incident/$encodedTxId?email=$encodedEmail")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doInput = true
                }
                val raw = readResponseBody(connection)
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }

                lastResult = TransactionResult(
                    success = json.optBoolean("success", false),
                    status = json.optString("status", "unknown"),
                    txId = json.optString("txId", txId),
                    txHash = json.optString("txHash", ""),
                    explorerLink = json.optString("explorerLink", ""),
                    error = json.optString("error", ""),
                    rawJson = json.toString(2)
                )
                if (lastResult!!.status in listOf("success", "failed")) return@withContext lastResult!!
            } catch (e: Exception) {
                lastResult = TransactionResult(
                    success = false, status = "polling-error", txId = txId, txHash = "",
                    explorerLink = "", error = "Polling attempt ${attempt + 1}: ${e.message}", rawJson = ""
                )
            }
        }

        lastResult ?: TransactionResult(
            success = false, status = "timeout", txId = txId, txHash = "",
            explorerLink = "", error = "Polling timeout after $maxAttempts attempts", rawJson = ""
        )
    }

    override suspend fun anchorEvidence(
        incidentId: String,
        mediaType: String,
        mediaHash: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("mediaType", mediaType).put("mediaHash", mediaHash)
            val connection = (URL("$backendUrl/api/incident/$incidentId/evidence")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 60000
                doInput = true
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val raw = readResponseBody(connection)
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            code in 200..299 && json.optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = try { connection.inputStream } catch (_: Exception) { connection.errorStream }
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}
