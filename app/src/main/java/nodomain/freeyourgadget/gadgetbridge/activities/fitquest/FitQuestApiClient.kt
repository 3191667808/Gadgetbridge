/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.fitquest

import androidx.core.net.toUri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client for FitQuest, a self-hosted personal-fitness RPG.
 *
 * FitQuest uses long-lived server-side sessions stored in a signed
 * cookie (named "fitquest_session" by default, configurable via the
 * COOKIE_NAME env var on the server). After a successful POST to
 * /auth/login, the server sets the cookie in the response and we
 * capture its raw value to send verbatim with every subsequent
 * request. No OAuth, no refresh tokens — sessions last 30 days by
 * default and are tied to the cookie alone.
 *
 * The FIT upload endpoint is /import — it accepts a raw .fit file
 * as the binary request body (Content-Type: application/octet-stream),
 * not multipart. We use OkHttp directly here because the bundled
 * InternetUtils.uploadBinaryFile always wraps in multipart form-data.
 */
class FitQuestApiClient(
    private val baseUrl: String,
    private val tokenManager: FitQuestTokenManager
) {
    private val LOG = LoggerFactory.getLogger(FitQuestApiClient::class.java)

    // 30s timeout: FitQuest runs on a LAN dev box; uploads are
    // sub-second but a cold-start (loading gemma3:12b etc.) can
    // push first-request latency up.
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authHeaderValue(): String? {
        val v = tokenManager.getSessionCookie() ?: return null
        return "${FitQuestTokenManager.COOKIE_NAME}=$v"
    }

    private fun buildAuthHeaders(): MutableMap<String, String> {
        val h: MutableMap<String, String> = mutableMapOf()
        authHeaderValue()?.let { h["Cookie"] = it }
        return h
    }

    /**
     * Username/password login. POSTs JSON to /auth/login and parses
     * the Set-Cookie header from the response so subsequent calls
     * can authenticate without re-prompting.
     *
     * @return FitQuestLoginResult.Success on 2xx,
     *         InvalidCredentials on 401,
     *         NetworkError on any other failure.
     */
    fun login(username: String, password: String): FitQuestLoginResult {
        try {
            val uri = "$baseUrl/auth/login".toUri()
            val body = JSONObject().apply {
                put("identifier", username)
                put("password", password)
            }.toString()

            val request = Request.Builder()
                .url(uri.toString())
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val status = response.code
                if (status in 200..299) {
                    // Detect TOTP_REQUIRED before we save anything.
                    // If the server returns requiresTotp=true, it has
                    // set a TOTP_PENDING cookie that requireUser()
                    // rejects for non-/auth/login/totp routes — saving
                    // it as a regular session would silently break
                    // /import later. Surface it as a separate result
                    // and don't persist the cookie.
                    val bodyText = response.body?.string() ?: ""
                    val userJson = try { JSONObject(bodyText) } catch (e: Exception) { JSONObject() }
                    if (userJson.optBoolean("requiresTotp", false)) {
                        LOG.warn("FitQuest login: TOTP required for {}", username)
                        return FitQuestLoginResult.RequiresTotp
                    }
                    // Capture the session cookie. The server's
                    // @fastify/cookie emits "fitquest_session=<value>; Path=/; ..."
                    // (possibly signed). headers("set-cookie") returns
                    // ALL set-cookie headers — OkHttp's singular header()
                    // only returns the first, so we'd silently drop any
                    // extra cookies the server starts sending later.
                    val setCookies = response.headers("set-cookie")
                    if (setCookies.isEmpty()) {
                        LOG.error("FitQuest login succeeded but no Set-Cookie header")
                        return FitQuestLoginResult.NetworkError("Server did not return a session cookie")
                    }
                    val cookieValue = setCookies
                        .mapNotNull { parseCookieValue(it, FitQuestTokenManager.COOKIE_NAME) }
                        .firstOrNull()
                    if (cookieValue == null) {
                        LOG.error("FitQuest login: no Set-Cookie matched ${FitQuestTokenManager.COOKIE_NAME}")
                        return FitQuestLoginResult.NetworkError("Server response did not include a session cookie")
                    }
                    tokenManager.saveSession(baseUrl, username, cookieValue)
                    // Actual response shape is { user: { username: ... } }
                    // (see publicUser() in server auth.ts). Read from
                    // the nested object — the previous top-level read
                    // always fell through to the fallback.
                    val usernameOut = userJson.optJSONObject("user")
                        ?.optString("username", username)
                        ?: username
                    return FitQuestLoginResult.Success(usernameOut)
                }
                if (status == 401) return FitQuestLoginResult.InvalidCredentials
                val errMsg = try {
                    JSONObject(response.body?.string() ?: "{}").optString("error", "HTTP $status")
                } catch (e: Exception) { "HTTP $status" }
                LOG.error("FitQuest login failed: HTTP $status - $errMsg")
                return FitQuestLoginResult.NetworkError(errMsg)
            }
        } catch (e: Exception) {
            LOG.error("FitQuest login error", e)
            return FitQuestLoginResult.NetworkError(e.localizedMessage ?: "Network error")
        }
    }

    /**
     * Upload a .fit file to /import. The server inspects the fileId,
     * dispatches to the right parser (activity / sleep / hrv /
     * monitor / metrics), persists the rows, and returns the list
     * of created entries + any skips.
     *
     * Runs on a background thread (caller doesn't need to wrap in
     * a coroutine). Callback signature mirrors the Endurain and
     * Wanderer clients: (filename, summary).
     *   - success: (file.name, "Imported: 3 workouts, ...")
     *   - failure: (null, "HTTP 401" / "Network error" / ...)
     */
    fun uploadActivity(file: File, callback: (String?, String?) -> Unit) {
        Thread {
            try {
                val uri = "$baseUrl/import".toUri()
                val cookieValue = authHeaderValue()
                if (cookieValue == null) {
                    callback(null, "Not logged in to FitQuest")
                    return@Thread
                }
                val fileBytes = file.readBytes()
                val request = Request.Builder()
                    .url(uri.toString())
                    .addHeader("Cookie", cookieValue)
                    .addHeader("Content-Type", "application/octet-stream")
                    .post(fileBytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val status = response.code
                    val responseText = response.body?.string()
                    if (status in 200..299 && responseText != null) {
                        LOG.debug("FitQuest upload {}: {}", status, responseText)
                        callback(file.name, summarizeUploadResponse(responseText))
                    } else {
                        val msg = if (!responseText.isNullOrEmpty()) {
                            try {
                                JSONObject(responseText).optString("error", "HTTP $status")
                            } catch (e: Exception) { "HTTP $status" }
                        } else { "HTTP $status" }
                        LOG.error("FitQuest upload failed for {}: {}", file.name, msg)
                        callback(null, msg)
                    }
                }
            } catch (e: Exception) {
                LOG.error("FitQuest upload error for {}", file.name, e)
                callback(null, e.localizedMessage ?: "Network error")
            }
        }.start()
    }

    /**
     * Best-effort logout. Server-side this clears the session row.
     * We always clear the local token regardless of whether the
     * server call succeeded.
     */
    fun logout(): Boolean {
        var ok = false
        try {
            val uri = "$baseUrl/auth/logout".toUri()
            val cookieValue = authHeaderValue()
            if (cookieValue != null) {
                val request = Request.Builder()
                    .url(uri.toString())
                    .addHeader("Cookie", cookieValue)
                    .addHeader("Content-Type", "application/json")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    ok = response.code in 200..299
                }
            } else {
                // No local session anyway — nothing to tell the server.
                ok = true
            }
        } catch (e: Exception) {
            LOG.warn("FitQuest logout error, clearing local session anyway", e)
        }
        tokenManager.clearSession()
        return ok
    }

    /**
     * Confirm the stored cookie is still valid server-side. Used
     * when the user opens the prefs screen to refresh the
     * "Logged in as X" status line.
     */
    fun validateSession(): Boolean {
        try {
            val cookieValue = authHeaderValue() ?: return false
            val request = Request.Builder()
                .url("$baseUrl/auth/me".toUri().toString())
                .addHeader("Cookie", cookieValue)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val text = response.body?.string() ?: return false
                // Parse the JSON and check for the user.username
                // field rather than substring-matching "\"username\"".
                // A substring match would falsely succeed if the
                // server returned a 200 with an error body containing
                // "username" in a stack trace or message.
                return try {
                    JSONObject(text).optJSONObject("user")
                        ?.has("username") == true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            LOG.debug("FitQuest session validation failed", e)
            return false
        }
    }

    /**
     * Reduce a /import JSON response to a one-line human summary.
     * "3 workouts, 412 measurements" etc.
     *
     * Exposed as `internal` so the unit test in src/test can hit it
     * without standing up a MockWebServer — the response format
     * is the only thing worth testing here, and the JSON shape is
     * stable per FitQuest's import.ts contract.
     */
    internal fun summarizeUploadResponse(responseText: String): String {
        return try {
            val root = JSONObject(responseText)
            val files = root.optJSONArray("files")
            if (files == null || files.length() == 0) return "Empty response from server"
            val file = files.getJSONObject(0)
            val counts = mutableMapOf<String, Int>()
            val created = file.optJSONArray("created")
            if (created != null) {
                for (i in 0 until created.length()) {
                    val c = created.getJSONObject(i)
                    val kind = c.optString("kind", "row")
                    counts[kind] = (counts[kind] ?: 0) + 1
                }
            }
            val parts = counts.entries.sortedBy { it.key }
                .joinToString(", ") { "${it.value} ${it.key}" }
            val skipped = file.optJSONArray("skipped")
            val skippedCount = skipped?.length() ?: 0
            val tail = if (skippedCount > 0) " ($skippedCount skipped)" else ""
            if (parts.isEmpty()) "Imported (no rows)$tail" else "Imported: $parts$tail"
        } catch (e: Exception) {
            "Imported"
        }
    }

    /**
     * Pull the cookie value out of a Set-Cookie header. Format is
     * "name=value; Path=/; HttpOnly; SameSite=Lax". The first
     * segment is the only one we need.
     *
     * `internal` so the unit test can exercise the corner cases
     * (missing cookie, wrong name, signed cookies with `%3A` URL
     * encoding in the value) without a live server.
     */
    internal fun parseCookieValue(setCookieHeader: String, cookieName: String): String? {
        val firstSegment = setCookieHeader.split(';').firstOrNull()?.trim() ?: return null
        val eq = firstSegment.indexOf('=')
        if (eq <= 0) return null
        val name = firstSegment.substring(0, eq).trim()
        val value = firstSegment.substring(eq + 1).trim()
        if (name != cookieName) return null
        return value.ifEmpty { null }
    }
}

/**
 * Result type for FitQuest login. We keep the network-level
 * distinction (success / wrong-password / TOTP-required /
 * unreachable) so the UI can show different copy + severity
 * for each case.
 */
sealed class FitQuestLoginResult {
    data class Success(val username: String) : FitQuestLoginResult()
    data object InvalidCredentials : FitQuestLoginResult()
    /// User has TOTP enabled and the server has issued a
    /// TOTP_PENDING cookie. The caller should prompt for the
    /// 6-digit code and call /auth/login/totp to complete.
    data object RequiresTotp : FitQuestLoginResult()
    data class NetworkError(val message: String) : FitQuestLoginResult()
}