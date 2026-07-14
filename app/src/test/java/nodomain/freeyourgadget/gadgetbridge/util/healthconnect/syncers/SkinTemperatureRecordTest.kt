package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Skin temperature is a series record, and Health Connect matches series records by the record's
 * own boundary rather than by the sample times inside it - so one record per 24-hour slice was
 * unreadable for anything asking about a shorter window (the same trap as issue #5679).
 */
class SkinTemperatureRecordTest {

    private val zone = ZoneId.of("UTC")
    private val metadata = Metadata.unknownRecordingMethod(
        Device(type = Device.TYPE_RING, manufacturer = "test", model = "test")
    )
    private val t0 = Instant.parse("2026-07-14T00:00:00Z")

    private fun sample(minutesIn: Long, celsius: Float): TemperatureSample = object : TemperatureSample {
        override fun getTimestamp(): Long = t0.plusSeconds(minutesIn * 60).toEpochMilli()
        override fun getTemperature(): Float = celsius
        override fun getTemperatureType(): Int = TemperatureSample.TYPE_SKIN
        override fun getTemperatureLocation(): Int = TemperatureSample.LOCATION_FINGER
    }

    @Test
    fun aFullDayOfSamples_isSplitIntoHourlyRecords() {
        // A reading every ten minutes for 24 hours: one record, once upon a time.
        val samples = (0 until 24 * 6).map { sample(it * 10L, 33.0f) }

        val records = TemperatureSyncer.buildSkinTemperatureRecords(samples, 33.0, zone, metadata, "ring")

        assertTrue("expected roughly one record per hour, got ${records.size}", records.size >= 24)
        for (record in records) {
            val span = Duration.between(record.startTime, record.endTime)
            assertTrue("record spans $span, which exceeds the one-hour cap", span <= Duration.ofHours(1).plusSeconds(1))
        }

        // Splitting must not lose a reading.
        assertEquals(samples.size, records.sumOf { it.deltas.size })
    }

    /**
     * Health Connect defines a delta as the difference from the record's baseline, so a consumer
     * reconstructs each reading as baseline + delta. Differencing against the previous sample
     * instead made a steady temperature read back as a run of baseline values.
     */
    @Test
    fun everyDeltaIsAgainstTheBaseline() {
        val records = TemperatureSyncer.buildSkinTemperatureRecords(
            listOf(sample(0, 34.0f), sample(1, 34.5f), sample(2, 34.2f)),
            baseline = 33.0,
            zoneId = zone,
            metadata = metadata,
            deviceName = "ring"
        )

        val deltas = records.flatMap { it.deltas }.map { it.delta.inCelsius }
        assertEquals(1.0, deltas[0], 0.001)   // 34.0 - 33.0
        assertEquals(1.5, deltas[1], 0.001)   // 34.5 - 33.0
        assertEquals(1.2, deltas[2], 0.001)   // 34.2 - 33.0
    }

    /** The reading a consumer gets back must be the reading the device took. */
    @Test
    fun aSteadyTemperatureReconstructsToItself() {
        val baseline = 33.0
        val records = TemperatureSyncer.buildSkinTemperatureRecords(
            (0 until 5).map { sample(it.toLong(), 34.0f) },
            baseline = baseline,
            zoneId = zone,
            metadata = metadata,
            deviceName = "ring"
        )

        val reconstructed = records.flatMap { it.deltas }.map { baseline + it.delta.inCelsius }
        assertEquals(List(5) { 34.0 }, reconstructed.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun noSamples_yieldsNoRecords() {
        assertTrue(TemperatureSyncer.buildSkinTemperatureRecords(emptyList(), 33.0, zone, metadata, "ring").isEmpty())
    }
}
