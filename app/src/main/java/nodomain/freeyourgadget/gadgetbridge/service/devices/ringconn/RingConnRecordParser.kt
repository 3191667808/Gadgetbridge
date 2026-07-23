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

/** One 2.5-minute activity bucket from a `4c` record frame. */
data class RingConnActivityRecord(
    val unixSeconds: Long,
    val steps: Int,
    val sleepFlagged: Boolean,
)

/** One parsed record frame. For `47` (wellness) frames [activityRecords] is empty. */
data class RingConnRecordFrame(
    val frameId: Int,
    val remaining: Int,
    val activityRecords: List<RingConnActivityRecord>,
)

/** Battery snapshot from a `10`/`87` status frame: [level] 0-100, [charging] true on the charger. */
data class RingConnBatteryStatus(
    val level: Int,
    val charging: Boolean,
)

/**
 * Pure parser for RingConn Gen2 record frames: `0x4c` activity (steps at body[14], sleepFlagged when body[6:11]==0x01) and `0x47` wellness (skipped). Both XOR-trailed; timestamp is u32-BE offset from 2020-01-01 00:00:00 UTC+8.
 */
object RingConnRecordParser {

    /** Frame id of the step-bearing activity record stream. */
    const val FRAME_ID_ACTIVITY = 0x4c

    private const val FRAME_ID_WELLNESS = 0x47
    private const val REQUIRED_BYTE_1 = 0x00
    private const val ACTIVITY_RECORD_LEN = 23
    private const val WELLNESS_RECORD_LEN = 47
    private const val MIN_FRAME_LEN = 5 // at least frame_id + 0x00 + remaining + 1 record + trailer
    private const val HEADER_LEN = 3 // frame_id + 0x00 + remaining
    private const val TRAILER_LEN = 1
    private const val TIMESTAMP_LEN = 4
    private const val STEPS_OFFSET_IN_BODY = 14
    private const val SLEEP_CHECK_START = 6
    private const val SLEEP_CHECK_END = 11 // exclusive
    private const val SLEEP_FLAG_VALUE = 0x01

    /** Ring timestamps are seconds since 2020-01-01 00:00:00 UTC+8; add this for unix seconds. */
    const val TIMESTAMP_OFFSET_UNIX = 1_577_808_000L

    /** Status frame ids: the ring pushes `10` every ~14s; `87` is the poll-reply (identical layout). */
    private const val FRAME_ID_STATUS_PUSH = 0x10
    private const val FRAME_ID_STATUS_POLL = 0x87
    private const val STATUS_FRAME_LEN = 19
    private const val BATTERY_OFFSET = 1
    private const val CHARGE_OFFSET = 2
    private const val CHARGING_VALUE = 0x04
    private const val MAX_BATTERY = 100

    private const val BYTE_MASK = 0xFF
    private const val BYTE_MASK_LONG = 0xFFL
    private const val BYTE_SHIFT = 8
    private const val ACK_MODE_BIT = 0x80
    private const val TIMESTAMP_OFFSET_0 = 0
    private const val TIMESTAMP_OFFSET_1 = 1
    private const val TIMESTAMP_OFFSET_2 = 2
    private const val TIMESTAMP_OFFSET_3 = 3

    /**
     * Parse a record frame, or null if invalid (bad frame id/length/XOR trailer, or too few records). Never throws.
     */
    fun parse(frame: ByteArray): RingConnRecordFrame? {
        if (!isValidFrameStructure(frame)) return null

        val frameId = frame[0].toInt() and BYTE_MASK
        val remaining = frame[2].toInt() and BYTE_MASK
        val recordLen = if (frameId == FRAME_ID_ACTIVITY) ACTIVITY_RECORD_LEN else WELLNESS_RECORD_LEN
        val recordCount = (frame.size - HEADER_LEN - TRAILER_LEN) / recordLen
        val activityRecords = if (frameId == FRAME_ID_ACTIVITY) {
            parseActivityRecords(frame, recordCount)
        } else {
            emptyList()
        }
        return RingConnRecordFrame(frameId, remaining, activityRecords)
    }

