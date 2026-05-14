/*  Copyright (C) 2026 Dany Mestas

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.test.oura;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraOpcode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraPacket;

public class OuraPacketTest {

    @Test
    public void frame_getFirmwareVersion() {
        // From BLE.md § 0x08: "0803 0000 00"
        final byte[] frame = OuraPacket.frame(OuraOpcode.OP_GET_FW, new byte[]{0x00, 0x00, 0x00});
        assertArrayEquals(new byte[]{0x08, 0x03, 0x00, 0x00, 0x00}, frame);
    }

    @Test
    public void frame_getBattery_emptyPayload() {
        // From BLE.md § 0x0C: "0C00"
        final byte[] frame = OuraPacket.frame(OuraOpcode.OP_GET_BATTERY, new byte[0]);
        assertArrayEquals(new byte[]{0x0C, 0x00}, frame);
    }

    @Test
    public void parse_batteryResponse() {
        // Verification example A (BLE.md): 0D 06 63 00 00 00 A2 10
        final byte[] frame = new byte[]{0x0D, 0x06, 0x63, 0x00, 0x00, 0x00, (byte) 0xA2, 0x10};
        final OuraPacket pkt = OuraPacket.parse(frame);
        assertEquals(OuraOpcode.OP_GET_BATTERY_RESP, pkt.tag);
        assertEquals(6, pkt.payload.length);
        assertEquals(99, pkt.payload[0] & 0xff);
    }

    @Test
    public void parse_authNonceResponse() {
        // Verification example B: 2F 10 2C <15 bytes>
        final byte[] frame = new byte[]{0x2F, 0x10, 0x2C,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};
        final OuraPacket pkt = OuraPacket.parse(frame);
        assertEquals(OuraOpcode.OP_EXT, pkt.tag);
        assertEquals(0x10, pkt.payload.length);
        assertEquals(OuraOpcode.EXT_NONCE_RESP, pkt.payload[0] & 0xff);
    }

    @Test
    public void frameExt_authPayload_layout() {
        final byte[] cipher = new byte[OuraOpcode.AUTH_OK == 0 ? 16 : 16];
        for (int i = 0; i < 16; i++) {
            cipher[i] = (byte) (0xA0 | i);
        }
        final byte[] frame = OuraPacket.frameExt(OuraOpcode.EXT_AUTH, cipher);
        assertEquals(0x2F, frame[0] & 0xff);
        assertEquals(0x11, frame[1] & 0xff);
        assertEquals(OuraOpcode.EXT_AUTH, frame[2] & 0xff);
    }

    @Test
    public void readU32LE() {
        final byte[] buf = new byte[]{(byte) 0xC2, (byte) 0xBC, (byte) 0x82, 0x05};
        assertEquals(0x0582BCC2L, OuraPacket.readU32LE(buf, 0));
    }

    @Test
    public void readU16LE() {
        final byte[] buf = new byte[]{(byte) 0xFF, 0x01};
        assertEquals(0x01FF, OuraPacket.readU16LE(buf, 0));
    }

    @Test
    public void u32LE_roundTrip() {
        final byte[] enc = OuraPacket.u32LE(0x0582BCC2L);
        assertArrayEquals(new byte[]{(byte) 0xC2, (byte) 0xBC, (byte) 0x82, 0x05}, enc);
    }
}
