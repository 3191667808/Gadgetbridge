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
 * Wanderer (GPX). Used by the workout detail screen, the multi-select upload from the workout
 * list, and the background auto-upload worker.
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
     * FIT-native devices (Garmin, iGPSPORT) keep the original .fit at rawDetailsPath, which is
     * copied verbatim. For any other device the FIT is synthesized from the summary (and the
     * activity track, if one is available).
     *
     * [summaryData] supplies the session-level fields of the exported FIT (distance, calories,
     * average heart rate, pool length). Callers that have already parsed it pass it in to avoid
     * re-parsing; when omitted it is read off [summary].
     *
     * Blocking: call from an IO context.
     */
    fun buildFitFile(
        context: Context,
        gbDevice: GBDevice,
        summary: BaseActivitySummary,
        summaryData: ActivitySummaryData? = null
    ): File {
        val effectiveSummaryData = summaryData ?: summary.summaryData?.let {
            try {
                ActivitySummaryData.fromJson(it)
            } catch (e: Exception) {
                LOG.warn("Failed to parse stored summary data for summary {}", summary.id, e)
                null
            }
        }
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
            FitExporter().performExport(track, summary, effectiveSummaryData, outFile)
        }
        return outFile
    }

    /**
     * Outcome of an upload attempt. [remoteActivityId] is the id the service assigned to the new
     * activity, null on failure. [photoMediaId] is the media entry the header photo was uploaded
     * as, null when there was no photo or the photo step failed. [reason] is a localized failure
     * explanation, null on success.
     */
    data class UploadResult(
        val success: Boolean,
        val remoteActivityId: String?,
        val reason: String?,
        val photoMediaId: Int? = null
    )

    /**
     * Uploads [fitFile] to Endurain: refresh the access token, upload, then set the activity type,
     * name and header photo on the created activity. [callback] fires with an [UploadResult].
     *
     * The type/name and photo steps are best-effort: they run only after a successful upload and
     * their failure does not change the result.
     */
    fun uploadToEndurain(
        context: Context,
        summary: BaseActivitySummary,
        fitFile: File,
        callback: (UploadResult) -> Unit
    ) {
        val serverUrl = GBApplication.getPrefs().preferences.getString(PREF_ENDURAIN_SERVER, null)
        if (serverUrl == null) {
            callback(UploadResult(false, null, null))
            return
        }
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(serverUrl, tokenManager)
        val kind = ActivityKind.fromCode(summary.activityKind)
        val name = nameFor(context, summary)

        tokenManager.performTokenRefresh(serverUrl) {
            LOG.info("Uploading workout '{}' (type {}) to Endurain", name, kind)
            apiClient.uploadActivity(fitFile) { newId, reason ->
                if (newId == null) {
                    callback(UploadResult(false, null, reason))
                    return@uploadActivity
                }
                try {
                    apiClient.editActivity(newId, kind, name)
                } catch (e: Exception) {
                    LOG.warn("Endurain editActivity failed for id {}", newId, e)
                }
                val photoPath = summary.headerPhoto
                if (photoPath == null) {
                    callback(UploadResult(true, newId.toString(), null))
                    return@uploadActivity
                }
                try {
                    apiClient.uploadActivityPhoto(newId, File(photoPath)) { mediaId ->
                        callback(UploadResult(true, newId.toString(), null, mediaId))
                    }
                } catch (e: Exception) {
                    LOG.warn("Endurain uploadActivityPhoto failed for id {}", newId, e)
                    callback(UploadResult(true, newId.toString(), null))
                }
            }
        }
    }

    /**
     * Uploads [gpxFile] to Wanderer (GPX only). [callback] fires with an [UploadResult].
     */
    fun uploadToWanderer(
        context: Context,
        gpxFile: File,
        callback: (UploadResult) -> Unit
    ) {
        val serverUrl = GBApplication.getPrefs().preferences.getString(PREF_WANDERER_SERVER, null)
        if (serverUrl == null) {
            callback(UploadResult(false, null, null))
            return
        }
        val apiClient = WandererApiClient(serverUrl, WandererTokenManager(context))
        apiClient.uploadActivity(gpxFile) { newId, message ->
            if (newId != null && message == null) {
                LOG.info("Uploaded GPX to Wanderer, ID {}", newId)
                callback(UploadResult(true, newId.toString(), null))
            } else {
                callback(UploadResult(false, null, message))
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
    ): UploadResult = awaitUpload(context, timeoutSeconds) { done ->
        uploadToEndurain(context, summary, fitFile, done)
    }

    /**
     * Blocking variant of [uploadToWanderer]. See [uploadToEndurainBlocking].
     */
    fun uploadToWandererBlocking(
        context: Context,
        gpxFile: File,
        timeoutSeconds: Long = DEFAULT_UPLOAD_TIMEOUT_SECONDS
    ): UploadResult = awaitUpload(context, timeoutSeconds) { done ->
        uploadToWanderer(context, gpxFile, done)
    }

    /**
     * Outcome of a header photo sync. [mediaId] is the media entry now holding the photo, null
     * when the photo was removed or the upload failed.
     */
    data class PhotoSyncResult(val success: Boolean, val mediaId: Int?)

    /**
     * Brings the header photo of an already-uploaded Endurain activity in line with the local
     * workout: deletes the media entry a previous sync created ([previousMediaId]) and uploads
     * [photoFile], which may be null when the photo was removed locally.
     *
     * Media the user attached on the server is left alone, since only the entry Gadgetbridge
     * created is deleted. Blocking; call off the main thread.
     */
    fun syncEndurainPhotoBlocking(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaId: Int?,
        timeoutSeconds: Long = DEFAULT_UPLOAD_TIMEOUT_SECONDS
    ): PhotoSyncResult {
        val activityId = remoteActivityId.toIntOrNull()
        if (activityId == null) {
            LOG.warn("Cannot sync photo: non-numeric Endurain activity id '{}'", remoteActivityId)
            return PhotoSyncResult(false, previousMediaId)
        }
        val serverUrl = GBApplication.getPrefs().preferences.getString(PREF_ENDURAIN_SERVER, null)
            ?: return PhotoSyncResult(false, previousMediaId)
        val tokenManager = EndurainTokenManager(context)
        val apiClient = EndurainApiClient(serverUrl, tokenManager)

        val latch = CountDownLatch(1)
        var result = PhotoSyncResult(false, previousMediaId)
        tokenManager.performTokenRefresh(serverUrl) {
            Thread {
                try {
                    var removed = previousMediaId == null
                    if (previousMediaId != null) {
                        removed = apiClient.deleteActivityMedia(previousMediaId)
                        if (!removed) {
                            // Already gone server-side counts as removed, so a photo replaced
                            // after a manual deletion still goes through.
                            val remaining = apiClient.listActivityMedia(activityId)
                            removed = remaining != null && remaining.none { it.id == previousMediaId }
                        }
                    }
                    if (photoFile == null) {
                        result = PhotoSyncResult(removed, null)
                        latch.countDown()
                    } else {
                        apiClient.uploadActivityPhoto(activityId, photoFile) { mediaId ->
                            result = PhotoSyncResult(mediaId != null, mediaId)
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Endurain photo sync failed for activity {}", activityId, e)
                    latch.countDown()
                }
            }.start()
        }
        return if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) result else PhotoSyncResult(false, previousMediaId)
    }

    private inline fun awaitUpload(
        context: Context,
        timeoutSeconds: Long,
        start: ((UploadResult) -> Unit) -> Unit
    ): UploadResult {
        val latch = CountDownLatch(1)
        var result = UploadResult(false, null, null)
        start { r ->
            result = r
            latch.countDown()
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            return UploadResult(false, null, context.getString(R.string.auto_upload_timed_out))
        }
        return result
    }

    const val DEFAULT_UPLOAD_TIMEOUT_SECONDS = 90L
}
