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

import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the FitQuestApiClient helpers that don't need a live
 * server. The HTTP paths (login / upload / logout / validateSession)
 * are exercised by the end-to-end test against a real FitQuest
 * dev server (see docs/PR_FITQUEST_LIVE_TEST.md in the PR).
 *
 * What we test here:
 *   - parseCookieValue: handles the 4 different Set-Cookie shapes
 *     we see in the wild (plain, signed, with attributes, wrong name)
 *   - summarizeUploadResponse: rounds the {files: [{created: [...],
 *     skipped: [...]}]} JSON shape down to a one-line summary the
 *     UI toasts.
 */
class FitQuestApiClientTest : TestBase() {
    private fun newClient(): FitQuestApiClient {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences(
            FitQuestTokenManager.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().apply()
        return FitQuestApiClient("http://localhost:3001", FitQuestTokenManager(ctx))
    }

    // ---- parseCookieValue ----

    @Test
    fun parseCookieValue_plainCookie() {
        val client = newClient()
        val raw = "fitquest_session=abc123; Path=/; HttpOnly; SameSite=Lax"
        assertEquals("abc123", client.parseCookieValue(raw, "fitquest_session"))
    }

    @Test
    fun parseCookieValue_signedCookieWithUrlEncoding() {
        // @fastify/cookie emits signed cookies as "s:value.signature"
        // and the value can contain URL-encoded characters.
        val client = newClient()
        val raw = "fitquest_session=s%3Areal-token-value.fakesig123; Path=/; HttpOnly"
        assertEquals("s%3Areal-token-value.fakesig123",
            client.parseCookieValue(raw, "fitquest_session"))
    }

    @Test
    fun parseCookieValue_wrongName() {
        // If the user has a custom COOKIE_NAME on the server,
        // parseCookieValue should return null rather than guess.
        val client = newClient()
        val raw = "different_cookie_name=secretvalue; Path=/"
        assertNull(client.parseCookieValue(raw, "fitquest_session"))
    }

    @Test
    fun parseCookieValue_malformed() {
        val client = newClient()
        // Missing '=' — can't parse
        assertNull(client.parseCookieValue("fitquest_session", "fitquest_session"))
        // Empty value
        assertNull(client.parseCookieValue("fitquest_session=; Path=/", "fitquest_session"))
        // Only attributes, no name=value
        assertNull(client.parseCookieValue("Path=/; HttpOnly", "fitquest_session"))
        // Empty header
        assertNull(client.parseCookieValue("", "fitquest_session"))
    }

    @Test
    fun parseCookieValue_preservesEqualsInValue() {
        // Some servers (or middleware like proxies) can rewrite the
        // cookie value to include '=' characters. The first '=' is
        // the delimiter; everything after is part of the value.
        val client = newClient()
        val raw = "fitquest_session=base64==; Path=/"
        assertEquals("base64==", client.parseCookieValue(raw, "fitquest_session"))
    }

    // ---- summarizeUploadResponse ----

    @Test
    fun summarizeUploadResponse_workoutsAndMeasurements() {
        val client = newClient()
        val body = """
            { "files": [{
                "filename": "x.fit",
                "fitKind": "activity",
                "created": [
                    {"kind": "workout", "id": "w1"},
                    {"kind": "workout", "id": "w2"},
                    {"kind": "measurement", "metric": "HRV", "value": 71},
                    {"kind": "measurement", "metric": "RESTING_HR", "value": 50}
                ],
                "skipped": []
            }]}
        """.trimIndent()
        // Sorted alphabetically: measurement (2), workout (2)
        assertEquals("Imported: 2 measurement, 2 workout", client.summarizeUploadResponse(body))
    }

    @Test
    fun summarizeUploadResponse_withSkipped() {
        val client = newClient()
        val body = """
            { "files": [{
                "created": [{"kind": "workout", "id": "w1"}],
                "skipped": [
                    {"reason": "decoder errors"},
                    {"reason": "no FileId"},
                    {"reason": "already imported"}
                ]
            }]}
        """.trimIndent()
        assertEquals("Imported: 1 workout (3 skipped)", client.summarizeUploadResponse(body))
    }

    @Test
    fun summarizeUploadResponse_emptyCreated() {
        val client = newClient()
        val body = """{ "files": [{ "created": [], "skipped": [] }] }"""
        assertEquals("Imported (no rows)", client.summarizeUploadResponse(body))
    }

    @Test
    fun summarizeUploadResponse_malformedJson() {
        // Should never throw — a bad response just falls back to
        // "Imported" so the toast still shows *something*.
        val client = newClient()
        assertEquals("Imported", client.summarizeUploadResponse("not json {"))
    }

    @Test
    fun summarizeUploadResponse_emptyFilesArray() {
        val client = newClient()
        assertEquals("Empty response from server",
            client.summarizeUploadResponse("""{ "files": [] }"""))
    }
}