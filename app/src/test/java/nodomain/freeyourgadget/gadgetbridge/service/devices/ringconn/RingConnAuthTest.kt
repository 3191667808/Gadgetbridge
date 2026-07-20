/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ringconn

import org.junit.Assert.assertEquals
import org.junit.Test

class RingConnAuthTest {
    @Test
    fun authCommand_matches_captured_on_device_pair() {
        // btsnoop 2026-07-02: challenge 0x11 -> response e1 b9 23; V = f6^96^2a = 0x4a (ring MAC ..F6:96:2A)
        val mac = byteArrayOf(0x00, 0x00, 0x00, 0xF6.toByte(), 0x96.toByte(), 0x2A)
        val cmd = RingConnAuth.authCommand(challenge = 0x11, mac = mac)
        assertEquals(
            "01 01 e1 b9 23 00",
            cmd.joinToString(" ") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) },
        )
    }
}
