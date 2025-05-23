package nodomain.freeyourgadget.gadgetbridge.devices.sr08ring;

import java.util.UUID;

public class SR08RingConstants {
    public static final UUID CHARACTERISTIC_WRITE = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb");
    public static final UUID CHARACTERISTIC_READ = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb");
    public static final byte CMD_SET_TIME = 1;
    public static final byte CMD_SET_HOUR_FORMAT = 29;
    public static final byte CMD_SET_APP_ID = 72;
    public static final byte CMD_SET_SPO2_ENABLED = 62;
    public static final byte SET_LIVE_HEART_RATE_ENABLED = 20;
    public static final byte SET_LIVE_HEART_RATE_DISABLED = 21;
    public static final byte CMD_SET_LANG = 33;

    public static final byte CMD_GET_BATTERY = 11;
    public static final byte CMD_GET_DEVICE_INFO = 12;

    public static final byte CMD_TRIGGER_ACTIVITY_REPORT = 16;
    public static final byte CMD_TRIGGER_HEART_RATE_REPORT = 22;
    public static final byte CMD_TRIGGER_SPORT_REPORT = 37;
}
