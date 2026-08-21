data class Entry(
    val a: Int,
    val b: Int,
) {
    companion object {
        fun decode(buf: ByteBuffer): Entry {
            val a = (buf.get().toInt() and 0xFF)
            val b = buf.int
            return Entry(
                a = a,
                b = b,
            )
        }
    }

    fun encode(buf: ByteBuffer) {
        buf.put((a).toByte())
        buf.putInt(b)
    }
}

data class M(
    val entries: List<Entry>,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(M::class.java)

        fun decode(payload: ByteArray): M {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val entries = buildList {
                while (buf.hasRemaining()) {
                    add(Entry.decode(buf))
                }
            }
            if (buf.remaining() > 0) {
                LOG.warn("M: {} unexpected trailing byte(s): {}", buf.remaining(), GB.hexdump(payload, buf.position(), buf.remaining()))
            }
            return M(
                entries = entries,
            )
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(entries.size * 5).order(ByteOrder.LITTLE_ENDIAN)
        for (record in entries) {
            record.encode(buf)
        }
        return buf.array()
    }
}
