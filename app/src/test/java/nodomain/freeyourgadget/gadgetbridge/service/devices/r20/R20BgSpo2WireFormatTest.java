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

/** Wire-format tests for the background SpO2 monitoring opcodes. */
public class R20BgSpo2WireFormatTest {

    @Test
    public void enableBgSpO2Monitor_on_payloadIs0x01() {
        R20Packet p = R20Packet.enableBgSpO2Monitor(true);
        byte[] b = p.encode();
        // grp=0x01 key=0x26 lo=0x07 hi=0x00 payload=0x01 crc_lo crc_hi
        assertEquals(0x01, b[0] & 0xFF);
        assertEquals(0x26, b[1] & 0xFF);
        assertEquals(0x01, b[4] & 0xFF);
    }

    @Test
    public void enableBgSpO2Monitor_off_payloadIs0x00() {
        R20Packet p = R20Packet.enableBgSpO2Monitor(false);
        byte[] b = p.encode();
        assertEquals(0x00, b[4] & 0xFF);
    }

    @Test
    public void setMonitorInterval_encodesMinutesAsSingleByte() {
        for (int m : new int[]{1, 5, 10, 30, 60, 120, 240}) {
            R20Packet p = R20Packet.setMonitorInterval(m);
            byte[] b = p.encode();
            assertEquals(0x01, b[0] & 0xFF);
            assertEquals(0x0C, b[1] & 0xFF);
            assertEquals("interval " + m, m, b[4] & 0xFF);
        }
    }
}
