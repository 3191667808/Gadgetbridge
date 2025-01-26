package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo;

public enum CardoMessage {
    GET((byte) 0x00, true),
    INIT((byte) 0x03, false),
    SET((byte) 0x10, true),
    SUBSCRIBE((byte) 0x22, false),
    CONTROL((byte) 0x30, false),
    CONFIG((byte) 0x40, true),
    DEVICE_ALIAS((byte) 0x42, true),
    DEVICE_SERIAL_NUMBER((byte) 0x43, true),
    DEVICE_STATE((byte) 0x50, false),
    BATTERY_STATUS((byte) 0x51, false),
    ;

    public final byte command;
    public final boolean hasLength;

    CardoMessage(byte command, boolean hasLength) {
        this.command = command;
        this.hasLength = hasLength;
    }

    public static CardoMessage getByCommand(byte cmd) {
        for (CardoMessage cm : values()) {
            if (cm.command == cmd) {
                return cm;
            }
        }
        return null;
    }

}
