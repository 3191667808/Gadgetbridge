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

/**
 * Pure orchestration for a RingConn Gen2 sync (connect -> auth -> record replay): given each inbound notification and an injected clock, returns commands to write and step buckets to persist. No Android/timers/I/O. Ack policy enforced here: EVERY stream is acked, including `4c` activity records, which advances the ring's shared replay cursor and makes GB the consuming reader.
 *
 * UNDER INVESTIGATION (2026-07-24): whether GB's `4c` acks starve the official app of the same records. Do not treat the cursor semantics below as settled.
 */
class RingConnSyncEngine @JvmOverloads constructor(
    private val mac: ByteArray,
    private val nowUnixSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    data class Bucket(
        val unixSeconds: Long,
        val motionIndex: Int,
        val motionLevel: Int,
        val still: Boolean,
        val asleep: Boolean = false,
        val spo2: Int? = null,
    )
    data class Actions(
        val commandsToWrite: List<ByteArray>,
        val bucketsToPersist: List<Bucket>,
        val activityDrained: Boolean,
        val battery: RingConnBatteryStatus? = null,
    )

    /** First write after notifications are enabled: request the status/challenge frame. */
    fun start(): List<ByteArray> = listOf(CMD_STATUS.copyOf())

    fun onNotification(frame: ByteArray): Actions {
        if (frame.isEmpty()) return EMPTY
        val id = frame[0].toInt() and BYTE_MASK
        val record = RingConnRecordParser.parse(frame)
        val battery = RingConnRecordParser.parseBattery(frame)
        return when {
            record != null -> onRecordFrame(record, id)
            battery != null -> Actions(emptyList(), emptyList(), false, battery)
            id == EVENT_FRAME_ID -> Actions(listOf(RingConnRecordParser.ackCommand(id)), emptyList(), false)
            isChallenge(id, frame) -> onChallenge(frame)
            else -> EMPTY
        }
    }

    private fun isChallenge(id: Int, frame: ByteArray) =
        frame.size >= CHALLENGE_MIN_LENGTH && id == CHALLENGE_ID && frame[1].toInt() == 0

    private fun onChallenge(frame: ByteArray): Actions {
        val challenge = frame[CHALLENGE_INDEX].toInt() and BYTE_MASK
        val commands = listOf(
            RingConnAuth.authCommand(challenge, mac),
            CMD_DESCRIPTOR.copyOf(),
            timeSyncCommand(),
            CMD_POLL.copyOf(),
        )
        return Actions(commands, emptyList(), false)
    }

    private fun onRecordFrame(frame: RingConnRecordFrame, id: Int): Actions {
        val buckets = frame.activityRecords.map {
            Bucket(it.unixSeconds, it.motionIndex, it.motionLevel, it.still, it.asleep, it.spo2)
        }
        // Ack every record frame, including `4c` activity: the ack advances the ring's shared replay
        // cursor, the only way to drain a multi-batch backlog. (Not acking pins the cursor to the oldest
        // batch, so GB re-reads the same records and under-counts. GB becomes the primary consumer.)
        val drained = id == RingConnRecordParser.FRAME_ID_ACTIVITY && frame.remaining == 0
        return Actions(listOf(RingConnRecordParser.ackCommand(id)), buckets, drained)
    }

    /** `02 00 <ringTs:u32-be> 00 01 00`: time-sync as the official app sends each session. */
    private fun timeSyncCommand(): ByteArray {
        val ringTs = nowUnixSeconds() - RingConnRecordParser.TIMESTAMP_OFFSET_UNIX
        return byteArrayOf(
            0x02, 0x00,
            (ringTs ushr 24).toByte(), (ringTs ushr 16).toByte(),
            (ringTs ushr 8).toByte(), ringTs.toByte(),
            0x00, 0x01, 0x00,
        )
    }

    private companion object {
        val EMPTY = Actions(emptyList(), emptyList(), false)
        val CMD_STATUS = byteArrayOf(0x01, 0x00, 0x00)
        val CMD_DESCRIPTOR = byteArrayOf(0xd0.toByte(), 0x00, 0x00)
        val CMD_POLL = byteArrayOf(0x07, 0x00, 0x00)
        const val EVENT_FRAME_ID = 0x11
        const val CHALLENGE_ID = 0x81
        const val CHALLENGE_MIN_LENGTH = 4
        const val CHALLENGE_INDEX = 2
        const val BYTE_MASK = 0xFF
    }
}
