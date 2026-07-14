package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.SyncContext
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * A Health Connect client that records what it was asked to write.
 *
 * The production code reaches Health Connect only through [HealthConnectSupport], which takes its
 * client as a lambda - so a syncer can be driven end to end on a plain JVM, with no emulator and no
 * mocking framework, simply by handing it one of these.
 */
class FakeHealthConnectClient(
    private val failWith: ((List<Record>) -> Exception?)? = null
) : HealthConnectClient {
    val inserted = mutableListOf<Record>()
    var insertCalls = 0
        private set

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        insertCalls++
        failWith?.invoke(records)?.let { throw it }
        inserted.addAll(records)
        return InsertRecordsResponse(records.map { "id" })
    }

    inline fun <reified T : Record> recordsOf(): List<T> = inserted.filterIsInstance<T>()

    override val permissionController: PermissionController get() = throw NotImplementedError()
    override suspend fun updateRecords(records: List<Record>) = throw NotImplementedError()
    override suspend fun deleteRecords(recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>) = throw NotImplementedError()
    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) = throw NotImplementedError()
    override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> = throw NotImplementedError()
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> = throw NotImplementedError()
    override suspend fun aggregate(request: AggregateRequest): AggregationResult = throw NotImplementedError()
    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> = throw NotImplementedError()
    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> = throw NotImplementedError()
    override suspend fun getChangesToken(request: ChangesTokenRequest): String = throw NotImplementedError()
    override suspend fun getChanges(changesToken: String): ChangesResponse = throw NotImplementedError()
}

object HealthConnectTestSupport {

    val TEST_DEVICE: GBDevice =
        GBDevice("00:11:22:33:44:55", "Testie", "Testie Alias", "Test Folder", DeviceType.TEST)

    val TEST_METADATA: Metadata = Metadata.unknownRecordingMethod(
        Device(type = Device.TYPE_WATCH, manufacturer = "test", model = "test")
    )

    internal fun support(client: HealthConnectClient, enabled: Boolean = true): HealthConnectSupport =
        HealthConnectSupport({ client }, { enabled })

    internal fun context(
        client: HealthConnectClient,
        grantedPermissions: Set<String>,
        sliceStart: Instant,
        sliceEnd: Instant,
        activitySamples: List<ActivitySample> = emptyList(),
        enabled: Boolean = true,
        gbDevice: GBDevice = TEST_DEVICE
    ): SyncContext = SyncContext(
        support = support(client, enabled),
        // Only the sleep and workout syncers read strings off the Context; the rest never touch it.
        androidContext = org.mockito.Mockito.mock(android.content.Context::class.java),
        gbDevice = gbDevice,
        metadata = TEST_METADATA,
        zoneId = ZoneId.of("UTC"),
        sliceStart = sliceStart,
        sliceEnd = sliceEnd,
        grantedPermissions = grantedPermissions,
        activitySamples = activitySamples
    )

    /** A minimal ActivitySample; every field the syncers read is settable. */
    fun activitySample(
        timestamp: Int,
        steps: Int = 0,
        heartRate: Int = 0,
        activeCalories: Int = 0,
        distanceCm: Int = 0
    ): ActivitySample = object : ActivitySample {
        override fun getTimestamp(): Int = timestamp
        override fun getProvider(): nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider<*>? = null
        override fun getRawKind(): Int = nodomain.freeyourgadget.gadgetbridge.model.ActivityKind.UNKNOWN.code
        override fun getKind(): nodomain.freeyourgadget.gadgetbridge.model.ActivityKind =
            nodomain.freeyourgadget.gadgetbridge.model.ActivityKind.UNKNOWN
        override fun getRawIntensity(): Int = 0
        override fun getIntensity(): Float = 0f
        override fun getSteps(): Int = steps
        override fun getDistanceCm(): Int = distanceCm
        override fun getActiveCalories(): Int = activeCalories
        override fun getHeartRate(): Int = heartRate
        override fun setHeartRate(value: Int) {}
    }
}
