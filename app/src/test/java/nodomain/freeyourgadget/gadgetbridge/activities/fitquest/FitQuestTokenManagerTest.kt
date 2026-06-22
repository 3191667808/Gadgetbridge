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
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the FitQuest integration that don't need the network:
 *
 *  - FitQuestTokenManager round-trip (save → read → clear)
 *  - Logout semantics (keeps server URL + username, drops cookie)
 *
 * The actual HTTP paths (login + upload + logout to the server)
 * are covered by an end-to-end test against a live FitQuest dev
 * server (see docs/PR_FITQUEST_LIVE_TEST.md) because OkHttp is
 * straightforward enough that mocking it adds more noise than
 * coverage.
 */
class FitQuestTokenManagerTest : TestBase() {
    private fun newManager(): FitQuestTokenManager {
        val ctx = RuntimeEnvironment.getApplication()
        // Each test starts from a clean slate so the persisted
        // cookie from a previous run can't leak into the next.
        ctx.getSharedPreferences(
            FitQuestTokenManager.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().apply()
        return FitQuestTokenManager(ctx)
    }

    @Test
    fun emptyManager_isNotLoggedIn() {
        val tm = newManager()
        assertFalse("new manager should not be logged in", tm.isLoggedIn())
        assertNull(tm.getSessionCookie())
        assertNull(tm.getServerUrl())
        assertNull(tm.getUsername())
    }

    @Test
    fun saveSession_persistsAllFields() {
        val tm = newManager()
        tm.saveSession(
            baseUrl = "http://10.0.0.59:3001",
            username = "josh",
            cookieValue = "s%3Asigned-token-value.fakesig"
        )
        assertTrue(tm.isLoggedIn())
        assertEquals("http://10.0.0.59:3001", tm.getServerUrl())
        assertEquals("josh", tm.getUsername())
        assertEquals("s%3Asigned-token-value.fakesig", tm.getSessionCookie())
    }

    @Test
    fun saveSession_overwritesPrevious() {
        val tm = newManager()
        tm.saveSession("http://old:3001", "old", "old-cookie")
        tm.saveSession("http://new:3001", "new", "new-cookie")
        assertEquals("http://new:3001", tm.getServerUrl())
        assertEquals("new", tm.getUsername())
        assertEquals("new-cookie", tm.getSessionCookie())
    }

    @Test
    fun clearSession_keepsServerAndUsername_dropsCookie() {
        // Logout semantics: we don't want the user to re-type the
        // server URL + username after every logout — only the
        // password. The cookie is the only secret that needs to
        // go.
        val tm = newManager()
        tm.saveSession("http://10.0.0.59:3001", "josh", "secret-cookie")
        tm.clearSession()
        assertFalse(tm.isLoggedIn())
        assertNull(tm.getSessionCookie())
        assertEquals("http://10.0.0.59:3001", tm.getServerUrl())
        assertEquals("josh", tm.getUsername())
    }

    @Test
    fun clearAll_wipesEverything() {
        val tm = newManager()
        tm.saveSession("http://10.0.0.59:3001", "josh", "secret-cookie")
        tm.clearAll()
        assertFalse(tm.isLoggedIn())
        assertNull(tm.getSessionCookie())
        assertNull(tm.getServerUrl())
        assertNull(tm.getUsername())
    }

    @Test
    fun cookieNameConstant_matchesServerDefault() {
        // The FitQuest server's default cookie name is
        // "fitquest_session" (api/src/lib/config.ts). If we ever
        // change the client default, this test fails loudly.
        assertEquals("fitquest_session", FitQuestTokenManager.COOKIE_NAME)
    }

    @Test
    fun newManagerInstance_seesPersistedData() {
        // Sanity check that SharedPreferences persists across
        // manager instances (this is the "user logs out and back
        // in" scenario).
        val tm1 = newManager()
        tm1.saveSession("http://10.0.0.59:3001", "josh", "cookie-value")
        val tm2 = FitQuestTokenManager(RuntimeEnvironment.getApplication())
        assertTrue(tm2.isLoggedIn())
        assertEquals("cookie-value", tm2.getSessionCookie())
        assertEquals("josh", tm2.getUsername())
    }
}