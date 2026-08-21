data class M(
    val a: Float,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            require(payload.size >= 2) {
                "M payload must be at least 2 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val a = (buf.short.toInt()) * 0.1f
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                a = a,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort((kotlin.math.round(a / 0.1f).toInt()).toShort())
        return buf.array()
    }
}
