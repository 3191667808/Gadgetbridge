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
import org.junit.Assert.assertNull
import org.junit.Test

class RingConnRecordParserTest {
    private fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = hex[2 * i].digitToInt(16); val lo = hex[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }

    @Test fun parses_6record_activity_frame() {
        val frame = hexDecode(
            "4c00000c3b853f4f3d020012174246223b451975a52c12ea5d000c3b85d550000200120c1a161014109d404019555a17000c3b866b4f000200120b101010101000410853b33a44000c3b87014f0001855e0c101010101000000401100017340c3b8797503d0281120a101010100f05900801308c00d00c3b882d50000481120a101010100f01e2c41cf0040000f5"
        )
        val parsed = RingConnRecordParser.parse(frame)
        assertEquals(0x4c, parsed?.frameId)
        assertEquals(0, parsed?.remaining)
        assertEquals(6, parsed?.activityRecords?.size)
        val expected = listOf(
            1783035327 to 44, 1783035477 to 25, 1783035627 to 83,
            1783035777 to 1, 1783035927 to 1, 1783036077 to 28
        )
        expected.forEachIndexed { idx, (sec, step) ->
            assertEquals(sec.toLong(), parsed?.activityRecords?.get(idx)?.unixSeconds)
            assertEquals(step, parsed?.activityRecords?.get(idx)?.motionIndex)
            assertEquals(false, parsed?.activityRecords?.get(idx)?.still)
        }
    }

