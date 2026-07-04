package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.sleep.CorrectedSleepSession
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SleepSyncerTest {
    @Test
    fun sleepClientRecordVersion_usesSuppliedRunVersionForRawAndEditedRecords() {
        val metadata = Metadata.autoRecorded(Device(Device.TYPE_WATCH, "Acme", "Band"))
        val sourceStartTs = 1_700_003_600L
        val rawSession = session(
            sourceStartTs = sourceStartTs,
            sourceEndTs = sourceStartTs + 3_600,
            startTs = sourceStartTs,
            endTs = sourceStartTs + 3_600,
            updatedAt = 1,
            edited = false
        )
        val editedSession = session(
            sourceStartTs = sourceStartTs,
            sourceEndTs = sourceStartTs + 3_600,
            startTs = sourceStartTs + 7_200,
            endTs = sourceStartTs + 10_800,
            updatedAt = 9_999,
            edited = true
        )
        val recordVersion = 1_700_000_500_000L

        val rawMetadata = sleepClientRecordMetadata(
            metadata,
            rawSession,
            Instant.ofEpochSecond(rawSession.startTs),
            recordVersion
        )
        val editedMetadata = sleepClientRecordMetadata(
            metadata,
            editedSession,
            Instant.ofEpochSecond(editedSession.startTs),
            recordVersion
        )

        assertEquals(recordVersion, rawMetadata.clientRecordVersion)
        assertEquals(recordVersion, editedMetadata.clientRecordVersion)
        assertEquals(rawMetadata.clientRecordId, editedMetadata.clientRecordId)
    }

    private fun session(
        sourceStartTs: Long,
        sourceEndTs: Long,
        startTs: Long,
        endTs: Long,
        updatedAt: Long,
        edited: Boolean
    ): CorrectedSleepSession = CorrectedSleepSession(
        null,
        1,
        1,
        sourceStartTs,
        sourceEndTs,
        startTs,
        endTs,
        0,
        updatedAt,
        edited,
        listOf(SleepStage(startTs, endTs, ActivityKind.LIGHT_SLEEP))
    )
}
