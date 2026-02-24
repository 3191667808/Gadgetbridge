package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import org.junit.Assert.*
import org.junit.Test

class ShouldAdvanceSyncStateTest {

    private val baseEpoch = 1_700_000_000L

    @Test
    fun activityDataType_alwaysAdvances_evenWithZeroSynced() {
        assertTrue(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.ACTIVITY,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + 3600
            )
        )
    }

    @Test
    fun heartRateVariability_alwaysAdvances() {
        assertTrue(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.HRV,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + 3600
            )
        )
    }

    @Test
    fun sleep_doesNotAdvance_whenZeroSyncedAndNotStale() {
        assertFalse(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.SLEEP,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + 86400
            )
        )
    }

    @Test
    fun sleep_advances_whenRecordsSynced() {
        assertTrue(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.SLEEP,
                sliceTotalSynced = 1,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + 86400
            )
        )
    }

    @Test
    fun sleep_advances_whenStaleExceedsMaxStaleness() {
        val beyondStaleness = HealthConnectUtils.MAX_STALENESS_SECONDS + 1
        assertTrue(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.SLEEP,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + beyondStaleness
            )
        )
    }

    @Test
    fun sleep_doesNotAdvance_whenExactlyAtMaxStaleness() {
        assertFalse(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.SLEEP,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + HealthConnectUtils.MAX_STALENESS_SECONDS
            )
        )
    }

    @Test
    fun workouts_doesNotAdvance_whenZeroSyncedAndNotStale() {
        assertFalse(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + 86400
            )
        )
    }

    @Test
    fun workouts_advances_whenRecordsSynced() {
        assertTrue(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS,
                sliceTotalSynced = 3,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + 86400
            )
        )
    }

    @Test
    fun workouts_advances_whenStale() {
        val beyondStaleness = HealthConnectUtils.MAX_STALENESS_SECONDS + 1
        assertTrue(
            HealthConnectUtils.shouldAdvanceSyncState(
                HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS,
                sliceTotalSynced = 0,
                currentPersistedEpoch = baseEpoch,
                sliceEndEpoch = baseEpoch + beyondStaleness
            )
        )
    }

    @Test
    fun nonLateArrivingTypes_alwaysAdvance() {
        val alwaysAdvanceTypes = HealthConnectPermissionManager.HealthConnectDataType.entries
            .filter { it !in HealthConnectUtils.LATE_ARRIVING_DATA_TYPES }

        for (dataType in alwaysAdvanceTypes) {
            assertTrue(
                "Expected $dataType to always advance sync state",
                HealthConnectUtils.shouldAdvanceSyncState(
                    dataType,
                    sliceTotalSynced = 0,
                    currentPersistedEpoch = baseEpoch,
                    sliceEndEpoch = baseEpoch + 3600
                )
            )
        }
    }

    @Test
    fun lateArrivingDataTypes_containsSleepAndWorkouts() {
        assertTrue(HealthConnectPermissionManager.HealthConnectDataType.SLEEP in HealthConnectUtils.LATE_ARRIVING_DATA_TYPES)
        assertTrue(HealthConnectPermissionManager.HealthConnectDataType.WORKOUTS in HealthConnectUtils.LATE_ARRIVING_DATA_TYPES)
        assertEquals(2, HealthConnectUtils.LATE_ARRIVING_DATA_TYPES.size)
    }
}