    @Test fun parses_1record_activity_frame_with_sleep_flag() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val parsed = RingConnRecordParser.parse(frame)
        assertEquals(0x4c, parsed?.frameId)
        assertEquals(0, parsed?.remaining)
        assertEquals(1, parsed?.activityRecords?.size)
        val rec = parsed?.activityRecords?.get(0)
        assertEquals(1783059027L, rec?.unixSeconds)
        assertEquals(0, rec?.motionIndex)
        assertEquals(true, rec?.still)
    }

    @Test fun parses_6record_frame_with_remaining() {
        val frame = hexDecode(
            "4c002b0c3c075342210a7d5c0a010101010100000000000000040c3c07e9411f0a7f120a010101010100000000000000000c3c087f471f09805d0a010101010100000000000000040c3c091542170a87120a010101010100000000000000000c3c09ab45180a875e0a010101010100000000000000040c3c0a4142150a78120a01010101010000000000000000d5"
        )
        val parsed = RingConnRecordParser.parse(frame)
        assertEquals(0x4c, parsed?.frameId)
        assertEquals(43, parsed?.remaining)
        assertEquals(6, parsed?.activityRecords?.size)
        val first = parsed?.activityRecords?.get(0)
        assertEquals(1783068627L, first?.unixSeconds)
        parsed?.activityRecords?.forEach { rec ->
            assertEquals(0, rec.motionIndex); assertEquals(true, rec.still)
        }
    }

    @Test fun computes_motionLevel_as_mean_of_body_6_to_10() {
        // First record from parses_6record_activity_frame: body[6..10] = 42, 46, 22, 3b, 45 (hex)
        // = 66, 70, 34, 59, 69 (decimal) -> mean = 59
        val frame = hexDecode(
            "4c00000c3b853f4f3d020012174246223b451975a52c12ea5d000c3b85d550000200120c1a161014109d404019555a17000c3b866b4f000200120b101010101000410853b33a44000c3b87014f0001855e0c101010101000000401100017340c3b8797503d0281120a101010100f05900801308c00d00c3b882d50000481120a101010100f01e2c41cf0040000f5"
        )
        val parsed = RingConnRecordParser.parse(frame)
        val first = parsed?.activityRecords?.get(0)
        assertEquals(59, first?.motionLevel)
    }

    @Test fun parses_47_wellness_frame_empty_records() {
        val frame = hexDecode(
            "4700000c3c22240296000000a4690a4690a4690a4690a4290a428fa428fa428fa428fa428fa428fa428fa428fa428fa428f0d3"
        )
        val parsed = RingConnRecordParser.parse(frame)
        assertEquals(0x47, parsed?.frameId)
        assertEquals(0, parsed?.remaining)
        assertEquals(0, parsed?.activityRecords?.size)
    }

    @Test fun rejects_bad_xor_trailer() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val corrupt = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(RingConnRecordParser.parse(corrupt))
    }
    @Test fun rejects_truncated_frame() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        assertNull(RingConnRecordParser.parse(frame.copyOf(frame.size - 1)))
    }
    @Test fun rejects_invalid_frame_id() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val corrupt = frame.copyOf().also { it[0] = 0x10 }
        var x = 0; for (i in 0 until corrupt.size - 1) x = x xor (corrupt[i].toInt() and 0xFF)
        corrupt[corrupt.size - 1] = x.toByte()
        assertNull(RingConnRecordParser.parse(corrupt))
    }
    @Test fun rejects_invalid_byte_1() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val corrupt = frame.copyOf().also { it[1] = 0x01 }
        var x = 0; for (i in 0 until corrupt.size - 1) x = x xor (corrupt[i].toInt() and 0xFF)
        corrupt[corrupt.size - 1] = x.toByte()
        assertNull(RingConnRecordParser.parse(corrupt))
    }
    @Test fun rejects_empty_frame() = assertNull(RingConnRecordParser.parse(byteArrayOf()))

    @Test fun ack_command_0x4c() {
        val ack = RingConnRecordParser.ackCommand(0x4c)
        assertEquals(3, ack.size); assertEquals(0xcc.toByte(), ack[0])
        assertEquals(0x00.toByte(), ack[1]); assertEquals(0x00.toByte(), ack[2])
    }
    @Test fun ack_command_0x47() {
        val ack = RingConnRecordParser.ackCommand(0x47)
        assertEquals(3, ack.size); assertEquals(0xc7.toByte(), ack[0])
        assertEquals(0x00.toByte(), ack[1]); assertEquals(0x00.toByte(), ack[2])
    }

    // Battery frames below are real captures from device panther (2026-07-23), XOR-valid.
    @Test fun parses_battery_charging_from_poll_reply() {
        val b = RingConnRecordParser.parseBattery(hexDecode("87020400000000F000F6000000000F350B46F0"))
        assertEquals(2, b?.level); assertEquals(true, b?.charging)
    }
    @Test fun parses_battery_full_charging_push() {
        val b = RingConnRecordParser.parseBattery(hexDecode("10640400000000ED00EC0000000010E90A3CBE"))
        assertEquals(100, b?.level); assertEquals(true, b?.charging)
    }
    @Test fun parses_battery_discharging() {
        val b = RingConnRecordParser.parseBattery(hexDecode("10630200000000EB00EA0000000010E90AFF7C"))
        assertEquals(99, b?.level); assertEquals(false, b?.charging)
    }
    @Test fun parses_battery_full_off_charger() {
        val b = RingConnRecordParser.parseBattery(hexDecode("10640100000000EB00EB0000000010E70AFF77"))
        assertEquals(100, b?.level); assertEquals(false, b?.charging)
    }
    @Test fun battery_rejects_bad_xor() {
        val f = hexDecode("10640400000000ED00EC0000000010E90A3CBE")
            .also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(RingConnRecordParser.parseBattery(f))
    }
    @Test fun battery_rejects_short_frame() {
        assertNull(RingConnRecordParser.parseBattery(hexDecode("100000")))
    }
    @Test fun battery_rejects_non_status_id() {
        // 0x50 broadcast is not a status frame -> null
        assertNull(RingConnRecordParser.parseBattery(
            hexDecode("50000017866B06690015310C53F35B15120C53F66C10010C54E42D110000008600180100000000")))
    }

    // Temperature frames below are real captures from device panther (2026-07-24), XOR-valid.
    @Test fun parses_temperature_channels() {
        val t = RingConnRecordParser.parseTemperature(hexDecode("1056030000DD0138014200000000106C00FF61"))
        assertEquals(312, t?.channelA); assertEquals(322, t?.channelB)
    }
    @Test fun parses_temperature_second_sample() {
        val t = RingConnRecordParser.parseTemperature(hexDecode("1056020001070136014000000000106900FFB2"))
        assertEquals(310, t?.channelA); assertEquals(320, t?.channelB)
    }
    @Test fun temperature_channelB_reads_warmer_than_channelA() {
        // Gap narrows as the ring warms; B above A held across every frame captured.
        val t = RingConnRecordParser.parseTemperature(hexDecode("10560300017E0135014400000000106900FFCD"))
        assertEquals(309, t?.channelA); assertEquals(324, t?.channelB)
    }
    @Test fun temperature_rejects_bad_xor() {
        val f = hexDecode("1056030000DD0138014200000000106C00FF61")
            .also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(RingConnRecordParser.parseTemperature(f))
    }
    @Test fun temperature_rejects_short_frame() {
        assertNull(RingConnRecordParser.parseTemperature(hexDecode("100000")))
    }
}
