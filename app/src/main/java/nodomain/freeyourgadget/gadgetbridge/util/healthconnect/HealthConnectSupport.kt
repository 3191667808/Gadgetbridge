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

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import org.slf4j.LoggerFactory
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

/**
 * The only route from Gadgetbridge into Health Connect.
 *
 * Every record the app writes passes through [insert]. Nothing else may hold a
 * [HealthConnectClient]: the client arrives as a provider lambda, which keeps this class free of
 * any Android Context and lets the syncers be exercised on a plain JVM against a fake client.
 *
 * [insert] is where the toggle, the quota, cancellation and the platform's undocumented size
 * limits are all handled, once.
 */
internal class HealthConnectSupport(
    private val clientProvider: () -> HealthConnectClient,
    private val syncEnabled: () -> Boolean
) {
    private val writeSemaphore = Semaphore(MAX_CONCURRENT_WRITES)

    fun requireSyncEnabled() {
        if (!syncEnabled()) {
            throw HealthConnectSyncDisabledException("Health Connect writing is disabled in Gadgetbridge")
        }
    }

    /**
     * Inserts [records], splitting them into chunks and retrying transient failures.
     *
     * Throws [HealthConnectSyncDisabledException] if the user turned Health Connect off (including
     * mid-run, since the gate is re-checked before every chunk), [HealthConnectRateLimitException]
     * if the app's Health Connect quota is exhausted, and [SyncException] for anything else that
     * survives the retries.
     */
    @Throws(SyncException::class)
    suspend fun insert(records: List<Record>) {
        if (records.isEmpty()) {
            return
        }
        for (chunk in records.chunked(CHUNK_SIZE)) {
            insertChunk(chunk)
        }
    }

    private suspend fun insertChunk(chunk: List<Record>) {
        var current = chunk
        var lastException: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            // Re-checked per attempt, not once per run: flipping the master switch off must stop an
            // in-flight sync, not merely prevent the next one.
            requireSyncEnabled()
            coroutineContext.ensureActive()
            awaitActiveRateLimit()

            try {
                writeSemaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        clientProvider().insertRecords(current)
                    }
                }
                LOG.debug("$HC_SYNC_TAG Inserted {} record(s) into Health Connect.", current.size)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthConnectAbortException) {
                throw e
            } catch (e: SecurityException) {
                // A permission was revoked. Retrying cannot help, and the caller needs to
                // reconcile the stored permission set.
                LOG.error("$HC_SYNC_TAG Permission error while inserting records. Aborting sync.", e)
                throw SyncException(
                    GBApplication.getContext().getString(
                        R.string.health_connect_permission_denied,
                        e.localizedMessage ?: ""
                    ),
                    e
                )
            } catch (e: Exception) {
                lastException = e

                if (HealthConnectRateLimitBackoff.isRateLimitFailure(e)) {
                    // Not a bad payload: the same records write fine once the quota refills. Give up
                    // on the whole run rather than spend the remaining attempts, and let the caller
                    // report it as a quota problem instead of a data problem.
                    throw HealthConnectRateLimitBackoff.markRateLimited(e)
                }

                // The platform enforces a 1 MB per-record limit that no API exposes. The only
                // variable-size record we build is the GPS route on an ExerciseSessionRecord.
                val shrunk = shrinkOversizedRoute(current, e)
                if (shrunk != null) {
                    current = shrunk
                    continue
                }

                // It also enforces a per-call payload limit. React to the rejection rather than
                // guess a safe chunk size up front, so the common case stays at one API call.
                if (isOversizedChunk(e) && current.size > 1) {
                    LOG.warn(
                        "$HC_SYNC_TAG Health Connect rejected a {}-record chunk as too large; splitting and retrying.",
                        current.size
                    )
                    val half = current.size / 2
                    insertChunk(current.subList(0, half))
                    insertChunk(current.subList(half, current.size))
                    return
                }

                if (attempt < MAX_RETRIES) {
                    val delayMillis = INITIAL_DELAY_MS * 2.0.pow(attempt).toLong()
                    LOG.warn(
                        "$HC_SYNC_TAG Failed to insert records, retrying in {}ms (attempt {}/{}).",
                        delayMillis, attempt + 1, MAX_RETRIES, e
                    )
                    delay(delayMillis)
                }
            }
        }

        throw SyncException(
            GBApplication.getContext().getString(
                R.string.health_connect_sync_failed_retries,
                lastException?.localizedMessage ?: ""
            ),
            lastException
        )
    }

    private suspend fun awaitActiveRateLimit() {
        val remaining = HealthConnectRateLimitBackoff.remainingMillis()
        if (remaining > 0) {
            LOG.warn("$HC_SYNC_TAG Health Connect quota exhausted; waiting {}ms before writing.", remaining)
            delay(remaining)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(HealthConnectSupport::class.java)

        internal const val HC_SYNC_TAG = "[HC_SYNC]"

        /**
         * Health Connect charges quota per API call, so a bigger chunk is cheaper: a day of
         * per-minute samples costs two calls rather than eight. If the platform rejects the
         * payload as too large, [insertChunk] halves it and retries.
         */
        internal const val CHUNK_SIZE = 1000
        internal const val MAX_SAMPLES_PER_HEART_RATE_RECORD = 1000

        private const val MAX_CONCURRENT_WRITES = 2
        private const val MAX_RETRIES = 5
        private const val INITIAL_DELAY_MS = 1000L

        // Matches "...single record size limit: 1000000, was: 1700644" from the HC platform.
        private val RECORD_SIZE_REGEX =
            Regex("single record size limit:\\s*(\\d+),\\s*was:\\s*(\\d+)")

        // Matches the sibling per-call limit, e.g. "...chunk size limit: 5000000, was: 6200000".
        private val CHUNK_SIZE_REGEX =
            Regex("chunk size limit:\\s*(\\d+),\\s*was:\\s*(\\d+)")

        private fun isOversizedChunk(e: Exception): Boolean =
            CHUNK_SIZE_REGEX.containsMatchIn(e.message ?: "")

        /**
         * If [e] is a "record size exceeded" error and [records] contains a downsizable
         * ExerciseSessionRecord route, returns a copy of the list with every route decimated by
         * the limit/was ratio (with margin). Returns null when the error is unrelated or nothing
         * can be shrunk, so the caller falls back to normal retry/abort.
         */
        internal fun shrinkOversizedRoute(records: List<Record>, e: Exception): List<Record>? {
            val match = RECORD_SIZE_REGEX.find(e.message ?: "") ?: return null
            val limit = match.groupValues[1].toLongOrNull() ?: return null
            val was = match.groupValues[2].toLongOrNull() ?: return null
            if (limit <= 0 || was <= limit) {
                return null
            }

            // Aim for 90% of the limit to leave room for per-point overhead we don't model.
            val keepRatio = (limit.toDouble() / was.toDouble()) * 0.9
            var shrankAny = false
            val result = records.map { record ->
                if (record !is ExerciseSessionRecord) {
                    return@map record
                }
                val route = (record.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute
                    ?: return@map record
                val points = route.route
                val target = (points.size * keepRatio).toInt()
                if (points.size < 2 || target >= points.size) {
                    return@map record
                }
                shrankAny = true
                val decimated = decimateRoute(points, target.coerceAtLeast(2))
                LOG.warn(
                    "$HC_SYNC_TAG ExerciseSessionRecord route too large ({} bytes > {} limit); decimated route from {} to {} points and retrying.",
                    was, limit, points.size, decimated.size
                )
                ExerciseSessionRecord(
                    startTime = record.startTime,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.endTime,
                    endZoneOffset = record.endZoneOffset,
                    exerciseType = record.exerciseType,
                    title = record.title,
                    notes = record.notes,
                    segments = record.segments,
                    laps = record.laps,
                    exerciseRoute = ExerciseRoute(decimated),
                    metadata = record.metadata
                )
            }
            return if (shrankAny) result else null
        }

        /** Uniformly decimates [points] down to [target] points, preserving first and last. */
        private fun decimateRoute(
            points: List<ExerciseRoute.Location>,
            target: Int
        ): List<ExerciseRoute.Location> {
            val kept = ArrayList<ExerciseRoute.Location>(target)
            val lastIndex = points.size - 1
            val step = lastIndex.toDouble() / (target - 1).toDouble()
            var idx = 0.0
            repeat(target - 1) {
                // Clamp below lastIndex so the explicit last point is never duplicated
                // (HC rejects routes with duplicate timestamps).
                kept.add(points[idx.toInt().coerceAtMost(lastIndex - 1)])
                idx += step
            }
            kept.add(points.last())
            return kept
        }
    }
}
