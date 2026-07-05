/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.EndurainApiClient
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.EndurainTokenManager
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.WandererApiClient
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.WandererTokenManager
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.export.FitExporter
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared orchestration for exporting a single workout and uploading it to Endurain (FIT) or
 * Wanderer (GPX). Extracted from [WorkoutDetailsFragment] so the detail screen, the multi-select
 * list, and the background auto-upload worker all behave identically — same name fallback, same
 * status/reason handling, same NPE-safe edit step.
 *
 * All methods are blocking / callback-based and safe to call off the main thread; the upload
 * clients spawn their own worker threads internally.
 */
object WorkoutUploader {
    private val LOG = LoggerFactory.getLogger(WorkoutUploader::class.java)

    const val PREF_ENDURAIN_SERVER = "endurain_server"
    const val PREF_WANDERER_SERVER = "wanderer_server"

    /**
     * A workout's display name, falling back to the localized activity-kind label when the
     * summary carries no user-set name (most devices, e.g. Xiaomi/Mi Band, never set one).
     */
    fun nameFor(context: Context, summary: BaseActivitySummary): String =
        summary.name ?: ActivityKind.fromCode(summary.activityKind).getLabel(context)

    /**
     * Builds a `.fit` file for [summary] in the cache directory and returns it.
     *
     * FIT-native devices (Garmin, iGPSPORT) keep the original .fit at rawDetailsPath — it is
     * copied verbatim. For any other device the FIT is synthesized from the summary (and the
     * activity track, if one is available). [summaryData] is optional and may be null when the
     * caller only has a bare summary (list / worker).
     *
     * Blocking — call from an IO context.
     */
    fun buildFitFile(
        context: Context,
        gbDevice: GBDevice,
        summary: BaseActivitySummary,
        summaryData: ActivitySummaryData? = null
    ): File {
        val kindLabel = ActivityKind.fromCode(summary.activityKind).getLabel(context).lowercase()
        val fileName = FileUtils.makeValidFileName(
            "Workout-${kindLabel}-${DateTimeUtils.formatIso8601(summary.startTime)}.fit"
        )
        val cacheSubDir = File(context.cacheDir, "raw")
        cacheSubDir.mkdirs()
        val outFile = File(cacheSubDir, fileName)

        val rawFit = FitExporter.resolveRawFitFile(summary)
        if (rawFit != null) {
            rawFit.copyTo(outFile, overwrite = true)
        } else {
            val activityTrackProvider = gbDevice.deviceCoordinator
                .getActivityTrackProvider(gbDevice, context)
            val track = try {
                activityTrackProvider?.getActivityTrack(summary)
            } catch (e: Exception) {
                LOG.warn("Failed to load activity track for FIT export", e)
                null
            }
            FitExporter().performExport(track, summary, summaryData, outFile)
        }
        return outFile
    }

    /**
     * Uploads [fitFile] to Endurain: refresh the access token, upload, then set the activity
     * type + name. [callback] fires with (success, reason) — reason is a localized failure
     * explanation on failure, null on success. The name-edit step runs only after a successful
     * upload, so a failure there can never flip a successful upload to "failed".
     */
    fun uploadToEndurain(
        context: Context,
        summary: BaseActivitySummary,
        fitFile: File,
        callback: (success: Boolean, reason: String?) -> Unit
    ) {
        val serverUrl = GBApplication.getPrefs().preferences.getString(PREF_ENDURAIN_SERVER, null)
        if (serverUrl == null) {
            callback(false, null)
            return
        }
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(serverUrl, tokenManager)
        val kind = ActivityKind.fromCode(summary.activityKind)
        val name = nameFor(context, summary)

        tokenManager.performTokenRefresh(serverUrl) {
            LOG.info("Uploading workout '{}' (type {}) to Endurain", name, kind)
            apiClient.uploadActivity(fitFile) { newId, reason ->
                if (newId != null) {
                    // Best-effort: set the type/name on the freshly created activity. A failure
                    // here must not mark the (already successful) upload as failed.
                    try {
                        apiClient.editActivity(newId, kind, name)
                    } catch (e: Exception) {
                        LOG.warn("Endurain editActivity failed for id {}", newId, e)
                    }
                    callback(true, null)
                } else {
                    callback(false, reason)
                }
            }
        }
    }

    /**
     * Uploads [gpxFile] to Wanderer (GPX only). [callback] fires with (success, reason).
     */
    fun uploadToWanderer(
        context: Context,
        gpxFile: File,
        callback: (success: Boolean, reason: String?) -> Unit
    ) {
        val serverUrl = GBApplication.getPrefs().preferences.getString(PREF_WANDERER_SERVER, null)
        if (serverUrl == null) {
            callback(false, null)
            return
        }
        val apiClient = WandererApiClient(serverUrl, WandererTokenManager(context))
        apiClient.uploadActivity(gpxFile) { newId, message ->
            if (newId != null && message == null) {
                LOG.info("Uploaded GPX to Wanderer, ID {}", newId)
                callback(true, null)
            } else {
                callback(false, message)
            }
        }
    }

    /**
     * Blocking variant of [uploadToEndurain] for use off the main thread (background worker,
     * batch upload from a coroutine on Dispatchers.IO). Waits up to [timeoutSeconds] for the
     * async upload to complete.
     */
    fun uploadToEndurainBlocking(
        context: Context,
        summary: BaseActivitySummary,
        fitFile: File,
        timeoutSeconds: Long = DEFAULT_UPLOAD_TIMEOUT_SECONDS
    ): Pair<Boolean, String?> = awaitUpload(context, timeoutSeconds) { done ->
        uploadToEndurain(context, summary, fitFile, done)
    }

    /**
     * Blocking variant of [uploadToWanderer]. See [uploadToEndurainBlocking].
     */
    fun uploadToWandererBlocking(
        context: Context,
        gpxFile: File,
        timeoutSeconds: Long = DEFAULT_UPLOAD_TIMEOUT_SECONDS
    ): Pair<Boolean, String?> = awaitUpload(context, timeoutSeconds) { done ->
        uploadToWanderer(context, gpxFile, done)
    }

    private inline fun awaitUpload(
        context: Context,
        timeoutSeconds: Long,
        start: ((success: Boolean, reason: String?) -> Unit) -> Unit
    ): Pair<Boolean, String?> {
        val latch = CountDownLatch(1)
        var success = false
        var reason: String? = null
        start { ok, r ->
            success = ok
            reason = r
            latch.countDown()
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            return false to context.getString(R.string.internet_helper_error_server_unreachable)
        }
        return success to reason
    }

    const val DEFAULT_UPLOAD_TIMEOUT_SECONDS = 90L
}
