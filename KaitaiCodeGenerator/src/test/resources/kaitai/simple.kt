/**
 * A tiny schema exercising every field shape the emitter supports - scaled scalar, enum bitfield, bool bitfield, optional tail.
 */
data class SimpleMsg(
    val value: Float,
    val shade: Color,
    val active: Boolean,
    val extra: Int? = null,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(SimpleMsg::class.java)

        fun decode(payload: ByteArray): SimpleMsg {
            require(payload.size >= 3) {
                "SimpleMsg payload must be at least 3 bytes, got ${payload.size}"
            }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val value = (buf.short.toInt()) * 0.1f
            val rawShade = buf.get().toInt() and 0xFF
            val shade = Color.fromValue(rawShade and 0x7f)
            val active = ((rawShade shr 7) and 0x1) == 1
            var extra: Int? = null
            if (payload.size >= 4) {
                extra = (buf.get().toInt() and 0xFF)
            }
            if (buf.remaining() > 0) {
                LOG.warn("SimpleMsg: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return SimpleMsg(
                value = value,
                shade = shade,
                active = active,
                extra = extra,
            )
        }
    }

    fun encode(): ByteArray {
        val optionalPresent = listOf(extra).map { it != null }
        require(optionalPresent.all { it } || optionalPresent.none { it }) {
            "optional tail fields must be all present or all absent"
        }
        val size = if (optionalPresent[0]) 4 else 3
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort((kotlin.math.round(value / 0.1f).toInt()).toShort())
        run {
            var raw = 0
            raw = raw or ((shade.value) shl 0)
            raw = raw or (((if (active) 1 else 0)) shl 7)
            buf.put(raw.toByte())
        }
        if (optionalPresent[0]) {
            buf.put((extra!!).toByte())
        }
        return buf.array()
    }
}
