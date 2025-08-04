package nodomain.freeyourgadget.gadgetbridge.devices.sr08ring;

import java.util.UUID;

public class SR08RingConstants {
    public static final UUID CHARACTERISTIC_WRITE = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb");
    public static final UUID CHARACTERISTIC_READ = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb");
    
    public static final byte CMD_SET_TIME = 0x1;
    public static final byte CMD_SET_HOUR_FORMAT = 0x1d;
    public static final byte CMD_SET_APP_ID = 0x48;
    public static final byte CMD_SET_SPO2_ENABLED = 0x3e;
    public static final byte CMD_SET_LIVE_HEART_RATE_ENABLED = 0x14;
    public static final byte CMD_SET_LIVE_HEART_RATE_DISABLED = 0x15;
    public static final byte CMD_SET_LANG = 0x21;
    public static final byte CMD_SET_CAMERA_MODE = 0x7;

    public static final byte CMD_GET_BATTERY = 0xb;
    public static final byte CMD_GET_DEVICE_INFO = 0x0c;

    public static final byte CMD_TRIGGER_ACTIVITY_REPORT = 0x10;
    public static final byte CMD_TRIGGER_HEART_RATE_REPORT = 0x16;
    public static final byte CMD_TRIGGER_SPORT_REPORT = 0x25;

    public static final byte CMD_TRIGGER_BLINK = 0x4;
}
