package nodomain.freeyourgadget.gadgetbridge.util.sensorcontext

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class PhoneSensorContextTest {
    @After
    fun tearDown() {
        PhoneSensorContext.clearForTests()
    }

    @Test
    fun ringBufferCapsAndEvictsOldest() {
        PhoneSensorContext.setCapacityForTests(3)

        PhoneSensorContext.addPressure(1, 1013.0)
        PhoneSensorContext.addPressure(2, 1012.0)
        PhoneSensorContext.addPressure(3, 1011.0)
        PhoneSensorContext.addPressure(4, 1010.0)

        val snapshot = PhoneSensorContext.snapshot()
        assertEquals(3, snapshot.pressureSeries.size)
        assertEquals(listOf(2L, 3L, 4L), snapshot.pressureSeries.map { it.tsMs })
    }

    @Test
    fun concurrentAddsAreThreadSafe() {
        PhoneSensorContext.setCapacityForTests(2_000)

        val first = Thread {
            repeat(1_000) { PhoneSensorContext.addAccelMagnitude(it.toLong(), it.toDouble()) }
        }
        val second = Thread {
            repeat(1_000) { PhoneSensorContext.addAccelMagnitude((1_000 + it).toLong(), it.toDouble()) }
        }

        first.start()
        second.start()
        first.join()
        second.join()

        val snapshot = PhoneSensorContext.snapshot()
        assertEquals(2_000, snapshot.accelMagnitudeSeries.size)
        assertEquals(2_000, snapshot.accelMagnitudeSeries.map { it.tsMs }.toSet().size)
    }

    @Test
    fun snapshotIsCopyNotLiveView() {
        PhoneSensorContext.addLightLux(1, 10.0)

        val snapshot = PhoneSensorContext.snapshot()
        assertNotSame(PhoneSensorContext.lightLuxSeries, snapshot.lightLuxSeries)
        snapshot.lightLuxSeries.clear()

        assertEquals(1, PhoneSensorContext.snapshot().lightLuxSeries.size)
    }
}
