package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The write kernel: the one place Gadgetbridge talks to Health Connect, and therefore the one place
 * the toggle, the quota and the platform's size limits have to be honoured.
 */
class HealthConnectSupportTest {

    @Before
    fun clearBackoff() {
        HealthConnectRateLimitBackoff.reset()
    }

    private fun steps(count: Int): List<StepsRecord> = (0 until count).map { i ->
        val end = Instant.parse("2026-07-14T08:00:00Z").plusSeconds(i * 60L)
        StepsRecord(
            startTime = end.minusSeconds(60),
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            count = 10,
            metadata = Metadata.autoRecorded(HealthConnectTestSupport.TEST_METADATA.device!!)
        )
    }

    // ------------------------------------------------------------------------------------- toggle

    /**
     * The guarantee: with Health Connect switched off in Gadgetbridge, no record reaches it. The
     * check lives here rather than at the entry points precisely so that a toggle flipped off
     * during a sync stops it.
     */
    @Test
    fun writingIsRefusedWhenTheToggleIsOff() {
        val client = FakeHealthConnectClient()
        val support = HealthConnectSupport({ client }, { false })

        try {
            runBlocking { support.insert(steps(3)) }
            fail("a write must not be attempted while Health Connect is disabled")
        } catch (expected: HealthConnectSyncDisabledException) {
            // expected
        }

        assertEquals("nothing may reach Health Connect", 0, client.insertCalls)
    }

    @Test
    fun writingProceedsWhenTheToggleIsOn() {
        val client = FakeHealthConnectClient()
        runBlocking { HealthConnectTestSupport.support(client).insert(steps(3)) }
        assertEquals(3, client.inserted.size)
    }

    /** A toggle flipped off between chunks stops the run, rather than finishing what it started. */
    @Test
    fun aToggleFlippedOffMidRunStopsTheRemainingChunks() {
        val client = FakeHealthConnectClient()
        var enabled = true
        val support = HealthConnectSupport({ client }, { enabled })

        // Two chunks' worth; the first write turns the toggle off behind us.
        val records = steps(HealthConnectSupport.CHUNK_SIZE + 10)
        try {
            runBlocking {
                support.insert(records.take(HealthConnectSupport.CHUNK_SIZE))
                enabled = false
                support.insert(records.drop(HealthConnectSupport.CHUNK_SIZE))
            }
            fail("the second write must be refused")
        } catch (expected: HealthConnectSyncDisabledException) {
            // expected
        }

        assertEquals("only the chunk written before the toggle went off", 1, client.insertCalls)
    }

    // --------------------------------------------------------------------------------- rate limit

    /**
     * Health Connect charges quota per API call, so exhausting it is a "come back later", not a
     * "this data is bad". It must not be retried into oblivion, and it must be distinguishable from
     * a malformed payload.
     */
    @Test
    fun aQuotaRejectionAbortsRatherThanBurningTheRetries() {
        val client = FakeHealthConnectClient {
            IllegalStateException("API call quota has been exceeded, please try again later")
        }
        val support = HealthConnectTestSupport.support(client)

        try {
            runBlocking { support.insert(steps(1)) }
            fail("a quota rejection must surface as a rate-limit abort")
        } catch (e: HealthConnectRateLimitException) {
            assertTrue("the caller needs to know how long to wait", e.retryAfterMillis > 0)
        }

        assertEquals("a quota rejection must not be retried", 1, client.insertCalls)
    }

    @Test
    fun aQuotaRejectionIsRecognisedThroughTheCauseChain() {
        // androidx rewraps the platform exception, so the marker often survives only in the cause.
        val wrapped = IllegalStateException(
            "insert failed",
            IllegalStateException("Rate limited: too many requests")
        )
        assertTrue(HealthConnectRateLimitBackoff.isRateLimitFailure(wrapped))
    }

    @Test
    fun anOrdinaryFailureIsNotMistakenForAQuotaRejection() {
        assertTrue(!HealthConnectRateLimitBackoff.isRateLimitFailure(IllegalStateException("bad record")))
    }

    // -------------------------------------------------------------------------------- oversizing

    /** The platform rejects an oversized payload; splitting it is cheaper than guessing small. */
    @Test
    fun anOversizedChunkIsSplitAndRetried() {
        var rejectionsLeft = 1
        val client = FakeHealthConnectClient { records ->
            if (rejectionsLeft > 0 && records.size > 2) {
                rejectionsLeft--
                IllegalStateException("Records chunk size limit: 5000000, was: 6200000")
            } else {
                null
            }
        }

        runBlocking { HealthConnectTestSupport.support(client).insert(steps(8)) }

        assertEquals("every record still lands", 8, client.inserted.size)
        assertTrue("the rejected chunk must have been split", client.insertCalls > 1)
    }
}
