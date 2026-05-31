package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap;

import java.util.HashMap;
import java.util.Map;

public enum FunctionCode {
    HOLD(0xF0),
    READ(0xF1),
    WRITE(0xF2),
    DELETE(0xF3),
    NOTIFICATION(0xF4),
    RESPONSE(0xF5),
    UNKNOWN(-1);

    private final int value;
    private static final Map<Integer, FunctionCode> map = new HashMap<>();

    FunctionCode(int value) {
        this.value = value;
    }

    static {
        for (FunctionCode code : FunctionCode.values()) {
            map.put(code.value, code);
        }
    }

    public static FunctionCode fromValue(int value) {
        return map.getOrDefault(value, UNKNOWN);
    }

    public int getValue() {
        return value;
    }
}


