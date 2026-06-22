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

import android.content.Context
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.GBApplication

/**
 * Persists the FitQuest session cookie + server URL + username in
 * SharedPreferences so the user only has to log in once per device.
 *
 * FitQuest uses a single long-lived cookie (named "fitquest_session"
 * by default — see COOKIE_NAME below). No OAuth, no refresh
 * token, no expiry logic on our side — the server's session row
 * has its own `expiresAt` and the cookie's maxAge is set to
 * sessionTtlDays (30 by default) when the server creates it.
 *
 * The same SharedPreferences blob that GBApplication holds is
 * used. We piggyback on the `preferences` field, which already
 * backs the Endurain / Wanderer setup wizards. Three keys are
 * owned by this manager:
 *
 *   - "fitquest_server"    → base URL (e.g. "http://10.0.0.59:3001")
 *   - "fitquest_username"  → login name (display in prefs only)
 *   - "fitquest_cookie"    → raw signed cookie value (sent verbatim
 *                            on every authenticated request)
 */
class FitQuestTokenManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Match the default FitQuest server-side cookie name. If the
     * user's server is configured with COOKIE_NAME=something_else,
     * the user has to also override this constant on the client
     * (TODO: read from a setting if/when that becomes common).
     */
    companion object {
        const val COOKIE_NAME = "fitquest_session"
        const val PREFS_NAME = "gadgetbridge-fitquest-prefs"
        const val KEY_SERVER = "fitquest_server"
        const val KEY_USERNAME = "fitquest_username"
        const val KEY_COOKIE = "fitquest_cookie"
    }

    fun saveSession(baseUrl: String, username: String, cookieValue: String) {
        prefs.edit {
            putString(KEY_SERVER, baseUrl)
            putString(KEY_USERNAME, username)
            putString(KEY_COOKIE, cookieValue)
        }
    }

    fun getServerUrl(): String? = prefs.getString(KEY_SERVER, null)

    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    fun getSessionCookie(): String? = prefs.getString(KEY_COOKIE, null)

    fun isLoggedIn(): Boolean = !getSessionCookie().isNullOrEmpty() && !getServerUrl().isNullOrEmpty()

    fun clearSession() {
        prefs.edit {
            remove(KEY_COOKIE)
            // Keep server URL + username — the user can re-enter
            // their password without re-typing the host.
        }
    }

    fun clearAll() {
        prefs.edit { clear() }
    }
}