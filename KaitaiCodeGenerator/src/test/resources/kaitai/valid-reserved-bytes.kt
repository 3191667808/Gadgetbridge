data class M(
    val reserved: ByteArray,
    val a: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            require(payload.size >= 5) {
                "M payload must be at least 5 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val reserved = ByteArray(4).also { buf.get(it) }
            val a = (buf.get().toInt() and 0xFF)
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                reserved = reserved,
                a = a,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        require(reserved.size == 4) {
            "reserved must be 4 bytes, got ${reserved.size}"
        }
        buf.put(reserved)
        buf.put((a).toByte())
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is M) return false
        return reserved.contentEquals(other.reserved) &&
                a == other.a
    }

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + (reserved.contentHashCode())
        result = 31 * result + (a.hashCode())
        return result
    }
}
