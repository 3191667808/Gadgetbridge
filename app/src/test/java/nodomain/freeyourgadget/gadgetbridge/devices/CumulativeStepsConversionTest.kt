package nodomain.freeyourgadget.gadgetbridge.devices

import de.greenrobot.dao.AbstractDao
import de.greenrobot.dao.Property
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Devices that report a running total (Garmin, Cmf, Overmax) have their samples converted into
 * per-minute deltas by [AbstractSampleProvider.convertCumulativeSteps], and the conversion depends
 * on the window it is asked for.
 *
 * That is what made Health Connect disagree with Gadgetbridge's own step chart (issues #5735,
 * #5954): the charts always ask from local midnight, where the conversion happens to be right,
 * while Health Connect asks from wherever its sync cursor sits.
 */
class CumulativeStepsConversionTest {

    /** 2026-07-14T00:00:00Z, so the whole fixture sits inside one UTC day. */
    private val midnight = Instant.parse("2026-07-14T00:00:00Z").epochSecond.toInt()

    /** AbstractActivitySample leaves the accessors to the generated entities; this stands in. */
    private class Sample(private var ts: Int) : AbstractActivitySample() {
        private var stepCount = 0
        private var distance = 0
        private var calories = 0

        override fun setTimestamp(timestamp: Int) { ts = timestamp }
        override fun getTimestamp(): Int = ts
        override fun setSteps(steps: Int) { stepCount = steps }
        override fun getSteps(): Int = stepCount
        override fun setDistanceCm(distanceCm: Int) { distance = distanceCm }
        override fun getDistanceCm(): Int = distance
        override fun setActiveCalories(activeCalories: Int) { calories = activeCalories }
        override fun getActiveCalories(): Int = calories
        override fun setUserId(userId: Long) {}
        override fun getUserId(): Long = 0
        override fun setDeviceId(deviceId: Long) {}
        override fun getDeviceId(): Long = 0
    }

    /**
     * An in-memory stand-in for a device that stores a running total. Only the pieces
     * [AbstractSampleProvider.convertCumulativeSteps] actually touches are real.
     */
    private class CumulativeProvider(
        private val stored: List<Sample>
    ) : AbstractSampleProvider<Sample>(null, null) {

        override fun getLastSampleWithStepsBefore(timestampTo: Int, stepsSampleProperty: Property?): Sample? =
            stored.filter { it.timestamp <= timestampTo }.maxByOrNull { it.timestamp }

        override fun getSampleDao(): AbstractDao<Sample, *> = throw UnsupportedOperationException()
        override fun getRawKindSampleProperty(): Property? = null
        override fun getTimestampSampleProperty(): Property = throw UnsupportedOperationException()
        override fun getDeviceIdentifierSampleProperty(): Property = throw UnsupportedOperationException()
        override fun normalizeType(rawType: Int): ActivityKind = ActivityKind.UNKNOWN
        override fun toRawActivityKind(activityKind: ActivityKind): Int = 0
        override fun normalizeIntensity(rawIntensity: Int): Float = 0f
        override fun createActivitySample(): Sample = Sample(0)
    }

    /** Minute-by-minute running totals: 10 steps every minute, all on the same day. */
    private fun storedDay(minutes: Int): List<Sample> =
        (0 until minutes).map { i ->
            Sample(midnight + i * 60).apply { setSteps((i + 1) * 10) }
        }

    /** A fresh copy of a window of the stored day, as the DAO would hand it over. */
    private fun window(stored: List<Sample>, fromMinute: Int, toMinute: Int): List<Sample> =
        stored.subList(fromMinute, toMinute).map { s ->
            Sample(s.timestamp).apply { setSteps(s.steps) }
        }

    private fun convert(stored: List<Sample>, fromMinute: Int, toMinute: Int): List<Int> {
        val samples = window(stored, fromMinute, toMinute)
        CumulativeProvider(stored).convertCumulativeSteps(samples, null)
        return samples.map { it.steps }
    }

    @Test
    fun fullDayWindow_yieldsThePerMinuteSteps() {
        val stored = storedDay(10)
        // The first sample of the day has no predecessor, so it keeps its running total - which for
        // minute one is the same thing as its step count.
        assertEquals(List(10) { 10 }, convert(stored, 0, 10))
    }

    /**
     * The regression. A window opened mid-day used to inflate its second sample by the running
     * total of the day so far, because the conversion captured its baseline after having already
     * rewritten the first sample into a delta.
     */
    @Test
    fun midDayWindow_matchesTheSameStretchOfTheFullDay() {
        val stored = storedDay(10)
        val fullDay = convert(stored, 0, 10)
        val fromMinuteFive = convert(stored, 5, 10)

        assertEquals(
            "a window opened mid-day must report the same steps as that stretch of the full day",
            fullDay.subList(5, 10),
            fromMinuteFive
        )
        assertEquals(List(5) { 10 }, fromMinuteFive)
    }

    @Test
    fun midDayWindow_preservesTheTotal() {
        val stored = storedDay(10)
        // Minutes 3..10 of a day accruing 10 steps a minute: 70 steps, no more and no less.
        assertEquals(70, convert(stored, 3, 10).sum())
    }

    /**
     * A provider that does not pre-shift its timestamps (Cmf) would otherwise look its own first
     * sample up as its own predecessor and subtract it from itself, zeroing it.
     */
    @Test
    fun firstSampleOfAWindow_isNotSubtractedFromItself() {
        val stored = storedDay(10)
        val converted = convert(stored, 5, 10)
        assertEquals("the first sample of the window must not be zeroed", 10, converted.first())
    }

    @Test
    fun startOfDay_hasNoPredecessorToSubtract() {
        val stored = storedDay(3)
        // Nothing precedes midnight, so the running total stands as the count.
        assertEquals(listOf(10, 10, 10), convert(stored, 0, 3))
    }

    private fun Instant.atUtc() = atZone(ZoneOffset.UTC)
}
