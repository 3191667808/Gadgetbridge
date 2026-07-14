package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.runBlocking
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.FakeHealthConnectClient
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectSyncDisabledException
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectTestSupport
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectTestSupport.activitySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HeartRateSyncerTest {

    private val grantedPermissions = setOf(HealthPermission.getWritePermission(HeartRateRecord::class))
    private val baseTs = 1_700_000_000

    private fun sync(values: List<Int>, enabled: Boolean = true): FakeHealthConnectClient {
        val samples = values.mapIndexed { i, bpm -> activitySample(baseTs + i * 60, heartRate = bpm) }
        val client = FakeHealthConnectClient()
        val ctx = HealthConnectTestSupport.context(
            client = client,
            grantedPermissions = grantedPermissions,
            sliceStart = Instant.ofEpochSecond(baseTs.toLong()),
            sliceEnd = Instant.ofEpochSecond(baseTs.toLong() + values.size * 60L),
            activitySamples = samples,
            enabled = enabled
        )
        runBlocking { HeartRateSyncer.sync(ctx) }
        return client
    }

    private fun syncBpm(values: List<Int>): List<Long> =
        sync(values).recordsOf<HeartRateRecord>().flatMap { it.samples }.map { it.beatsPerMinute }

    @Test
    fun sentinel255_isNotSynced() {
        val bpm = syncBpm(listOf(60, 255, 62))
        assertTrue("255 sentinel must not reach Health Connect", 255L !in bpm)
        assertEquals(listOf(60L, 62L), bpm)
    }

    @Test
    fun zero_isNotSynced() {
        val bpm = syncBpm(listOf(60, 0, 62))
        assertEquals(listOf(60L, 62L), bpm)
    }

    @Test
    fun validRange_isSynced() {
        val bpm = syncBpm(listOf(1, 300))
        assertEquals(listOf(1L, 300L), bpm)
    }

    @Test
    fun aboveHcLimit_isNotSynced() {
        val bpm = syncBpm(listOf(60, 301, 62))
        assertEquals(listOf(60L, 62L), bpm)
    }

    /**
     * Health Connect matches a series record by the record's own boundary, never by the sample
     * times inside it, so a night in one record is unreadable for anything querying a shorter
     * window. Issue #5679.
     */
    @Test
    fun longRun_isSplitIntoRecordsOfAtMostOneHour() {
        // Nine hours of continuous minute-by-minute heart rate: one overnight block, once upon a time.
        val records = sync(List(9 * 60) { 60 }).recordsOf<HeartRateRecord>()

        assertTrue("expected the run to be split into several records, got ${records.size}", records.size >= 9)
        for (record in records) {
            val span = Duration.between(record.startTime, record.endTime)
            assertTrue(
                "record spans $span, which exceeds the one-hour cap",
                span <= MAX_HEART_RATE_RECORD_SPAN
            )
        }

        // Splitting must not drop or duplicate a single sample.
        val samples = records.flatMap { it.samples }
        assertEquals(9 * 60, samples.size)
        assertEquals(samples.size, samples.map { it.time }.distinct().size)
    }

    /** The gate is enforced inside the write, so nothing reaches Health Connect when it is off. */
    @Test(expected = HealthConnectSyncDisabledException::class)
    fun disabledToggle_blocksTheWrite() {
        sync(listOf(60, 61, 62), enabled = false)
    }
}
