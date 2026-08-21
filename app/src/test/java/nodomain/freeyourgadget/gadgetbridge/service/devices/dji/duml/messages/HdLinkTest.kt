package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the generated [HdLink.HdLinkState.Request] (see `KaitaiCodeGenerator/src/main/resources/duml/hdlink.ksy`).
 */
class HdLinkTest {
    @Test
    fun decode_ofRealCapturedByte_meansLinked() {
        val state = HdLink.HdLinkState.Request.decode(byteArrayOf(0x02))
        assertEquals(2, state.state)
    }

    @Test
    fun decodeOfEncode_roundTrips() {
        val original = HdLink.HdLinkState.Request(state = 0x00)
        assertArrayEquals(byteArrayOf(0x00), original.encode())
        assertEquals(original, HdLink.HdLinkState.Request.decode(original.encode()))
    }
}
