data class M(
    val a: Int,
    val b: Int? = null,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            require(payload.size >= 1) {
                "M payload must be at least 1 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val a = (buf.get().toInt() and 0xFF)
            var b: Int? = null
            if (payload.size >= 2) {
                b = (buf.get().toInt() and 0xFF)
            }
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
        val optionalPresent = listOf(b).map { it != null }
        require(optionalPresent.all { it } || optionalPresent.none { it }) {
            "optional tail fields must be all present or all absent"
        }
        val size = if (optionalPresent[0]) 2 else 1
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((a).toByte())
        if (optionalPresent[0]) {
            buf.put((b!!).toByte())
        }
        return buf.array()
    }
}
