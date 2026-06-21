package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos

import org.junit.Assert.*
import org.junit.Test

class ZeppOsDeviceSourcesTest {
    @Test
    fun testNoDuplicateProductIdAndVersion() {
        // We need all (productId, productVersion) pairs to be distinct
        val pairs = ZeppOsDeviceSources.DEVICE_SOURCES.map { Pair(it.productId, it.productVersion) }
        val uniquePairs = pairs.toSet()
        assertEquals(
            "Found duplicate (productId, productVersion) pairs in DEVICE_SOURCES",
            pairs.size,
            uniquePairs.size
        )
    }

    @Test
    fun testNoDuplicateDeviceSource() {
        // We need all deviceSources to be distinct
        val pairs = ZeppOsDeviceSources.DEVICE_SOURCES.map { it.deviceSource }
        val uniquePairs = pairs.toSet()
        assertEquals(
            "Found duplicate deviceSources in DEVICE_SOURCES",
            pairs.size,
            uniquePairs.size
        )
    }

    @Test
    fun testDeviceSource() {
        for (info in ZeppOsDeviceSources.DEVICE_SOURCES) {
            if (info.deviceSource > 65535) {
                assertEquals(
                    "Found unexpected value for deviceSource: " + info.deviceSource,
                    (info.productId shl 16) or info.productVersion,
                    info.deviceSource
                )
            }
        }
    }
}
