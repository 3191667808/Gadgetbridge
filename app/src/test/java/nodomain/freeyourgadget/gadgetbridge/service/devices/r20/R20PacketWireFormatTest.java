/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.r20;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.devices.r20.R20Constants;

/** Verifies the wire-format invariants discovered via HCI snoop of the
 *  official companion app. Any regression here means the firmware will
 *  silently reject our commands. */
public class R20PacketWireFormatTest {

    @Test
    public void startMeasurement_isTwoBytes_actionThenType() {
        // Companion app sends [0x01, 0x02] to start SpO2 (type=2)
        R20Packet pkt = R20Packet.startMeasurement(2, true);
        byte[] bytes = pkt.encode();
        // frame: grp key lo hi payload[2] crc_lo crc_hi  -> 8 bytes total
        assertEquals(8, bytes.length);
        // payload is bytes[4..6]
        assertArrayEquals(new byte[]{0x01, 0x02}, new byte[]{bytes[4], bytes[5]});
    }

    @Test
    public void stopMeasurement_isTwoBytes() {
        R20Packet pkt = R20Packet.startMeasurement(0, false);
        byte[] bytes = pkt.encode();
        assertEquals(8, bytes.length);
        assertArrayEquals(new byte[]{0x00, 0x00}, new byte[]{bytes[4], bytes[5]});
    }

    @Test
    public void primeSensors_emitsAppCtl0x0C_with_0x0101() {
        R20Packet pkt = R20Packet.primeSensors();
        byte[] bytes = pkt.encode();
        assertEquals(0x03, bytes[0]);        // group: AppControl
        assertEquals(0x0C, bytes[1]);        // key
        assertEquals(0x01, bytes[4]);
        assertEquals(0x01, bytes[5]);
    }

    @Test
    public void deleteHistory_sendsSinglePayloadByte0x02() {
        for (int op : new int[]{
                R20Constants.HEALTH_DELETE_SPORT,
                R20Constants.HEALTH_DELETE_SLEEP,
                R20Constants.HEALTH_DELETE_HEART,
                R20Constants.HEALTH_DELETE_BLOOD,
                R20Constants.HEALTH_DELETE_ALL}) {
            R20Packet pkt = R20Packet.deleteHistory(op);
            byte[] bytes = pkt.encode();
            assertEquals(7, bytes.length); // grp key lo hi 0x02 crc_lo crc_hi
            assertEquals((op >> 8) & 0xFF, bytes[0] & 0xFF);
            assertEquals(op & 0xFF, bytes[1] & 0xFF);
            assertEquals(0x02, bytes[4] & 0xFF);
        }
    }

    @Test
    public void historyAck_isFourPayloadBytes() {
        R20Packet pkt = R20Packet.historyAck(R20Constants.HEALTH_STREAM_ALL, 20);
        byte[] bytes = pkt.encode();
        assertEquals(10, bytes.length);
        // payload: stream_hi, stream_lo, len_lo, len_hi
        assertEquals(0x05, bytes[4] & 0xFF);
        assertEquals(0x18, bytes[5] & 0xFF);
        assertEquals(20,   bytes[6] & 0xFF);
        assertEquals(0,    bytes[7] & 0xFF);
    }
}
