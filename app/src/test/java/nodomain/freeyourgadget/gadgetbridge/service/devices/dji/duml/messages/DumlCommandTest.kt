package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages

import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAddress
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlModuleType
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlCommandTest {
    @Test
    fun unknown_encode_echoesBackTheRawPayloadItWasDecodedFrom() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val unknown = DumlCommand.decode(
            cmdSet = 0x99,
            cmd = 0x99,
            packetType = DumlPacketType.REQUEST,
            payload = payload
        )
        assertTrue(unknown is DumlCommand.Unknown)
        assertArrayEquals(payload, unknown.encode())
    }

    @Test
    fun unknown_canBeWrappedInToPacket_justLikeAGeneratedCommand() {
        val unknown = DumlCommand.decode(
            cmdSet = 0x99,
            cmd = 0x99,
            packetType = DumlPacketType.REQUEST,
            payload = byteArrayOf(0x2a)
        )
        val packet = unknown.toPacket(
            sender = DumlAddress(type = DumlModuleType.App),
            receiver = DumlAddress(type = DumlModuleType.Wifi),
            seq = 1
        )

        assertEquals(0x99, packet.cmdSet)
        assertEquals(0x99, packet.cmd)
        assertArrayEquals(byteArrayOf(0x2a), packet.payload)
    }

    @Test
    fun decode_ofAKnownCommand_dispatchesAwayFromUnknown() {
        val payload = Wifi.PairingPinApproved.Request(status = 0x00).encode()
        val decoded = DumlCommand.decode(
            cmdSet = Wifi.CMD_SET,
            cmd = Wifi.PairingPinApproved.CMD,
            packetType = DumlPacketType.REQUEST,
            payload = payload
        )
        assertTrue(decoded !is DumlCommand.Unknown)
    }
}
