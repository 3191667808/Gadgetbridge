package nodomain.freeyourgadget.gadgetbridge.devices.sr08ring;

import java.util.Map;
import java.util.UUID;

public class SR08RingConstants {
    public static final UUID CHARACTERISTIC_WRITE = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb");
    public static final UUID CHARACTERISTIC_READ = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb");
    public static final byte CMD_SET_TIME = 1;
    public static final  byte CMD_GET_BATTERY = 11;
    public static final byte CMD_SET_HOUR_FORMAT = 29;
    public static final  byte CMD_SET_APP_ID = 72;
}
