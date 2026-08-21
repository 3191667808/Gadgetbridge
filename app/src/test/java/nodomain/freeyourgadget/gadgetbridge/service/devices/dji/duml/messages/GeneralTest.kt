package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the generated [General.SetTime.Request] (see `KaitaiCodeGenerator/src/main/resources/duml/general.ksy`).
 */
class GeneralTest {
    @Test
    fun setTimeRequest_encode_isYearLeThenFiveBytes() {
        val request = General.SetTime.Request(year = 2026, month = 8, day = 13, hour = 21, minute = 5, second = 30)
        val expected = byteArrayOf(
            0xea.toByte(), 0x07, // 2026 = 0x07ea, LE
            8, 13, 21, 5, 30,
        )
        assertArrayEquals(expected, request.encode())
    }

    @Test
    fun setTimeRequest_decodeOfEncode_roundTrips() {
        val original = General.SetTime.Request(year = 2026, month = 8, day = 13, hour = 21, minute = 5, second = 30)
        val decoded = General.SetTime.Request.decode(original.encode())
        assertEquals(original, decoded)
    }
}
