package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap;

import java.util.HashMap;
import java.util.Map;

public enum ResourceType {
    MAIN(0x01),
    INDEX(0x02),
    PWR(0x0B),
    CONTROL(0x10),
    FEC(0x11),
    LEV(0x14),
    SHFT(0x22),
    LGT(0x23),
    RDR(0x28),
    HRM(0x78),
    SC(0x79),
    CAD(0x80),
    SPD(0x81),
    UNKNOWN(-1);

    private final int value;
    private static final Map<Integer, ResourceType> map = new HashMap<>();

    ResourceType(int value) {
        this.value = value;
    }

    static {
        for (ResourceType type : ResourceType.values()) {
            map.put(type.value, type);
        }
    }

    public static ResourceType fromValue(int value) {
        return map.getOrDefault(value, UNKNOWN);
    }

    public int getValue() {
        return value;
    }
}


