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

/** Pure orchestration for a sync: turns each notification plus an injected clock into commands
 *  to write and step buckets to persist. No Android, timers or I/O. Every stream is acked,
 *  `4c` included, which advances the ring's shared replay cursor. */
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
        /** Live steps banked since the last record frame, attributed to this epoch. */
        val steps: Int = 0,
    )
    data class Actions(
        val commandsToWrite: List<ByteArray>,
        val bucketsToPersist: List<Bucket>,
        val activityDrained: Boolean,
        val battery: RingConnBatteryStatus? = null,
        /** Steps accrued since the previous status frame; 0 when unknown. See [stepDeltaFrom]. */
        val stepsDelta: Int = 0,
    )

    /** Previous status-frame step accumulator, for differencing; null until the first status frame. */
    private var lastStepAccumulator: Int? = null

    /** Steps counted since the last record frame, awaiting a real epoch to attribute them to. */
    private var bankedSteps: Int = 0

    /** Carry step tracking across a re-sync: a fresh engine is built on every connect and manual
     *  fetch, and banked steps are only attributed once a record frame supplies a real epoch. */
    fun adoptStepState(previous: RingConnSyncEngine) {
        lastStepAccumulator = previous.lastStepAccumulator
        bankedSteps = previous.bankedSteps
    }

    /** First write after notifications are enabled: request the status/challenge frame. */
    fun start(): List<ByteArray> = listOf(CMD_STATUS.copyOf())

    fun onNotification(frame: ByteArray): Actions {
        if (frame.isEmpty()) return EMPTY
        val id = frame[0].toInt() and BYTE_MASK
        val record = RingConnRecordParser.parse(frame)
        val battery = RingConnRecordParser.parseBattery(frame)
        return when {
            record != null -> onRecordFrame(record, id)
            battery != null -> Actions(emptyList(), emptyList(), false, battery, stepDeltaFrom(frame))
            id == EVENT_FRAME_ID -> Actions(listOf(RingConnRecordParser.ackCommand(id)), emptyList(), false)
            isChallenge(id, frame) -> onChallenge(frame)
            else -> EMPTY
        }
    }

    /** Difference consecutive accumulators into steps taken. A drop is read as a reset, which
     *  would over-count if one landed mid-window; the first reading of a session yields 0
     *  because without a baseline its absolute value is not a delta we can claim. */
    private fun stepDeltaFrom(frame: ByteArray): Int {
        val current = RingConnRecordParser.parseStepAccumulator(frame) ?: return 0
        val previous = lastStepAccumulator
        lastStepAccumulator = current
        if (previous == null) return 0
        val delta = if (current >= previous) current - previous else current
        bankedSteps += delta
        return delta
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
        // Hand banked live steps to the newest REAL epoch in this batch. Never synthesise a
        // timestamp: the ring's epochs are 150 s apart but drift up to 22 s off any computed
        // grid, and an off-phase row would double-count against the record that arrives later.
        val newestSeconds = frame.activityRecords.maxOfOrNull { it.unixSeconds }
        var toAttribute = if (newestSeconds != null) bankedSteps.also { bankedSteps = 0 } else 0
        val buckets = frame.activityRecords.map {
            val steps = if (it.unixSeconds == newestSeconds) toAttribute.also { toAttribute = 0 } else 0
            Bucket(it.unixSeconds, it.motionIndex, it.motionLevel, it.still, it.asleep, it.spo2, steps)
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
