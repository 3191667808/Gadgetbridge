package nodomain.freeyourgadget.gadgetbridge.devices.magene;

import java.util.HashMap;
import java.util.Map;

public enum FileType {
    BOOT(0),
    PROTOCOL_TASK(1),
    APP(2),
    GPS(3),
    FIT(4),
    WORKOUT(5),
    ROUTE(6),
    FONT(7),
    OFFLINE_AGNSS(8),
    PREPARE_UPLOAD_FIT(9),
    SYSTEM_CONFIG(10),
    USER_PROFILE(11),
    USER_AVATAR(12),
    RIDING_SUMMARY(13),
    WIFI_CONFIG(14),
    MY_BIKE_CONFIG(15),
    SENSOR_CONFIG(16),
    RIDING_MODE_CONFIG(17),
    LOG(18),
    EXCEPTION(19),
    SMART_THING(20),
    MAP(21),
    VIRTUAL_ROUTE(22),
    NOTIFICATION_CONFIG(23),
    INDOOR_TRAIN(24),
    CLIMB_PLAN(25),
    QUICK_NAVIGATION(26),
    FREE_RIDE(27),
    SEGMENT(28),
    TRACE(29),
    GROUP_TRACK(30),
    OFFLINE_LOCATION(31),
    SEGMENT_PANEL(32),
    WEATHER(33),
    DEFAULT(255),
    UNKNOWN(-1);

    private final int value;
    private static final Map<Integer, FileType> map = new HashMap<>();

    FileType(int value) {
        this.value = value;
    }

    static {
        for (FileType type : FileType.values()) {
            map.put(type.value, type);
        }
    }

    public static FileType fromValue(int value) {
        return map.getOrDefault(value, UNKNOWN);
    }

    public int getValue() {
        return value;
    }
}