    private fun isValidFrameStructure(frame: ByteArray): Boolean {
        val sizValid = frame.size >= MIN_FRAME_LEN && xorValid(frame)
        if (!sizValid) return false

        val frameId = frame[0].toInt() and BYTE_MASK
        val frameIdValid = frameId == FRAME_ID_ACTIVITY || frameId == FRAME_ID_WELLNESS
        val byte1Valid = (frame[1].toInt() and BYTE_MASK) == REQUIRED_BYTE_1

        val recordLen = if (frameId == FRAME_ID_ACTIVITY) ACTIVITY_RECORD_LEN else WELLNESS_RECORD_LEN
        val recordCount = (frame.size - HEADER_LEN - TRAILER_LEN) / recordLen
        val lengthValid = recordCount >= 1 && HEADER_LEN + recordCount * recordLen + TRAILER_LEN == frame.size

        return frameIdValid && byte1Valid && lengthValid
    }

    /**
     * Build an ACK command `<id|0x80> 00 00` (`47` -> `c7 00 00`). Acking advances the ring's shared per-stream replay cursor: required on `47`/`11`, deliberately NEVER sent for `4c` activity frames.
     */
    fun ackCommand(frameId: Int): ByteArray {
        return byteArrayOf(
            ((frameId or ACK_MODE_BIT) and BYTE_MASK).toByte(),
            0x00,
            0x00
        )
    }

    /**
     * Battery from a `10`/`87` status frame (byte[1] = percent 0-100, byte[2] == `04` while charging), or null if the frame isn't a valid status frame. Never throws.
     */
    fun parseBattery(frame: ByteArray): RingConnBatteryStatus? {
        if (frame.size < STATUS_FRAME_LEN) return null
        val id = frame[0].toInt() and BYTE_MASK
        if (id != FRAME_ID_STATUS_PUSH && id != FRAME_ID_STATUS_POLL) return null
        if (!xorValid(frame)) return null
        val level = frame[BATTERY_OFFSET].toInt() and BYTE_MASK
        if (level > MAX_BATTERY) return null
        val charging = (frame[CHARGE_OFFSET].toInt() and BYTE_MASK) == CHARGING_VALUE
        return RingConnBatteryStatus(level, charging)
    }

    private fun parseActivityRecords(frame: ByteArray, recordCount: Int): List<RingConnActivityRecord> {
        val records = mutableListOf<RingConnActivityRecord>()
        var offset = HEADER_LEN

        for (i in 0 until recordCount) {
            val timestamp = readU32BE(frame, offset)
            val unixSeconds = timestamp + TIMESTAMP_OFFSET_UNIX
            val bodyStart = offset + TIMESTAMP_LEN

            val steps = frame[bodyStart + STEPS_OFFSET_IN_BODY].toInt() and BYTE_MASK

            val sleepFlagged = isSleepFlagged(frame, bodyStart)

            records.add(RingConnActivityRecord(unixSeconds, steps, sleepFlagged))
            offset += ACTIVITY_RECORD_LEN
        }

        return records
    }

    private fun isSleepFlagged(frame: ByteArray, bodyStart: Int): Boolean {
        for (i in SLEEP_CHECK_START until SLEEP_CHECK_END) {
            if ((frame[bodyStart + i].toInt() and BYTE_MASK) != SLEEP_FLAG_VALUE) {
                return false
            }
        }
        return true
    }

    private fun readU32BE(frame: ByteArray, offset: Int): Long {
        val b0 = frame[offset + TIMESTAMP_OFFSET_0].toLong() and BYTE_MASK_LONG
        val b1 = frame[offset + TIMESTAMP_OFFSET_1].toLong() and BYTE_MASK_LONG
        val b2 = frame[offset + TIMESTAMP_OFFSET_2].toLong() and BYTE_MASK_LONG
        val b3 = frame[offset + TIMESTAMP_OFFSET_3].toLong() and BYTE_MASK_LONG
        return (((b0 shl BYTE_SHIFT) or b1) shl BYTE_SHIFT or b2) shl BYTE_SHIFT or b3
    }

    /** Trailer (last byte) is the XOR of all preceding bytes. */
    private fun xorValid(frame: ByteArray): Boolean {
        var x = 0
        for (i in 0 until frame.size - 1) {
            x = x xor (frame[i].toInt() and BYTE_MASK)
        }
        return (x and BYTE_MASK) == (frame[frame.size - 1].toInt() and BYTE_MASK)
    }
}
