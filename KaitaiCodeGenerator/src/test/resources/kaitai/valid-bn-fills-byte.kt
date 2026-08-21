data class M(
    val a: Int,
    val b: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            require(payload.size >= 1) {
                "M payload must be at least 1 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val rawA = buf.get().toInt() and 0xFF
            val a = rawA and 0xf
            val b = (rawA shr 4) and 0xf
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                a = a,
                b = b,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        run {
            var raw = 0
            raw = raw or ((a) shl 0)
            raw = raw or ((b) shl 4)
            buf.put(raw.toByte())
        }
        return buf.array()
    }
}
