sealed interface Body

data class ConfigBody(
    val operType: Int,
    val modType: Int,
) : Body {
    companion object {
        fun decode(buf: ByteBuffer): ConfigBody {
            val operType = (buf.get().toInt() and 0xFF)
            val modType = (buf.get().toInt() and 0xFF)
            return ConfigBody(
                operType = operType,
                modType = modType,
            )
        }
    }

    fun encode(buf: ByteBuffer) {
        buf.put((operType).toByte())
        buf.put((modType).toByte())
    }
}

data class EncryptBody(
    val modType: Int,
    val bufData: String,
) : Body {
    companion object {
        fun decode(buf: ByteBuffer): EncryptBody {
            val modType = (buf.get().toInt() and 0xFF)
            val bufData = ByteArray(4).also { buf.get(it) }.toString(Charsets.UTF_8)
            return EncryptBody(
                modType = modType,
                bufData = bufData,
            )
        }
    }

    fun encode(buf: ByteBuffer) {
        buf.put((modType).toByte())
        buf.put(bufData.toByteArray(Charsets.UTF_8).copyOf(4))
    }
}

class BodyUnknown(val rawBytes: ByteArray) : Body {
    companion object {
        fun decode(buf: ByteBuffer): BodyUnknown {
            val rawBytes = ByteArray(buf.remaining())
            buf.get(rawBytes)
            return BodyUnknown(rawBytes)
        }
    }

    fun encode(buf: ByteBuffer) {
        buf.put(rawBytes)
    }
}

data class M(
    val cmdType: Int,
    val body: Body,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val cmdType = (buf.get().toInt() and 0xFF)
            val body = when (cmdType) {
                3 -> ConfigBody.decode(buf)
                4 -> EncryptBody.decode(buf)
                else -> BodyUnknown.decode(buf)
            }
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                cmdType = cmdType,
                body = body,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(1 + (when (val v = body) {
            is ConfigBody -> 2
            is EncryptBody -> 5
            is BodyUnknown -> v.rawBytes.size
        })).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((cmdType).toByte())
        when (val v = body) {
            is ConfigBody -> v.encode(buf)
            is EncryptBody -> v.encode(buf)
            is BodyUnknown -> v.encode(buf)
        }
        return buf.array()
    }
}
