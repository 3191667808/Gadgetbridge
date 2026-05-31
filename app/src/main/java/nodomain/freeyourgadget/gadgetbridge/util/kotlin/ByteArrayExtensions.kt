package nodomain.freeyourgadget.gadgetbridge.util.kotlin

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions

fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (prefix.size > this.size) {
        return false
    }
    for (i in prefix.indices) {
        if (this[i] != prefix[i]) {
            return false
        }
    }
    return true
}

fun ByteArray.beShortAt(offset: Int): Short =
    (this[offset + 1].toLong() shl 8 or this[offset].toLong()).toShort()

fun ByteArray.leShortAt(offset: Int): Short =
    (this[offset].toLong() shl 8 or this[offset + 1].toLong()).toShort()

fun ByteArray.beIntAt(offset: Int): Int =
    (beShortAt(offset + 2).toLong() shl 16 or beShortAt(offset).toLong()).toInt()

fun ByteArray.leIntAt(offset: Int): Int =
    (leShortAt(offset).toLong() shl 16 or leShortAt(offset + 2).toLong()).toInt()

fun ByteArray.beLongAt(offset: Int): Long =
    beIntAt(offset + 4).toLong() shl 32 or beIntAt(offset).toLong()

fun ByteArray.leLongAt(offset: Int): Long =
    leIntAt(offset).toLong() shl 32 or leIntAt(offset + 4).toLong()


fun ByteArray.readUint16LE(offset: Int): Int {
    return BLETypeConversions.toUint16(this, offset)
}
