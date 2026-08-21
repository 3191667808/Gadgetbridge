data class SimpleMsg(
    val a: Int,
    val b: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(SimpleMsg::class.java)

        fun decode(payload: ByteArray): SimpleMsg {
            require(payload.size >= 3) {
                "SimpleMsg payload must be at least 3 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val a = (buf.get().toInt() and 0xFF)
            val b = (buf.short.toInt() and 0xFFFF)
            if (buf.remaining() > 0) {
                LOG.warn("SimpleMsg: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return SimpleMsg(
                a = a,
                b = b,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((a).toByte())
        buf.putShort((b).toShort())
        return buf.array()
    }
}
