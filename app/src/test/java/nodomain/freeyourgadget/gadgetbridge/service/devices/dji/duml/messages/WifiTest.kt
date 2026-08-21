package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages

import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAddress
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlModuleType
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacket
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the generated [Wifi] hierarchy (see `KaitaiCodeGenerator/src/main/resources/duml/wifi.ksy`).
 */
class WifiTest {
    @Test
    fun setPairingPinRequest_encode_isLengthPrefixedUtf8ForBothStrings() {
        val request = Wifi.SetPairingPin.Request(id = "AB", pin = "9")

        val expected = byteArrayOf(
            2, 'A'.code.toByte(), 'B'.code.toByte(), // id: 1-byte length + UTF-8
            1, '9'.code.toByte(), // pin: 1-byte length + UTF-8
        )
        assertTrue(expected.contentEquals(request.encode()))
    }

    @Test
    fun setPairingPinRequest_decodeOfEncode_roundTrips() {
        val original = Wifi.SetPairingPin.Request(id = "gadgetbridge", pin = "123456")
        val decoded = Wifi.SetPairingPin.Request.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun setPairingPinRequest_decode_toleratesAndIgnoresTrailingBytes() {
        val validPayload = Wifi.SetPairingPin.Request(id = "a", pin = "b").encode()
        val withTrailingGarbage = validPayload + byteArrayOf(0x00)
        val decoded = Wifi.SetPairingPin.Request.decode(withTrailingGarbage)
        assertEquals(Wifi.SetPairingPin.Request(id = "a", pin = "b"), decoded)
    }

    @Test
    fun setPairingPinResponse_decodeOfEncode_roundTrips() {
        val original = Wifi.SetPairingPin.Response(status = 0x00, pairingState = 0x02)
        val decoded = Wifi.SetPairingPin.Response.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun pairingPinApprovedRequest_decodeOfEncode_roundTrips() {
        val original = Wifi.PairingPinApproved.Request(status = 0x00)
        val decoded = Wifi.PairingPinApproved.Request.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun dumlCommand_decode_dispatchesSetPairingPinResponseUnderResponsePacketType() {
        val payload = Wifi.SetPairingPin.Response(status = 0x00, pairingState = 0x01).encode()
        val packet = DumlPacket(
            sender = DumlAddress(type = DumlModuleType.Wifi),
            receiver = DumlAddress(type = DumlModuleType.App),
            seq = 1,
            packetType = DumlPacketType.RESPONSE,
            cmdSet = Wifi.CMD_SET,
            cmd = Wifi.SetPairingPin.CMD,
            payload = payload,
        )
        assertTrue(DumlCommand.decode(packet) is Wifi.SetPairingPin.Response)
    }

    @Test
    fun dumlCommand_decode_dispatchesPairingPinApprovedRequestUnderBothPacketTypes() {
        val payload = Wifi.PairingPinApproved.Request(status = 0x00).encode()
        for (packetType in listOf(DumlPacketType.REQUEST, DumlPacketType.RESPONSE)) {
            val packet = DumlPacket(
                sender = DumlAddress(type = DumlModuleType.Wifi),
                receiver = DumlAddress(type = DumlModuleType.App),
                seq = 1,
                packetType = packetType,
                cmdSet = Wifi.CMD_SET,
                cmd = Wifi.PairingPinApproved.CMD,
                payload = payload,
            )
            assertTrue(
                "expected dispatch under $packetType",
                DumlCommand.decode(packet) is Wifi.PairingPinApproved.Request
            )
        }
    }
}
