/*  Copyright (C) 2026 Mark Struchkov

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.endurain

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Client for RideHub (https://ridehub.bike), a hosted cycling community
 * platform. Unlike Endurain and Wanderer it is not self-hosted, so there is no
 * server field to configure — only a personal upload token.
 */
class RideHubApiClient(private val tokenManager: RideHubTokenManager) {
    private val LOG = LoggerFactory.getLogger(RideHubApiClient::class.java)

    companion object {
        const val BASE_URL = "https://ridehub.bike"

        /**
         * Upload tokens are `rh_` followed by 43 URL-safe base64 characters.
         * Checking the prefix before saving turns a mistyped or wrong-service
         * string into an error during setup instead of a lost first upload.
         */
        const val TOKEN_PREFIX = "rh_"
    }

    private fun buildHeaders(): MutableMap<String, String> {
        val headers: MutableMap<String, String> = mutableMapOf()

        tokenManager.getAPIToken()?.let { token ->
            headers["Authorization"] = "Bearer $token"
        }

        return headers
    }

    /**
     * Verifies both that the server is reachable and that [apiToken] is accepted,
     * by calling a cheap authenticated endpoint. [apiToken] is passed in
     * explicitly because during setup it is not persisted yet. [callback] fires
     * with (reachable, reason): reason is null on success, otherwise a
     * localized, user-facing explanation.
     */
    fun checkServerReachable(apiToken: String, callback: (reachable: Boolean, reason: String?) -> Unit) {
        Thread {
            val context = GBApplication.getContext()
            try {
                val headers = mutableMapOf("Authorization" to "Bearer $apiToken")
                // networkFailureReason() (via onError) is more specific than
                // connectFailureReason: it distinguishes could-not-resolve-host /
                // refused / timed-out / TLS.
                var networkReason: String? = null
                val response = InternetUtils.doJsonRequest(
                    uri = "$BASE_URL/api/activities/import/ping".toUri(),
                    requestHeaders = headers,
                    onError = { reason -> networkReason = reason }
                )
                when {
                    // No usable response at all: offline / helper unavailable / server down.
                    response == null ->
                        callback(false, networkReason ?: InternetUtils.connectFailureReason(context, BASE_URL))
                    // The endpoint answers {"ok": true, "athlete": {...}} when the token is good.
                    response.has("ok") ->
                        callback(true, null)
                    // Server responded but rejected the credentials (unknown or revoked token).
                    else ->
                        callback(false, context.getString(R.string.toast_error_invalid_api_key))
                }
            } catch (e: Exception) {
                LOG.error("RideHub reachability check failed", e)
                callback(false, InternetUtils.connectFailureReason(context, BASE_URL))
            }
        }.start()
    }

    /**
     * Uploads one activity file (FIT or GPX). RideHub determines the format from
     * the file name extension, so the extension must be preserved.
     *
     * A repeated upload of the same activity is not an error: the server answers
     * with the existing activity's id, so retrying is always safe.
     */
    fun uploadActivity(file: File, callback: (String?, String?) -> Unit) {
        Thread {
            try {
                val uri = "$BASE_URL/api/activities/import".toUri()
                val headers = buildHeaders()

                InternetUtils.uploadBinaryFile(
                    uri = uri,
                    file = file,
                    requestHeaders = headers
                ) { success, statusCode, responseText, reason ->
                    if (success && statusCode != null && statusCode in 200..299 && responseText != null) {
                        LOG.debug("Response $statusCode from RideHub: $responseText")
                        val jsonObject = JSONObject(responseText)
                        callback(jsonObject.getString("id"), null)
                    } else {
                        // Prefer the server's own error message; fall back to the network reason.
                        val message = try {
                            if (responseText != null) JSONObject(responseText).getString("message") else null
                        } catch (e: Exception) {
                            null
                        } ?: reason ?: statusCode?.let { "HTTP $it" }
                        LOG.error("Activity upload failed: {}", message)
                        callback(null, message)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Activity upload error", e)
                callback(null, null)
            }
        }.start()
    }
}
