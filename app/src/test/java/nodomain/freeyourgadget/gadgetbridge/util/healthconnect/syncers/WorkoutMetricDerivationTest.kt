package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The metrics a workout track implies but does not state.
 *
 * Issues #6414 (speed), #6415 (elevation) and #6421 (cadence): Gadgetbridge drew all three from the
 * track and Health Connect got none of them.
 */
class WorkoutMetricDerivationTest {

    private val t0 = 1_700_000_000_000L

    private fun point(
        secondsIn: Long,
        speed: Float = -1f,
        cadence: Int = -1,
        altitude: Double = GPSCoordinate.UNKNOWN_ALTITUDE,
        distance: Double = -1.0,
        location: GPSCoordinate? = null
    ): ActivityPoint {
        val p = ActivityPoint()
        p.time = Date(t0 + secondsIn * 1000)
        if (speed >= 0) p.speed = speed
        if (cadence >= 0) p.cadence = cadence
        if (distance >= 0) p.distance = distance
        // ActivityPoint.getAltitude() falls back to the fix's altitude, which is where a track
        // without a barometer carries it.
        p.location = location ?: if (altitude > GPSCoordinate.UNKNOWN_ALTITUDE) {
            GPSCoordinate(0.0, 0.0, altitude)
        } else {
            null
        }
        return p
    }

    // ------------------------------------------------------------------------------------- speed

    @Test
    fun speed_usesTheValueTheDeviceRecorded() {
        val samples = WorkoutMetricDerivation.speedSamples(
            listOf(point(0, speed = 3.0f), point(10, speed = 4.0f))
        )
        assertEquals(listOf(3.0, 4.0), samples.map { it.value })
    }

    /** A Huawei hike: the track has positions and timestamps, but no speed on any point. */
    @Test
    fun speed_isDerivedFromCumulativeDistanceWhenThePointsCarryNone() {
        val samples = WorkoutMetricDerivation.speedSamples(
            listOf(
                point(0, distance = 0.0),
                point(10, distance = 20.0),   // 20 m in 10 s
                point(20, distance = 50.0)    // 30 m in 10 s
            )
        )
        assertEquals(listOf(2.0, 3.0), samples.map { it.value })
    }

    @Test
    fun speed_fallsBackToTheDistanceBetweenFixes() {
        // ~111 m north at the equator is 0.001 degrees of latitude.
        val samples = WorkoutMetricDerivation.speedSamples(
            listOf(
                point(0, location = GPSCoordinate(0.0, 0.0)),
                point(100, location = GPSCoordinate(0.0, 0.001))
            )
        )
        assertEquals(1, samples.size)
        assertEquals(1.11, samples[0].value, 0.05)
    }

    @Test
    fun speed_discardsImplausibleJumps() {
        // A bad fix teleporting a degree of latitude in one second is not a 100 km/s runner.
        val samples = WorkoutMetricDerivation.speedSamples(
            listOf(
                point(0, location = GPSCoordinate(0.0, 0.0)),
                point(1, location = GPSCoordinate(0.0, 1.0))
            )
        )
        assertTrue("an implausible fix must not become a speed sample", samples.isEmpty())
    }

    // ----------------------------------------------------------------------------------- cadence

    @Test
    fun cadence_readsThePerPointValues() {
        val samples = WorkoutMetricDerivation.cadenceSamples(
            listOf(point(0, cadence = 80), point(10, cadence = 0), point(20, cadence = 85))
        )
        // 0 means "not pedalling / not recorded", not "a cadence of zero".
        assertEquals(listOf(80.0, 85.0), samples.map { it.value })
    }

    @Test
    fun cadence_isEmptyWhenTheTrackHasNone() {
        assertTrue(WorkoutMetricDerivation.cadenceSamples(listOf(point(0), point(10))).isEmpty())
    }

    // --------------------------------------------------------------------------------- elevation

    @Test
    fun elevation_sumsTheClimbsAndIgnoresTheDescents() {
        val gain = WorkoutMetricDerivation.elevationGainMeters(
            listOf(
                point(0, altitude = 100.0),
                point(10, altitude = 150.0),  // +50
                point(20, altitude = 120.0),  // -30, ignored
                point(30, altitude = 170.0)   // +50
            )
        )
        assertEquals(100.0, gain, 0.001)
    }

    @Test
    fun elevation_ignoresSensorNoise() {
        // A barometer jittering around a fixed altitude must not accumulate a climb.
        val points = (0..100).map { point(it * 10L, altitude = 100.0 + if (it % 2 == 0) 0.3 else -0.3) }
        assertEquals(0.0, WorkoutMetricDerivation.elevationGainMeters(points), 0.001)
    }

    @Test
    fun elevation_stillSeesASlowClimbMadeOfSmallSteps() {
        // Each step is below the noise floor, but the reference is held rather than consumed, so
        // the climb is not quietly swallowed one sub-threshold step at a time.
        val points = (0..100).map { point(it * 10L, altitude = 100.0 + it * 0.3) }
        assertEquals(30.0, WorkoutMetricDerivation.elevationGainMeters(points), 1.0)
    }

    @Test
    fun elevation_isZeroWhenTheTrackHasNoAltitudes() {
        assertEquals(0.0, WorkoutMetricDerivation.elevationGainMeters(listOf(point(0), point(10))), 0.001)
    }
}
