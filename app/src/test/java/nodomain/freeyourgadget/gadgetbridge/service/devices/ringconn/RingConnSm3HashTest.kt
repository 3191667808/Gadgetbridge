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

class RingConnSm3HashTest {
    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    @Test
    fun sm3_abc_known_answer() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            hex(RingConnSm3Hash.digest("abc".encodeToByteArray())),
        )
    }

    @Test
    fun sm3_64byte_known_answer() {
        val msg = "abcd".repeat(16).encodeToByteArray()
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            hex(RingConnSm3Hash.digest(msg)),
        )
    }
}
