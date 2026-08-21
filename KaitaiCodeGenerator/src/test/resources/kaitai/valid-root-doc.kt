data class M(
    val a: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            require(payload.size >= 1) {
                "M payload must be at least 1 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val a = (buf.get().toInt() and 0xFF)
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                a = a,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((a).toByte())
        return buf.array()
    }
}
