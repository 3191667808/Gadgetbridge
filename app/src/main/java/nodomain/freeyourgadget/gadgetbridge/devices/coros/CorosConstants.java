package nodomain.freeyourgadget.gadgetbridge.devices.coros;

import java.util.UUID;

public final class CorosConstants {

    private CorosConstants() {}

    public static final String COROS_PACE_3_NAME_PREFIX = "coros pace 3 ";
    public static final String COROS_PACE_4_NAME_PREFIX = "coros pace 4 ";

    public static final UUID UUID_SERVICE_CHANNEL1 =
            UUID.fromString("6e400001-b5a3-f393-e0a9-77656c6f6f70");
    public static final UUID UUID_CHARACTERISTIC_CH1_RX =
            UUID.fromString("6e400003-b5a3-f393-e0a9-77656c6f6f70");

    public static final UUID UUID_SERVICE_CHANNEL2 =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID UUID_CHARACTERISTIC_CH2_RX =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    public static final UUID UUID_SERVICE_CHANNEL3 =
            UUID.fromString("6e400001-b5a3-f393-e0a9-77757c7f7f70");
    public static final UUID UUID_CHARACTERISTIC_CH3_RX =
            UUID.fromString("6e400003-b5a3-f393-e0a9-77757c7f7f70");
}
