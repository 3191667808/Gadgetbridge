/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import android.os.Build

/** Health Connect refused the write because the app's API quota is exhausted. */
class HealthConnectRateLimitException(
    val retryAfterMillis: Long,
    message: String,
    cause: Throwable? = null
) : HealthConnectAbortException(message, cause)

/**
 * Health Connect meters its rate limit per API *call*, not per record: a quota rejection reports
 * `requested: 1` however many records the call carried. A backfill that spends one unit of quota
 * per chunk can therefore run the daily allowance down and stop writing partway through, which
 * from the outside is indistinguishable from data silently going missing.
 *
 * Two consequences, both handled here:
 *
 *  - Batch aggressively. [HealthConnectSupport] sends large chunks and only splits when the
 *    platform actually rejects the payload, rather than pre-emptively slicing into many calls.
 *  - A quota rejection is not a bad record. The same data writes fine once the quota refills, so
 *    the sync must back off and come back rather than burn its retries and mark good data failed.
 *
 * The backoff is process-global because the quota is: every device and data type in a run draws on
 * the same allowance.
 */
object HealthConnectRateLimitBackoff {
    private const val DEFAULT_BACKOFF_MILLIS = 60_000L

    private val MESSAGE_MARKERS = listOf("rate limited", "quota has been exceeded")

    @Volatile
    private var retryAfterEpochMillis: Long = 0L

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (retryAfterEpochMillis - nowMillis).coerceAtLeast(0L)

    fun markRateLimited(
        cause: Throwable,
        nowMillis: Long = System.currentTimeMillis()
    ): HealthConnectRateLimitException {
        retryAfterEpochMillis = maxOf(retryAfterEpochMillis, nowMillis + DEFAULT_BACKOFF_MILLIS)
        return HealthConnectRateLimitException(
            remainingMillis(nowMillis),
            cause.message ?: "Health Connect API quota exhausted",
            cause
        )
    }

    /**
     * The platform throws `android.health.connect.HealthConnectException` with
     * `ERROR_RATE_LIMIT_EXCEEDED`, but androidx rewraps it as a plain [IllegalStateException] on
     * the way out, so the error code survives only in the cause chain and the message text. Check
     * both.
     */
    fun isRateLimitFailure(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is HealthConnectRateLimitException) {
                return true
            }
            if (isPlatformRateLimitException(cause)) {
                return true
            }
            val message = cause.message
            if (message != null && MESSAGE_MARKERS.any { message.contains(it, ignoreCase = true) }) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun isPlatformRateLimitException(throwable: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        return throwable is android.health.connect.HealthConnectException &&
                throwable.errorCode == android.health.connect.HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
    }

    /** Test seam: forget any active backoff. */
    internal fun reset() {
        retryAfterEpochMillis = 0L
    }
}
