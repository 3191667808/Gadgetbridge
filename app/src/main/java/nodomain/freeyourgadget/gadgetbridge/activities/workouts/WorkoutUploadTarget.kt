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
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.EndurainTokenManager
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.WandererTokenManager
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import java.io.File

/** File format a service accepts when an activity is uploaded. */
enum class WorkoutPayloadFormat { FIT, GPX }

/**
 * One online fitness tracker, described by what it accepts and by what it can still change on an
 * activity after the initial upload. [WorkoutUploadWorker] drives every service through this, so
 * supporting another one means adding an implementation rather than another branch.
 */
interface WorkoutUploadTarget {
    /**
     * Value stored in `WorkoutUpload.service`. Persisted, so see the registry in
     * [WorkoutUploadStore] before choosing one.
     */
    val service: Int

    /** Format to export a workout in for this service. */
    val format: WorkoutPayloadFormat

    /** Whether a workout with no GPS track can be uploaded at all. */
    val requiresTrack: Boolean

    /** Whether the user has finished setting this service up. */
    fun isLoggedIn(context: Context): Boolean

    /** Whether the user asked for workouts to be uploaded to this service automatically. */
    fun isAutoUploadEnabled(prefs: GBPrefs): Boolean

    /** Localized "upload to this service failed: %s" message. */
    fun failureMessage(context: Context, reason: String): String

    /** Notification id used to report a failure, so services do not overwrite each other. */
    val failureNotificationId: Int

    fun upload(
        context: Context,
        summary: BaseActivitySummary,
        payload: File
    ): WorkoutUploader.UploadResult

    /** Pushes the name and activity kind onto an activity that already exists remotely. */
    fun updateMetadata(
        context: Context,
        remoteActivityId: String,
        summary: BaseActivitySummary
    ): Boolean

    /**
     * Replaces the track of an existing remote activity, or null when the service has no endpoint
     * for it. A null result means the only way to push a changed track is to re-create the
     * activity, which the caller decides about separately since it is destructive.
     */
    fun replaceTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary
    ): Boolean?

    /**
     * Brings the header photo of an existing remote activity in line with the local workout, or
     * null when the service has no media API. [photoFile] is null when the photo was removed.
     */
    fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaId: Int?
    ): WorkoutUploader.PhotoSyncResult?
}

/** Every supported upload target. */
object WorkoutUploadTargets {
    val ALL: List<WorkoutUploadTarget> = listOf(EndurainUploadTarget, WandererUploadTarget)

    /** Targets the user has both enabled and finished setting up. */
    fun active(context: Context, prefs: GBPrefs): List<WorkoutUploadTarget> =
        ALL.filter { it.isAutoUploadEnabled(prefs) && it.isLoggedIn(context) }
}

object EndurainUploadTarget : WorkoutUploadTarget {
    override val service = WorkoutUploadStore.SERVICE_ENDURAIN
    override val format = WorkoutPayloadFormat.FIT

    // A FIT can be synthesized from the summary alone, so a trackless workout still uploads.
    override val requiresTrack = false

    override val failureNotificationId = 4711

    override fun isLoggedIn(context: Context) = EndurainTokenManager(context).isLoggedIn()

    override fun isAutoUploadEnabled(prefs: GBPrefs) =
        prefs.getBoolean(GBPrefs.ENDURAIN_AUTO_UPLOAD_ENABLED, false)

    override fun failureMessage(context: Context, reason: String): String =
        context.getString(nodomain.freeyourgadget.gadgetbridge.R.string.auto_upload_failed_endurain, reason)

    override fun upload(context: Context, summary: BaseActivitySummary, payload: File) =
        WorkoutUploader.uploadToEndurainBlocking(context, summary, payload)

    override fun updateMetadata(context: Context, remoteActivityId: String, summary: BaseActivitySummary) =
        WorkoutUploader.updateEndurainMetadataBlocking(context, remoteActivityId, summary)

    // Endurain's API can edit an activity's metadata but never its track.
    override fun replaceTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary
    ): Boolean? = null

    override fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaId: Int?
    ) = WorkoutUploader.syncEndurainPhotoBlocking(context, remoteActivityId, photoFile, previousMediaId)
}

object WandererUploadTarget : WorkoutUploadTarget {
    override val service = WorkoutUploadStore.SERVICE_WANDERER
    override val format = WorkoutPayloadFormat.GPX

    // Wanderer stores trails, so there is nothing to upload without a track.
    override val requiresTrack = true

    override val failureNotificationId = 4712

    override fun isLoggedIn(context: Context) = WandererTokenManager(context).isLoggedIn()

    override fun isAutoUploadEnabled(prefs: GBPrefs) =
        prefs.getBoolean(GBPrefs.WANDERER_AUTO_UPLOAD_ENABLED, false)

    override fun failureMessage(context: Context, reason: String): String =
        context.getString(nodomain.freeyourgadget.gadgetbridge.R.string.auto_upload_failed_wanderer, reason)

    override fun upload(context: Context, summary: BaseActivitySummary, payload: File) =
        WorkoutUploader.uploadToWandererBlocking(context, payload)

    // Wanderer infers a trail's name and type from the uploaded file.
    override fun updateMetadata(context: Context, remoteActivityId: String, summary: BaseActivitySummary) = true

    override fun replaceTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary
    ): Boolean = WorkoutUploader.updateWandererTrackBlocking(context, remoteActivityId, payload, summary)

    override fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaId: Int?
    ): WorkoutUploader.PhotoSyncResult? = null
}
