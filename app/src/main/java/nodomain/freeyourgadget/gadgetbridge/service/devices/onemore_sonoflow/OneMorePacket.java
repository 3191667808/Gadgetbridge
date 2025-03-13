package nodomain.freeyourgadget.gadgetbridge.service.devices.onemore_sonoflow;

public class OneMorePacket {

    // sent from phone
    public static final byte[] REQUEST_PREAMBLE = { 0x11, 0x01, 0x00 };

    // sent from headphones
    public static final byte[] RESPONSE_PREAMBLE = { 0x01, 0x01, 0x00 };

    public static final byte GET_BATTERY_COMMAND = 0x4e;

    public static final byte GET_NOISE_CONTROL_COMMAND = 0x5f;
    public static final byte SET_NOISE_CONTROL_COMMAND = 0x5e;

    public static final byte GET_LDAC_COMMAND = 0x6c;
    public static final byte SET_LDAC_COMMAND = 0x6b;

    public static final byte GET_DUAL_DEVICE_COMMAND = 0x77;
    public static final byte SET_DUAL_DEVICE_COMMAND = 0x76;


    public static byte[] createBatteryRequestPacket() {
        byte[] flags = { 0x00, 0x00, 0x00 };
        byte[] checksum = { 0x1c, 0x42 };       // TODO: calculate

        return concat(REQUEST_PREAMBLE, GET_BATTERY_COMMAND, flags, checksum);
    }

    public static byte[] createNoiseControlModeRequestPacket() {
        byte[] flags = { 0x00, 0x00, 0x00 };
        byte[] checksum = { 0x0c, 0x43 };       // TODO: calculate

        return concat(REQUEST_PREAMBLE, GET_NOISE_CONTROL_COMMAND, flags, checksum);
    }

    public static byte[] createLdacModeRequestPacket() {
        byte[] flags = { 0x00, 0x00, 0x00 };
        byte[] checksum = { 0x0d, 0x71 };       // TODO: calculate

        return concat(REQUEST_PREAMBLE, GET_LDAC_COMMAND, flags, checksum);
    }

    public static byte[] createDualDeviceModeRequestPacket() {
        byte[] flags = { 0x00, 0x00, 0x00 };
        byte[] checksum = { 0x0c, 0x6b };       // TODO: calculate

        return concat(REQUEST_PREAMBLE, GET_DUAL_DEVICE_COMMAND, flags, checksum);
    }

    private static byte[] concat(Object... args) {
        if (args == null || args.length == 0) {
            return new byte[0];
        }

        int totalLength = 0;
        for (Object arg : args) {
            if (arg instanceof byte[]) {
                totalLength += ((byte[]) arg).length;
            } else if (arg instanceof Byte) {
                totalLength++;
            } else {
                throw new IllegalArgumentException("Invalid argument type: " + arg.getClass().getName() + ".  Expected byte[] or Byte.");
            }
        }

        byte[] result = new byte[totalLength];
        int offset = 0;

        for (Object arg : args) {
            if (arg instanceof byte[]) {
                byte[] byteArray = (byte[]) arg;
                System.arraycopy(byteArray, 0, result, offset, byteArray.length);
                offset += byteArray.length;
            } else if (arg instanceof Byte) {
                result[offset++] = (Byte) arg;
            }
        }

        return result;
    }
}
