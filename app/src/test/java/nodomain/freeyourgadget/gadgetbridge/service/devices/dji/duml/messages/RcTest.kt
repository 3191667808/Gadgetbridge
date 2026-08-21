package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages

import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the generated [Rc.BatteryInfo.Request] (see `KaitaiCodeGenerator/src/main/resources/duml/rc.ksy`).
 */
class RcTest {
    private val knownPayload = GB.hexStringToByteArray("3e0900005b00")

    @Test
    fun decode_ofRealCapturedPayload_matchesTheDissectorsWorkedExample() {
        val battery = Rc.BatteryInfo.Request.decode(knownPayload)
        assertEquals(2366, battery.remainingCapacity)
        assertEquals(0, battery.unknown2)
        assertEquals(91, battery.percent)
        assertEquals(2600, battery.remainingCapacity * 100 / battery.percent)
    }

    @Test
    fun encode_ofDecodedGoldenPayload_isByteExact() {
        val battery = Rc.BatteryInfo.Request.decode(knownPayload)
        assertEquals(knownPayload.toList(), battery.encode().toList())
    }
}
