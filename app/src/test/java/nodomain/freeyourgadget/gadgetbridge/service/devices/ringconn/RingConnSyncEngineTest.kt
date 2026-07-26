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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingConnSyncEngineTest {
    // Ring MAC ..F6:96:2A -> V=0x4a; challenge 0x11 -> auth response e1 b9 23 (see RingConnAuthTest).
    private val mac = byteArrayOf(0x00, 0x00, 0x00, 0xF6.toByte(), 0x96.toByte(), 0x2A)

    // Fixed clock: ringTs = now - 1_577_808_000 = 0x01020304 -> time-sync body 01 02 03 04.
    private val fixedNow = 1_577_808_000L + 0x01020304L
    private fun engine() = RingConnSyncEngine(mac, nowUnixSeconds = { fixedNow })

    private fun hexDecode(hex: String) = ByteArray(hex.length / 2) { i ->
        ((hex[2 * i].digitToInt(16) shl 4) or hex[2 * i + 1].digitToInt(16)).toByte()
    }

    @Test fun start_requests_status() {
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x00), engine().start().single())
    }

    @Test fun challenge_triggers_auth_then_postauth_sequence() {
        val challenge = byteArrayOf(0x81.toByte(), 0x00, 0x11, 0x90.toByte())
        val actions = engine().onNotification(challenge)
        assertEquals(4, actions.commandsToWrite.size)
        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0xe1.toByte(), 0xb9.toByte(), 0x23, 0x00),
            actions.commandsToWrite[0],
        )
        assertArrayEquals(byteArrayOf(0xd0.toByte(), 0x00, 0x00), actions.commandsToWrite[1])
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x01, 0x02, 0x03, 0x04, 0x00, 0x01, 0x00),
            actions.commandsToWrite[2],
        )
        assertArrayEquals(byteArrayOf(0x07, 0x00, 0x00), actions.commandsToWrite[3])
        assertTrue(actions.bucketsToPersist.isEmpty())
    }

    @Test fun activity_frame_persists_and_acks_last_batch() {
        // Vector B: 1-record 4c, remaining=0, still, motionIndex=0.
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val actions = engine().onNotification(frame)
        assertEquals(1, actions.commandsToWrite.size)   // ack 4c to advance the cursor -> cc
        assertArrayEquals(byteArrayOf(0xcc.toByte(), 0x00, 0x00), actions.commandsToWrite[0])
        assertEquals(1, actions.bucketsToPersist.size)
        assertEquals(1783059027L, actions.bucketsToPersist[0].unixSeconds)
        assertEquals(0, actions.bucketsToPersist[0].motionIndex)
        assertTrue(actions.bucketsToPersist[0].still)
        assertTrue(actions.activityDrained)             // remaining == 0
    }

    @Test fun activity_frame_with_remaining_acks_and_not_drained() {
        // Vector C: 6-record 4c, remaining=43.
        val frame = hexDecode("4c002b0c3c075342210a7d5c0a010101010100000000000000040c3c07e9411f0a7f120a010101010100000000000000000c3c087f471f09805d0a010101010100000000000000040c3c091542170a87120a010101010100000000000000000c3c09ab45180a875e0a010101010100000000000000040c3c0a4142150a78120a01010101010000000000000000d5")
        val actions = engine().onNotification(frame)
        assertEquals(1, actions.commandsToWrite.size)   // ack 4c -> cc, to pull the next batch
        assertArrayEquals(byteArrayOf(0xcc.toByte(), 0x00, 0x00), actions.commandsToWrite[0])
        assertEquals(6, actions.bucketsToPersist.size)
        assertEquals(false, actions.activityDrained)
    }

    @Test fun wellness_frame_is_acked_no_buckets() {
        // Vector D: 47 wellness frame.
        val frame = hexDecode("4700000c3c22240296000000a4690a4690a4690a4690a4290a428fa428fa428fa428fa428fa428fa428fa428fa428fa428f0d3")
        val actions = engine().onNotification(frame)
        assertEquals(1, actions.commandsToWrite.size)
        assertArrayEquals(byteArrayOf(0xc7.toByte(), 0x00, 0x00), actions.commandsToWrite[0]) // 47 -> c7
        assertTrue(actions.bucketsToPersist.isEmpty())
    }

    @Test fun event_frame_is_acked() {
        val actions = engine().onNotification(byteArrayOf(0x11, 0x00, 0x00))
        assertEquals(1, actions.commandsToWrite.size)
        assertArrayEquals(byteArrayOf(0x91.toByte(), 0x00, 0x00), actions.commandsToWrite[0]) // 11 -> 91
    }

    @Test fun status_push_surfaces_battery_without_commands() {
        val actions = engine().onNotification(hexDecode("10640400000000ED00EC0000000010E90A3CBE"))
        assertEquals(100, actions.battery?.level)
        assertEquals(true, actions.battery?.charging)
        assertTrue(actions.commandsToWrite.isEmpty())
        assertTrue(actions.bucketsToPersist.isEmpty())
    }

    // Step deltas: the ring's accumulator is absolute-and-resetting, so only in-session rises count.
    private val sixRecordFrame =
        "4c002b0c3c075342210a7d5c0a010101010100000000000000040c3c07e9411f0a7f120a010101010100000000000000000c3c087f471f09805d0a010101010100000000000000040c3c091542170a87120a010101010100000000000000000c3c09ab45180a875e0a010101010100000000000000040c3c0a4142150a78120a01010101010000000000000000d5"

    private val steps221 = hexDecode("1056030000DD0138014200000000106C00FF61")
    private val steps263 = hexDecode("1056020001070136014000000000106900FFB2")
    private val steps382 = hexDecode("10560300017E0135014400000000106900FFCD")

    @Test fun first_status_frame_reports_no_step_delta() {
        // No prior reading, so the accumulator's absolute value is not a delta we may claim.
        assertEquals(0, engine().onNotification(steps221).stepsDelta)
    }

    @Test fun rising_accumulator_reports_the_difference() {
        val e = engine()
        e.onNotification(steps221)
        assertEquals(42, e.onNotification(steps263).stepsDelta)
    }

    @Test fun accumulator_reset_reports_the_new_value_as_the_delta() {
        // Observed 7->42->...->147->0: on reset the fresh value is itself the steps since the reset.
        val e = engine()
        e.onNotification(steps382)
        assertEquals(221, e.onNotification(steps221).stepsDelta)
    }

    @Test fun unchanged_accumulator_reports_no_delta() {
        val e = engine()
        e.onNotification(steps263)
        assertEquals(0, e.onNotification(steps263).stepsDelta)
    }

    // Live steps are banked in memory and attributed to a REAL epoch when its record arrives, so no
    // timestamp is ever invented: the ring's epochs drift up to 22 s off any anchor+k*150 grid.
    @Test fun banked_steps_land_on_the_newest_bucket_of_the_next_record_frame() {
        val e = engine()
        e.onNotification(steps221)
        e.onNotification(steps263) // +42
        val buckets = e.onNotification(hexDecode(sixRecordFrame)).bucketsToPersist
        val newest = buckets.maxByOrNull { it.unixSeconds }!!
        assertEquals(42, newest.steps)
        assertEquals(0, buckets.filter { it !== newest }.sumOf { it.steps })
    }

    @Test fun banked_steps_are_not_attributed_twice() {
        val e = engine()
        e.onNotification(steps221)
        e.onNotification(steps263)
        e.onNotification(hexDecode(sixRecordFrame))
        val second = e.onNotification(hexDecode(sixRecordFrame)).bucketsToPersist
        assertEquals(0, second.sumOf { it.steps })
    }

    @Test fun record_frame_with_no_banked_steps_attributes_zero() {
        assertEquals(0, engine().onNotification(hexDecode(sixRecordFrame))
            .bucketsToPersist.sumOf { it.steps })
    }

    @Test fun record_frames_do_not_disturb_the_step_baseline() {
        // 4c records carry no accumulator; they must not reset the delta tracking.
        val e = engine()
        e.onNotification(steps221)
        e.onNotification(hexDecode(sixRecordFrame))
        assertEquals(42, e.onNotification(steps263).stepsDelta)
    }
}
