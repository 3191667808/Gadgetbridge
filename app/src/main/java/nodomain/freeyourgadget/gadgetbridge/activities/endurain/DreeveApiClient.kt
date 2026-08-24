package nodomain.freeyourgadget.gadgetbridge.activities.endurain

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

class DreeveApiClient(
    private val baseUrl: String,
    private val tokenManager: DreeveTokenManager
) {
    private val LOG = LoggerFactory.getLogger(DreeveApiClient::class.java)

    private fun buildHeaders(): MutableMap<String, String> {
        val headers: MutableMap<String, String> = mutableMapOf()

        tokenManager.getAPIToken()?.let { token ->
            headers["Authorization"] = "Bearer $token"
        }

        return headers
    }

    fun checkServerReachable(apiToken: String, callback: (reachable: Boolean, reason: String?) -> Unit) {
        Thread {
            val context = GBApplication.getContext()
            try {
                val uri = "$baseUrl/api/v1/status".toUri()
                val headers = mutableMapOf("Authorization" to "Bearer $apiToken")

                var networkReason: String? = null
                val response = InternetUtils.doJsonRequest(
                    uri = uri,
                    requestHeaders = headers,
                    onError = { reason -> networkReason = reason }
                )

                when {
                    response == null ->
                        callback(false, networkReason ?: InternetUtils.connectFailureReason(context, baseUrl))

                    response.has("error") ->
                        callback(false, response.getString("message"))

                    response.has("version") ->
                        callback(true, null)


                    else ->
                        callback(false, "Invalid API key")
                }
            } catch (e: Exception) {
                LOG.error("Dreeve reachability check failed", e)
                callback(false, InternetUtils.connectFailureReason(context, baseUrl))
            }
        }.start()
    }

    fun uploadActivity(file: File, callback: (success: Boolean, message: String?) -> Unit) {
        Thread {
            try {
                val uri = "$baseUrl/api/v1/activity/upload".toUri()
                val headers = buildHeaders()

                InternetUtils.uploadBinaryFile(
                    uri = uri,
                    file = file,
                    requestHeaders = headers,
                    method = "POST"
                ) { success, statusCode, response, reason ->
                    if (success && statusCode != null && statusCode ==  202 && response != null) {
                        LOG.debug("Response $statusCode from Dreeve: $response")
                        callback(true, null)
                    } else {
                        val message = try {
                            if (response != null) JSONObject(response).getString("message") else null
                        } catch (e: Exception) {
                            null
                        } ?: reason ?: statusCode?.let { "HTTP $it" }
                        LOG.error("Dreeve activity upload failed: {}", message)
                        callback(false, message)
                    }
                }
            } catch(e: Exception) {
                LOG.error("Dreeve activity upload error", e)
                callback(false, null)
            }
        }.start()
    }
}