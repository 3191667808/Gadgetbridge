data class M(
    val name: String,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = buf.get().toInt() and 0xFF
            val name = String(payload, buf.position(), nameLen, Charsets.UTF_8).also { buf.position(buf.position() + nameLen) }
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                name = name,
            )
        }
    }

    fun encode(): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        return buf.array()
    }
}
