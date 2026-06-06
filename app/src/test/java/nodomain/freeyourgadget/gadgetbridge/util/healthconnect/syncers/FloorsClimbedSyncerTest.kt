package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.util.sensorcontext.TimestampedDouble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FloorsClimbedSyncerTest {
    @Test
    fun buildRecordsComputesFloorsFromPressureSlice() {
        val start = Instant.ofEpochMilli(1_000)
        val end = Instant.ofEpochMilli(4_000)
        val records = FloorsClimbedSyncer.buildRecords(
            pressureSeries = listOf(
                TimestampedDouble(500, 900.0),
                TimestampedDouble(1_000, 1013.25),
                TimestampedDouble(2_000, 1012.80),
                TimestampedDouble(3_000, 1012.45),
                TimestampedDouble(5_000, 1000.0)
            ),
            sliceStartBoundary = start,
            sliceEndBoundary = end,
            offset = ZoneId.of("UTC"),
            metadata = Metadata.autoRecorded(Device(type = Device.TYPE_PHONE, manufacturer = "Android", model = "Phone"))
        )

        assertEquals(1, records.size)
        assertEquals(2.0, records.first().floors, 0.0)
        assertEquals(start, records.first().startTime)
        assertEquals(end, records.first().endTime)
    }

    @Test
    fun buildRecordsSkipsWhenNoFloorsDetected() {
        val records = FloorsClimbedSyncer.buildRecords(
            pressureSeries = listOf(
                TimestampedDouble(1_000, 1013.25),
                TimestampedDouble(2_000, 1013.24),
                TimestampedDouble(3_000, 1013.23)
            ),
            sliceStartBoundary = Instant.ofEpochMilli(1_000),
            sliceEndBoundary = Instant.ofEpochMilli(3_000),
            offset = ZoneId.of("UTC"),
            metadata = Metadata.autoRecorded(Device(type = Device.TYPE_PHONE, manufacturer = "Android", model = "Phone"))
        )

        assertTrue(records.isEmpty())
    }
}
