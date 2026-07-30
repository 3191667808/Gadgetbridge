/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal object BloodPressureSyncer : AbstractTimeSampleSyncer<BloodPressureSample, BloodPressureRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(BloodPressureSyncer::class.java)
    override val recordClass: KClass<BloodPressureRecord> = BloodPressureRecord::class
    override val clientRecordType = "blood-pressure"

    override fun getSampleProvider(
        gbDevice: GBDevice,
        daoSession: DaoSession
    ): TimeSampleProvider<out BloodPressureSample>? =
        gbDevice.deviceCoordinator.getBloodPressureSampleProvider(gbDevice, daoSession)

    override fun convertSample(
        sample: BloodPressureSample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String
    ): BloodPressureRecord? {
        val systolic = sample.bpSystolic.toDouble()
        val diastolic = sample.bpDiastolic.toDouble()
        if (systolic !in 20.0..200.0 || diastolic !in 10.0..180.0) {
            logger.skipOutOfRange(
                deviceName,
                "BloodPressure",
                "$systolic/$diastolic",
                "systolic 20..200 and diastolic 10..180 mmHg"
            )
            return null
        }

        return BloodPressureRecord(
            time = Instant.ofEpochMilli(sample.timestamp),
            zoneOffset = offset,
            metadata = metadata,
            systolic = Pressure.millimetersOfMercury(systolic),
            diastolic = Pressure.millimetersOfMercury(diastolic),
            bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN
        )
    }
}
