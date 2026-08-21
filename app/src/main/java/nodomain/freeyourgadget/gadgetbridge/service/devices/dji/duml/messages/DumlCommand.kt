package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages

import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAck
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAddress
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacket
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacketType
import nodomain.freeyourgadget.gadgetbridge.util.GB

/**
 * A DUML command family: one (cmdSet, cmd) pair, grouping both directions
 * of the exchange under a single named operation. The two directions usually
 * do not share a payload shape - a query carries little, the answer carries
 * the actual data, so each direction still gets its own type.
 *
 * Subclasses are generated from the .ksy schemas under
 * `KaitaiCodeGenerator/src/main/resources/duml` (see `:KaitaiCodeGenerator:genDuml`);
 * each schema file contributes its own decoders, merged into [DECODERS]
 * below as `GENERATED_DUML_DECODERS`.
 *
 * Unknown payloads are decoded to [Unknown].
 */
sealed class DumlCommand(val cmdSet: Int, val cmd: Int) {

    abstract fun encode(): ByteArray

    /** Anything not (yet) modeled - carries whichever direction was observed. */
    class Unknown(
        cmdSet: Int,
        cmd: Int,
        val packetType: DumlPacketType,
        val rawPayload: ByteArray,
    ) : DumlCommand(cmdSet, cmd) {
        override fun encode(): ByteArray = rawPayload

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return cmdSet == other.cmdSet && cmd == other.cmd &&
                    packetType == other.packetType && rawPayload.contentEquals(other.rawPayload)
        }

        override fun hashCode(): Int =
            (31 * (31 * (31 * cmdSet + cmd) + packetType.hashCode())) + rawPayload.contentHashCode()

        override fun toString(): String =
            "Unknown(cmdSet=0x%02x, cmd=0x%02x, %s, payload=%s)".format(cmdSet, cmd, packetType, GB.hexdump(rawPayload))
    }

    companion object {
        // Outer key is cmdSet, inner key is (cmd, packetType) - the two
        // directions of an exchange aren't guaranteed to share a payload
        // shape, so a decoder registered for one packetType must never also
        // match the other.
        private val DECODERS: Map<Int, Map<Pair<Int, DumlPacketType>, (ByteArray) -> DumlCommand>> =
            GENERATED_DUML_DECODERS

        fun decode(cmdSet: Int, cmd: Int, packetType: DumlPacketType, payload: ByteArray): DumlCommand =
            DECODERS[cmdSet]?.get(cmd to packetType)?.invoke(payload) ?: Unknown(cmdSet, cmd, packetType, payload)

        fun decode(packet: DumlPacket): DumlCommand =
            decode(packet.cmdSet, packet.cmd, packet.packetType, packet.payload)
    }
}

/** Wraps a command in a full [DumlPacket] envelope. */
fun DumlCommand.toPacket(
    sender: DumlAddress,
    receiver: DumlAddress,
    seq: Int,
    ack: DumlAck = DumlAck.ACK_AFTER_EXEC,
    packetType: DumlPacketType = DumlPacketType.REQUEST,
): DumlPacket = DumlPacket(
    sender = sender,
    receiver = receiver,
    seq = seq,
    ack = ack,
    packetType = packetType,
    cmdSet = cmdSet,
    cmd = cmd,
    payload = encode(),
)
