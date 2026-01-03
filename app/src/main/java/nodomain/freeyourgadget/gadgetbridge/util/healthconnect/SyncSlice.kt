package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import java.time.Instant
import java.time.ZoneOffset

/**
 * A sync slice excludes the timestamp at the start, but includes the timestamp at the end, that is: ]startBoundary, endBoundary].
 */
data class SyncSlice(
    val startBoundary: Instant,
    val endBoundary: Instant,
    val offset: ZoneOffset
) {
    /**
     * A slice
     */
    fun contains(timestamp: Instant): Boolean = timestamp.isAfter(startBoundary) &&
            (timestamp.isBefore(endBoundary) || timestamp == endBoundary)

    override fun toString(): String {
        return "]$startBoundary, $endBoundary]"
    }
}
