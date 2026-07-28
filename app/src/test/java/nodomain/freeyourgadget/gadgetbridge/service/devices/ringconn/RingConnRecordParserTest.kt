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
import org.junit.Assert.assertNotNull
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
    @Test fun byte_1_is_the_high_byte_of_remaining_not_a_guard() {
        // Was asserted to be a reject; it is really remaining = 0x0100. Treating it as a guard
        // dropped every frame once the backlog passed 255 records.
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val high = frame.copyOf().also { it[1] = 0x01 }
        var x = 0; for (i in 0 until high.size - 1) x = x xor (high[i].toInt() and 0xFF)
        high[high.size - 1] = x.toByte()
        assertEquals(256, RingConnRecordParser.parse(high)?.remaining)
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

    // Step accumulator (u16-BE at bytes 4/5) read off the same panther status frames as temperature.
    @Test fun parses_step_accumulator() {
        assertEquals(221, RingConnRecordParser.parseStepAccumulator(
            hexDecode("1056030000DD0138014200000000106C00FF61")))
    }
    @Test fun step_accumulator_rises_across_the_capture() {
        // Same session, later frames: 221 -> 263 -> 382, which is what an accumulator does.
        assertEquals(263, RingConnRecordParser.parseStepAccumulator(
            hexDecode("1056020001070136014000000000106900FFB2")))
        assertEquals(382, RingConnRecordParser.parseStepAccumulator(
            hexDecode("10560300017E0135014400000000106900FFCD")))
    }
    @Test fun step_accumulator_is_zero_on_the_charger() {
        assertEquals(0, RingConnRecordParser.parseStepAccumulator(
            hexDecode("10640400000000ED00EC0000000010E90A3CBE")))
    }
    @Test fun step_accumulator_rejects_non_status_id() {
        assertNull(RingConnRecordParser.parseStepAccumulator(
            hexDecode("50000017866B06690015310C53F35B15120C53F66C10010C54E42D110000008600180100000000")))
    }
    @Test fun step_accumulator_rejects_bad_xor() {
        val f = hexDecode("1056030000DD0138014200000000106C00FF61")
            .also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(RingConnRecordParser.parseStepAccumulator(f))
    }

    // Sleep frames below are real captures from device panther (2026-07-25 overnight), XOR-valid.
    private val allSleepFrame =
        "4c00ac0c58ddb54d000a855c0a010101010100000000000000040c58de4b4a130a78120a010101010100000000000c00000c58dee147150a785a0a010101010100000000000000040c58df7746150a77120a010101010100000000000000000c58e00d46170a87590a010101010100000000000000040c58e0a349170a7d120a0101010101000000000000000073"

    // Record 0 is still (asleep), record 1 carries the same b2 == 10 while moving (a daytime stray).
    private val strayFrame =
        "4c000a0c593ca13c210a785b0a010101010100000000000000040c593d373c240a7d120a010101014d00000000009033700c593dcd3d23097f600a2f2f21202000007b00000000040c593e633b220400120b213e011c181051012840de2e300c593ef93d230500120b413901100113047317822d1af00c593f8f3f00037e5e0a01016115181b01a82d53022154ee"

    @Test fun confidence_saturates_at_10_and_marks_sleep() {
        val parsed = RingConnRecordParser.parse(hexDecode(allSleepFrame))
        assertEquals(6, parsed?.activityRecords?.size)
        parsed?.activityRecords?.forEach { rec ->
            assertEquals(10, rec.confidence)
            assertEquals(true, rec.still)
            assertEquals(true, rec.asleep)
        }
    }

    @Test fun confidence_of_10_while_moving_is_not_sleep() {
        // b2 == 10 alone fires ~10x/day while awake and moving; the motion gate removes every one.
        val parsed = RingConnRecordParser.parse(hexDecode(strayFrame))
        val still = parsed?.activityRecords?.get(0)
        val moving = parsed?.activityRecords?.get(1)
        assertEquals(10, still?.confidence); assertEquals(true, still?.asleep)
        assertEquals(10, moving?.confidence); assertEquals(false, moving?.asleep)
        assertEquals(false, moving?.still)
    }

    @Test fun confidence_is_low_while_active() {
        val parsed = RingConnRecordParser.parse(hexDecode(strayFrame))
        assertEquals(4, parsed?.activityRecords?.get(3)?.confidence)
        assertEquals(false, parsed?.activityRecords?.get(3)?.asleep)
    }

    @Test fun existing_still_frame_is_marked_asleep() {
        val parsed = RingConnRecordParser.parse(hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c"))
        assertEquals(true, parsed?.activityRecords?.get(0)?.asleep)
    }

    @Test fun parses_spo2_and_drops_the_no_sample_sentinel() {
        // The ring measures SpO2 only intermittently; unmeasured epochs carry 12/13, which is a
        // plausible-looking percentage and must never reach the chart.
        val recs = RingConnRecordParser.parse(hexDecode(allSleepFrame))?.activityRecords
        assertEquals(listOf(92, null, 90, null, 89, null), recs?.map { it.spo2 })
    }

    @Test fun spo2_sentinel_is_dropped_regardless_of_motion() {
        val recs = RingConnRecordParser.parse(hexDecode(strayFrame))?.activityRecords
        assertEquals(listOf(91, null, 96, null, null, 94), recs?.map { it.spo2 })
    }

    @Test fun spo2_rejects_implausible_values() {
        // body[4] = 0x0f (15) appeared twice in the 2026-07-25 capture; well below any real SpO2.
        val frame = hexDecode("4c00000c3be1d34d130a7f0f0a0101010101000000000000000462")
        assertNull(RingConnRecordParser.parse(frame)?.activityRecords?.get(0)?.spo2)
    }

    @Test fun parses_frame_with_backlog_over_255_records() {
        // Captured from the ring 2026-07-27 after ~30 h offline: remaining is u16 BE (0x02cd =
        // 717), so the high byte is set. Read as one byte the frame was rejected and never acked,
        // which pinned the replay cursor and made the ring resend this batch forever.
        val frame = hexDecode(
            "4c02cd0c5b135d550003006048402d3734422902cd13416d2df40c5b13f354000200120c3f3b39393629d1" +
                "a20881103c300c5b148951220600120c504d29411124e1f70e91a70fa00c5b151f4f2b0581120b3131" +
                "3531403ab0c22645cf47e00c5b15b55100010061172e2d28343453520022e16737340c5b164b502e04" +
                "7f120c1a111a3a1125704c2703621e506f"
        )
        val parsed = RingConnRecordParser.parse(frame)
        assertNotNull(parsed)
        assertEquals(717, parsed?.remaining)
        assertEquals(6, parsed?.activityRecords?.size)
    }

    @Test fun active_frame_is_not_asleep() {
        val frame = hexDecode(
            "4c00000c3b853f4f3d020012174246223b451975a52c12ea5d000c3b85d550000200120c1a161014109d404019555a17000c3b866b4f000200120b101010101000410853b33a44000c3b87014f0001855e0c101010101000000401100017340c3b8797503d0281120a101010100f05900801308c00d00c3b882d50000481120a101010100f01e2c41cf0040000f5"
        )
        val parsed = RingConnRecordParser.parse(frame)
        assertEquals(2, parsed?.activityRecords?.get(0)?.confidence)
        assertEquals(false, parsed?.activityRecords?.get(0)?.asleep)
    }
}
