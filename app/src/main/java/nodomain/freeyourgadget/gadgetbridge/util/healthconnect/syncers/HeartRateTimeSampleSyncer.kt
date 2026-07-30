/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal object HeartRateTimeSampleSyncer : AbstractTimeSampleSyncer<HeartRateSample, HeartRateRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(HeartRateTimeSampleSyncer::class.java)
    override val recordClass: KClass<HeartRateRecord> = HeartRateRecord::class
    override val clientRecordType = "heart-rate"

    override fun getSampleProvider(
        gbDevice: GBDevice,
        daoSession: DaoSession
    ): TimeSampleProvider<out HeartRateSample>? =
        gbDevice.deviceCoordinator.getHeartRateSampleProvider(gbDevice, daoSession)

    override fun convertSample(
        sample: HeartRateSample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String
    ): HeartRateRecord? {
        val bpm = sample.heartRate
        if (bpm !in 1..300 || bpm == 255) {
            if (bpm != 0) {
                logger.skipOutOfRange(deviceName, "HeartRate", bpm, "1..300 bpm (255 excluded)")
            }
            return null
        }

        val time = Instant.ofEpochMilli(sample.timestamp)
        return HeartRateRecord(
            startTime = time,
            startZoneOffset = offset,
            endTime = time.plusMillis(1),
            endZoneOffset = offset,
            samples = listOf(HeartRateRecord.Sample(time, bpm.toLong())),
            metadata = metadata
        )
    }
}
