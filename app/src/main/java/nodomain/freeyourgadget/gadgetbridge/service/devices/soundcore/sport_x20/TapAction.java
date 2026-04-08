package nodomain.freeyourgadget.gadgetbridge.service.devices.soundcore.sport_x20;

enum TapAction {
    DOUBLE_TAP((byte) 0x00, (byte) 0x30),
    LONG_PRESS((byte) 0x01, (byte) 0x40),
    SINGLE_TAP((byte) 0x02, (byte) 0x60);

    private final byte code;
    private final byte functionPrefix;

    TapAction(final byte code, final byte functionPrefix) {
        this.code = code;
        this.functionPrefix = functionPrefix;
    }

    public byte getCode() {
        return code;
    }

    public byte getFunctionPrefix() {
        return functionPrefix;
    }
}
