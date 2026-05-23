/*  Copyright (C) 2026 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.database.migrations

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.database.DataMigration
import nodomain.freeyourgadget.gadgetbridge.database.DataMigrationStatus
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.entities.DeviceDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericMetricSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.MetricSample
import org.slf4j.LoggerFactory

/**
 * Populates the [BaseActivitySummaryDao.Properties.HasGps] column for all existing activity
 * summaries by inspecting their stored data.
 *
 * Rows are processed in batches; any row with a `NULL` value for [BaseActivitySummary.hasGps]
 * is considered pending. This makes the migration naturally resumable across app restarts.
 */
class BaseActivitySummaryGpsVo2Migration : DataMigration {
    override val id = "activity_summary_has_gps_and_vo2"

    private val deviceById: MutableMap<Long, GBDevice> = HashMap()

    override fun migrate(context: Context, status: DataMigrationStatus) {
        if (status.position < 0) {
            // First call - get the start and total remaining
            GBApplication.acquireDbReadOnly().use { db ->
                val dao = db.daoSession.baseActivitySummaryDao
                val firstActivity = dao.queryBuilder()
                    .orderAsc(BaseActivitySummaryDao.Properties.Id)
                    .limit(1)
                    .list()

                if (firstActivity.isEmpty()) {
                    LOG.debug("No activities to migrate")
                    status.done = true
                    return
                }

                // We use the ID since there might be multiple activities with the same startTime, and we might get
                // stuck in an infinite loop
                status.position = firstActivity[0].id
                status.total = dao.queryBuilder()
                    .where(BaseActivitySummaryDao.Properties.Id.ge(status.position))
                    .count()

                LOG.debug(
                    "There is a total of {} activities to migrate, starting from id={}",
                    status.total,
                    status.position
                )
            }
        }

        GBApplication.acquireDB().use { db ->
            val baseActivitySummaryDao = db.daoSession.baseActivitySummaryDao
            val deviceDao = db.daoSession.deviceDao

            val batch = baseActivitySummaryDao.queryBuilder()
                .where(BaseActivitySummaryDao.Properties.Id.ge(status.position))
                .orderAsc(BaseActivitySummaryDao.Properties.StartTime)
                .limit(BATCH_SIZE)
                .list()

            if (batch.isEmpty()) {
                LOG.debug("Got an empty batch")
                status.done = true
                return
            }

            LOG.debug("Processing batch of {} activities", batch.size)

            val genericMetricSamples = ArrayList<GenericMetricSample>(batch.size)

            for (summary in batch) {
                LOG.trace("Migrating activity {} at {}", summary.id, summary.startTime)

                val gbDevice = deviceById.compute(summary.deviceId) { _, _ ->
                    val devices = deviceDao.queryBuilder()
                        .where(DeviceDao.Properties.Id.eq(summary.deviceId))
                        .list()
                    if (!devices.isEmpty()) {
                        return@compute GBApplication.app()
                            .deviceManager
                            .getDeviceByAddress(devices[0].identifier)
                    }
                    return@compute null
                }
                if (gbDevice == null) {
                    LOG.error("Failed to find device {}", summary.deviceId)
                    summary.hasGps = false
                } else {
                    val activitySummaryParser = gbDevice.deviceCoordinator.getActivitySummaryParser(gbDevice, context)
                    if (activitySummaryParser == null) {
                        LOG.warn(
                            "Unable to parse activity {} for {} ({}), summary parser is null",
                            summary.id,
                            gbDevice.address,
                            gbDevice.type
                        )
                        status.position = summary.id + 1
                        continue
                    }
                    // Re-processing the summary should be enough to populate the hasGps column
                    val workout = activitySummaryParser.parseWorkout(summary, true)
                    if (summary.hasGps == null) {
                        LOG.warn("summary parser did not populate hasGps")
                        summary.hasGps = false
                    }
                    val vo2max = workout.data.getNumber(ActivitySummaryEntries.MAXIMUM_OXYGEN_UPTAKE, 0).toDouble()
                    if (vo2max > 0) {
                        val sample = GenericMetricSample()
                        sample.timestamp = summary.startTime.time
                        sample.setMetric(MetricSample.Metric.GARMIN_MET_MAX_VO2, vo2max, null)
                        sample.deviceId = summary.deviceId
                        sample.userId = summary.userId
                        genericMetricSamples.add(sample)
                    }
                }

                baseActivitySummaryDao.update(summary)

                status.position = summary.id + 1
            }

            if (!genericMetricSamples.isEmpty()) {
                LOG.debug("Persisting {} vo2max samples", genericMetricSamples.size)
                db.daoSession.genericMetricSampleDao.insertOrReplaceInTx(genericMetricSamples)
            }

            status.processed += batch.size.toLong()

            return
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(BaseActivitySummaryGpsVo2Migration::class.java)
        private const val BATCH_SIZE = 10
    }
}
