data class M(
    val byte0Lo: Int,
    val byte0Hi: Int,
    val byte1Lo: Int,
    val byte1Hi: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            require(payload.size >= 2) {
                "M payload must be at least 2 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val rawByte0Lo = buf.get().toInt() and 0xFF
            val byte0Lo = rawByte0Lo and 0xf
            val byte0Hi = (rawByte0Lo shr 4) and 0xf
            val rawByte1Lo = buf.get().toInt() and 0xFF
            val byte1Lo = rawByte1Lo and 0xf
            val byte1Hi = (rawByte1Lo shr 4) and 0xf
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                byte0Lo = byte0Lo,
                byte0Hi = byte0Hi,
                byte1Lo = byte1Lo,
                byte1Hi = byte1Hi,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        run {
            var raw = 0
            raw = raw or ((byte0Lo) shl 0)
            raw = raw or ((byte0Hi) shl 4)
            buf.put(raw.toByte())
        }
        run {
            var raw = 0
            raw = raw or ((byte1Lo) shl 0)
            raw = raw or ((byte1Hi) shl 4)
            buf.put(raw.toByte())
        }
        return buf.array()
    }
}
