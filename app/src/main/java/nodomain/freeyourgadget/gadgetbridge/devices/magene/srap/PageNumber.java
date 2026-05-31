package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap;

import java.util.HashMap;
import java.util.Map;

public enum PageNumber {
    NODE_BASIC_INFO(0x01),
    NODE_SERIAL_INFO(0x02),
    NODE_ADDRESS_INFO(0x03),
    NODE_RESOURCE_TYPE_INFO(0x04),
    SPU_BASIC_INFO(0x11),
    SPU_TIER(0x12),
    IDENTITY_DEVICE_INFO(0x13),
    SPU_MANUFACTURER_BRAND_NAME(0x14),
    SPU_BLUETOOTH_INFO(0x15),
    ALTITUDE_CORRECTION(0x1F),
    OTA_CONTROL(0x23),
    FILE_TRANSFORM_CONTROL(0x24),
    COMMON_FILE_INFO(0x25),
    COMMON_FILE(0x26),
    NODE_BLUETOOTH_INFO(0x27),
    CRASH_ANALYZE(0x31),
    CRASH_STATISTICS(0x32),
    COMMON_READ(0x40),
    COMMON_WRITE(0x41),
    FILE_LIST_GET(0x42),
    FILES_VERSION(0x43),
    NOTIFICATION(0x44),
    BOND_SYNC_STATE_CONTROL(0xE1),
    DEVICE_STATUS(0xF0),
    DEVICE_CONTROL(0xF2),
    GENERAL_INFO(0xF3),
    UNKNOWN(-1);

    private final int value;
    private static final Map<Integer, PageNumber> map = new HashMap<>();

    PageNumber(int value) {
        this.value = value;
    }

    static {
        for (PageNumber page : PageNumber.values()) {
            map.put(page.value, page);
        }
    }

    public static PageNumber fromValue(int value) {
        return map.getOrDefault(value, UNKNOWN);
    }

    public int getValue() {
        return value;
    }
}


